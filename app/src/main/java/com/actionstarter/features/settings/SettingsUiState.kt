package com.actionstarter.features.settings

import com.actionstarter.ai.model.ModelCatalog
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelDownloadFailureReason
import com.actionstarter.ai.model.ModelStorage

/**
 * F97契約（計画書§7.1・§12.6・§13.5 S-6範囲裁定、P7-C6）。Settings画面のUI状態。
 *
 * [EventSelectionUiState]のようなsealed階層ではなく、[com.actionstarter.features.departure.
 * DepartureUiState]／[com.actionstarter.features.execution.ExecutionUiState]と同型の
 * フラットなdata class構成を採る。理由: AIトグル・モデル状態・容量表示は互いに排他的な
 * 「画面状態」ではなく、Settings画面が常に同時表示する独立した情報の集合であるため
 * （sealed interfaceで表現すると本来並存すべき情報を強制的に択一にしてしまう）。
 *
 * S-6裁定によりSettings画面は「AIトグル＋モデル状態＋DL/削除＋容量表示」の4項目に限定する
 * （フル機能のSettingsは対象外）。
 *
 * @param aiEnabled [com.actionstarter.ai.AiPreferences.aiEnabled]の現在値（既定OFF、T-SET-1）。
 * @param isDeviceSupported [com.actionstarter.ai.model.DeviceCapability]によるRAM／ABI判定の
 *   複合結果。falseの場合、Settings画面はトグルを無効表示し[deviceUnsupportedReason]の理由文言を
 *   出す（T-SET-3、§8.6 #1・#2、エラー＆レスキューマップ#19）。
 * @param selectedModel DLの対象モデル。P7-C6時点では常に[ModelCatalog.GEMMA_4_E2B_IT]
 *   （ユーザー確定の既定モデル）。将来のモデル選択UIに備え型としては保持するが、本サイクルの
 *   Settings画面自体は選択UIを持たない（S-6範囲）。
 * @param modelStatus [selectedModel]のDL/導入状態（T-SET-4）。
 * @param requiredBytesForDownload DL開始に必要な空き容量（`selectedModel.sizeBytes ×
 *   `[ModelStorage.CAPACITY_SAFETY_FACTOR]`、§95.6・T-SET-5）。[ModelStorage.hasSufficientSpace]
 *   ／[com.actionstarter.ai.model.ModelDownloader]が実際に強制する閾値と同一の値を表示する
 *   （表示上の「必要容量」と実際にDLが要求する容量が乖離すると誤解を招くため、生の
 *   `sizeBytes`ではなく安全マージン込みの値を既定にする）。
 * @param availableBytes 現在の空き容量（[ModelStorage.availableBytes]、T-SET-5）。
 */
data class SettingsUiState(
    val aiEnabled: Boolean = false,
    val isDeviceSupported: Boolean = true,
    val deviceUnsupportedReason: DeviceUnsupportedReason? = null,
    val selectedModel: ModelCatalogEntry = ModelCatalog.GEMMA_4_E2B_IT,
    val modelStatus: ModelDownloadStatus = ModelDownloadStatus.NotInstalled,
    val requiredBytesForDownload: Long = (selectedModel.sizeBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong(),
    val availableBytes: Long = 0L
) {
    /** [availableBytes]が[requiredBytesForDownload]を下回るか（T-SET-5の容量不足明示）。 */
    val hasInsufficientStorage: Boolean
        get() = availableBytes < requiredBytesForDownload
}

/**
 * T-SET-3。AIトグルを無効化する理由（§8.6 #1「RAM不足」／#2「ABI非対応」、
 * エラー＆レスキューマップ#19「動かない機能をONにできない」）。
 */
enum class DeviceUnsupportedReason {
    INSUFFICIENT_RAM,
    UNSUPPORTED_ABI
}

/**
 * T-SET-4。[SettingsUiState.selectedModel]のDL/導入状態（未DL／DL中（進捗）／導入済み・
 * 検証済み／失敗）。
 *
 * [Installed]は「検証済み」を意味する: [com.actionstarter.ai.model.ModelDownloader.download]は
 * DL完了直後に[com.actionstarter.ai.model.ModelVerifier.verify]が合格した場合にのみ
 * [ModelStorage.commit]で正式配置するため（§8.6 #5）、正式配置先にファイルが存在すること自体が
 * 「DL完了時点でSHA-256検証を通過した」ことの証跡となる。Settings画面表示のたびに2.59GBの
 * ファイルを再ハッシュする再検証はUX上現実的でないため行わない（ロード直前の再検証は
 * [com.actionstarter.ai.LocalAiGateway]の§8.6 #12が別途担う）。
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
