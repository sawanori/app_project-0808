package com.actionstarter.ai.model

import android.content.Context
import java.io.File

/**
 * F90契約（計画書§7.1・§5.4・§95.6・§14 P7-C1／P7契約確定）。モデルファイルの配置・容量
 * ガード・原子的リネーム・削除のinterface。
 *
 * **interface化（Fable 5裁定5、2026-08-10、ADR-0048）**: 具象クラスから本interfaceへ変更し、
 * 実装は[ModelStorageImpl]（同ファイル）へ分離した（[DeviceCapability]のKDoc「interface化」
 * 参照、理由は同一）。
 *
 * **保存先（T-MDL-14）**: `context.noBackupFilesDir/models/`固定。Auto Backupの対象外にし、
 * 数百MBのバックアップ吸い上げ（エラー＆レスキューマップ#7）を構造的に防ぐ。
 *
 * **原子的コミット（T-MDL-12・T-MDL-13）**: DL中は`.part`拡張子で書き込み、
 * [ModelVerifier]の検証通過後にのみ正式名へリネームする。リネーム前にプロセスが落ちた場合は
 * `.part`のみが残存し、次回起動時に未完了として自動削除する（[deleteOrphanedPartFiles]）。
 *
 * **容量ガード（§95.6・T-MDL-4〜5）**: 必要量 = モデルサイズ × [CAPACITY_SAFETY_FACTOR]
 * （1.5倍）。`StatFs`による空き容量がこれを下回る場合はDLを開始しない。
 *
 * 契約scaffold（TDD厳守）時点では[ModelStorageImpl]の各メソッド本体は宣言のみであり、
 * 実装本体はP7-C4で行う（T-MDL-4〜5・T-MDL-12〜15）。
 */
interface ModelStorage {
    /** 導入済みモデルの絶対パス。未導入なら`null`（§8.6 #11の判定に使う）。 */
    fun installedModelPath(): String?

    /** [requiredBytes]×[CAPACITY_SAFETY_FACTOR]の空き容量が確保できるか（§95.6）。 */
    fun hasSufficientSpace(requiredBytes: Long): Boolean

    /** [entry]のDL中一時ファイル（`.part`拡張子）。 */
    fun partFile(entry: ModelCatalogEntry): File

    /** [entry]の正式配置先ファイル（検証通過後のみここへリネームされる）。 */
    fun finalFile(entry: ModelCatalogEntry): File

    /** 検証通過後に[partFile]を[finalFile]へ原子的リネームする。成功可否を返す。 */
    fun commit(entry: ModelCatalogEntry): Boolean

    /** [entry]の実ファイルを削除する（検証失敗時・ユーザー削除操作の両方から呼ばれる）。 */
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
 * [ModelStorage]の実装（Fable 5裁定5、ADR-0048）。`context.noBackupFilesDir`配下の
 * 実ファイルシステムを操作する。
 *
 * @param context `noBackupFilesDir`・`StatFs`取得に用いる`applicationContext`。
 */
class ModelStorageImpl(private val context: Context) : ModelStorage {

    override fun installedModelPath(): String? {
        TODO("P7-C4で実装")
    }

    override fun hasSufficientSpace(requiredBytes: Long): Boolean {
        TODO("P7-C4で実装（StatFs(noBackupFilesDir).availableBytesとの比較）")
    }

    override fun partFile(entry: ModelCatalogEntry): File {
        TODO("P7-C4で実装")
    }

    override fun finalFile(entry: ModelCatalogEntry): File {
        TODO("P7-C4で実装")
    }

    override fun commit(entry: ModelCatalogEntry): Boolean {
        TODO("P7-C4で実装")
    }

    override fun delete(entry: ModelCatalogEntry) {
        TODO("P7-C4で実装")
    }

    override fun deleteOrphanedPartFiles() {
        TODO("P7-C4で実装")
    }
}
