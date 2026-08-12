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
import com.actionstarter.analytics.AnalyticsDomain
import com.actionstarter.analytics.AnalyticsStore
import com.actionstarter.domain.model.PersonalExecutionProfile
import com.actionstarter.features.settings.DeleteBehaviorLogResult
import com.actionstarter.features.settings.DeviceUnsupportedReason
import com.actionstarter.features.settings.ModelDownloadStatus
import com.actionstarter.features.settings.ModelOption
import com.actionstarter.features.settings.SettingsUiState
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
 * P7-C6（計画書§12.6 T-SET-1〜6、F97、§13.5 S-6範囲裁定）／Phase 8.5 F-B（計画書
 * `docs/plans/phase8.5-adaptive-model-selection.md`§6 T-P85-16〜23、ADR-0062）。
 * [SettingsViewModel]のテスト。
 *
 * **Phase 8.5 F-Bでの変更（T-P85-23、意図・アサーションは維持）**: `SettingsViewModel`が
 * 単数`selectedModel`から`availableModels: List<ModelCatalogEntry>`へ変わったことに伴い、
 * 既存18件は[newViewModel]ヘルパーを`availableModels = listOf(entry)`へ差し替えるのみで
 * 対応した（各テストメソッド本体は不変）。[SettingsUiState.modelStatus]（単数）は
 * [SettingsUiState.models]（複数行、常に「自動」＋[availableModels]分）へ変わったため、
 * 本ファイル内の拡張プロパティ[SettingsUiState.soleSpecificModelStatus]で
 * 「[ModelOption.Specific]側の唯一の行のstatus」を取り出し、既存18件のアサーションを
 * 意味的に不変のまま最小差分で書き換えた（旧名→新名の対応表・各理由は完了報告に記載）。
 *
 * **Green段階で発見した例外1件**: 上記18件のうち`onDownloadRequested_success_
 * transitionsToInstalled_andPersistsSelectedModelId`のみ、Red時点の機械的書換では
 * アサーション（`prefs.selectedModelId == entry.id`）を温存していたが、これは承認済み設計
 * （計画書§3設計F-B-2「ダウンロード完了は選択を自動変更しない」）と矛盾する齟齬だった。
 * Green実装時に発見し、`onDownloadRequested_success_transitionsToInstalled_
 * doesNotChangeSelectedModelId`へ改名のうえアサーションを是正した（該当テスト直上のコメント
 * 参照。実装ではなくテスト側の書換漏れが原因）。
 *
 * **Green実装完了（本コミット）**: [SettingsViewModel.refresh]・[SettingsViewModel.
 * onModelSelected]・[SettingsViewModel.onDownloadRequested]・[SettingsViewModel.
 * onDeleteRequested]を実装した。§11確認事項1（ADR-0062、`isMemoryInsufficient`）のテストは
 * `uiState_models_isMemoryInsufficient_trueOnlyForEntriesExceedingCurrentAvailableMemory`を
 * Green段階で追加した（§6起票時点のT-P85-16〜22には独立IDが割り当てられていなかった）。
 *
 * **E2（Robolectric必須）**: [SettingsViewModel]は`viewModelScope`を使うため
 * `@RunWith(AndroidJUnit4::class)`＋`Dispatchers.setMain`。
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

    /**
     * Phase 10 C4新設（計画書§3.4、T-P10-16/17/18、Red段階）。[clearAllResult]で
     * [AnalyticsStore.clearAll]の戻り値を制御し、[clearAllCallCount]で誤タップ防止ガード
     * （確認前にclearAllが呼ばれていないこと）を検証する。他4つのrecordXxxメソッドは
     * Settings画面の削除導線と無関係のため呼び出し記録を持たない
     * （[RecoveryViewModelAnalyticsTest.FakeAnalyticsStore]等、既存Phase 10テストと同型の
     * 最小fake）。
     */
    private class FakeAnalyticsStore(
        private val clearAllResult: Result<Unit> = Result.success(Unit)
    ) : AnalyticsStore {
        var clearAllCallCount: Int = 0
            private set

        override suspend fun recordStepDone(eventCategory: String, stepType: String, durationMs: Long?) = Unit
        override suspend fun recordStepSkipped(eventCategory: String, semanticAction: String) = Unit
        override suspend fun recordDelayDetected(eventCategory: String) = Unit
        override suspend fun recordRecoverySelected(eventCategory: String, semanticAction: String) = Unit
        override suspend fun recordAiWordingOutcome(
            domain: AnalyticsDomain,
            eventCategory: String,
            aiAdopted: Boolean,
            fallbackReason: String?
        ) = Unit

        override suspend fun getProfile(eventCategory: String): PersonalExecutionProfile? = null

        override suspend fun clearAll(): Result<Unit> {
            clearAllCallCount++
            return clearAllResult
        }
    }

    /**
     * [availableMemoryBytes]はPhase 8.5 F-B追加（§11確認事項1、`isMemoryInsufficient`用）。
     * 既定`Long.MAX_VALUE`は既存呼び出し全件の「常にhasAvailableMemory=true」挙動を変えない
     * 後方互換パラメータ。
     */
    private class FakeDeviceCapability(
        private val tier: DeviceTier = DeviceTier.TIER_1_STANDARD,
        private val abiSupported: Boolean = true,
        private val availableMemoryBytes: Long = Long.MAX_VALUE
    ) : DeviceCapability {
        override fun classify(): DeviceTier = tier
        override fun isAbiSupported(): Boolean = abiSupported
        override fun hasAvailableMemory(requiredBytes: Long): Boolean = availableMemoryBytes >= requiredBytes
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

        /** Phase 8.5 F-B追加（T-P85-16〜18用）: [entries]全件を「導入済み」にする。 */
        fun preinstallAll(entries: List<ModelCatalogEntry>, content: ByteArray) {
            entries.forEach { entry ->
                partFile(entry).apply {
                    parentFile?.mkdirs()
                    writeBytes(content)
                }
                val ok = partFile(entry).renameTo(finalFile(entry))
                check(ok) { "test fixture bug: rename failed for ${entry.id}" }
            }
            // installed（単一値、明示選択解決専用）は最後のentryのままでよい——本fakeの
            // installedEntry()はT-SET-4系（単一モデル）用であり、Phase 8.5のmodels一覧構築は
            // finalFile().isFileを個別に見る設計（本番ModelSelector/SettingsViewModelと同型）。
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

    private fun entryFor(id: String, content: ByteArray, peakRamBytes: Long = 1L): ModelCatalogEntry = ModelCatalogEntry(
        id = id,
        displayName = "Test Model $id",
        downloadUrl = "https://example.invalid/$id.litertlm",
        sha256 = sha256Hex(content),
        sizeBytes = content.size.toLong(),
        peakRamBytes = peakRamBytes,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false
    )

    /**
     * Phase 8.5 F-Bで`selectedModel: ModelCatalogEntry`単数パラメータが`availableModels:
     * List<ModelCatalogEntry>`へ変わったことに伴う変更点はここへ集約した（T-P85-23）。
     * 既存18テストは`availableModels = listOf(entry)`（要素数1）で呼び出すことで、
     * 「アプリのカタログ候補が1つだけの状況でのGateway機構検証」という元の意図を保つ。
     */
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
        availableModels = listOf(entry)
    )

    /**
     * Phase 8.5 F-B追加（T-P85-23）。既存18テストは常に`availableModels`へ1件だけ渡すため、
     * [SettingsUiState.models]のうち[ModelOption.Specific]側は必ず1行だけ存在する。
     * 旧`uiState.modelStatus`（単数）と意味的に同じ値をこの1行から取り出す。
     */
    private val SettingsUiState.soleSpecificModelStatus: ModelDownloadStatus
        get() = models.single { it.option is ModelOption.Specific }.status
            ?: error("test fixture bug: Specific row must always have a non-null status")

    private val SettingsUiState.soleSpecificRequiredBytes: Long
        get() = models.single { it.option is ModelOption.Specific }.requiredBytesForDownload

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

        assertEquals(ModelDownloadStatus.NotInstalled, viewModel.uiState.value.soleSpecificModelStatus)
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
            viewModel.uiState.value.soleSpecificModelStatus
        )
    }

    // ------------------------------------------------------------------
    // T-SET-4/6: DL成功・失敗
    // ------------------------------------------------------------------

    // **Green段階で発見・修正した齟齬（捏造禁止・原因調査のうえ報告）**: 本テストは元々
    // 「DL成功後はAiPreferences.selectedModelIdへentry.idが反映される」ことを検証していた
    // （旧・単一モデル版の挙動をT-P85-23の機械的書換で温存したもの）。しかしPhase 8.5 F-Bの
    // 承認済み設計（計画書§3設計F-B-2「『選択』と『ダウンロード』は別アクションとして分離する
    // （ダウンロード完了は選択を自動変更しない。§7『DL中に選択変更』参照）」、
    // [SettingsViewModel.onModelSelected]のKDocにも明記）はこれと矛盾する。Green実装（本ファイルと
    // 同時に完成した[SettingsViewModel.onDownloadRequested]）は選択を変更しない仕様で実装した
    // ため、本テスト名とアサーションを是正した（実装ではなくテスト側の書換漏れが原因）。
    @Test
    fun onDownloadRequested_success_transitionsToInstalled_doesNotChangeSelectedModelId() = runTest {
        val storage = FakeModelStorage(tempDir())
        val content = ByteArray(4096) { (it % 251).toByte() }
        val entry = entryFor("model-a", content)
        val prefs = FakeAiPreferences(selectedModelId = AiPreferences.AUTO_SELECT_MODEL_ID)
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, content), aiPreferences = prefs)

        viewModel.onDownloadRequested(entry).join()

        assertEquals(ModelDownloadStatus.Installed, viewModel.uiState.value.soleSpecificModelStatus)
        assertEquals(
            "ダウンロード完了は選択(AiPreferences.selectedModelId)を自動変更するべきではありません" +
                "（『選択』と『ダウンロード』は別アクション。計画書§3設計F-B-2・§7『DL中に選択変更』参照）",
            AiPreferences.AUTO_SELECT_MODEL_ID,
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

        viewModel.onDownloadRequested(entry).join()

        val status = viewModel.uiState.value.soleSpecificModelStatus
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

        viewModel.onDownloadRequested(entry).join()

        val status = viewModel.uiState.value.soleSpecificModelStatus
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

        viewModel.onDownloadRequested(entry).join()

        val status = viewModel.uiState.value.soleSpecificModelStatus
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

        val firstJob = viewModel.onDownloadRequested(entry)
        advanceUntilIdle()
        assertTrue("読み取りゲートへ到達するべきです", reachedGate.await(5, TimeUnit.SECONDS))

        val secondJob = viewModel.onDownloadRequested(entry)
        assertEquals("in-flight中の再呼び出しは同一Jobを返すべきです", firstJob, secondJob)

        releaseGate.countDown()
        firstJob.join()
        assertEquals(ModelDownloadStatus.Installed, viewModel.uiState.value.soleSpecificModelStatus)
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

        val job = viewModel.onDownloadRequested(entry)
        advanceUntilIdle()
        assertTrue(
            "実IOスレッドが最初のread()（ゲート）へ到達するべきです",
            reachedGate.await(5, TimeUnit.SECONDS)
        )

        val statusWhileDownloading = viewModel.uiState.value.soleSpecificModelStatus
        assertTrue(
            "読み取り開始直前はDownloading状態であるべきです(T-SET-4「DL中(進捗)」)。実際: $statusWhileDownloading",
            statusWhileDownloading is ModelDownloadStatus.Downloading
        )
        assertEquals(content.size.toLong(), (statusWhileDownloading as ModelDownloadStatus.Downloading).totalBytes)

        releaseGate.countDown()
        job.join()

        assertEquals(ModelDownloadStatus.Installed, viewModel.uiState.value.soleSpecificModelStatus)
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
            state.soleSpecificRequiredBytes
        )
        assertEquals(500_000L, state.availableBytes)
        assertTrue(
            "300,000×1.5=450,000 > 500,000の空きは足りているはずなので不足扱いにならない",
            state.availableBytes >= state.soleSpecificRequiredBytes
        )
    }

    @Test
    fun initialUiState_insufficientStorage_isFlagged() {
        val storage = FakeModelStorage(tempDir())
        storage.availableBytesOverride = 100_000L
        val entry = entryFor("model-a", ByteArray(300_000))
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)))

        val state = viewModel.uiState.value
        assertTrue(
            "300,000×1.5=450,000 > 100,000の空きなので容量不足として明示されるべきです(T-SET-5)",
            state.availableBytes < state.soleSpecificRequiredBytes
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
        assertEquals(ModelDownloadStatus.Installed, viewModel.uiState.value.soleSpecificModelStatus)

        viewModel.onDeleteRequested(entry)

        assertEquals(ModelDownloadStatus.NotInstalled, viewModel.uiState.value.soleSpecificModelStatus)
        assertNull(storage.installedModelPath())
    }

    @Test
    fun onDeleteRequested_whenNothingInstalled_doesNotThrow() {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", ByteArray(10))
        val viewModel = newViewModel(entry, storage, FakeHttpRangeClient(200, ByteArray(0)))

        viewModel.onDeleteRequested(entry)

        assertEquals(ModelDownloadStatus.NotInstalled, viewModel.uiState.value.soleSpecificModelStatus)
    }

    // ------------------------------------------------------------------
    // Phase 8.5 F-B新規（計画書§6 T-P85-16〜22、ADR-0062）。以下は全件[SettingsViewModel.refresh]
    // 等が`TODO()`のためNotImplementedErrorでRedになるのが正しい。
    // ------------------------------------------------------------------

    private fun gemma4LikeEntry(): ModelCatalogEntry = entryFor("gemma4-like", "gemma4-like-content".toByteArray())
    private fun qwen06bLikeEntry(): ModelCatalogEntry = entryFor("qwen06b-like", "qwen06b-like-content".toByteArray())

    private fun newMultiModelViewModel(
        storage: FakeModelStorage,
        aiPreferences: FakeAiPreferences = FakeAiPreferences(),
        deviceCapability: DeviceCapability = FakeDeviceCapability()
    ): SettingsViewModel = SettingsViewModel(
        aiPreferences = aiPreferences,
        modelDownloader = ModelDownloader(storage, ModelVerifierImpl(), FakeHttpRangeClient(200, ByteArray(0))),
        modelStorage = storage,
        deviceCapability = deviceCapability,
        availableModels = listOf(gemma4LikeEntry(), qwen06bLikeEntry())
    )

    // T-P85-16: 正常 - モデル一覧が「自動」「Gemma4」「Qwen0.6B」の3件で構成される
    // （Qwen1.7B非含有。availableModelsの既定候補集合そのもの）
    @Test
    fun uiState_models_containsAutoAndBothCandidates_only() {
        val storage = FakeModelStorage(tempDir())
        val viewModel = newMultiModelViewModel(storage)

        val options = viewModel.uiState.value.models.map { it.option }
        assertEquals(3, options.size)
        assertTrue(options.contains(ModelOption.Auto))
        assertTrue(options.contains(ModelOption.Specific(gemma4LikeEntry())))
        assertTrue(options.contains(ModelOption.Specific(qwen06bLikeEntry())))
    }

    // T-P85-17: 正常 - 各行の状態（未DL/DL中/検証済み）が個別のインストール状況を反映する
    @Test
    fun uiState_models_eachRowReflectsIndividualInstallState() {
        val storage = FakeModelStorage(tempDir())
        storage.preinstall(gemma4LikeEntry(), "gemma4-like-content".toByteArray())
        val viewModel = newMultiModelViewModel(storage)

        val gemmaRow = viewModel.uiState.value.models.single { it.option == ModelOption.Specific(gemma4LikeEntry()) }
        val qwenRow = viewModel.uiState.value.models.single { it.option == ModelOption.Specific(qwen06bLikeEntry()) }
        assertEquals(ModelDownloadStatus.Installed, gemmaRow.status)
        assertEquals(ModelDownloadStatus.NotInstalled, qwenRow.status)
    }

    // T-P85-18: 正常 - TIER_1端末→Qwen0.6B行に推奨バッジ／TIER_2端末→Gemma4行に推奨バッジ
    @Test
    fun uiState_models_recommendationBadge_followsDeviceTier() {
        val storage = FakeModelStorage(tempDir())
        val tier1ViewModel = newMultiModelViewModel(storage, deviceCapability = FakeDeviceCapability(tier = DeviceTier.TIER_1_STANDARD))
        val tier1Qwen = tier1ViewModel.uiState.value.models.single { it.option == ModelOption.Specific(qwen06bLikeEntry()) }
        assertTrue("TIER_1端末ではQwen0.6B行が推奨されるべきです(T-P85-18)", tier1Qwen.isRecommended)

        val tier2Storage = FakeModelStorage(tempDir())
        val tier2ViewModel = newMultiModelViewModel(tier2Storage, deviceCapability = FakeDeviceCapability(tier = DeviceTier.TIER_2_OPT_IN))
        val tier2Gemma = tier2ViewModel.uiState.value.models.single { it.option == ModelOption.Specific(gemma4LikeEntry()) }
        assertTrue("TIER_2端末ではGemma4行が推奨されるべきです(T-P85-18)", tier2Gemma.isRecommended)
    }

    // 計画書§11確認事項1（ADR-0062、コーディネーター確認済み「Gemma4等が現在のavailMemで
    // 不適合な場合の行内注記」）。T-P85-16〜22は計画書§6起票時点（Step 3・Red）では本項目に
    // 独立IDを割り当てていなかった（§11のユーザー確認はStep 2完了後・Step 3着手前に確定した
    // 事項だが、§6テストケース表への反映が漏れていた）。Green実装（本ファイルと同時に完成した
    // [SettingsViewModel.refresh]の`isMemoryInsufficient`計算）に伴うテスト網羅性確保のため、
    // 本テストをGreen段階で追加する（Red段階の事前記述ではない点を完了報告で開示する）。
    @Test
    fun uiState_models_isMemoryInsufficient_trueOnlyForEntriesExceedingCurrentAvailableMemory() {
        val storage = FakeModelStorage(tempDir())
        val bigEntry = entryFor("big-model", "big".toByteArray(), peakRamBytes = 4_000_000_000L)
        val smallEntry = entryFor("small-model", "small".toByteArray(), peakRamBytes = 100_000_000L)
        val margin = DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES
        val availMemBytes = 1_000_000_000L
        check(availMemBytes >= smallEntry.defaultProfilePeakRamBytes + margin) {
            "test fixture bug: smallEntry should fit within availMemBytes"
        }
        check(availMemBytes < bigEntry.defaultProfilePeakRamBytes + margin) {
            "test fixture bug: bigEntry should exceed availMemBytes"
        }
        val viewModel = SettingsViewModel(
            aiPreferences = FakeAiPreferences(),
            modelDownloader = ModelDownloader(storage, ModelVerifierImpl(), FakeHttpRangeClient(200, ByteArray(0))),
            modelStorage = storage,
            deviceCapability = FakeDeviceCapability(availableMemoryBytes = availMemBytes),
            availableModels = listOf(bigEntry, smallEntry)
        )

        val bigRow = viewModel.uiState.value.models.single { it.option == ModelOption.Specific(bigEntry) }
        val smallRow = viewModel.uiState.value.models.single { it.option == ModelOption.Specific(smallEntry) }
        assertTrue(
            "現在の空きメモリ(安全マージン込み)を満たせないモデルはisMemoryInsufficient=trueであるべきです(§11確認事項1)",
            bigRow.isMemoryInsufficient
        )
        assertFalse(
            "現在の空きメモリ(安全マージン込み)を満たせるモデルはisMemoryInsufficient=falseであるべきです",
            smallRow.isMemoryInsufficient
        )
    }

    // T-P85-19: 正常 - 「自動」選択でaiPreferences.selectedModelIdがAUTO_SELECT_MODEL_IDになる
    @Test
    fun onModelSelected_auto_persistsAutoSelectSentinel() {
        val storage = FakeModelStorage(tempDir())
        val prefs = FakeAiPreferences()
        val viewModel = newMultiModelViewModel(storage, aiPreferences = prefs)

        viewModel.onModelSelected(ModelOption.Auto)

        assertEquals(AiPreferences.AUTO_SELECT_MODEL_ID, prefs.selectedModelId)
        assertTrue(viewModel.uiState.value.models.single { it.option == ModelOption.Auto }.isSelected)
    }

    // T-P85-20: 異常 - TIER_0端末→モデル一覧全体が無効化される（既存isDeviceSupported連動の維持）
    @Test
    fun uiState_deviceUnsupported_isDeviceSupportedFalse_forModelList() {
        val storage = FakeModelStorage(tempDir())
        val viewModel = newMultiModelViewModel(storage, deviceCapability = FakeDeviceCapability(tier = DeviceTier.TIER_0_UNSUPPORTED))

        assertFalse(
            "TIER_0端末ではisDeviceSupportedがfalseとなり、モデル一覧(選択・DL導線)全体が" +
                "無効化されるべきです(T-P85-20)",
            viewModel.uiState.value.isDeviceSupported
        )
    }

    // T-P85-21: エッジ - 1モデルDL中は他行のDL/削除ボタンが無効化される
    // （UI層〔SettingsScreen〕の無効化条件はGreenで配線。ここではViewModel状態からその条件
    // 〔いずれかの行がDownloading〕を導出できることを検証する）
    @Test
    fun uiState_whileOneModelDownloading_otherRowsCanBeDetectedAsBusy() = runTest {
        val storage = FakeModelStorage(tempDir())
        val content = ByteArray(4096) { (it % 251).toByte() }
        val gemma = gemma4LikeEntry()
        val reachedGate = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val httpClient = FakeHttpRangeClient(200, content, wrapBody = { GateControlledInputStream(it, reachedGate, releaseGate) })
        val viewModel = SettingsViewModel(
            aiPreferences = FakeAiPreferences(),
            modelDownloader = ModelDownloader(storage, ModelVerifierImpl(), httpClient),
            modelStorage = storage,
            deviceCapability = FakeDeviceCapability(),
            availableModels = listOf(gemma, qwen06bLikeEntry())
        )

        val job = viewModel.onDownloadRequested(gemma)
        advanceUntilIdle()
        assertTrue(reachedGate.await(5, TimeUnit.SECONDS))

        assertTrue(
            "いずれかの行がDownloading中であることをuiState.modelsから判定できるべきです(T-P85-21の基盤)",
            viewModel.uiState.value.models.any { it.status is ModelDownloadStatus.Downloading }
        )

        releaseGate.countDown()
        job.join()
    }

    // T-P85-22: エッジ - 明示選択中のモデルを削除した直後のUI状態
    // （計画書§7: 選択値自体は変更せず、行の状態のみNotInstalledへ戻る）
    @Test
    fun onDeleteRequested_ofExplicitlySelectedModel_statusRevertsButSelectionUnchanged() {
        val storage = FakeModelStorage(tempDir())
        val gemma = gemma4LikeEntry()
        storage.preinstall(gemma, "gemma4-like-content".toByteArray())
        val prefs = FakeAiPreferences(selectedModelId = gemma.id)
        val viewModel = newMultiModelViewModel(storage, aiPreferences = prefs)

        viewModel.onDeleteRequested(gemma)

        val gemmaRow = viewModel.uiState.value.models.single { it.option == ModelOption.Specific(gemma) }
        assertEquals(
            "削除直後は行の状態がNotInstalledへ戻るべきです(T-P85-22)",
            ModelDownloadStatus.NotInstalled,
            gemmaRow.status
        )
        assertEquals(
            "選択値(AiPreferences.selectedModelId)自体は削除操作で変更されないべきです(計画書§7)",
            gemma.id,
            prefs.selectedModelId
        )
    }

    // ------------------------------------------------------------------
    // Phase 10 C4新規（計画書§3.4「全削除導線」、T-P10-16/17/18、Step 3 Red）。
    // onDeleteBehaviorLogRequested・onDeleteBehaviorLogDialogDismissed・
    // onDeleteBehaviorLogConfirmed・onDeleteBehaviorLogResultAcknowledgedは全件`TODO()`のため
    // NotImplementedErrorでRedになるのが正しい（Phase 8.5 F-B、本ファイル上部のクラスKDoc
    // 「以下は全件...TODO()のため...」の記述と同型のスキャフォールド）。コーディネーター指示に
    // よりGreenは次ラウンドへ分離するため、本ラウンドではRed化の確認のみを目的とする。
    // ------------------------------------------------------------------

    private fun newViewModelForDeletion(analyticsStore: FakeAnalyticsStore): SettingsViewModel {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", "content".toByteArray())
        return SettingsViewModel(
            aiPreferences = FakeAiPreferences(),
            modelDownloader = ModelDownloader(storage, ModelVerifierImpl(), FakeHttpRangeClient(200, ByteArray(0))),
            modelStorage = storage,
            deviceCapability = FakeDeviceCapability(),
            availableModels = listOf(entry),
            analyticsStore = analyticsStore
        )
    }

    // T-P10-18: 正常 - 削除ボタン相当の操作は確認ダイアログを表示するのみで、clearAll()を
    // 一切呼ばない（誤タップ防止）。
    @Test
    fun onDeleteBehaviorLogRequested_showsConfirmationDialog_doesNotCallClearAllYet() {
        val analyticsStore = FakeAnalyticsStore()
        val viewModel = newViewModelForDeletion(analyticsStore)

        viewModel.onDeleteBehaviorLogRequested()

        assertTrue(
            "確認ダイアログを表示するべきです(T-P10-18)",
            viewModel.uiState.value.isDeleteBehaviorLogDialogVisible
        )
        assertEquals(
            "ダイアログ表示だけではclearAll()を呼んではいけません(T-P10-18、誤タップ防止)",
            0,
            analyticsStore.clearAllCallCount
        )
    }

    // T-P10-18: 異常（キャンセル経路） - ダイアログをキャンセルしてもclearAll()は呼ばれない。
    @Test
    fun onDeleteBehaviorLogDialogDismissed_hidesDialog_doesNotCallClearAll() {
        val analyticsStore = FakeAnalyticsStore()
        val viewModel = newViewModelForDeletion(analyticsStore)
        viewModel.onDeleteBehaviorLogRequested()

        viewModel.onDeleteBehaviorLogDialogDismissed()

        assertFalse(
            "キャンセル後はダイアログが閉じるべきです(T-P10-18)",
            viewModel.uiState.value.isDeleteBehaviorLogDialogVisible
        )
        assertEquals(
            "キャンセル経路ではclearAll()を呼んではいけません(T-P10-18)",
            0,
            analyticsStore.clearAllCallCount
        )
    }

    // T-P10-16: 正常 - 確認操作でclearAll()が正確に1回呼ばれ、ダイアログが閉じる
    // （確認ダイアログを経由した場合のみ削除APIへ到達することの直接証明）。
    @Test
    fun onDeleteBehaviorLogConfirmed_callsClearAllExactlyOnce_hidesDialog() = runTest {
        val analyticsStore = FakeAnalyticsStore()
        val viewModel = newViewModelForDeletion(analyticsStore)
        viewModel.onDeleteBehaviorLogRequested()

        viewModel.onDeleteBehaviorLogConfirmed().join()

        assertEquals(
            "確認操作でclearAll()が正確に1回呼ばれるべきです(T-P10-16)",
            1,
            analyticsStore.clearAllCallCount
        )
        assertFalse(
            "確認後はダイアログが閉じるべきです",
            viewModel.uiState.value.isDeleteBehaviorLogDialogVisible
        )
    }

    // T-P10-16: 正常 - clearAll()成功時はuiState.deleteBehaviorLogResultがSuccessになる。
    @Test
    fun onDeleteBehaviorLogConfirmed_success_setsSuccessResult() = runTest {
        val analyticsStore = FakeAnalyticsStore(clearAllResult = Result.success(Unit))
        val viewModel = newViewModelForDeletion(analyticsStore)
        viewModel.onDeleteBehaviorLogRequested()

        viewModel.onDeleteBehaviorLogConfirmed().join()

        assertEquals(
            "成功時はSuccessを明示するべきです(T-P10-16)",
            DeleteBehaviorLogResult.Success,
            viewModel.uiState.value.deleteBehaviorLogResult
        )
    }

    // T-P10-17: 異常 - clearAll()失敗時は例外を握り潰さずuiState.deleteBehaviorLogResultが
    // Failureになる（サイレント化しない、§8エラー＆レスキューマップ「全データ削除（Settings）」行）。
    @Test
    fun onDeleteBehaviorLogConfirmed_failure_setsFailureResult_doesNotSwallow() = runTest {
        val analyticsStore = FakeAnalyticsStore(
            clearAllResult = Result.failure(IllegalStateException("simulated Room I/O failure"))
        )
        val viewModel = newViewModelForDeletion(analyticsStore)
        viewModel.onDeleteBehaviorLogRequested()

        viewModel.onDeleteBehaviorLogConfirmed().join()

        assertEquals(
            "失敗を握り潰さずFailureとして明示するべきです(T-P10-17)",
            DeleteBehaviorLogResult.Failure,
            viewModel.uiState.value.deleteBehaviorLogResult
        )
    }

    // T-P10-17エッジケース: analyticsStoreが未注入(null、Room初期化失敗時のC1 AppContainer防御と
    // 同型)でもクラッシュせず、サイレントに何もしないのではなくFailureとして明示する。
    @Test
    fun onDeleteBehaviorLogConfirmed_analyticsStoreNull_setsFailureResult_doesNotCrash() = runTest {
        val storage = FakeModelStorage(tempDir())
        val entry = entryFor("model-a", "content".toByteArray())
        val viewModel = SettingsViewModel(
            aiPreferences = FakeAiPreferences(),
            modelDownloader = ModelDownloader(storage, ModelVerifierImpl(), FakeHttpRangeClient(200, ByteArray(0))),
            modelStorage = storage,
            deviceCapability = FakeDeviceCapability(),
            availableModels = listOf(entry),
            analyticsStore = null
        )
        viewModel.onDeleteBehaviorLogRequested()

        viewModel.onDeleteBehaviorLogConfirmed().join()

        assertEquals(
            "analyticsStore未注入時もクラッシュせずFailureとして明示するべきです(T-P10-17、C1防御と同型)",
            DeleteBehaviorLogResult.Failure,
            viewModel.uiState.value.deleteBehaviorLogResult
        )
    }

    // T-P10-16/17: 正常 - 結果表示の確認操作でdeleteBehaviorLogResultがnullへ戻る。
    @Test
    fun onDeleteBehaviorLogResultAcknowledged_clearsResult() = runTest {
        val analyticsStore = FakeAnalyticsStore(clearAllResult = Result.success(Unit))
        val viewModel = newViewModelForDeletion(analyticsStore)
        viewModel.onDeleteBehaviorLogRequested()
        viewModel.onDeleteBehaviorLogConfirmed().join()
        assertEquals(DeleteBehaviorLogResult.Success, viewModel.uiState.value.deleteBehaviorLogResult)

        viewModel.onDeleteBehaviorLogResultAcknowledged()

        assertNull(
            "確認操作後はdeleteBehaviorLogResultがnullへ戻るべきです",
            viewModel.uiState.value.deleteBehaviorLogResult
        )
    }
}
