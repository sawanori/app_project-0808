package com.actionstarter.ai.model

import com.actionstarter.ai.EngineLoadStateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 8.5 Step 3（Red）。計画書`docs/plans/phase8.5-adaptive-model-selection.md`§6
 * T-P85-1〜9。[ModelSelector]の失敗テスト。
 *
 * **現状のRed原因**: [ModelSelectorImpl.select]の本体は`TODO()`のため、以下は全件
 * `NotImplementedError`によりRedになるのが正しい（本プロジェクトの既存規約、
 * `DeviceCapabilityTest`等のP7-C1/C2契約scaffoldと同型）。
 *
 * [DeviceCapability]／[ModelStorage]はいずれもfake実装（Robolectric不要）。[FakeModelStorage]は
 * `finalFile(entry).isFile`が実ファイルシステム判定であるため、JVM一時ディレクトリへ実ファイルを
 * 書き込むことで「導入済み」を表現する（`ModelStorage`interface自体は変更しないため）。
 */
class ModelSelectorTest {

    private class FakeDeviceCapability(private val availableBytes: Long) : DeviceCapability {
        override fun classify(): DeviceTier = DeviceTier.TIER_1_STANDARD
        override fun isAbiSupported(): Boolean = true
        override fun hasAvailableMemory(requiredBytes: Long): Boolean = availableBytes >= requiredBytes
    }

    /** [installedEntries]のみ実ファイルを一時ディレクトリへ書き込み「導入済み」を表現する。 */
    private class FakeModelStorage(installedEntries: Set<ModelCatalogEntry>) : ModelStorage {
        private val tempDir: File = Files.createTempDirectory("model-selector-test").toFile()

