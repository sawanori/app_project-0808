package com.actionstarter.ai.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * P7-C4（計画書§12.4・T-MDL-6〜8・T-MDL-16・F88・ADR-0053／ADR-0054）。[ModelDownloader]の
 * Greenテスト。
 *
 * **E1（純JVM）**: [ModelDownloader]自身は`android.*`を一切importしない（`ai/model/
 * ModelDownloader.kt`実測）。本ファイルは[ModelStorage]をfake実装（[FakeModelStorage]、
 * `Context`非依存の一時ディレクトリベース）へ、HTTP接続を[HttpRangeClient]のfake実装
 * （[FakeHttpRangeClient]）へそれぞれ差し替えることでRobolectricを介さずJVM上で完結させる
 * （タスク指示「実HTTP DLのテストはfake HttpClient/URLで」）。[ModelVerifier]は本物の
 * [ModelVerifierImpl]を使う（android非依存、`ModelVerifierTest`で確認済み。DL完了後の
 * 検証パイプライン自体を本物のロジックで検証するため）。
 *
 * **実ネットワークDLは一切行わない**（P7-C0が`build/models/`へ取得済みの実モデルも本ファイルは
 * 参照しない。タスク制約「本サイクルは実DLを実行しない」）。
 */
class ModelDownloaderTest {

    /** [ModelStorage]のfake実装。`Context`非依存の一時ディレクトリを使いE1を維持する。 */
    private class FakeModelStorage(private val rootDir: File) : ModelStorage {
        /** [hasSufficientSpace]が参照する「現在の空き容量」。既定は潤沢（テストで上書きする）。 */
        var availableBytes: Long = Long.MAX_VALUE / 4

        /** [commit]を失敗させたいテスト用のスイッチ（既定は成功）。 */
        var commitShouldSucceed: Boolean = true

        val deletedEntries: MutableList<ModelCatalogEntry> = mutableListOf()

        // ModelDownloaderは呼ばないため未使用（本fakeのテスト対象外）。
        override fun installedModelPath(): String? = null
        override fun installedEntry(): ModelCatalogEntry? = null

        override fun hasSufficientSpace(requiredBytes: Long): Boolean =
            availableBytes >= (requiredBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong()

        override fun partFile(entry: ModelCatalogEntry): File = File(rootDir, "${entry.id}.part")

        override fun finalFile(entry: ModelCatalogEntry): File = File(rootDir, "${entry.id}.final")

        override fun commit(entry: ModelCatalogEntry): Boolean {
            if (!commitShouldSucceed) return false
            val source = partFile(entry)
            if (!source.isFile) return false
            return source.renameTo(finalFile(entry))
        }

        override fun delete(entry: ModelCatalogEntry) {
            deletedEntries.add(entry)
            partFile(entry).delete()
            finalFile(entry).delete()
        }

        override fun deleteOrphanedPartFiles() {
            // ModelDownloaderは呼ばないため未使用（本fakeのテスト対象外）。
        }
    }

