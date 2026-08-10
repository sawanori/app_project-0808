@file:OptIn(ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.ai.AiPreferences
import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceTier
import com.actionstarter.ai.model.HttpRangeClient
import com.actionstarter.ai.model.HttpRangeConnection
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelDownloadFailureReason
import com.actionstarter.ai.model.ModelDownloader
import com.actionstarter.ai.model.ModelLicense
import com.actionstarter.ai.model.ModelStorage
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.features.settings.DeviceUnsupportedReason
import com.actionstarter.features.settings.ModelDownloadStatus
import com.actionstarter.features.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P7-C6（計画書§12.6 T-SET-1〜6、F97、§13.5 S-6範囲裁定）。[SettingsViewModel]のGreenテスト。
 *
 * **E2（Robolectric必須）**: [SettingsViewModel]は`viewModelScope`（androidx.lifecycle、
 * `Dispatchers.Main.immediate`前提）を使うため`@RunWith(AndroidJUnit4::class)`＋
 * `Dispatchers.setMain`（[DepartureViewModelTest]と同型）。
 *
 * **[ModelDownloader]は本物を使う**（[com.actionstarter.ai.model.ModelDownloaderTest]と同じ
 * 方針。`ModelDownloader`自体はinterface化されていない具象クラスのため〔ADR-0048の対象4型に
 * 含まれない〕、その下位協力者（[ModelStorage]／[com.actionstarter.ai.model.HttpRangeClient]）を
 * fakeへ差し替えることでE2完結させる）。[com.actionstarter.ai.model.ModelVerifierImpl]も本物を
 * 使い、DL完了後のSHA-256検証パイプライン自体を通す。
 *
 * T-SET-4の「DL中（進捗）」検証（[onDownloadRequested_progressReflectsDownloadingState]）は
 * [ModelDownloader.download]が内部で`withContext(Dispatchers.IO)`を使い実スレッドへホップする
 * ため、`CountDownLatch`で実スレッド側の進捗到達を待ち合わせる（`runTest`の仮想スケジューラは
 * 実スレッドの完了を認識できないため、素朴な`advanceUntilIdle()`だけでは中間状態を
 * 決定的に観測できない。KDoc内注記参照）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeAiPreferences(
        override var aiEnabled: Boolean = false,
        override var selectedModelId: String? = null
    ) : AiPreferences

    private class FakeDeviceCapability(
        private val tier: DeviceTier = DeviceTier.TIER_1_STANDARD,
        private val abiSupported: Boolean = true
    ) : DeviceCapability {
        override fun classify(): DeviceTier = tier
        override fun isAbiSupported(): Boolean = abiSupported
        override fun hasAvailableMemory(requiredBytes: Long): Boolean = true
    }

    /** [ModelStorage]のfake実装（[com.actionstarter.ai.model.ModelDownloaderTest]と同型）。 */
    private class FakeModelStorage(private val rootDir: File) : ModelStorage {
        var availableBytesOverride: Long = Long.MAX_VALUE / 4
        private var installed: ModelCatalogEntry? = null

        override fun installedModelPath(): String? = installed?.let { finalFile(it).absolutePath }
        override fun installedEntry(): ModelCatalogEntry? = installed?.takeIf { finalFile(it).isFile }
        override fun hasSufficientSpace(requiredBytes: Long): Boolean =
            availableBytesOverride >= (requiredBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong()

        override fun availableBytes(): Long = availableBytesOverride
        override fun partFile(entry: ModelCatalogEntry): File = File(rootDir, "${entry.id}.part")
        override fun finalFile(entry: ModelCatalogEntry): File = File(rootDir, "${entry.id}.final")

        override fun commit(entry: ModelCatalogEntry): Boolean {
            val source = partFile(entry)
            if (!source.isFile) return false
            val ok = source.renameTo(finalFile(entry))
            if (ok) installed = entry
            return ok
        }

        override fun delete(entry: ModelCatalogEntry) {
            partFile(entry).delete()
            finalFile(entry).delete()
            if (installed?.id == entry.id) installed = null
        }

        override fun deleteOrphanedPartFiles() {}

        /** テストの便宜: 事前に「導入済み」状態を作る（DLを経由せず直接installedへ反映する）。 */
        fun preinstall(entry: ModelCatalogEntry, content: ByteArray) {
            partFile(entry).apply {
                parentFile?.mkdirs()
                writeBytes(content)
            }
            commit(entry)
        }
    }

    /** [HttpRangeClient]のfake実装（[com.actionstarter.ai.model.ModelDownloaderTest]と同型）。 */
    private class FakeHttpRangeClient(
        private val statusCode: Int,
        private val bodyBytes: ByteArray,
        private val throwOnOpen: IOException? = null,
        private val wrapBody: (InputStream) -> InputStream = { it }
    ) : HttpRangeClient {
        override fun open(url: String, rangeStartInclusive: Long): HttpRangeConnection {
            throwOnOpen?.let { throw it }
            val fixedStatusCode = statusCode
            val body = wrapBody(ByteArrayInputStream(bodyBytes))
            return object : HttpRangeConnection {
                override val statusCode: Int = fixedStatusCode
                override val body: InputStream = body
                override fun close() {}
            }
        }
    }

    /**
     * `read()`の最初の呼び出しでブロックし、[reachedGate]をcountDownしてから[releaseGate]の
     * 解放を待つ`InputStream`ラッパー（T-SET-4進捗テスト専用の同期ゲート、クラスKDoc参照）。
     */
    private class GateControlledInputStream(
        private val delegate: InputStream,
        private val reachedGate: CountDownLatch,
        private val releaseGate: CountDownLatch
    ) : InputStream() {
        private var gateTriggered = false

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!gateTriggered) {
                gateTriggered = true
                reachedGate.countDown()
                releaseGate.await(5, TimeUnit.SECONDS)
            }
            return delegate.read(b, off, len)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("settings-vm-test").toFile().apply { deleteOnExit() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

    private fun entryFor(id: String, content: ByteArray): ModelCatalogEntry = ModelCatalogEntry(
        id = id,
        displayName = "Test Model $id",
        downloadUrl = "https://example.invalid/$id.litertlm",
        sha256 = sha256Hex(content),
        sizeBytes = content.size.toLong(),
        peakRamBytes = 1L,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false
    )

    private fun newViewModel(
        entry: ModelCatalogEntry,
        modelStorage: FakeModelStorage,
        httpClient: HttpRangeClient,
        aiPreferences: FakeAiPreferences = FakeAiPreferences(),
        deviceCapability: DeviceCapability = FakeDeviceCapability()
    ): SettingsViewModel = SettingsViewModel(
        aiPreferences = aiPreferences,
        modelDownloader = ModelDownloader(modelStorage, ModelVerifierImpl(), httpClient),
        modelStorage = modelStorage,
        deviceCapability = deviceCapability,
        selectedModel = entry
    )

    // ------------------------------------------------------------------
    // T-SET-1: 初回起動時aiEnabled==false
    // ------------------------------------------------------------------

    @Test
    fun initialUiState_aiDisabled_reflectsPreferencesDefault() {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", "content".toByteArray())
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)))

        assertFalse("初回表示のaiEnabledはAiPreferencesの既定値(false)を反映するべきです(T-SET-1)", viewModel.uiState.value.aiEnabled)
    }

    // ------------------------------------------------------------------
    // T-SET-2: トグルONで永続化
    // ------------------------------------------------------------------

    @Test
    fun onAiEnabledToggled_true_persistsThroughAiPreferences() {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", "content".toByteArray())
        val prefs = FakeAiPreferences()
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)), aiPreferences = prefs)

        viewModel.onAiEnabledToggled(true)

        assertTrue("AiPreferences.aiEnabledへ書き込まれるべきです(T-SET-2)", prefs.aiEnabled)
        assertTrue("uiState.aiEnabledも即時反映されるべきです", viewModel.uiState.value.aiEnabled)
    }

    @Test
    fun onAiEnabledToggled_falseAfterTrue_persistsFalse() {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", "content".toByteArray())
        val prefs = FakeAiPreferences()
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)), aiPreferences = prefs)

        viewModel.onAiEnabledToggled(true)
        viewModel.onAiEnabledToggled(false)

        assertFalse(prefs.aiEnabled)
        assertFalse(viewModel.uiState.value.aiEnabled)
    }

    // ------------------------------------------------------------------
    // T-SET-3: 非対応端末ではトグル無効・理由文言
    // ------------------------------------------------------------------

    @Test
    fun refresh_deviceUnsupportedDueToAbi_disablesToggle_exposesAbiReason() {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", "content".toByteArray())
        val viewModel = newViewModel(
            entry,
            storage,
            FakeHttpRangeClient(200, ByteArray(0)),
            deviceCapability = FakeDeviceCapability(tier = DeviceTier.TIER_1_STANDARD, abiSupported = false)
        )

        assertFalse("ABI非対応端末ではisDeviceSupportedがfalseであるべきです(T-SET-3)", viewModel.uiState.value.isDeviceSupported)
        assertEquals(DeviceUnsupportedReason.UNSUPPORTED_ABI, viewModel.uiState.value.deviceUnsupportedReason)
    }

    @Test
    fun refresh_deviceUnsupportedDueToRam_disablesToggle_exposesRamReason() {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", "content".toByteArray())
        val viewModel = newViewModel(
            entry,
            storage,
            FakeHttpRangeClient(200, ByteArray(0)),
            deviceCapability = FakeDeviceCapability(tier = DeviceTier.TIER_0_UNSUPPORTED, abiSupported = true)
        )

        assertFalse("RAM不足端末ではisDeviceSupportedがfalseであるべきです(T-SET-3)", viewModel.uiState.value.isDeviceSupported)
        assertEquals(DeviceUnsupportedReason.INSUFFICIENT_RAM, viewModel.uiState.value.deviceUnsupportedReason)
    }

    // T-SET-3安全網: 非対応端末ではonAiEnabledToggled(true)が無視される（エラー＆レスキューマップ#19）。
    @Test
    fun onAiEnabledToggled_whenDeviceUnsupported_isIgnored_doesNotPersist() {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", "content".toByteArray())
        val prefs = FakeAiPreferences()
        val viewModel = newViewModel(
            entry,
            storage,
            FakeHttpRangeClient(200, ByteArray(0)),
            aiPreferences = prefs,
            deviceCapability = FakeDeviceCapability(tier = DeviceTier.TIER_0_UNSUPPORTED)
        )

        viewModel.onAiEnabledToggled(true)

        assertFalse(
            "非対応端末ではONにできてはいけません（動かない機能をONにできない、エラー＆レスキューマップ#19）",
            prefs.aiEnabled
        )
        assertFalse(viewModel.uiState.value.aiEnabled)
    }

    // ------------------------------------------------------------------
    // T-SET-4: モデル状態表示（未DL／導入済み）
    // ------------------------------------------------------------------

    @Test
    fun initialUiState_modelNotInstalled_whenNoFileExists() {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", "content".toByteArray())
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)))

        assertEquals(ModelDownloadStatus.NotInstalled, viewModel.uiState.value.modelStatus)
    }

    @Test
    fun initialUiState_modelInstalled_whenFinalFileMatchesSelectedModel() {
        val storage = FakeModelStorage(tempDir())
        val content = "already-downloaded".toByteArray()
        val entry = entryFor("model-a", content)
        storage.preinstall(entry, content)
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)))

        assertEquals(
            "既にfinalFileが存在する場合はInstalled(導入済み・検証済み)であるべきです(T-SET-4)",
            ModelDownloadStatus.Installed,
            viewModel.uiState.value.modelStatus
        )
    }

    // ------------------------------------------------------------------
    // T-SET-4/6: DL成功・失敗
    // ------------------------------------------------------------------

    @Test
    fun onDownloadRequested_success_transitionsToInstalled_andPersistsSelectedModelId() = runTest {
        val storage = FakeModelStorage(tempDir())
        val content = ByteArray(4096) { (it % 251).toByte() }
        val entry = entryFor("model-a", content)
        val prefs = FakeAiPreferences()
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, content), aiPreferences = prefs)

        viewModel.onDownloadRequested().join()

        assertEquals(ModelDownloadStatus.Installed, viewModel.uiState.value.modelStatus)
        assertEquals(
            "DL成功後はAiPreferences.selectedModelIdへ反映されるべきです",
            entry.id,
            prefs.selectedModelId
        )
    }

    @Test
    fun onDownloadRequested_verificationFailure_surfacesFailedStatus_modelRemainsNotInstalled() = runTest {
        val storage = FakeModelStorage(tempDir())
        val content = "real-content".toByteArray()
        val entry = entryFor("model-a", content)
        // サーバが破損データ(検証不合格)を返すシナリオ: bodyBytesをcontentと同じ長さ
        // （sizeBytesチェックはpassさせ、SHA-256のみ不一致にする。T-MDL-11「サイズ一致・
        // ハッシュ不一致（改竄想定）」と同型。長さを変えるとModelDownloaderのSIZE_EXCEEDED
        // 分岐が先に発火し検証パイプラインへ到達しないため、必ず同じ長さにする）だが
        // 異なる内容にする。
        val corrupted = "real-CONTENT".toByteArray()
        check(corrupted.size == content.size) { "test fixture bug: corrupted must have the same length as content" }
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, corrupted))

        viewModel.onDownloadRequested().join()

        val status = viewModel.uiState.value.modelStatus
        assertTrue(
            "SHA-256不一致はFailed(VERIFICATION_FAILED)として表面化するべきです(T-SET-4/6、§8.6 #5)",
            status is ModelDownloadStatus.Failed && status.reason == ModelDownloadFailureReason.VERIFICATION_FAILED
        )
        assertNull("検証失敗したファイルはinstalledPathとして残らないべきです", storage.installedModelPath())
    }

    @Test
    fun onDownloadRequested_insufficientStorage_surfacesFailedStatus_doesNotThrow() = runTest {
        val storage = FakeModelStorage(tempDir())
        storage.availableBytesOverride = 0L
        val entry = entryFor("model-a", ByteArray(1000))
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(1000)))

        viewModel.onDownloadRequested().join()

        val status = viewModel.uiState.value.modelStatus
        assertTrue(
            "容量不足はFailed(INSUFFICIENT_STORAGE)として表面化しクラッシュしないべきです(T-SET-6)",
            status is ModelDownloadStatus.Failed && status.reason == ModelDownloadFailureReason.INSUFFICIENT_STORAGE
        )
    }

    @Test
    fun onDownloadRequested_networkFailure_surfacesFailedStatus_basicUnaffected() = runTest {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", ByteArray(1000))
        val prefs = FakeAiPreferences(aiEnabled = false)
        val viewModel = newViewModel(
            entry,
            storage,
            FakeHttpRangeClient(200, ByteArray(0), throwOnOpen = IOException("simulated network failure")),
            aiPreferences = prefs
        )

        viewModel.onDownloadRequested().join()

        val status = viewModel.uiState.value.modelStatus
        assertTrue(status is ModelDownloadStatus.Failed && status.reason == ModelDownloadFailureReason.NETWORK_ERROR)
        // T-SET-6「Basic機能には影響がない」の直接検証: DL失敗はaiEnabled等の無関係な状態を
        // 一切変えない。
        assertFalse(prefs.aiEnabled)
        assertFalse(viewModel.uiState.value.aiEnabled)
    }

    // in-flightガード: DL中に再度呼んでも二重起動しない（EventSelectionViewModel.refreshJobと同型）。
    @Test
    fun onDownloadRequested_calledTwiceWhileInFlight_returnsSameJob_doesNotStartSecondConnection() = runTest {
        val storage = FakeModelStorage(tempDir())
        val content = ByteArray(4096) { (it % 251).toByte() }
        val entry = entryFor("model-a", content)
        val reachedGate = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val httpClient = FakeHttpRangeClient(200, content, wrapBody = { GateControlledInputStream(it, reachedGate, releaseGate) })
        val viewModel = newViewModel(entry, storage, httpClient)

        val firstJob = viewModel.onDownloadRequested()
        advanceUntilIdle()
        assertTrue("読み取りゲートへ到達するべきです", reachedGate.await(5, TimeUnit.SECONDS))

        val secondJob = viewModel.onDownloadRequested()
        assertEquals("in-flight中の再呼び出しは同一Jobを返すべきです", firstJob, secondJob)

        releaseGate.countDown()
        firstJob.join()
        assertEquals(ModelDownloadStatus.Installed, viewModel.uiState.value.modelStatus)
    }

    // T-SET-4: DL中(進捗)がuiStateへ反映される（クラスKDoc「CountDownLatchで実スレッド側の
    // 進捗到達を待ち合わせる」参照）。
    @Test
    fun onDownloadRequested_progressReflectsDownloadingState() = runTest {
        val storage = FakeModelStorage(tempDir())
        val content = ByteArray(4096) { (it % 251).toByte() }
        val entry = entryFor("model-a", content)
        val reachedGate = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val httpClient = FakeHttpRangeClient(200, content, wrapBody = { GateControlledInputStream(it, reachedGate, releaseGate) })
        val viewModel = newViewModel(entry, storage, httpClient)

        val job = viewModel.onDownloadRequested()
        advanceUntilIdle()
        assertTrue(
            "実IOスレッドが最初のread()（ゲート）へ到達するべきです",
            reachedGate.await(5, TimeUnit.SECONDS)
        )

        val statusWhileDownloading = viewModel.uiState.value.modelStatus
        assertTrue(
            "読み取り開始直前はDownloading状態であるべきです(T-SET-4「DL中(進捗)」)。実際: $statusWhileDownloading",
            statusWhileDownloading is ModelDownloadStatus.Downloading
        )
        assertEquals(content.size.toLong(), (statusWhileDownloading as ModelDownloadStatus.Downloading).totalBytes)

        releaseGate.countDown()
        job.join()

        assertEquals(ModelDownloadStatus.Installed, viewModel.uiState.value.modelStatus)
    }

    // ------------------------------------------------------------------
    // T-SET-5: 容量表示
    // ------------------------------------------------------------------

    @Test
    fun initialUiState_exposesRequiredAndAvailableBytes_withSafetyMargin() {
        val storage = FakeModelStorage(tempDir())
        storage.availableBytesOverride = 500_000L
        val entry = entryFor("model-a", ByteArray(300_000))
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)))

        val state = viewModel.uiState.value
        assertEquals(
            "requiredBytesForDownloadはsizeBytes×CAPACITY_SAFETY_FACTORであるべきです(T-SET-5、§95.6)",
            (300_000L * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong(),
            state.requiredBytesForDownload
        )
        assertEquals(500_000L, state.availableBytes)
        assertTrue(
            "300,000×1.5=450,000 > 500,000の空きは足りているはずなのでhasInsufficientStorageはfalse",
            !state.hasInsufficientStorage
        )
    }

    @Test
    fun initialUiState_insufficientStorage_isFlagged() {
        val storage = FakeModelStorage(tempDir())
        storage.availableBytesOverride = 100_000L
        val entry = entryFor("model-a", ByteArray(300_000))
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)))

        assertTrue(
            "300,000×1.5=450,000 > 100,000の空きなので容量不足として明示されるべきです(T-SET-5)",
            viewModel.uiState.value.hasInsufficientStorage
        )
    }

    // ------------------------------------------------------------------
    // 削除導線
    // ------------------------------------------------------------------

    @Test
    fun onDeleteRequested_removesInstalledModel_revertsToNotInstalled() {
        val storage = FakeModelStorage(tempDir())
        val content = "installed".toByteArray()
        val entry = entryFor("model-a", content)
        storage.preinstall(entry, content)
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)))
        assertEquals(ModelDownloadStatus.Installed, viewModel.uiState.value.modelStatus)

        viewModel.onDeleteRequested()

        assertEquals(ModelDownloadStatus.NotInstalled, viewModel.uiState.value.modelStatus)
        assertNull(storage.installedModelPath())
    }

    @Test
    fun onDeleteRequested_whenNothingInstalled_doesNotThrow() {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", ByteArray(10))
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)))

        viewModel.onDeleteRequested()

        assertEquals(ModelDownloadStatus.NotInstalled, viewModel.uiState.value.modelStatus)
    }
}
