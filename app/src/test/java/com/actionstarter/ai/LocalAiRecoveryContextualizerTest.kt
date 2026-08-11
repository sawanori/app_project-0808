package com.actionstarter.ai

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.RecoveryPlan
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-P9-1〜4（計画書`docs/plans/phase9-recovery-ai.md`§3.1・§7、ADR-0063想定）。
 * [LocalAiRecoveryContextualizer]・[overlay]（Recovery版）のRedテスト。
 *
 * **fakeの設計**: [LocalAiPlanContextualizerTest]・[AiGatewayTestFixtures]が確立した
 * 「[LocalLanguageModel]の境界のみfake化し、[LocalAiGateway]自体は実物をRobolectricで駆動する」
 * 既存規約をそのまま踏襲する。
 *
 * **現状のRed原因**: [LocalAiRecoveryContextualizer.contextualize]・[overlay]（Recovery版）が
 * いずれも`TODO()`のため、経路上の[LocalAiGateway.generateRecovery]（同じく`TODO()`）へ到達した
 * 時点で全件`NotImplementedError`によりRedになるのが正しい。
 */
@RunWith(RobolectricTestRunner::class)
class LocalAiRecoveryContextualizerTest {

    private fun sampleEvent(title: String = "Movie theater"): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = title,
        notes = null,
        startDate = Instant.parse("2026-08-10T18:00:00Z"),
        locationName = "Shibuya",
        coordinates = Coordinate(lat = 35.0, lon = 139.0),
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun sampleContext(event: ExecutionEvent = sampleEvent()): RecoveryContext = RecoveryContext(
        currentTime = Instant.parse("2026-08-10T17:15:00Z"),
        currentLocation = Coordinate(lat = 1.0, lon = 2.0),
        event = event,
        unfinishedSteps = emptyList(),
        latestTravelEstimate = Duration.ofMinutes(20),
        plannedDepartureTime = Instant.parse("2026-08-10T17:30:00Z")
    )

