package com.actionstarter.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionstarter.ai.AiPreferences
import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceTier
import com.actionstarter.ai.model.ModelCatalog
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelDownloadResult
import com.actionstarter.ai.model.ModelDownloader
import com.actionstarter.ai.model.ModelStorage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * F97実装（計画書§7.1・§12.6・§13.5 S-6範囲裁定、P7-C6）。Settings画面のViewModel。
 *
 * コンストラクタ注入は[AppContainer]の既存規約どおり、[com.actionstarter.ai.AiPreferences]・
 * [com.actionstarter.ai.model.ModelDownloader]・[com.actionstarter.ai.model.ModelStorage]・
 * [com.actionstarter.ai.model.DeviceCapability]の4型のみ（いずれもP7-C1/C3/C4でinterface化済み、
 * ADR-0048）。**[com.actionstarter.ai.LocalAiGateway]は注入しない**——本画面はAI有効化の器
 * （トグル・DL導線）を提供するのみで、実推論（`generatePlan`）を一切呼び出さない。この
 * コンストラクタ契約自体が「AI OFF/未DLでもSettings画面がクラッシュしない・推論を起動しない」
 * ことの構造的な裏付けになる（タスク指示「AI ON/OFFトグルの安全性」。[SettingsAiSafetyTest]が
 * `AiPreferencesImpl`／`ModelStorageImpl`／`LocalAiGateway`本物の組み合わせで独立に回帰ロックする）。
 *
 * [selectedModel]の既定は[ModelCatalog.GEMMA_4_E2B_IT]（ユーザー確定の既定モデル、2.59GB）。
 * S-6裁定によりモデル選択UIは範囲外のため、本画面は常にこの1モデルのみを対象にする。
 *
 * **`installedEntry()`のcatalog順序ヒューリスティックについて（ADR-0053再検討トリガーへの
 * 回答）**: [ModelStorage.installedEntry]は本番`catalog`（[ModelCatalog.ALL]、Qwen3-0.6B先頭）を
 * 順に走査し実ファイルが存在する最初のエントリを返す設計であり、[AiPreferences.
 * selectedModelId]は参照しない（P7-C4完了記録の申し送り「P7-C6でselectedModelIdによる複数
 * モデル選択が入る場合、この解決方法をselectedModelIdベースへ切り替えることを検討すること」への
 * 回答）。本サイクルではこの走査ロジック自体を変更しない——理由は、Settings画面がこのサイクルで
 * DL対象とするモデルは常に[ModelCatalog.GEMMA_4_E2B_IT]の1つのみ（S-6のモデル選択UI対象外裁定）
 * であり、実機の`noBackupFilesDir/models/`配下に他のcatalogエントリ（Qwen3-0.6B／Qwen3-1.7B）の
 * ファイルが存在することは実運用上あり得ない（それらはP7-C8の比較probeがホスト側
 * `build/models/`へ取得したものであり、端末内ストレージへは配置されない）ため、catalog順序に
 * よる誤判定リスクは実質ゼロである。将来複数モデル選択UIを追加するサイクルで改めて
 * `selectedModelId`ベースへの切替を検討すべき（Phase 8以降への申し送り、本体タスク最終報告に
 * 明記）。[resolveModelStatus]は[ModelStorage.installedEntry]の結果が[selectedModel]と
 * **id一致するか**を確認しており（catalogの並びに依存せず、誤って別モデルをInstalled表示しない
 * 一段の防御）、この決定は既存P7-C4/C5の`ModelStorage`／`LocalAiGateway`実装を一切変更しない。
 */
