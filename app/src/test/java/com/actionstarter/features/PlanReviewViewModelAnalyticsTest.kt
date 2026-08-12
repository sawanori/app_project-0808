@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.ai.AiGatewayTestFixtures
import com.actionstarter.ai.LocalAiPlanContextualizer
import com.actionstarter.analytics.AnalyticsDomain
import com.actionstarter.analytics.AnalyticsStore
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.features.planreview.PlanReviewViewModel
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.planning.BasicPlanningEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Phase 10 C2（計画書§3.2「AI_WORDING_OUTCOME」、レビューCRITICAL・§13 No.1、Step 3
 * Red）。**Plan側採否ケース**——`PlanReviewViewModel`のAI推論完了経路から
 * `AnalyticsStore.recordAiWordingOutcome(domain=PLAN, ...)`が正しく呼ばれることを検証する。
 * Recovery側は[RecoveryViewModelAnalyticsTest]でカバーする。Phase 12比較実験の主データは
 * こちらのPlan側である旨、計画書§0に明記済み。
 */
@RunWith(AndroidJUnit4::class)
class PlanReviewViewModelAnalyticsTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var originalDefaultLocale: Locale

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // PlanReviewViewModelTestと同じ理由（クラスKDoc参照）: EventCategoryClassifier.classify
        // へ渡すlocaleを実行環境非依存にする。
        originalDefaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalDefaultLocale)
        Dispatchers.resetMain()
    }

    private fun sampleEvent(): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = Instant.parse("2026-08-10T10:00:00Z"),
        locationName = null,
        coordinates = null,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    // T-P10-6b: 正常（Plan側採否ケース） - AI応答がAppliedならAI_WORDING_OUTCOME
    // (domain=PLAN, aiAdopted=true, fallbackReason=null)を記録する。
    @Test
    fun tP10_6b_aiApplied_recordsAiWordingOutcomePlanAdopted() = runTest(testDispatcher) {
        val fakeStore = FakeAnalyticsStore()
        val event = sampleEvent()
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "保険証を持って行く")
                )
            )
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p10_6b")

        PlanReviewViewModel(
            planningEngine = BasicPlanningEngine(),
            sharedPlanViewModel = SharedPlanViewModel().apply { selectEvent(event) },
            aiPlanContextualizer = LocalAiPlanContextualizer(gateway),
            analyticsStore = fakeStore
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "AI適用成功時はAI_WORDING_OUTCOME(domain=PLAN)をちょうど1回記録するべきです(T-P10-6b)",
            1,
            fakeStore.aiWordingOutcomeCalls.size
        )
        val call = fakeStore.aiWordingOutcomeCalls.single()
        assertEquals(AnalyticsDomain.PLAN, call.domain)
        assertTrue("Applied時はaiAdopted=trueであるべきです(T-P10-6b)", call.aiAdopted)
        assertEquals(null, call.fallbackReason)
    }

    // T-P10-7b: 異常（Plan側採否ケース） - AI OFF（aiEnabled=false）ならAI_DISABLEDで
    // FallbackしAI_WORDING_OUTCOME(domain=PLAN, aiAdopted=false, fallbackReason="AI_DISABLED")
    // を記録する。
    @Test
    fun tP10_7b_aiDisabled_recordsAiWordingOutcomePlanNotAdopted() = runTest(testDispatcher) {
        val fakeStore = FakeAnalyticsStore()
        val event = sampleEvent()
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(outcomes = emptyList())
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p10_7b", aiEnabled = false)

        PlanReviewViewModel(
            planningEngine = BasicPlanningEngine(),
            sharedPlanViewModel = SharedPlanViewModel().apply { selectEvent(event) },
            aiPlanContextualizer = LocalAiPlanContextualizer(gateway),
            analyticsStore = fakeStore
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeStore.aiWordingOutcomeCalls.size)
        val call = fakeStore.aiWordingOutcomeCalls.single()
        assertEquals(AnalyticsDomain.PLAN, call.domain)
        assertTrue("AI OFF時はaiAdopted=falseであるべきです(T-P10-7b)", !call.aiAdopted)
        assertEquals("AI_DISABLED", call.fallbackReason)
    }

    // T-P10-6c: 異常（回帰ガード） - aiPlanContextualizerがnull（AIフェーズskip）のときは
    // AI_WORDING_OUTCOMEを一切記録しない（AI OFF「フェーズに到達すらしない」ケースと、
    // AI ONだが結果がFallbackするケースを区別する）。
    @Test
    fun tP10_6c_noAiPlanContextualizer_recordsNoAiWordingOutcome() = runTest(testDispatcher) {
        val fakeStore = FakeAnalyticsStore()
        val event = sampleEvent()

        PlanReviewViewModel(
            planningEngine = BasicPlanningEngine(),
            sharedPlanViewModel = SharedPlanViewModel().apply { selectEvent(event) },
            aiPlanContextualizer = null,
            analyticsStore = fakeStore
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            "aiPlanContextualizerがnullのときはAI_WORDING_OUTCOMEを記録しないべきです(T-P10-6c)",
            fakeStore.aiWordingOutcomeCalls.isEmpty()
        )
    }

    private class FakeAnalyticsStore : AnalyticsStore {
        data class AiWordingOutcomeCall(
            val domain: AnalyticsDomain,
            val eventCategory: String,
            val aiAdopted: Boolean,
            val fallbackReason: String?
        )

        val aiWordingOutcomeCalls = mutableListOf<AiWordingOutcomeCall>()

        override suspend fun recordStepDone(eventCategory: String, stepType: String, durationMs: Long?) = Unit
        override suspend fun recordStepSkipped(eventCategory: String, semanticAction: String) = Unit
        override suspend fun recordDelayDetected(eventCategory: String) = Unit
        override suspend fun recordRecoverySelected(eventCategory: String, semanticAction: String) = Unit

        override suspend fun recordAiWordingOutcome(
            domain: AnalyticsDomain,
            eventCategory: String,
            aiAdopted: Boolean,
            fallbackReason: String?
        ) {
            aiWordingOutcomeCalls += AiWordingOutcomeCall(domain, eventCategory, aiAdopted, fallbackReason)
        }

        override suspend fun clearAll(): Result<Unit> = Result.success(Unit)
    }
}
