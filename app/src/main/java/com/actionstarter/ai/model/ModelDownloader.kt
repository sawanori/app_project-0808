package com.actionstarter.ai.model

/**
 * F88実装（計画書§7.1・§18・§95.6・§9本文・T-AIISO-6・§14 P7-C1）。モデルのHTTPダウンロード。
 *
 * **T-AIISO-6の唯一の許可ファイル**: `ai/`配下でネットワークAPI（`java.net.`／
 * `HttpURLConnection`／`URL(`）を参照してよいのは本ファイルのみ（§9本文・
 * Gemini G1 CRITICAL #1）。[com.actionstarter.services.routing.UrlConnectionHttpPostClient]等、
 * `ai/`の外にある既存HTTP手段の再利用も禁止（同ガードが検出対象とする迂回経路）であり、
 * 本クラスは自己完結した実装を持つ（P7-C4で実装する際、既存の`services.routing`配下の
 * クラスをimportしない）。
 *
 * **HTTPS必須（T-MDL-16）**: `entry`の`downloadUrl`が`https`でなければ即座に
 * [ModelDownloadFailureReason.INSECURE_URL]で失敗する契約とする（平文DLの禁止）。
 *
 * **Range再開（T-MDL-6・T-MDL-7）**: [modelStorage]の`.part`ファイルの既存長からRangeヘッダの
 * オフセットを決定する。サーバがRangeを無視して200（全体）を返した場合は部分ファイルを破棄し
 * 先頭から再取得する（追記して壊さない）。
 *
 * **無限DL防止（T-MDL-8）**: 受信済み総バイト数が`entry`の`sizeBytes`を超えた時点で即座に
 * 中断する。
 *
 * 契約scaffold（P7-C1、TDD厳守）時点では宣言のみであり、実装本体はP7-C4で行う
 * （T-MDL-6〜8・T-MDL-16）。
 *
 * @param modelStorage F90。`.part`ファイルの配置・原子的リネームの委譲先。
 */
class ModelDownloader(private val modelStorage: ModelStorage) {

    /**
     * [entry]をダウンロードする。[onProgress]は`(受信済みバイト数, 総バイト数)`を都度通知する
     * （F88「進捗」）。キャンセルはsuspend関数の標準的なコルーチンキャンセルで表現し、専用の
     * キャンセルAPIは持たない（F88「キャンセル」、既存`RoutesApiRoutingService`と同型の設計）。
     */
    suspend fun download(
        entry: ModelCatalogEntry,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): ModelDownloadResult {
        TODO("P7-C4で実装（T-MDL-6〜8・T-MDL-16。HttpURLConnectionによるRange再開DL）")
    }
}

/** [ModelDownloader.download]の戻り値。 */
sealed interface ModelDownloadResult {
    data object Success : ModelDownloadResult

    data class Failed(val reason: ModelDownloadFailureReason, val detail: String?) : ModelDownloadResult

    data object Cancelled : ModelDownloadResult
}

/** [ModelDownloadResult.Failed]の理由（T-MDL-7・T-MDL-8・T-MDL-16）。 */
enum class ModelDownloadFailureReason {
    INSECURE_URL,
    NETWORK_ERROR,
    HTTP_ERROR,
    SIZE_EXCEEDED
}
