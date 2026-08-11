package com.actionstarter.ai.prompt

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * T-P9-6・T-P9-7（計画書`docs/plans/phase9-recovery-ai.md`§3.3・§7、ADR-0063想定）。
 * [RecoveryPromptBuilder]のテスト。[RecoveryPromptBuilder.build]・
 * [RecoveryPromptBuilder.buildSystemInstruction]本体が`TODO()`のため、全件`NotImplementedError`
 * によりRedになるのが正しい。
 */
class RecoveryPromptBuilderTest {

    private fun sampleEvent(): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = Instant.parse("2026-08-10T10:00:00Z"),
        locationName = "Shibuya",
        coordinates = Coordinate(lat = 35.0, lon = 139.0),
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun sampleContext(): RecoveryContext = RecoveryContext(
        currentTime = Instant.parse("2026-08-10T09:15:00Z"),
        currentLocation = Coordinate(lat = 1.0, lon = 2.0),
        event = sampleEvent(),
        unfinishedSteps = emptyList(),
        latestTravelEstimate = Duration.ofMinutes(20),
        plannedDepartureTime = Instant.parse("2026-08-10T09:00:00Z")
    )

    private fun sampleOption(semanticAction: String): RecoveryOption = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = semanticAction,
        title = "",
        explanation = "",
        estimatedArrival = Instant.parse("2026-08-10T09:45:00Z"),
        skippedStepIds = listOf(UUID.randomUUID())
    )

    // T-P9-6: 正常 - buildが座標・skippedStepIds・estimatedArrivalを一切含まない（PII/§15チェック）
    @Test
    fun build_neverIncludesCoordinatesSkippedStepIdsOrEstimatedArrival() {
        val builder = RecoveryPromptBuilder()
        val options = listOf(sampleOption("keep_all_steps"), sampleOption("skip_optional_steps"))

        val prompt = builder.build(sampleContext(), options)

        assertFalse("座標(緯度)を含むべきではありません(§15・PII最小化)", prompt.contains("35.0"))
        assertFalse("座標(経度)を含むべきではありません(§15・PII最小化)", prompt.contains("139.0"))
        assertFalse(
            "skippedStepIds(UUID)を含むべきではありません(§3.3、AIが触れるのはexplanationのみ)",
            options.flatMap { it.skippedStepIds }.any { prompt.contains(it.toString()) }
        )
        assertFalse(
            "estimatedArrivalの時刻表現を含むべきではありません(§15)",
            prompt.contains("09:45")
        )
    }

    // T-P9-6補足: buildがoptionsのsemanticActionを一覧として含む（echo必須集合、§3.3）
    @Test
    fun build_includesEachOptionSemanticActionAsEchoMandatorySet() {
        val builder = RecoveryPromptBuilder()
        val options = listOf(sampleOption("keep_all_steps"), sampleOption("change_transport_mode"))

        val prompt = builder.build(sampleContext(), options)

        assertTrue(prompt.contains("keep_all_steps"))
        assertTrue(prompt.contains("change_transport_mode"))
    }

    // T-P9-7: 正常 - buildSystemInstructionが「semantic_actionは与えられた値のecho」指示を含む
    @Test
    fun buildSystemInstruction_includesSemanticActionEchoInstruction() {
        val builder = RecoveryPromptBuilder()

        val instruction = builder.buildSystemInstruction(Locale.JAPAN)

        assertTrue(
            "「与えられた値をechoする（新しい値を作らない）」指示を含むべきです(§3.3)",
            instruction.contains("semantic_action", ignoreCase = true) &&
                (instruction.contains("echo", ignoreCase = true) || instruction.contains("given", ignoreCase = true))
        )
    }

    // T-P9-7補足（Gemini G2対応、計画書§3.3）: buildSystemInstructionが時刻・数値・URL禁止を明文化する
    @Test
    fun buildSystemInstruction_prohibitsClockTimesNumbersAndUrls() {
        val builder = RecoveryPromptBuilder()

        val instruction = builder.buildSystemInstruction(Locale.US)

        assertTrue(
            "時刻・数値・URLを含めない旨の明文ルールを含むべきです(§15・Gemini G2対応)",
            instruction.contains("clock time", ignoreCase = true) || instruction.contains("number", ignoreCase = true)
        )
    }

    // T-P9-41（F-4、計画書§3.9・§14発見①、優先繰り上げ）: buildSystemInstructionが
    // 「expected件数と同数だけ・列挙された値のみ」という強化された制約文言を含む（Qwen3-0.6Bが
    // 実測で余分な1件を追加し続ける挙動〔§14発見①〕への、プロンプト側からのベストエフォート対策。
    // 確実性はF-4のRecoverySchemaValidator側ロジックが担保し、本文言はあくまで補助策）。
    // 現状のbuildSystemInstructionは旧文言（「echo each given value back exactly once,
    // do not invent, omit, or duplicate any」）のみで、強化後の具体的な追加文言
    // 「no more and no fewer」を含まないため、AssertionErrorでRedになるのが正しい。
    @Test
    fun tP9_41_buildSystemInstruction_containsStrengthenedExactCountConstraint() {
        val builder = RecoveryPromptBuilder()

        val instruction = builder.buildSystemInstruction(Locale.JAPAN)

        assertTrue(
            "「多くも少なくもしない（no more and no fewer）」という強化された件数制約文言を" +
                "含むべきです(T-P9-41、F-4、§14発見①のQwen3-0.6B余剰追加挙動への対策): $instruction",
            instruction.contains("no more and no fewer", ignoreCase = true)
        )
    }
}
