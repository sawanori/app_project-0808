package com.actionstarter.ai.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * F88実装（計画書§7.1・§18・§95.6・§9本文・T-AIISO-6・§14 P7-C1／P7-C4）。モデルのHTTP
 * ダウンロード。DL完了後の検証・コミット（削除）までを一体のパイプラインとして扱う
 * （ADR-0054「破損/検証失敗時は削除してFallback/再DL導線」の実装根拠。
 * 信頼境界＝DLファイルを検証なしに使わない）。
 *
 * **T-AIISO-6の唯一の許可ファイル**: `ai/`配下でネットワークAPI（`java.net.`／
 * `HttpURLConnection`／`URL(`）を参照してよいのは本ファイルのみ（§9本文・
 * Gemini G1 CRITICAL #1）。[com.actionstarter.services.routing.UrlConnectionHttpPostClient]等、
 * `ai/`の外にある既存HTTP手段の再利用も禁止（同ガードが検出対象とする迂回経路）であり、
 * 本クラスは自己完結した実装を持つ（[UrlConnectionHttpRangeClient]、同ファイル。
 * `services.routing`配下のクラスをimportしない）。
 *
 * **HTTPS必須（T-MDL-16）**: [entry]の`downloadUrl`が`https`でなければ、接続を一切試みずに
 * 即座に[ModelDownloadFailureReason.INSECURE_URL]で失敗する（平文DLの禁止）。
 *
 * **容量ガード（§8.6 #3・§95.6・ADR-0053の訂正）**: DL開始前に
 * [ModelStorage.hasSufficientSpace]（`StatFs`ベース、モデルサイズ×1.5倍）を確認する。
 * 不足時は接続を試みず[ModelDownloadFailureReason.INSUFFICIENT_STORAGE]で失敗する。
 *
 * **Range再開（T-MDL-6・T-MDL-7）**: [modelStorage]の`.part`ファイルの既存長からRangeヘッダの
 * オフセットを決定する。サーバが`206 Partial Content`で応じればその位置から追記し、
 * Rangeを無視して`200 OK`（全体）を返した場合は部分ファイルを破棄し先頭から再取得する
 * （追記して壊さない）。
 *
 * **無限DL防止（T-MDL-8）**: 受信済み総バイト数が[entry]の`sizeBytes`を超えた時点で即座に
 * 中断し、破損した`.part`を削除して[ModelDownloadFailureReason.SIZE_EXCEEDED]を返す。
 *
 * **DL完了後の検証・コミット（ADR-0054）**: 転送完了後、[modelVerifier]で
 * `.part`ファイルを[entry]と照合する（§8.6 #5「DL完了直後の1回限り検証」）。合格すれば
 * [modelStorage.commit]で正式名へ原子的リネームして[ModelDownloadResult.Success]を返す。
 * 不合格（改竄・破損）なら[modelStorage.delete]で削除したうえで
 * [ModelDownloadFailureReason.VERIFICATION_FAILED]を返す（再DL導線はSettings側の責務、
 * §8.6 #5「再DL導線」）。
 *
 * @param modelStorage F90。`.part`ファイルの配置・容量ガード・原子的リネームの委譲先。
 * @param modelVerifier F89。DL完了直後のSHA-256／サイズ照合の委譲先（§8.6 #5）。
 * @param httpClient HTTP接続の抽象化。既定は[UrlConnectionHttpRangeClient]（本番）。テストは
 *   fake実装へ差し替える（実ネットワークDLを伴わないロジック検証、タスク指示）。
 */