    /** [HttpRangeClient]のfake実装。実ネットワークを一切使わない。 */
    private class FakeHttpRangeClient(
        private val statusCode: Int,
        private val bodyBytes: ByteArray,
        private val throwOnOpen: IOException? = null
    ) : HttpRangeClient {
        var lastRequestedRangeStart: Long = -1L
            private set

        var openCallCount: Int = 0
            private set

        override fun open(url: String, rangeStartInclusive: Long): HttpRangeConnection {
            openCallCount += 1
            lastRequestedRangeStart = rangeStartInclusive
            throwOnOpen?.let { throw it }

            val fixedStatusCode = statusCode
            val fixedBody = bodyBytes
            return object : HttpRangeConnection {
                override val statusCode: Int = fixedStatusCode
                override val body: InputStream = ByteArrayInputStream(fixedBody)
                override fun close() {
                    // fakeは実接続を持たないため何もしない。
                }
            }
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("model-downloader-test").toFile().apply { deleteOnExit() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

    private fun entryFor(
        id: String,
        content: ByteArray,
        downloadUrl: String = "https://example.invalid/$id.litertlm"
    ): ModelCatalogEntry = ModelCatalogEntry(
        id = id,
        displayName = "Test $id",
        downloadUrl = downloadUrl,
        sha256 = sha256Hex(content),
        sizeBytes = content.size.toLong(),
        peakRamBytes = 1L,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false
    )

    // ------------------------------------------------------------------
    // T-MDL-16: HTTPS必須
    // ------------------------------------------------------------------

    @Test
    fun download_insecureUrl_returnsFailedInsecureUrl_noConnectionOpened() = runTest {
        val content = "irrelevant".toByteArray()
        val entry = entryFor("insecure", content, downloadUrl = "http://example.invalid/insecure.litertlm")
        val storage = FakeModelStorage(tempDir())
        val http = FakeHttpRangeClient(statusCode = 200, bodyBytes = content)
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)

        val result = downloader.download(entry) { _, _ -> }

        assertTrue(result is ModelDownloadResult.Failed)
        assertEquals(ModelDownloadFailureReason.INSECURE_URL, (result as ModelDownloadResult.Failed).reason)
        assertEquals("平文URLでは接続を試みてはいけません(T-MDL-16)", 0, http.openCallCount)
    }

    // ------------------------------------------------------------------
    // 容量ガード（§8.6 #3・§95.6。ModelStorage.hasSufficientSpaceを使う。DeviceCapabilityは
    // RAM/ABI判定専用でありストレージ容量の概念を持たないため使用しない）
    // ------------------------------------------------------------------

    @Test
    fun download_insufficientStorage_returnsFailedInsufficientStorage_noConnectionOpened() = runTest {
        val content = "irrelevant".toByteArray()
        val entry = entryFor("low-space", content)
        val storage = FakeModelStorage(tempDir()).apply { availableBytes = 0L }
        val http = FakeHttpRangeClient(statusCode = 200, bodyBytes = content)
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)

        val result = downloader.download(entry) { _, _ -> }

        assertTrue(result is ModelDownloadResult.Failed)
        assertEquals(ModelDownloadFailureReason.INSUFFICIENT_STORAGE, (result as ModelDownloadResult.Failed).reason)
        assertEquals("容量不足時は接続を試みてはいけません(§95.6)", 0, http.openCallCount)
    }

    // ------------------------------------------------------------------
    // T-MDL-6/7: レジュームDL
    // ------------------------------------------------------------------

    // T-MDL-6: 正常系 - 既存部分ファイル長からRangeヘッダのオフセットが正しく決まる
    @Test
    fun download_existingPartialFile_requestsRangeFromExistingLength() = runTest {
        val fullContent = "0123456789ABCDEF".toByteArray() // 16バイト
        val entry = entryFor("resume", fullContent)
        val storage = FakeModelStorage(tempDir())
        storage.partFile(entry).writeBytes(fullContent.copyOfRange(0, 6)) // 既存6バイト
        val remaining = fullContent.copyOfRange(6, fullContent.size)
        val http = FakeHttpRangeClient(statusCode = 206, bodyBytes = remaining)
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)

        val result = downloader.download(entry) { _, _ -> }

        assertEquals("既存6バイトの続きからRangeを要求するべきです(T-MDL-6)", 6L, http.lastRequestedRangeStart)
        assertTrue("206で正しく再開できれば検証・コミットまで成功するはずです: $result", result is ModelDownloadResult.Success)
        assertArrayEquals(fullContent, storage.finalFile(entry).readBytes())
    }

    // T-MDL-7: 異常系 - サーバがRangeを無視して200(全体)を返した → 部分ファイルを破棄して
    // 先頭からやり直す（追記して壊さない）
    @Test
    fun download_serverIgnoresRangeAndReturns200_discardsPartialAndRestartsFromScratch() = runTest {
        val fullContent = "ABCDEFGHIJKLMNOP".toByteArray() // 16バイト
        val entry = entryFor("range-ignored", fullContent)
        val storage = FakeModelStorage(tempDir())
        // 既存の(食い違う)部分ファイル。追記されると壊れたファイルになる。
        storage.partFile(entry).writeBytes("XXXXXX".toByteArray())
        val http = FakeHttpRangeClient(statusCode = 200, bodyBytes = fullContent)
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)

        val result = downloader.download(entry) { _, _ -> }

        assertTrue(
            "Rangeが無視された場合でも先頭から正しく再取得し成功するはずです(T-MDL-7): $result",
            result is ModelDownloadResult.Success
        )
        assertArrayEquals(
            "追記されて壊れていないこと(部分ファイルを破棄し先頭から書き直したこと)を確認する",
            fullContent,
            storage.finalFile(entry).readBytes()
        )
    }

    // ------------------------------------------------------------------
    // T-MDL-8: 無限DL防止
    // ------------------------------------------------------------------

    @Test
    fun download_bodyExceedsCatalogSize_abortsWithSizeExceeded() = runTest {
        val declaredContent = "SHORT".toByteArray() // sizeBytes=5のカタログ定義
        val entry = entryFor("size-exceeded", declaredContent)
        val oversizedBody = "THIS_IS_LONGER_THAN_FIVE_BYTES".toByteArray() // 実際は31バイト
        val storage = FakeModelStorage(tempDir())
        val http = FakeHttpRangeClient(statusCode = 200, bodyBytes = oversizedBody)
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)

        val result = downloader.download(entry) { _, _ -> }

