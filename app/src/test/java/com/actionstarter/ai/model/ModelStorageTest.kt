package com.actionstarter.ai.model

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowStatFs

/**
 * P7-C4（計画書§12.4・T-MDL-4〜5・T-MDL-12〜15・F90・ADR-0053）。[ModelStorageImpl]のGreen
 * テスト。
 *
 * **計画書§12.1とのラベル不一致（[DeviceCapabilityTest]と同型の既報告事項）**: 計画書§12.4は
 * T-MDL-4〜5・T-MDL-12〜15を「E1」または「E2」とラベルするが、[ModelStorageImpl]の
 * コンストラクタは`android.content.Context`を必須引数とし、[hasSufficientSpace]は
 * `android.os.StatFs`（Android SDK同梱・純JVM実行時はスタブ）を使うため、いずれも
 * Robolectric必須（E2）である。本ファイルは全件`@RunWith(RobolectricTestRunner::class)`で
 * 実行する。
 *
 * `StatFs`はRobolectricでは実ファイルシステムを反映しない
 * （`ShadowStatFs`のjavadoc「Robolectric doesn't provide actual filesystem stats; rather,
 * it provides the ability to specify stats values in advance.」、Context7
 * `/websites/robolectric`で確認済み）。ブロックサイズは常に4096固定のため、本ファイルの
 * 容量境界テストは「1バイト不足」ではなく「1ブロック(4096バイト)不足」で表現する
 * （計画書T-MDL-5の意図——`>=`比較で不足時は拒否——をRobolectricの実際の精度で検証する）。
 */