class ModelDownloader(
    private val modelStorage: ModelStorage,
    private val modelVerifier: ModelVerifier,
    private val httpClient: HttpRangeClient = UrlConnectionHttpRangeClient()
) {

    /**
     * [entry]をダウンロードする。[onProgress]は`(受信済みバイト数, 総バイト数)`を都度通知する
     * （F88「進捗」、`totalBytes`は[entry.sizeBytes]の値を渡す）。キャンセルはsuspend関数の
     * 標準的なコルーチンキャンセルで表現し、専用のキャンセルAPIは持たない（F88「キャンセル」、
     * 既存`RoutesApiRoutingService`と同型の設計）。
     */
    suspend fun download(
        entry: ModelCatalogEntry,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): ModelDownloadResult = withContext(Dispatchers.IO) {
        if (!entry.downloadUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext ModelDownloadResult.Failed(
                ModelDownloadFailureReason.INSECURE_URL,
                "downloadUrl must use https: ${entry.downloadUrl}"
            )
        }
        if (!modelStorage.hasSufficientSpace(entry.sizeBytes)) {
            return@withContext ModelDownloadResult.Failed(
                ModelDownloadFailureReason.INSUFFICIENT_STORAGE,
                "Insufficient free space for ${entry.sizeBytes} bytes " +
                    "(x${ModelStorage.CAPACITY_SAFETY_FACTOR} margin required, §95.6)"
            )
        }

        val transferResult = transfer(entry, onProgress)
        if (transferResult is ModelDownloadResult.Failed) return@withContext transferResult

        finalizeDownload(entry)
    }

    /** HTTP転送本体（レジューム判定・ストリーミング書き込み・サイズ超過中断）。 */
    private fun transfer(
        entry: ModelCatalogEntry,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): ModelDownloadResult {
        val partFile = modelStorage.partFile(entry)
        val existingLength = if (partFile.isFile) partFile.length() else 0L

        return try {
            httpClient.open(entry.downloadUrl, existingLength).use { connection ->
                if (connection.statusCode != HttpURLConnection.HTTP_OK &&
                    connection.statusCode != HttpURLConnection.HTTP_PARTIAL
                ) {
                    return@use ModelDownloadResult.Failed(
                        ModelDownloadFailureReason.HTTP_ERROR,
                        "Unexpected HTTP status ${connection.statusCode}"
                    )
                }

                // T-MDL-7: サーバがRangeを無視して200(全体)を返したら部分ファイルを破棄して
                // 先頭からやり直す（追記して壊さない）。206ならそのまま追記する。
                val resumed = existingLength > 0 && connection.statusCode == HttpURLConnection.HTTP_PARTIAL
                if (!resumed && partFile.isFile) {
                    partFile.delete()
                }
                partFile.parentFile?.mkdirs()

                writeBody(connection.body, partFile, append = resumed, entry = entry, onProgress = onProgress)
            }
        } catch (e: IOException) {
            ModelDownloadResult.Failed(ModelDownloadFailureReason.NETWORK_ERROR, e.message ?: e.toString())
        }
    }

    private fun writeBody(
        body: InputStream,
        partFile: File,
        append: Boolean,
        entry: ModelCatalogEntry,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): ModelDownloadResult {
        var totalWritten = if (append) partFile.length() else 0L
        onProgress(totalWritten, entry.sizeBytes)

        FileOutputStream(partFile, append).use { output ->
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            while (true) {
                val read = body.read(buffer)
                if (read == -1) break

                totalWritten += read
                // T-MDL-8: カタログ定義値を超えたら即中断（無限DL防止）。破損した部分ファイルは
                // 次回の再開判断を誤らせないよう削除する。
                if (totalWritten > entry.sizeBytes) {
                    output.flush()
                    partFile.delete()
                    return ModelDownloadResult.Failed(
                        ModelDownloadFailureReason.SIZE_EXCEEDED,
                        "Received $totalWritten bytes, exceeding catalog size ${entry.sizeBytes}"
                    )
                }
                output.write(buffer, 0, read)
                onProgress(totalWritten, entry.sizeBytes)
            }
        }
        return ModelDownloadResult.Success
    }

    /** §8.6 #5。転送完了直後の1回限り検証→コミット、または削除して失敗を返す。 */
    private fun finalizeDownload(entry: ModelCatalogEntry): ModelDownloadResult {
        val partFile = modelStorage.partFile(entry)
        return when (val verification = modelVerifier.verify(partFile, entry)) {
            ModelVerificationResult.Valid ->
                if (modelStorage.commit(entry)) {
                    ModelDownloadResult.Success
                } else {
                    ModelDownloadResult.Failed(
                        ModelDownloadFailureReason.STORAGE_ERROR,
                        "ModelStorage.commit() failed after successful verification"
                    )
                }

            is ModelVerificationResult.Invalid -> {
                modelStorage.delete(entry)
                ModelDownloadResult.Failed(
                    ModelDownloadFailureReason.VERIFICATION_FAILED,
                    "Post-download verification failed: ${verification.reason} (§8.6 #5)"
                )
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE_BYTES: Int = 8192
    }
}

/** [ModelDownloader.download]の戻り値。 */
sealed interface ModelDownloadResult {
    data object Success : ModelDownloadResult

    data class Failed(val reason: ModelDownloadFailureReason, val detail: String?) : ModelDownloadResult

    data object Cancelled : ModelDownloadResult
}

/** [ModelDownloadResult.Failed]の理由（T-MDL-7・T-MDL-8・T-MDL-16・§8.6 #3・#5）。 */
enum class ModelDownloadFailureReason {
    INSECURE_URL,
    INSUFFICIENT_STORAGE,
    NETWORK_ERROR,
    HTTP_ERROR,
    SIZE_EXCEEDED,
    VERIFICATION_FAILED,
    STORAGE_ERROR
}

/**
 * HTTP接続の抽象化（ADR-0054）。[ModelDownloader]をfakeで完全に検証できるようにするための
 * 唯一の境界。本番実装は[UrlConnectionHttpRangeClient]。[ModelDownloader]の`public`コンストラクタ
 * が`httpClient`引数の型として公開するため、本interfaceは（[ModelDownloader]自身と同じ）
 * `public`とする（`internal`のままだと「publicな関数がinternal型を公開している」というKotlinの
 * 可視性整合性エラーになる）。
 */
interface HttpRangeClient {
    /**
     * [url]へGETする。[rangeStartInclusive]が0より大きい場合は`Range: bytes=<value>-`ヘッダを
     * 付ける（レジュームDL、T-MDL-6）。呼び出し側は返る[HttpRangeConnection]を`use{}`で
     * closeする責務を持つ。
     */
    fun open(url: String, rangeStartInclusive: Long): HttpRangeConnection
}

/** [HttpRangeClient.open]の戻り値。[body]を読み切ったら[close]で接続を解放する。 */
interface HttpRangeConnection : Closeable {
    val statusCode: Int
    val body: InputStream
}

/**
 * [HttpRangeClient]の`java.net.HttpURLConnection`実装（本番、T-AIISO-6の唯一の許可ファイル）。
 * [com.actionstarter.services.routing.UrlConnectionHttpPostClient]と同型の設計判断
 * （SDK追加なし、ブロッキングI/Oは呼び出し側が`Dispatchers.IO`で実行する前提、
 * `disconnect()`を確実に呼ぶ）だが、`ai/`外のクラスを一切importしない自己完結実装として
 * 独立に持つ（T-AIISO-6が検出する迂回経路そのものを避けるため）。
 */
internal class UrlConnectionHttpRangeClient : HttpRangeClient {
    override fun open(url: String, rangeStartInclusive: Long): HttpRangeConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        if (rangeStartInclusive > 0) {
            connection.setRequestProperty("Range", "bytes=$rangeStartInclusive-")
        }

        val resolvedStatusCode = connection.responseCode
        return object : HttpRangeConnection {
            override val statusCode: Int = resolvedStatusCode
            override val body: InputStream =
                if (resolvedStatusCode in 200..299) connection.inputStream else connection.errorStream
                    ?: connection.inputStream

            override fun close() {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS: Int = 15_000
    }
}
