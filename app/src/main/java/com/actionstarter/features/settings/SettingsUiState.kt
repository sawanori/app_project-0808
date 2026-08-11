package com.actionstarter.features.settings

import com.actionstarter.ai.model.ModelCatalog
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelDownloadFailureReason
import com.actionstarter.ai.model.ModelStorage

/**
 * F97契約（計画書§7.1・§12.6・§13.5 S-6範囲裁定、P7-C6／Phase 8.5 F-B）。Settings画面のUI状態。
 *
 * **Phase 8.5 F-Bでの変更（計画書`docs/plans/phase8.5-adaptive-model-selection.md`§3設計F-B-1、
 * ADR-0062）**: 単数`selectedModel`/`modelStatus`/`requiredBytesForDownload`を、モデルごとの
 * 状態を持つ[models]（[ModelOptionUiState]のリスト、「自動」＋カタログ候補）へ置き換えた
 * （S-6裁定「モデル選択UI対象外」はF-Aの`AiPreferences.AUTO_SELECT_MODEL_ID`導入とA54実機実測
 * （`phase8-a54-ram-tier-fix.md`§10.3）を受けて改訂、§11確認事項1〜3）。
 *
 * [EventSelectionUiState]のようなsealed階層ではなく、[com.actionstarter.features.departure.
 * DepartureUiState]／[com.actionstarter.features.execution.ExecutionUiState]と同型の
 * フラットなdata class構成を採る（AIトグル・モデル一覧・容量表示は互いに排他的な「画面状態」
 * ではなく常に同時表示する独立した情報の集合であるため）。
 *
 * @param aiEnabled [com.actionstarter.ai.AiPreferences.aiEnabled]の現在値（既定OFF、T-SET-1）。
 * @param isDeviceSupported [com.actionstarter.ai.model.DeviceCapability]によるRAM／ABI判定の
 *   複合結果。falseの場合、Settings画面はトグル・モデル一覧全体を無効表示し
 *   [deviceUnsupportedReason]の理由文言を出す（T-SET-3・T-P85-20、§8.6 #1・#2）。
 * @param models モデル一覧（「自動」＋カタログ候補、品質順）。Qwen3-1.7Bは含めない
 *   （`ModelSelector.DEFAULT_AUTO_CANDIDATES`と同じ候補集合、T-P85-16）。
 * @param availableBytes 現在の空き容量（[ModelStorage.availableBytes]、T-SET-5）。行ごとの
 *   必要容量は[ModelOptionUiState.requiredBytesForDownload]と比較して呼び出し側
 *   （[SettingsScreen]）が容量不足を判定する。
 */
data class SettingsUiState(
    val aiEnabled: Boolean = false,
    val isDeviceSupported: Boolean = true,
    val deviceUnsupportedReason: DeviceUnsupportedReason? = null,
    val models: List<ModelOptionUiState> = defaultModelOptions(),
    val availableBytes: Long = 0L
)

/**
 * [SettingsUiState.models]の既定値（「自動」＋実カタログ候補、いずれも未DL・非選択状態）。
 * `ModelSelector.DEFAULT_AUTO_CANDIDATES`と同じ候補集合（[ModelCatalog.GEMMA_4_E2B_IT]・
 * [ModelCatalog.QWEN3_0_6B_INT4_BLOCK32]、品質順）を用いる。
 */
private fun defaultModelOptions(): List<ModelOptionUiState> = listOf(
    ModelOptionUiState(
        option = ModelOption.Auto,
        status = null,
        isRecommended = false,
        isSelected = true,
        requiredBytesForDownload = 0L
    ),
    modelOptionFor(ModelCatalog.GEMMA_4_E2B_IT),
    modelOptionFor(ModelCatalog.QWEN3_0_6B_INT4_BLOCK32)
)

