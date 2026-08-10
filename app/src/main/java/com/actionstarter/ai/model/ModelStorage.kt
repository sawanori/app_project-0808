package com.actionstarter.ai.model

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * F90契約（計画書§7.1・§5.4・§95.6・§14 P7-C1／P7契約確定／P7-C4）。モデルファイルの配置・容量
 * ガード・原子的リネーム・削除のinterface。
 *
 * **interface化（Fable 5裁定5、2026-08-10、ADR-0048）**: 具象クラスから本interfaceへ変更し、
 * 実装は[ModelStorageImpl]（同ファイル）へ分離した（[DeviceCapability]のKDoc「interface化」
 * 参照、理由は同一）。
 *
 * **保存先（T-MDL-14・ADR-0053）**: `context.noBackupFilesDir/models/`固定。Auto Backupの
 * 対象外にし、数百MBのバックアップ吸い上げ（エラー＆レスキューマップ#7）を構造的に防ぐ。
 * P7-C0のスパイクが実際に使った配置（`build/models/`はホスト側の一時退避先であり本番配置とは
 * 別だが、「バックアップ対象外の専用ディレクトリへ隔離する」という方針は踏襲する）。
 *
 * **原子的コミット（T-MDL-12・T-MDL-13）**: DL中は`.litertlm.part`拡張子で書き込み、
 * [ModelVerifier]の検証通過後にのみ正式名（`.litertlm`）へリネームする。リネーム前にプロセスが
 * 落ちた場合は`.part`のみが残存し、次回起動時に未完了として自動削除する
 * （[deleteOrphanedPartFiles]）。
 *
 * **容量ガード（§95.6・T-MDL-4〜5）**: 必要量 = モデルサイズ × [CAPACITY_SAFETY_FACTOR]
 * （1.5倍）。`StatFs`による空き容量がこれを下回る場合はDLを開始しない。
 *
 * **導入済みモデルの解決方法（ADR-0053、P7-C4で確定）**: [installedEntry]／
 * [installedModelPath]は、コンストラクタで受け取った[ModelStorageImpl]の`catalog`
 * （既定[ModelCatalog.ALL]）を順に走査し、[finalFile]が実在する最初の[ModelCatalogEntry]を
 * 「導入済み」とみなす。Phase 7時点は`catalog`が単一エントリのため事実上「導入済みか否か」の
 * 二値判定と同義だが、§17「モデル名を製品仕様として固定しない」の交換可能性を保つため、
 * 特定エントリをハードコードせず走査ベースにした。[ModelStorageImpl]の`catalog`引数は
 * テストが小さなfixtureエントリへ差し替えるための注入点でもある（実モデル328MBに対する
 * SHA-256実測なしにGatewayの「導入済み」経路を検証するため。`LocalAiGatewayTest`の
 * `installedModelStorage()`参照）。
 */
interface ModelStorage {
    /** 導入済みモデルの絶対パス。未導入なら`null`（§8.6 #11の判定に使う）。 */
    fun installedModelPath(): String?

    /**
     * 導入済みモデルの[ModelCatalogEntry]。未導入なら`null`（§8.6 #11の判定に使う）。
     * [com.actionstarter.ai.LocalAiGateway]が§8.6 #12のロード前再検証（[ModelVerifier.verify]）
     * に渡す「期待値」の取得元でもある。
     */
    fun installedEntry(): ModelCatalogEntry?

    /** [requiredBytes]×[CAPACITY_SAFETY_FACTOR]の空き容量が確保できるか（§95.6）。 */
    fun hasSufficientSpace(requiredBytes: Long): Boolean

    /**
     * F97（計画書§12.6 T-SET-5、P7-C6）。現在の空き容量（バイト）。[hasSufficientSpace]と同じ
     * StatFs基盤を使う。Settings画面の容量表示専用に追加した。
     *
     * **デフォルト実装を持つ理由**: P7-C4で作成済みの既存fake実装
     * （[com.actionstarter.ai.model.ModelDownloaderTest.FakeModelStorage]・
     * [com.actionstarter.ai.LocalAiGatewayTest]内のfake、いずれも本メソッドを使わない）に
     * オーバーライドを強制しない（Kotlin interfaceのデフォルト本体）。実際の値は
     * [ModelStorageImpl]がオーバーライドする。デフォルト値`0L`は「情報なし」を安全側
     * （容量不足寄り）に倒した値であり、いずれの既存テストからも参照されないため実害はない。
     */
    fun availableBytes(): Long = 0L