@RunWith(RobolectricTestRunner::class)
class ModelStorageTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun entryFor(id: String, sizeBytes: Long = 1_000_000L): ModelCatalogEntry = ModelCatalogEntry(
        id = id,
        displayName = "Test Model $id",
        downloadUrl = "https://example.invalid/$id.litertlm",
        sha256 = "0".repeat(64),
        sizeBytes = sizeBytes,
        peakRamBytes = 1L,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false
    )

    private fun registerAvailableBlocks(availableBlocks: Int) {
        // ブロックサイズはRobolectricで常に4096固定（ShadowStatFs.getBlockSizeLong javadoc）。
        // blockCount/freeBlocksはavailableBytesの計算に使われないため、availableBlocksと
        // 同値を渡しておく（意味上は「空き=全体」を模している）。ModelStorageImpl.
        // hasSufficientSpaceが渡すのと同じ文字列表現(absolutePath)で登録し、パス正規化の
        // 差異によるマッチング失敗を避ける。
        ShadowStatFs.registerStats(
            context().noBackupFilesDir.absolutePath,
            availableBlocks,
            availableBlocks,
            availableBlocks
        )
    }

    companion object {
        private const val BLOCK_SIZE_BYTES = 4096L
    }

    // ------------------------------------------------------------------
    // T-MDL-4/5: 容量ガード（modelBytes × 1.5、境界値）
    // ------------------------------------------------------------------

    // T-MDL-4/5相当: 正常系・エッジ - 空き容量が必要量(modelBytes×1.5)ちょうど → 許可
    @Test
    fun hasSufficientSpace_availableExactlyAtRequiredThreshold_returnsTrue() {
        val requiredBytes = 1000L * BLOCK_SIZE_BYTES // modelBytes
        val thresholdBytes = (requiredBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong() // ×1.5
        registerAvailableBlocks((thresholdBytes / BLOCK_SIZE_BYTES).toInt())

        val result = ModelStorageImpl(context()).hasSufficientSpace(requiredBytes)

        assertTrue(
            "必要量(modelBytes×1.5)ちょうどの空き容量は許可されるべきです(T-MDL-4/5、§95.6)",
            result
        )
    }

    // T-MDL-5相当: エッジ - 空き容量が必要量を1ブロック(4096バイト)下回る → 拒否
    // （StatFsはRobolectric下でブロック単位の精度のため、計画書の「1バイト不足」を
    // 到達可能な最小単位で表現する。クラスKDoc参照）
    @Test
    fun hasSufficientSpace_availableOneBlockBelowRequiredThreshold_returnsFalse() {
        val requiredBytes = 1000L * BLOCK_SIZE_BYTES
        val thresholdBytes = (requiredBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong()
        registerAvailableBlocks((thresholdBytes / BLOCK_SIZE_BYTES).toInt() - 1)

        val result = ModelStorageImpl(context()).hasSufficientSpace(requiredBytes)

        assertFalse(
            "必要量(modelBytes×1.5)を下回る空き容量は拒否されるべきです(T-MDL-5、§95.6)",
            result
        )
    }

    // T-MDL-4相当: 正常系 - ×1.5の計算自体が正しいことの回帰ロック（1.5倍未満の空き容量では
    // 不足として扱われる。単純な「空き>=modelBytes」の判定に後退していないことを確認する）
    @Test
    fun hasSufficientSpace_availableCoversRawSizeButNotWithSafetyMargin_returnsFalse() {
        val requiredBytes = 1000L * BLOCK_SIZE_BYTES
        // 空き容量 = modelBytesそのもの（1.5倍していない）。×1.5判定なら不足のはず。
        registerAvailableBlocks((requiredBytes / BLOCK_SIZE_BYTES).toInt())

        val result = ModelStorageImpl(context()).hasSufficientSpace(requiredBytes)

        assertFalse(
            "空き容量がmodelBytes分しかない場合、×1.5マージンを満たさないため拒否されるべきです" +
                "(T-MDL-4、§95.6)",
            result
        )
    }

    // ------------------------------------------------------------------
    // 未導入のベースライン（installedEntry/installedModelPathがnullを返す）
    // ------------------------------------------------------------------

    @Test
    fun installedEntry_nothingPlaced_returnsNull() {
        val entry = entryFor("baseline-not-installed")
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))

        assertNull(storage.installedEntry())
        assertNull(storage.installedModelPath())
    }

    // T-MDL-12相当（前段）: 「検証前の名前ではロードされない」— .partファイルが存在するだけでは
    // installedEntry/installedModelPathはnullを返す（commit前は未導入扱い）
    @Test
    fun installedEntry_onlyPartFileExists_returnsNull() {
        val entry = entryFor("part-only")
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))
        storage.partFile(entry).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertNull(
            ".partファイルのみの状態はロード対象として扱われるべきではありません(T-MDL-12)",
            storage.installedEntry()
        )
        assertNull(storage.installedModelPath())
    }

    // ------------------------------------------------------------------
    // T-MDL-12/13: 原子的コミットと孤児.partファイルの掃除
    // ------------------------------------------------------------------

    // T-MDL-12相当: 正常系 - commit()でpartFile→finalFileへ原子的リネームされ、以後
    // installedEntry/installedModelPathが導入済みとして解決する
    @Test
    fun commit_movesPartFileToFinalFile_andBecomesInstalled() {
        val entry = entryFor("commit-success")
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))
        val bytes = byteArrayOf(10, 20, 30)
        storage.partFile(entry).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

        val committed = storage.commit(entry)

        assertTrue("commit()はpartFileが存在すれば成功するはずです", committed)
        assertFalse(".partファイルはリネーム後に残っていてはいけません", storage.partFile(entry).exists())
        assertTrue(storage.finalFile(entry).isFile)
        assertEquals(entry, storage.installedEntry())
        assertEquals(storage.finalFile(entry).absolutePath, storage.installedModelPath())
        assertArrayEquals(bytes, storage.finalFile(entry).readBytes())
    }

    // T-MDL-12相当（異常系）: partFileが存在しない状態でcommit()を呼ぶと失敗する（false）
    @Test
    fun commit_partFileMissing_returnsFalse() {
        val entry = entryFor("commit-missing-part")
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))

        val committed = storage.commit(entry)

        assertFalse(committed)
        assertNull(storage.installedEntry())
    }

    // T-MDL-13相当: 異常系 - リネーム前にプロセスが落ちた想定(.partだけ残存) →
    // deleteOrphanedPartFiles()で次回起動時に未完了として自動削除される
    @Test
    fun deleteOrphanedPartFiles_removesLeftoverPartFile_keepsFinalFilesIntact() {
        val orphaned = entryFor("orphaned-part")
        val installed = entryFor("kept-final")
        val storage = ModelStorageImpl(context(), catalog = listOf(orphaned, installed))

        storage.partFile(orphaned).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        storage.partFile(installed).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(8))
        }
        assertTrue(storage.commit(installed))
        assertTrue(storage.partFile(orphaned).isFile)

        storage.deleteOrphanedPartFiles()

        assertFalse(
            "孤児.partファイルはdeleteOrphanedPartFiles()で削除されるべきです(T-MDL-13)",
            storage.partFile(orphaned).exists()
        )
        assertTrue(
            "既にcommit済みのfinalFileはdeleteOrphanedPartFiles()の影響を受けるべきではありません",
            storage.finalFile(installed).isFile
        )
    }

    // ------------------------------------------------------------------
    // T-MDL-14: 保存先がnoBackupFilesDir配下である
    // ------------------------------------------------------------------

    @Test
    fun partFileAndFinalFile_areLocatedUnderNoBackupFilesDir() {
        val entry = entryFor("location-check")
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))
        val noBackupPath = context().noBackupFilesDir.absolutePath

        assertTrue(
            "partFileはnoBackupFilesDir配下であるべきです(T-MDL-14、Auto Backup対象外)",
            storage.partFile(entry).absolutePath.startsWith(noBackupPath)
        )
        assertTrue(
            "finalFileはnoBackupFilesDir配下であるべきです(T-MDL-14、Auto Backup対象外)",
            storage.finalFile(entry).absolutePath.startsWith(noBackupPath)
        )
    }

    // ------------------------------------------------------------------
    // T-MDL-15: 削除
    // ------------------------------------------------------------------

    // T-MDL-15相当: 正常系 - モデル削除で実ファイルが消え、installedEntry/installedModelPathも
    // 未導入(null)へ戻る。ModelStorage自身が検証できるのはこの「実ファイル削除」までであり、
    // AiPreferencesの状態リセットはSettings ViewModel(P7-C6)の責務でありModelStorage単体の
    // テストスコープ外（本タスクの制約「自己判断で組み立てを避け書ける範囲のみ書く」に基づく
    // 正直な範囲限定、T-GW-17等の既存「部分カバレッジ」表記と同じ方針）。
    @Test
    fun delete_removesFinalFile_andRevertsToNotInstalled() {
        val entry = entryFor("delete-target")
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))
        storage.partFile(entry).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        assertTrue(storage.commit(entry))
        assertEquals(entry, storage.installedEntry())

        storage.delete(entry)

        assertFalse("delete()後は実ファイルが存在してはいけません(T-MDL-15)", storage.finalFile(entry).exists())
        assertNull(
            "delete()後installedEntry()はnull(未導入)へ戻るべきです(T-MDL-15)",
            storage.installedEntry()
        )
        assertNull(storage.installedModelPath())
    }

    // T-MDL-15相当（異常系）: 存在しないentryをdelete()しても例外を投げない（冪等）
    @Test
    fun delete_nothingInstalled_doesNotThrow() {
        val entry = entryFor("delete-nothing")
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))

        storage.delete(entry)
    }
}
