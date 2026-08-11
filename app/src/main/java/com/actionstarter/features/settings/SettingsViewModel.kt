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
 * F97実装（計画書§7.1・§12.6・§13.5 S-6範囲裁定、P7-C6／Phase 8.5 F-B）。Settings画面のViewModel。
 *
 * コンストラクタ注入は[AppContainer]の既存規約どおり、[com.actionstarter.ai.AiPreferences]・
 * [com.actionstarter.ai.model.ModelDownloader]・[com.actionstarter.ai.model.ModelStorage]・
 * [com.actionstarter.ai.model.DeviceCapability]の4型（いずれもADR-0048でinterface化済み）に、
 * Phase 8.5 F-Bで[availableModels]を加える。**[com.actionstarter.ai.LocalAiGateway]は注入しない**
 * ——本画面はAI有効化の器（トグル・DL導線・モデル選択）を提供するのみで、実推論
 * （`generatePlan`）を一切呼び出さない（[SettingsAiSafetyTest]が独立に回帰ロックする）。
 *
 * **Phase 8.5 F-Bでの変更（計画書`docs/plans/phase8.5-adaptive-model-selection.md`§3設計F-B-2、
 * ADR-0062）**: 単数`selectedModel: ModelCatalogEntry`パラメータを廃し、[availableModels]
 * （品質順、既定は`ModelSelector.DEFAULT_AUTO_CANDIDATES`と同じ候補集合＝Qwen3-1.7B除外）へ
 * 置き換えた。S-6裁定「モデル選択UI対象外」は、F-Aの`AiPreferences.AUTO_SELECT_MODEL_ID`導入と
 * A54実機実測（`phase8-a54-ram-tier-fix.md`§10.3、日常使用6GB機でGemma4既定が恒常Basic縮退）を
 * 受けて改訂した（§11確認事項1〜3）。
 *
 * **Step 4（Green）で実装完了（本コミット）**: [refresh]・[onModelSelected]・
 * [onDownloadRequested]・[onDeleteRequested]の本体を実装した。[onAiEnabledToggled]は
 * 多モデル化と無関係な既存ロジックのため変更していない。
 */