    /** [entry]のDL中一時ファイル（`.litertlm.part`拡張子）。 */
    fun partFile(entry: ModelCatalogEntry): File

    /** [entry]の正式配置先ファイル（検証通過後のみここへリネームされる）。 */
    fun finalFile(entry: ModelCatalogEntry): File

    /** 検証通過後に[partFile]を[finalFile]へ原子的リネームする。成功可否を返す。 */
    fun commit(entry: ModelCatalogEntry): Boolean

    /** [entry]の実ファイル（`.part`・正式配置先の両方）を削除する（検証失敗時・ユーザー削除操作の両方から呼ばれる）。 */
    fun delete(entry: ModelCatalogEntry)

    /** 起動時に呼び出す想定。リネーム前に残存した孤児`.part`ファイルを掃除する（T-MDL-13）。 */
    fun deleteOrphanedPartFiles()

    companion object {
        /** §95.6「モデルサイズ×1.5倍以上」。 */
        const val CAPACITY_SAFETY_FACTOR: Double = 1.5

        /** `noBackupFilesDir`配下のサブディレクトリ名。 */
        const val MODELS_DIR_NAME: String = "models"
    }
}

/**
 * [ModelStorage]の実装（Fable 5裁定5、ADR-0048、本体実装ADR-0053）。`context.noBackupFilesDir`
 * 配下の実ファイルシステムを操作する。
 *
 * **P7-C4実装済み（Green）**: [commit]は`java.nio.file.Files.move`の`ATOMIC_MOVE`で
 * `partFile`→`finalFile`の同一ディレクトリ内リネームを行う（同一ファイルシステム上のため
 * `AtomicMoveNotSupportedException`は発生しない設計）。[hasSufficientSpace]は
 * `StatFs(noBackupFilesDir).availableBytes`と`requiredBytes × 1.5`を比較する。
 * [deleteOrphanedPartFiles]は`models/`直下の`*.litertlm.part`をすべて削除する。
 *
 * @param context `noBackupFilesDir`・`StatFs`取得に用いる`applicationContext`。
 * @param catalog [installedEntry]／[installedModelPath]が「導入済みか」を走査する対象の候補
 *   一覧。既定は本番カタログ全体（[ModelCatalog.ALL]）。テストは小さなfixtureエントリへ
 *   差し替えられる（クラスKDoc「導入済みモデルの解決方法」参照）。
 */
class ModelStorageImpl(
    private val context: Context,
    private val catalog: List<ModelCatalogEntry> = ModelCatalog.ALL
) : ModelStorage {

    override fun installedEntry(): ModelCatalogEntry? = catalog.firstOrNull { entry -> finalFile(entry).isFile }

    override fun installedModelPath(): String? = installedEntry()?.let { finalFile(it).absolutePath }

    // P7-C6 Green: consolidated onto a single StatFs call. hasSufficientSpace now delegates to
    // availableBytes() (DRY) instead of duplicating the StatFs(...).availableBytes read.
    override fun hasSufficientSpace(requiredBytes: Long): Boolean {
        val required = (requiredBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong()
        return availableBytes() >= required
    }

    override fun availableBytes(): Long = StatFs(context.noBackupFilesDir.absolutePath).availableBytes

    override fun partFile(entry: ModelCatalogEntry): File = File(modelsDir(), "${entry.id}$PART_FILE_SUFFIX")

    override fun finalFile(entry: ModelCatalogEntry): File = File(modelsDir(), "${entry.id}$FINAL_FILE_SUFFIX")

    override fun commit(entry: ModelCatalogEntry): Boolean {
        val source = partFile(entry)
        if (!source.isFile) return false
        return try {
            Files.move(
                source.toPath(),
                finalFile(entry).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            true
        } catch (e: IOException) {
            false
        }
    }

    override fun delete(entry: ModelCatalogEntry) {
        partFile(entry).delete()
        finalFile(entry).delete()
    }

    override fun deleteOrphanedPartFiles() {
        modelsDir().listFiles { file -> file.isFile && file.name.endsWith(PART_FILE_SUFFIX) }
            ?.forEach { it.delete() }
    }

    /** `context.noBackupFilesDir/models/`（T-MDL-14）。呼び出しのたびに存在保証する。 */
    private fun modelsDir(): File = File(context.noBackupFilesDir, ModelStorage.MODELS_DIR_NAME).apply { mkdirs() }

    companion object {
        private const val FINAL_FILE_SUFFIX = ".litertlm"
        private const val PART_FILE_SUFFIX = ".litertlm.part"
    }
}