        init {
            installedEntries.forEach { entry ->
                finalFile(entry).apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(1))
                }
            }
        }

        override fun installedModelPath(): String? = null // ModelSelectorは使用しない
        override fun installedEntry(): ModelCatalogEntry? = null // ModelSelectorは使用しない
        override fun hasSufficientSpace(requiredBytes: Long): Boolean = true // ModelSelectorは使用しない
        override fun partFile(entry: ModelCatalogEntry): File = File(tempDir, "${entry.id}.litertlm.part")
        override fun finalFile(entry: ModelCatalogEntry): File = File(tempDir, "${entry.id}.litertlm")
        override fun commit(entry: ModelCatalogEntry): Boolean = true // ModelSelectorは使用しない
        override fun delete(entry: ModelCatalogEntry) {
            finalFile(entry).delete()
        }
        override fun deleteOrphanedPartFiles() = Unit // ModelSelectorは使用しない
    }

    private fun gemma4LikeEntry(): ModelCatalogEntry = ModelCatalogEntry(
        id = "test-gemma4-like",
        displayName = "Test Gemma4-like Model",
        downloadUrl = "https://example.invalid/test-gemma4-like.litertlm",
        sha256 = "0".repeat(64),
        sizeBytes = 1L,
        peakRamBytes = 2L * GB,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false,
        defaultProfilePeakRamBytes = 2L * GB // 実カタログのGEMMA_4_E2B_ITと同値
    )

    private fun qwen06bLikeEntry(): ModelCatalogEntry = ModelCatalogEntry(
        id = "test-qwen06b-like",
        displayName = "Test Qwen3-0.6B-like Model",
        downloadUrl = "https://example.invalid/test-qwen06b-like.litertlm",
        sha256 = "1".repeat(64),
        sizeBytes = 1L,
        peakRamBytes = GB + GB / 4,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false,
        defaultProfilePeakRamBytes = GB + GB / 4 // 1.25GiB、実カタログのQWEN3_0_6B_INT4_BLOCK32と同値
    )

    /** T-P85-9専用: 自動選択の候補から除外されるべきQwen3-1.7B相当のfixture。 */
    private fun qwen17bLikeEntry(): ModelCatalogEntry = ModelCatalogEntry(
        id = "test-qwen17b-like",
        displayName = "Test Qwen3-1.7B-like Model (excluded from auto)",
        downloadUrl = "https://example.invalid/test-qwen17b-like.litertlm",
        sha256 = "2".repeat(64),
        sizeBytes = 1L,
        peakRamBytes = 2L * GB,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false,
        defaultProfilePeakRamBytes = 2L * GB
    )

    /** 品質順候補（Gemma4-like > Qwen06b-like）。Qwen17b-likeは含めない（T-P85-9の対象外確認）。 */
    private fun candidates(): List<ModelCatalogEntry> = listOf(gemma4LikeEntry(), qwen06bLikeEntry())

    private fun selector(availableBytes: Long, installed: Set<ModelCatalogEntry>): ModelSelector =
        ModelSelectorImpl(
            deviceCapability = FakeDeviceCapability(availableBytes),
            modelStorage = FakeModelStorage(installed),
            candidates = candidates()
        )

    companion object {
        private const val GB = 1024L * 1024 * 1024
    }

    // T-P85-1: 正常 - 両方導入・availMem=3GiB(両方適合) → 品質最上位のGemma4-likeを選ぶ
    @Test
    fun select_bothInstalledSufficientForBoth_returnsGemma4Like() {
        val entries = setOf(gemma4LikeEntry(), qwen06bLikeEntry())

        val result = selector(availableBytes = 3L * GB, installed = entries).select()

        assertEquals(gemma4LikeEntry(), result)
    }

    // T-P85-2: 正常 - 両方導入・availMem=2.0GiB(Gemma4-like不適合/Qwen06b-like適合) → Qwen06b-like
    @Test
    fun select_bothInstalledOnlyQwenFits_returnsQwen06bLike() {
        val entries = setOf(gemma4LikeEntry(), qwen06bLikeEntry())

        val result = selector(availableBytes = 2L * GB, installed = entries).select()

        assertEquals(qwen06bLikeEntry(), result)
    }

    // T-P85-3: エッジ - availMem=2.5GiBちょうど(Gemma4-like境界=以上で適合) → Gemma4-like
    @Test
    fun select_availMemExactlyTwoPointFiveGiB_returnsGemma4Like() {
        val entries = setOf(gemma4LikeEntry(), qwen06bLikeEntry())

        val result = selector(availableBytes = 2L * GB + GB / 2, installed = entries).select()

        assertEquals(gemma4LikeEntry(), result)
    }

    // T-P85-4: エッジ - availMem=1.75GiBちょうど(Qwen06b-like境界=以上で適合) → Qwen06b-like
    @Test
    fun select_availMemExactlyOnePointSevenFiveGiB_returnsQwen06bLike() {
        val entries = setOf(gemma4LikeEntry(), qwen06bLikeEntry())

        val result = selector(availableBytes = GB + GB / 4 + GB / 2, installed = entries).select()

        assertEquals(qwen06bLikeEntry(), result)
    }

    // T-P85-5: 異常 - 両方導入・availMem=1.0GiB(いずれも不適合) → 候補なし
    @Test
    fun select_bothInstalledInsufficientForBoth_returnsNull() {
        val entries = setOf(gemma4LikeEntry(), qwen06bLikeEntry())

        val result = selector(availableBytes = 1L * GB, installed = entries).select()

        assertNull(result)
    }

    // T-P85-6: 異常 - Gemma4-likeのみ導入・availMem=1.8GiB(Gemma4-like不適合・Qwen06b-like未導入)
    // → 候補なし（代替不可）
    @Test
    fun select_onlyGemma4LikeInstalledInsufficient_returnsNull() {
        val entries = setOf(gemma4LikeEntry())

        val result = selector(availableBytes = GB + (8L * GB) / 10, installed = entries).select()

        assertNull(result)
    }

    // T-P85-7: 正常 - Qwen06b-likeのみ導入・availMem=2GiB(適合) → Qwen06b-like
    @Test
    fun select_onlyQwen06bLikeInstalledSufficient_returnsQwen06bLike() {
        val entries = setOf(qwen06bLikeEntry())

        val result = selector(availableBytes = 2L * GB, installed = entries).select()

        assertEquals(qwen06bLikeEntry(), result)
    }

    // T-P85-8: 異常 - 両方未導入 → 候補なし
    @Test
    fun select_neitherInstalled_returnsNull() {
        val result = selector(availableBytes = 3L * GB, installed = emptySet()).select()

        assertNull(result)
    }

    // T-P85-9: エッジ - Qwen17b-likeのみ導入(candidatesに含まれない)・availMem=3GiB → 候補なし
    // （自動選択対象外の回帰ロック）
    @Test
    fun select_onlyExcludedQwen17bLikeInstalled_returnsNull() {
        val entries = setOf(qwen17bLikeEntry())

        val result = selector(availableBytes = 3L * GB, installed = entries).select()

        assertNull(result)
    }

    // ------------------------------------------------------------------
    // Phase 9.5（計画書`docs/plans/phase9.5-performance-quality.md`§3.10 F-5・§14発見②、
    // 優先繰り上げ・Red検収での差し戻し訂正）: ロード済み候補のavailMemガードスキップ。
    // select()の現行実装はまだengineLoadStateSourceを一切参照しないため、ロード済み候補が
    // availMem不足で除外される既存の挙動を検証するT-P95-46はAssertionErrorでRedになるのが正しい。
    // ------------------------------------------------------------------

    private class FakeEngineLoadStateSource(private val path: String?) : EngineLoadStateSource {
        override fun loadedModelPath(): String? = path
    }

    // T-P95-46（F-5、§14発見①・②の実例接地）: 正常 - Qwen06b-likeが導入済みかつ現在ロード済み・
    // availMem=1.0GiB（通常なら両方不適合）でも、ロード済み候補はavailMemガードをスキップし
    // 適合扱いになるため選ばれる（M実測§14発見②「auto経路でロード済みQwenがavailMem不足により
    // ModelSelector.select()の段階で弾かれ候補0件になる」欠陥の修正）。
    @Test
    fun tP95_46_qwenAlreadyLoadedWithLowAvailMem_isTreatedAsFittingAndSelected() {
        val entries = setOf(qwen06bLikeEntry())
        val storage = FakeModelStorage(entries)
        val loadedPath = storage.finalFile(qwen06bLikeEntry()).absolutePath

        val result = ModelSelectorImpl(
            deviceCapability = FakeDeviceCapability(availableBytes = 1L * GB),
            modelStorage = storage,
            candidates = candidates(),
            engineLoadStateSource = FakeEngineLoadStateSource(loadedPath)
        ).select()

        assertEquals(
            "ロード済み候補はavailMemガードをスキップし適合扱いで選ばれるべきです(T-P95-46、F-5)",
            qwen06bLikeEntry(),
            result
        )
    }

    // T-P95-47（F-5回帰・born-green）: 正常 - Qwen06b-likeがロード済みでも、より高品質な
    // Gemma4-likeが未ロードのままavailMemで通常どおり適合する場合は、品質順走査によりGemma4-like
    // が優先される（ロード済み側に人為的な優先権を与えない設計の確認）。
    @Test
    fun tP95_47_higherQualityCandidateFitsNormally_stillPreferredOverLoadedLowerCandidate() {
        val entries = setOf(gemma4LikeEntry(), qwen06bLikeEntry())
        val storage = FakeModelStorage(entries)
        val loadedPath = storage.finalFile(qwen06bLikeEntry()).absolutePath // Qwen側がロード済み

        val result = ModelSelectorImpl(
            deviceCapability = FakeDeviceCapability(availableBytes = 3L * GB), // Gemma4-likeも通常適合
            modelStorage = storage,
            candidates = candidates(),
            engineLoadStateSource = FakeEngineLoadStateSource(loadedPath)
        ).select()

        assertEquals(
            "ロード済みでない上位候補がavailMemで通常適合するなら、そちらが優先されるべきです" +
                "(T-P95-47、F-5回帰)",
            gemma4LikeEntry(),
            result
        )
    }
}