        assertTrue(result is ModelDownloadResult.Failed)
        assertEquals(ModelDownloadFailureReason.SIZE_EXCEEDED, (result as ModelDownloadResult.Failed).reason)
    }

    // ------------------------------------------------------------------
    // HTTP/ネットワーク異常系
    // ------------------------------------------------------------------

    @Test
    fun download_httpErrorStatus_returnsFailedHttpError() = runTest {
        val content = "x".toByteArray()
        val entry = entryFor("http-error", content)
        val storage = FakeModelStorage(tempDir())
        val http = FakeHttpRangeClient(statusCode = 404, bodyBytes = ByteArray(0))
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)

        val result = downloader.download(entry) { _, _ -> }

        assertTrue(result is ModelDownloadResult.Failed)
        assertEquals(ModelDownloadFailureReason.HTTP_ERROR, (result as ModelDownloadResult.Failed).reason)
    }

    @Test
    fun download_networkErrorDuringOpen_returnsFailedNetworkError() = runTest {
        val content = "x".toByteArray()
        val entry = entryFor("network-error", content)
        val storage = FakeModelStorage(tempDir())
        val http = FakeHttpRangeClient(
            statusCode = 200,
            bodyBytes = content,
            throwOnOpen = IOException("connection refused")
        )
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)

        val result = downloader.download(entry) { _, _ -> }

        assertTrue(result is ModelDownloadResult.Failed)
        assertEquals(ModelDownloadFailureReason.NETWORK_ERROR, (result as ModelDownloadResult.Failed).reason)
    }

    // ------------------------------------------------------------------
    // DL完了後の検証・コミット（ADR-0054、§8.6 #5「DL完了直後の1回限り検証」）
    // ------------------------------------------------------------------

    @Test
    fun download_successfulFreshDownload_verifiesAndCommits() = runTest {
        val content = "full-model-content-bytes".toByteArray()
        val entry = entryFor("success", content)
        val storage = FakeModelStorage(tempDir())
        val http = FakeHttpRangeClient(statusCode = 200, bodyBytes = content)
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)
        val progressCalls = mutableListOf<Pair<Long, Long>>()

        val result = downloader.download(entry) { downloaded, total -> progressCalls.add(downloaded to total) }

        assertTrue(result is ModelDownloadResult.Success)
        assertFalse("成功後は.partファイルが残っていてはいけません(T-MDL-12と同じ原則)", storage.partFile(entry).exists())
        assertTrue(storage.finalFile(entry).isFile)
        assertArrayEquals(content, storage.finalFile(entry).readBytes())
        assertTrue("進捗コールバックが最低1回は呼ばれるはずです(F88「進捗」)", progressCalls.isNotEmpty())
        assertEquals(content.size.toLong(), progressCalls.last().first)
        assertEquals(entry.sizeBytes, progressCalls.last().second)
    }

    // 異常系: DL自体は完走したがバイト列がカタログのSHA-256と一致しない(改竄・破損) →
    // Fallback相当のFailed(VERIFICATION_FAILED)＋ファイル削除（信頼境界＝検証なしに使わない）
    @Test
    fun download_bodyDoesNotMatchDeclaredHash_returnsFailedVerificationFailed_deletesFile() = runTest {
        val declaredContent = "expected-bytes-here!".toByteArray()
        val entry = entryFor("hash-mismatch", declaredContent)
        val tamperedBody = ByteArray(declaredContent.size) { 0x00 } // 同じ長さ・別内容
        val storage = FakeModelStorage(tempDir())
        val http = FakeHttpRangeClient(statusCode = 200, bodyBytes = tamperedBody)
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)

        val result = downloader.download(entry) { _, _ -> }

        assertTrue(result is ModelDownloadResult.Failed)
        assertEquals(ModelDownloadFailureReason.VERIFICATION_FAILED, (result as ModelDownloadResult.Failed).reason)
        assertTrue(
            "検証失敗時はModelStorage.deleteが呼ばれるべきです(§8.6 #5、信頼境界)",
            storage.deletedEntries.contains(entry)
        )
        assertFalse(storage.finalFile(entry).exists())
    }

    @Test
    fun download_commitFails_returnsFailedStorageError() = runTest {
        val content = "content".toByteArray()
        val entry = entryFor("commit-fails", content)
        val storage = FakeModelStorage(tempDir()).apply { commitShouldSucceed = false }
        val http = FakeHttpRangeClient(statusCode = 200, bodyBytes = content)
        val downloader = ModelDownloader(storage, ModelVerifierImpl(), http)

        val result = downloader.download(entry) { _, _ -> }

        assertTrue(result is ModelDownloadResult.Failed)
        assertEquals(ModelDownloadFailureReason.STORAGE_ERROR, (result as ModelDownloadResult.Failed).reason)
    }
}