    private fun option(semanticAction: String, skippedStepIds: List<UUID> = emptyList()): RecoveryOption = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = semanticAction,
        title = "",
        explanation = "",
        estimatedArrival = Instant.parse("2026-08-10T17:55:00Z"),
        skippedStepIds = skippedStepIds
    )

    // T-P9-1: 正常 - 全option一致→全explanation差し替え
    @Test
    fun contextualize_allOptionsMatched_appliesAllExplanations() = runTest {
        val basePlan = RecoveryPlan(
            options = listOf(option("keep_all_steps"), option("skip_optional_steps"))
        )
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.recoveryOptionsJson(
                        "keep_all_steps" to "Finish getting ready and leave when done.",
                        "skip_optional_steps" to "Skip the extra steps and leave now."
                    )
                )
            )
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p9_1")
        val contextualizer = LocalAiRecoveryContextualizer(gateway)

        val result = contextualizer.contextualize(basePlan, sampleContext())

        assertTrue("成功時はAppliedであるべきです(T-P9-1)", result is RecoveryContextualizationResult.Applied)
        val applied = result as RecoveryContextualizationResult.Applied
        assertTrue(applied.plan.options.all { it.explanation.isNotEmpty() })
    }

    // T-P9-2（F-4交差一致緩和により意味変更、計画書§3.9・§14発見①、Phase 9.5コミットF-4）:
    // エッジ→正常 - AI側semanticActionが一部欠落（2件中1件のみ返却）でも、交差{keep_all_steps}が
    // 非空のためRecoverySchemaValidatorはPrimaryの時点でValid（Retry不要）。overlayは交差に
    // 含まれないchange_transport_modeをper-option縮退（explanation=""のまま）で扱う
    // （overlayのKDoc「マッチ不能（未知semanticAction／supply不足）の候補」参照）。
    // 旧テスト名`bothAttemptsInvalid_returnsUnchanged`は完全一致必須だった旧契約（Phase 9）の
    // 検証であり、F-4がこのケースをUnchangedからApplied（部分適用）へ意図的に変更したため、
    // 期待値ごと更新した（RecoverySchemaValidatorTestの同型更新と対の変更）。
    @Test
    fun contextualize_missingOneSemanticActionInResponse_intersectionNonEmpty_appliedWithPartialCoverage() = runTest {
        val basePlan = RecoveryPlan(
            options = listOf(option("keep_all_steps"), option("change_transport_mode"))
        )
        // Primaryが1件のみ返す(2件中1件の部分カバレッジ。F-4により交差非空でValidになる)。
        val partialResponse = AiGatewayTestFixtures.recoveryOptionsJson(
            "keep_all_steps" to "Finish getting ready and leave when done."
        )
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(partialResponse)
            )
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p9_2")
        val contextualizer = LocalAiRecoveryContextualizer(gateway)

        val result = contextualizer.contextualize(basePlan, sampleContext())

        assertTrue(
            "F-4の交差一致緩和によりPrimaryが1/2件のみでもValid→Appliedへ至るべきです" +
                "(T-P9-2、計画書§3.9): $result",
            result is RecoveryContextualizationResult.Applied
        )
        val applied = result as RecoveryContextualizationResult.Applied
        assertEquals(
            "件数・順序は§13不変のままbasePlanの2件を維持するべきです",
            2,
            applied.plan.options.size
        )
        assertEquals(
            "交差に含まれるkeep_all_stepsはAI応答のexplanationへ差し替わるべきです",
            "Finish getting ready and leave when done.",
            applied.plan.options.first { it.semanticAction == "keep_all_steps" }.explanation
        )
        assertEquals(
            "交差に含まれないchange_transport_modeはper-option縮退でexplanationが空文字のまま" +
                "残るべきです(overlayのKDoc「マッチ不能の候補」)",
            "",
            applied.plan.options.first { it.semanticAction == "change_transport_mode" }.explanation
        )
    }

    // T-P9-3: 異常 - gateway.generateRecoveryがFallback即返却→Unchanged、explanationは""のまま
    @Test
    fun contextualize_gatewayFallbackImmediately_returnsUnchanged_explanationsStayEmpty() = runTest {
        val basePlan = RecoveryPlan(options = listOf(option("keep_all_steps")))
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond("unused"))
        )
        // aiEnabled=falseによりAI_DISABLEDで即Fallbackする(既存T-GW-2と同型の確立された経路)。
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p9_3", aiEnabled = false)
        val contextualizer = LocalAiRecoveryContextualizer(gateway)

        val result = contextualizer.contextualize(basePlan, sampleContext())

        assertTrue(result is RecoveryContextualizationResult.Unchanged)
        val unchanged = result as RecoveryContextualizationResult.Unchanged
        assertEquals(AiFallbackReason.AI_DISABLED, unchanged.reason)
        assertTrue(unchanged.plan.options.all { it.explanation.isEmpty() })
    }

    // T-P9-4: 不変条件 - overlay後もsemanticAction・skippedStepIds・estimatedArrival・
    // options件数・順序が完全不変(§13相当)
    @Test
    fun contextualize_appliedResult_neverChangesStructureTimeOrOrder() = runTest {
        val skipped = listOf(UUID.randomUUID(), UUID.randomUUID())
        val basePlan = RecoveryPlan(
            options = listOf(
                option("keep_all_steps"),
                option("skip_optional_steps", skippedStepIds = skipped),
                option("change_transport_mode")
            )
        )
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.recoveryOptionsJson(
                        "keep_all_steps" to "Finish getting ready and leave when done.",
                        "skip_optional_steps" to "Skip the extra steps and leave now.",
                        "change_transport_mode" to "Switch how you are getting there."
                    )
                )
            )
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p9_4")
        val contextualizer = LocalAiRecoveryContextualizer(gateway)

        val result = contextualizer.contextualize(basePlan, sampleContext())

        assertTrue(result is RecoveryContextualizationResult.Applied)
        val appliedOptions = (result as RecoveryContextualizationResult.Applied).plan.options
        assertEquals(3, appliedOptions.size)
        assertEquals(
            "semanticActionの並び順は不変であるべきです(T-P9-4、§13相当)",
            basePlan.options.map { it.semanticAction },
            appliedOptions.map { it.semanticAction }
        )
        assertEquals(
            "skippedStepIdsは不変であるべきです(T-P9-4)",
            basePlan.options.map { it.skippedStepIds },
            appliedOptions.map { it.skippedStepIds }
        )
        assertEquals(
            "estimatedArrivalは不変であるべきです(T-P9-4)",
            basePlan.options.map { it.estimatedArrival },
            appliedOptions.map { it.estimatedArrival }
        )
    }
}
