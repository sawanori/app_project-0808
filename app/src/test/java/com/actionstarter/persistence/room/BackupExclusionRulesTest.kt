package com.actionstarter.persistence.room

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 10 C1（計画書§3.4、レビューCRITICAL・§13 No.2、Step 3 Red）。バックアップ除外XML
 * （`data_extraction_rules.xml`・`backup_rules.xml`）のソースファイルを直接走査し、
 * [BehaviorLogDatabase.DATABASE_NAME]本体に加えRoom既定WAL journal modeのサイドカー
 * （`-wal`／`-shm`）3ファイルすべてが除外リストに列挙されていることを固定するpinning
 * テスト。Android resource解決を経由せず、ソースファイルを直接読む（Robolectric不要の
 * 純JVMテスト）——除外漏れは静的なXML記述の誤りであり、実行時挙動としてではなく
 * ソース内容そのものとして検証できるため。
 */
class BackupExclusionRulesTest {

    private val requiredExcludedPaths = listOf(
        BehaviorLogDatabase.DATABASE_NAME,
        "${BehaviorLogDatabase.DATABASE_NAME}-wal",
        "${BehaviorLogDatabase.DATABASE_NAME}-shm"
    )

    // T-P10-9b: 異常（回帰ガード） - data_extraction_rules.xml・backup_rules.xml双方の
    // ソースを走査し、behavior_log.db・behavior_log.db-wal・behavior_log.db-shmの3ファイル
    // すべてが除外リストに列挙されていることを確認する。
    @Test
    fun tP10_9b_dataExtractionRules_excludesAllThreeSidecarFiles() {
        val xml = resourceXmlFile("data_extraction_rules.xml").readText()
        requiredExcludedPaths.forEach { path ->
            assertTrue(
                "data_extraction_rules.xmlは『$path』をdomain=\"database\"のexcludeとして" +
                    "列挙するべきです(T-P10-9b、WAL/SHM経由のデータ漏出防止)",
                xml.contains("path=\"$path\"")
            )
        }
    }

    @Test
    fun tP10_9b_fullBackupContent_excludesAllThreeSidecarFiles() {
        val xml = resourceXmlFile("backup_rules.xml").readText()
        requiredExcludedPaths.forEach { path ->
            assertTrue(
                "backup_rules.xmlは『$path』をdomain=\"database\"のexcludeとして" +
                    "列挙するべきです(T-P10-9b、API26-30向け旧方式でも同じ3ファイル除外)",
                xml.contains("path=\"$path\"")
            )
        }
    }

    /**
     * `app/src/main/res/xml/`配下のソースファイルを直接指す。ワーキングディレクトリが
     * `app/`もしくはリポジトリルートのいずれで実行されても解決できるよう両方を試す。
     */
    private fun resourceXmlFile(fileName: String): File {
        val candidates = listOf(
            File("src/main/res/xml/$fileName"),
            File("app/src/main/res/xml/$fileName")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("$fileName not found under either candidate path: $candidates")
    }
}
