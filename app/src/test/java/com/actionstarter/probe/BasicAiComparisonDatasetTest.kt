package com.actionstarter.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 12（計画書`docs/plans/phase12-basic-ai-experiment.md`§3.2・§7 T-P12-1〜4）。
 * `BasicAiComparisonProbeTest.kt`（androidTest、`@Ignore`既定）が内包する固定30イベント
 * データセットの構造的整合性を、ソーススキャン型pinningテストとして純JVMで検証する
 * （`StringResourceParityTest`・`BackupExclusionRulesTest`と同型の手法、Robolectric不要）。
 *
 * **androidTestとJVM testを跨いだ検証を行う理由**: プローブ本体（`BasicAiComparisonProbeTest`）は
 * androidTestのため`:app:testDebugUnitTest`の対象外——実機なしでは実行できない。しかし
 * 固定30イベントの**構造**（件数・層内訳・重複なし・few-shot seedとの逐語重複なし）は
 * 純粋なテキスト走査で検証可能であり、C1のRed→Green（既存JVM件数無傷）をこの4テストが
 * 構成する（計画書§7・§11）。
 */
class BasicAiComparisonDatasetTest {

    private val probeSourceText: String by lazy { probeSourceFile().readText() }

    /**
     * `StringResourceParityTest.resolveFromRepo`と同じ「working directoryが`app/`だったり
     * リポジトリルートだったりし得る」制約に対応する汎用パス解決。
     */
    private fun probeSourceFile(): File {
        val relative = "src/androidTest/java/com/actionstarter/probe/BasicAiComparisonProbeTest.kt"

        val direct = File(relative)
        if (direct.isFile) return direct

        val fromRepoRoot = File("app", relative)
        if (fromRepoRoot.isFile) return fromRepoRoot

        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/$relative")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }

        error(
            "BasicAiComparisonProbeTest.kt not found. Tried relative path '$relative' from working " +
                "directory '${System.getProperty("user.dir")}' and its ancestors."
        )
    }

    private fun extractTitles(): List<String> {
        val titleRegex = Regex("""title = "([^"]+)"""")
        return titleRegex.findAll(probeSourceText).map { it.groupValues[1] }.toList()
    }

    private fun countOccurrences(marker: String): Int {
        var count = 0
        var index = probeSourceText.indexOf(marker)
        while (index >= 0) {
            count++
            index = probeSourceText.indexOf(marker, index + marker.length)
        }
        return count
    }

    /**
     * `ai/prompt/PlanPromptBuilder.kt`の`JAPANESE_FEW_SHOT_SEEDS`／`ENGLISH_FEW_SHOT_SEEDS`
     * （実コードで確認済み、計画書§2）。この16件のいずれとも固定30イベントのタイトルが
     * 逐語一致してはならない（Gemini H-4「seedタイトルの逐語再利用禁止」）。
     */
    private val KNOWN_FEW_SHOT_SEED_TITLES = setOf(
        "結婚式", "歯科検診", "出張", "打ち合わせ", "誕生日会", "健康診断", "旅行", "商談",
        "Wedding", "Dental checkup", "Business trip", "Team meeting",
        "Birthday party", "Health checkup", "Vacation trip", "Client negotiation"
    )

    // T-P12-1: 正常（回帰ガード・ソーススキャン型pinning） - データセットが正確に30件である。
    @Test
    fun tP12_1_probeDataset_containsExactlyThirtyEvents() {
        val proboEventCount = countOccurrences("ProbeEvent(title = ")
        assertEquals(
            "固定プローブイベントは正確に30件であるべきです(計画書§3.2、事前登録)",
            30,
            proboEventCount
        )
    }

    // T-P12-2: 正常（回帰ガード） - タイトルが16件のfew-shot seedタイトルのいずれとも逐語一致しない。
    @Test
    fun tP12_2_probeDataset_titlesDoNotVerbatimMatchAnyFewShotSeedTitle() {
        val titles = extractTitles()
        val collisions = titles.filter { it in KNOWN_FEW_SHOT_SEED_TITLES }

        assertTrue(
            "固定イベントのタイトルはfew-shot seedタイトルと逐語一致してはいけません" +
                "(Gemini H-4、計画書§3.2)。一致したタイトル=$collisions",
            collisions.isEmpty()
        )
    }

    // T-P12-3: 正常（回帰ガード） - L1/L2/L3各層が正確に10件ずつである。
    @Test
    fun tP12_3_probeDataset_eachLayerContainsExactlyTenEvents() {
        assertEquals("L1（seed非依存の日常）は10件であるべきです", 10, countOccurrences("layer = \"L1\""))
        assertEquals("L2（seed近縁で別語）は10件であるべきです", 10, countOccurrences("layer = \"L2\""))
        assertEquals("L3（AIが苦手な不規則）は10件であるべきです", 10, countOccurrences("layer = \"L3\""))
    }

    // T-P12-4: 異常（回帰ガード） - データセット内でタイトルが重複しない。
    @Test
    fun tP12_4_probeDataset_titlesAreAllDistinct() {
        val titles = extractTitles()
        val duplicates = titles.groupingBy { it }.eachCount().filter { it.value > 1 }.keys

        assertTrue(
            "固定30イベントのタイトルはすべて異なるべきです。重複=$duplicates",
            duplicates.isEmpty()
        )
    }
}