class SettingsViewModel(
    private val aiPreferences: AiPreferences,
    private val modelDownloader: ModelDownloader,
    private val modelStorage: ModelStorage,
    private val deviceCapability: DeviceCapability,
    private val availableModels: List<ModelCatalogEntry> = listOf(
        ModelCatalog.GEMMA_4_E2B_IT,
        ModelCatalog.QWEN3_0_6B_INT4_BLOCK32
    )
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * Phase 8.5 F-B新設。モデルごとに独立したDL Jobを保持する（計画書§7「DL中に選択変更」・
     * T-P85-21「1モデルDL中は他行のDL/削除ボタンが無効化される」の実装基盤）。keyは
     * [ModelCatalogEntry.id]。
     */
    private val downloadJobs: MutableMap<String, Job> = mutableMapOf()

    init {
        refresh()
    }

    /**
     * [aiPreferences]・[deviceCapability]・[modelStorage]の現在値から[uiState]全体を
     * 再計算する（T-SET-1〜5・T-P85-16〜20）。画面初期表示（[init]）に加え、DL成功・キャンセル・
     * 削除後にも呼ぶ（DL中の`Downloading`／DL失敗時の`Failed`は本メソッドが導出できる情報
     * （ファイル存在の有無）だけでは表現できないため、[onDownloadRequested]が[updateModelRow]で
     * 個別行を狙い撃ちして表現する。他行を巻き込まない設計は§7「DL中に別モデルのダウンロードが
     * 要求される」の「UI層で他行のDL/削除ボタンを無効化」前提と対になる）。
     *
     * **推奨バッジの並び規約（T-P85-18）**: [availableModels]は品質順（大→小）で並ぶ契約
     * （[com.actionstarter.ai.model.ModelSelector.DEFAULT_AUTO_CANDIDATES]と同じ規約）。
     * 先頭（index 0、Gemma4相当）はTIER_2_OPT_IN端末で推奨、2番目以降（index >= 1、Qwen0.6B相当）
     * はTIER_1_STANDARD端末で推奨とする。
     *
     * **§11確認事項1（ADR-0062）**: 各行の[ModelOptionUiState.isMemoryInsufficient]を
     * `!deviceCapability.hasAvailableMemory(entry.defaultProfilePeakRamBytes +
     * DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES)`で毎回評価する（DL状態と独立、削除提案はしない
     * 「行内注記のみ」の確定回答どおり）。
     */
    fun refresh() {
        val abiSupported = deviceCapability.isAbiSupported()
        val tier = deviceCapability.classify()
        val isDeviceSupported = abiSupported && tier != DeviceTier.TIER_0_UNSUPPORTED
        val deviceUnsupportedReason = when {
            isDeviceSupported -> null
            !abiSupported -> DeviceUnsupportedReason.UNSUPPORTED_ABI
            else -> DeviceUnsupportedReason.INSUFFICIENT_RAM
        }
        val selectedModelId = aiPreferences.selectedModelId

        val autoRow = ModelOptionUiState(
            option = ModelOption.Auto,
            status = null,
            isRecommended = false,
            isSelected = selectedModelId == AiPreferences.AUTO_SELECT_MODEL_ID,
            requiredBytesForDownload = 0L
        )
        val specificRows = availableModels.mapIndexed { index, entry ->
            val recommendedTier = if (index == 0) DeviceTier.TIER_2_OPT_IN else DeviceTier.TIER_1_STANDARD
            ModelOptionUiState(
                option = ModelOption.Specific(entry),
                status = if (modelStorage.finalFile(entry).isFile) {
                    ModelDownloadStatus.Installed
                } else {
                    ModelDownloadStatus.NotInstalled
                },
                isRecommended = tier == recommendedTier,
                isSelected = selectedModelId == entry.id,
                requiredBytesForDownload = (entry.sizeBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong(),
                isMemoryInsufficient = !deviceCapability.hasAvailableMemory(
                    entry.defaultProfilePeakRamBytes + DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES
                )
            )
        }

        _uiState.update {
            it.copy(
                aiEnabled = aiPreferences.aiEnabled,
                isDeviceSupported = isDeviceSupported,
                deviceUnsupportedReason = deviceUnsupportedReason,
                models = listOf(autoRow) + specificRows,
                availableBytes = modelStorage.availableBytes()
            )
        }
    }

    /**
     * T-SET-2。非対応端末では無視する（T-SET-3・エラー＆レスキューマップ#19の二重防御、
     * UI側もSwitch自体をenabled=falseにするがViewModel側にも安全網を持たせる）。
     * 多モデル化と無関係な既存ロジックのため無変更。
     */
    fun onAiEnabledToggled(enabled: Boolean) {
        if (!_uiState.value.isDeviceSupported) return
        aiPreferences.aiEnabled = enabled
        _uiState.update { it.copy(aiEnabled = enabled) }
    }

    /**
     * Phase 8.5 F-B新設（計画書§3設計F-B-2「選択」アクション、T-P85-19）。[option]を選択する
     * （「自動」なら`AiPreferences.AUTO_SELECT_MODEL_ID`、特定モデルならそのid）。
     * ダウンロード（[onDownloadRequested]）とは独立したアクションであり、DL完了が選択を
     * 自動変更することはない（計画書§7「DL中に選択変更」参照）。
     *
     * [refresh]を呼ばず狙い撃ちで`isSelected`のみ更新する（他行がDL中の場合にその`status`を
     * 巻き込んで壊さないため。[updateModelRow]の設計意図と同じ）。
     */
    fun onModelSelected(option: ModelOption) {
        val id = when (option) {
            ModelOption.Auto -> AiPreferences.AUTO_SELECT_MODEL_ID
            is ModelOption.Specific -> option.entry.id
        }
        aiPreferences.selectedModelId = id
        _uiState.update { state ->
            state.copy(models = state.models.map { row -> row.copy(isSelected = row.option == option) })
        }
    }

    /**
     * T-SET-4〜6・F88実配線・Phase 8.5 F-B（[entry]を指定した行のDL）。戻り値の[Job]はテストが
     * 完了を待つためのフック。[downloadJobs]に[entry.id]の[Job]が実行中（[Job.isActive]）なら
     * 二重起動せず既存Jobを返す（`onDownloadRequested_calledTwiceWhileInFlight_...`）。
     *
     * 呼び出し直後、コルーチン起動前に同期的に対象行を`Downloading(0, entry.sizeBytes)`へ
     * 更新する（T-P85-21の基盤——他行の無効化判定に空白期間を作らないため。実際の進捗は
     * [ModelDownloader.download]の`onProgress`コールバックが後続で上書きする）。
     *
     * 成功時は選択（`aiPreferences.selectedModelId`）を変更しない（KDoc[onModelSelected]参照。
     * ダウンロードと選択は別アクション）。[refresh]を呼んで全行をファイル存在ベースで
     * 再計算する——本メソッドが呼ばれている間は§7の設計によりUI層が他行のDL/削除ボタンを
     * 無効化しているため、同時に別モデルがDL中である状況は生じない前提（他行を巻き込む
     * リスクがない）。失敗時は対象行のみ[updateModelRow]で`Failed`へ更新し、`availableBytes`を
     * 再取得する（DL試行がストレージ状態を変え得るため、失敗理由がわかるよう最新値を反映する）。
     */
    fun onDownloadRequested(entry: ModelCatalogEntry): Job {
        downloadJobs[entry.id]?.let { existing -> if (existing.isActive) return existing }

        updateModelRow(entry) { it.copy(status = ModelDownloadStatus.Downloading(0L, entry.sizeBytes)) }

        val job = viewModelScope.launch {
            val result = modelDownloader.download(entry) { bytesDownloaded, totalBytes ->
                updateModelRow(entry) { it.copy(status = ModelDownloadStatus.Downloading(bytesDownloaded, totalBytes)) }
            }
            when (result) {
                ModelDownloadResult.Success -> refresh()
                is ModelDownloadResult.Failed -> {
                    updateModelRow(entry) { it.copy(status = ModelDownloadStatus.Failed(result.reason)) }
                    _uiState.update { it.copy(availableBytes = modelStorage.availableBytes()) }
                }
                ModelDownloadResult.Cancelled -> refresh()
            }
            downloadJobs.remove(entry.id)
        }
        downloadJobs[entry.id] = job
        return job
    }

    /**
     * モデル削除（[entry]を指定）。[ModelStorage.delete]は非suspendの同期I/O契約のため追加の
     * コルーチン起動をしない（既存契約を踏襲）。削除後は[refresh]で全行を再計算するため、
     * 対象行の`status`は`NotInstalled`へ戻る。`aiPreferences.selectedModelId`はここでは
     * 一切書き込まない——明示選択中のモデルを削除しても選択値自体は変更しない
     * （T-P85-22、計画書§7「明示選択中のモデルが削除される」）。
     */
    fun onDeleteRequested(entry: ModelCatalogEntry) {
        modelStorage.delete(entry)
        refresh()
    }

    /**
     * Phase 8.5 F-B新設。[entry]に対応する[ModelOption.Specific]行のみを[transform]で更新する
     * （[entry.id][ModelCatalogEntry.id]一致判定。他行には触れない）。[refresh]と異なり
     * `status`をファイル存在から再計算しないため、DL中の`Downloading`進捗や`Failed`のような
     * ファイルシステムだけからは導出できない一時状態を、他行を巻き込まずに表現できる。
     */
    private fun updateModelRow(entry: ModelCatalogEntry, transform: (ModelOptionUiState) -> ModelOptionUiState) {
        _uiState.update { state ->
            state.copy(
                models = state.models.map { row ->
                    val rowEntry = (row.option as? ModelOption.Specific)?.entry
                    if (rowEntry?.id == entry.id) transform(row) else row
                }
            )
        }
    }
}