private fun modelOptionFor(entry: ModelCatalogEntry): ModelOptionUiState = ModelOptionUiState(
    option = ModelOption.Specific(entry),
    status = ModelDownloadStatus.NotInstalled,
    isRecommended = false,
    isSelected = false,
    requiredBytesForDownload = (entry.sizeBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong()
)

/**
 * T-SET-3。AIトグル・モデル一覧を無効化する理由（§8.6 #1「RAM不足」／#2「ABI非対応」、
 * エラー＆レスキューマップ#19「動かない機能をONにできない」）。
 */
enum class DeviceUnsupportedReason {
    INSUFFICIENT_RAM,
    UNSUPPORTED_ABI
}

/**
 * Phase 8.5 F-B新設（計画書§3設計F-B-1）。[SettingsUiState.models]の1行が指す対象
 * （「自動」または特定モデル）。
 */
sealed interface ModelOption {
    /** 「自動（推奨）」— 空きメモリに応じて[com.actionstarter.ai.model.ModelSelector]が選ぶ。 */
    data object Auto : ModelOption

    /** 特定モデルの明示選択。 */
    data class Specific(val entry: ModelCatalogEntry) : ModelOption
}

/**
 * Phase 8.5 F-B新設（計画書§3設計F-B-1、T-P85-16〜22）。[SettingsUiState.models]の1行分の状態。
 *
 * @param option 「自動」か特定モデルか。
 * @param status DL/導入状態（[ModelOption.Auto]は`null`——ダウンロード概念を持たない）。
 * @param isRecommended 段ベースの推奨バッジ（[com.actionstarter.ai.model.DeviceCapability.classify]
 *   がTIER_1→Qwen0.6B行・TIER_2→Gemma4行、T-P85-18）。[ModelOption.Auto]は常に`false`
 *   （「自動」自体が推奨導線であるため個別バッジは付けない）。
 * @param isSelected [com.actionstarter.ai.AiPreferences.selectedModelId]がこの行を指すか
 *   （auto／各モデルidのいずれか1行のみ`true`、排他）。
 * @param requiredBytesForDownload DL開始に必要な空き容量（`entry.sizeBytes ×
 *   `[ModelStorage.CAPACITY_SAFETY_FACTOR]`）。[ModelOption.Auto]は`0L`。
 * @param isMemoryInsufficient Phase 8.5 F-B新設（計画書§11確認事項1、ADR-0062）。この端末の
 *   現在の空きメモリ（[com.actionstarter.ai.model.DeviceCapability.hasAvailableMemory]）が
 *   このモデルの実行に必要な量（`entry.defaultProfilePeakRamBytes +
 *   `[com.actionstarter.ai.model.DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES]`）を満たせない場合
 *   `true`（A54実機実測でGemma4が恒常的にこの状態になることが判明した、
 *   `phase8-a54-ram-tier-fix.md`§10.3参照）。DL/未DLいずれの状態でも独立して評価する（削除提案は
 *   しない、行内の注記表示のみに用いる。§11確認事項1「推奨案どおり」）。[ModelOption.Auto]は
 *   常に`false`（既定値。「自動」は動的に適合モデルを選ぶため個別の不足判定を持たない）。
 */
data class ModelOptionUiState(
    val option: ModelOption,
    val status: ModelDownloadStatus?,
    val isRecommended: Boolean,
    val isSelected: Boolean,
    val requiredBytesForDownload: Long,
    val isMemoryInsufficient: Boolean = false
)

/**
 * T-SET-4。[ModelOptionUiState]（[ModelOption.Specific]のみ）のDL/導入状態（未DL／DL中（進捗）／
 * 導入済み・検証済み／失敗）。
 *
 * [Installed]は「検証済み」を意味する: [com.actionstarter.ai.model.ModelDownloader.download]は
 * DL完了直後に[com.actionstarter.ai.model.ModelVerifier.verify]が合格した場合にのみ
 * [ModelStorage.commit]で正式配置するため（§8.6 #5）、正式配置先にファイルが存在すること自体が
 * 「DL完了時点でSHA-256検証を通過した」ことの証跡となる。
 */
sealed interface ModelDownloadStatus {
    data object NotInstalled : ModelDownloadStatus

    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadStatus {
        /** 0〜100の整数パーセント。[totalBytes]が0以下の場合は0（ゼロ除算防止）。 */
        val percent: Int
            get() = if (totalBytes <= 0L) 0 else ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    data object Installed : ModelDownloadStatus

    /** T-SET-6。[reason]により失敗理由を区別表示し、retry導線を出す（検証失敗はVERIFICATION_FAILED）。 */
    data class Failed(val reason: ModelDownloadFailureReason) : ModelDownloadStatus
}