class SettingsViewModel(
    private val aiPreferences: AiPreferences,
    private val modelDownloader: ModelDownloader,
    private val modelStorage: ModelStorage,
    private val deviceCapability: DeviceCapability,
    private val selectedModel: ModelCatalogEntry = ModelCatalog.GEMMA_4_E2B_IT
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(selectedModel = selectedModel))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    init {
        refresh()
    }

    /**
     * [aiPreferences]・[deviceCapability]・[modelStorage]の現在値から[uiState]全体を
     * 再計算する（T-SET-1〜5）。画面初期表示（[init]）に加え、DL成功・削除後にも呼ぶ。
     *
     * **[ModelDownloadStatus.Downloading]中は呼ばない**: [onDownloadRequested]の失敗経路は
     * [refresh]ではなく[refreshCapacityOnly]を使う——[resolveModelStatus]は常に
     * NotInstalled/Installedの2値しか返さないため、DL失敗直後に[refresh]を呼ぶと
     * [ModelDownloadStatus.Failed]の表示（失敗理由・retry導線）が即座に上書きされ消えてしまう。
     */
    fun refresh() {
        val abiSupported = deviceCapability.isAbiSupported()
        val tier = deviceCapability.classify()
        val deviceSupported = abiSupported && tier != DeviceTier.TIER_0_UNSUPPORTED
        val reason = when {
            !abiSupported -> DeviceUnsupportedReason.UNSUPPORTED_ABI
            tier == DeviceTier.TIER_0_UNSUPPORTED -> DeviceUnsupportedReason.INSUFFICIENT_RAM
            else -> null
        }

        _uiState.update {
            it.copy(
                aiEnabled = aiPreferences.aiEnabled,
                isDeviceSupported = deviceSupported,
                deviceUnsupportedReason = reason,
                modelStatus = resolveModelStatus(),
                requiredBytesForDownload = (selectedModel.sizeBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong(),
                availableBytes = modelStorage.availableBytes()
            )
        }
    }

    /** [ModelStorage.installedEntry]が[selectedModel]と同一idの場合のみInstalledとみなす。 */
    private fun resolveModelStatus(): ModelDownloadStatus =
        if (modelStorage.installedEntry()?.id == selectedModel.id) {
            ModelDownloadStatus.Installed
        } else {
            ModelDownloadStatus.NotInstalled
        }

    /**
     * T-SET-2。非対応端末では無視する（T-SET-3・エラー＆レスキューマップ#19の二重防御、
     * UIのSwitch自体もenabled=falseにするがViewModel側にも安全網を持たせる）。
     */
    fun onAiEnabledToggled(enabled: Boolean) {
        if (!_uiState.value.isDeviceSupported) return
        aiPreferences.aiEnabled = enabled
        _uiState.update { it.copy(aiEnabled = enabled) }
    }

    /**
     * T-SET-4〜6・F88実配線。戻り値の[Job]はテストが完了を待つためのフック
     * （本番のonClickは戻り値を無視する。Kotlinは戻り値を無視した呼び出しを許す）。
     * [downloadJob]が実行中なら二重起動せず既存Jobを返す（[com.actionstarter.features.
     * eventselection.EventSelectionViewModel.refresh]のin-flightガードと同型）。
     *
     * [ModelDownloader.download]自体が例外を投げることはない契約（IOExceptionはFailedへ
     * 写像済み、`ai/model/ModelDownloader.kt`実測）ため、本メソッドはtry/catchを持たない
     * ——サイレントな握り潰しではなく、握り潰す例外自体が存在しない設計であることの反映。
     */
    fun onDownloadRequested(): Job {
        downloadJob?.let { if (it.isActive) return it }
        val job = viewModelScope.launch {
            _uiState.update { it.copy(modelStatus = ModelDownloadStatus.Downloading(0L, selectedModel.sizeBytes)) }

            when (
                val result = modelDownloader.download(selectedModel) { bytesDownloaded, totalBytes ->
                    _uiState.update { it.copy(modelStatus = ModelDownloadStatus.Downloading(bytesDownloaded, totalBytes)) }
                }
            ) {
                ModelDownloadResult.Success -> {
                    aiPreferences.selectedModelId = selectedModel.id
                    refresh()
                }

                is ModelDownloadResult.Failed -> {
                    _uiState.update { it.copy(modelStatus = ModelDownloadStatus.Failed(result.reason)) }
                    refreshCapacityOnly()
                }

                ModelDownloadResult.Cancelled -> refresh()
            }
        }
        downloadJob = job
        return job
    }

    /**
     * DL失敗直後、容量表示だけを最新化する（[refresh]のKDoc参照。DLの試行で空き容量が
     * 変化し得るため、失敗理由の表示を保ったまま容量数値のみ更新する）。
     */
    private fun refreshCapacityOnly() {
        _uiState.update { it.copy(availableBytes = modelStorage.availableBytes()) }
    }

    /**
     * モデル削除。[ModelStorage.delete]は非suspendの同期I/O契約
     * （[ModelStorage]のKDoc「導入済みモデルの解決方法」、P7-C4既存契約）のため、追加の
     * コルーチン起動をせず同期的に扱う（ファイルメタデータ削除のみで実測上ブロッキング時間が
     * 無視できるほど短いことは既存`ModelStorageImpl.delete`実装の前提と同一）。
     */
    fun onDeleteRequested() {
        modelStorage.delete(selectedModel)
        refresh()
    }
}
