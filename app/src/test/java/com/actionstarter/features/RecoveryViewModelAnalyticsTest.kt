@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.ai.AiGatewayTestFixtures
import com.actionstarter.ai.LocalAiGateway
import com.actionstarter.ai.LocalAiRecoveryContextualizer
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.analytics.AnalyticsDomain
import com.actionstarter.analytics.AnalyticsStore
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.RecoveryPlan
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.features.recovery.RecoveryViewModel
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.recovery.RecoveryEngine
import com.actionstarter.services.location.LocationResult
import com.actionstarter.services.location.LocationService
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Phase 10 C2（計画書§3.2「DELAY_DETECTED」「STEP_SKIPPED」「RECOVERY_SELECTED」
 * 「AI_WORDING_OUTCOME」、レビュー§13 No.1・No.4、Step 3 Red）。[RecoveryViewModel]の
 * `init`／`useThisPlan`／`refresh`からの記録呼び出しを、フェイク[AnalyticsStore]で検証する。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryViewModelAnalyticsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    private fun samplePlan(): ExecutionPlan = ExecutionPlan(
        event = sampleEvent(),
        steps = emptyList(),
        transitionStart = Instant.parse("2026-08-10T08:30:00Z"),
        departureTime = Instant.parse("2026-08-10T09:00:00Z"),
        estimatedArrival = Instant.parse("2026-08-10T09:30:00Z"),
        arrivalBuffer = Duration.ofMinutes(15)
    )

    private val alwaysSuccessLocationService = object : LocationService {
        override suspend fun currentLocation(timeout: Duration): LocationResult =
            LocationResult.Success(coordinate = Coordinate(lat = 1.0, lon = 2.0), accuracyMeters = 5f, fixedAt = Instant.now())
    }

    private class FixedRecoveryEngine(private val result: RecoveryPlan) : RecoveryEngine {
        override suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan = result
    }

    // T-P10-4: 正常 - RecoveryViewModel構築（init）でDELAY_DETECTEDがちょうど1回記録される
    // （NavHostフック廃止・ViewModelライフサイクル単位の重複排除、レビュー§13 No.4）。
    @Test
    fun tP10_4_init_recordsDelayDetectedExactlyOnce() = runTest(testDispatcher) {
        val fakeStore = FakeAnalyticsStore()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(samplePlan()) }
        RecoveryViewModel(
            recoveryEngine = FixedRecoveryEngine(RecoveryPlan(options = emptyList())),
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC(),
            analyticsStore = fakeStore
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "RecoveryViewModel構築でDELAY_DETECTEDがちょうど1回記録されるべきです(T-P10-4)",
            1,
            fakeStore.delayDetectedCalls.size
        )
    }

    // T-P10-5 / T-P10-3: 正常 - useThisPlan()がRECOVERY_SELECTED（常に）とSTEP_SKIPPED
    // （option.skippedStepIdsが非空のときのみ）を記録する。RecoveryPlanApplier.applyの
    // 検証（skippedStepIdsはplan.steps側に実在しREQUIREDでないことを要求する）を満たすため、
    // samplePlan()ではなくOPTIONALステップを1件持つplanを使う。
    @Test
    fun tP10_5_useThisPlan_recordsRecoverySelectedAndStepSkipped() = runTest(testDispatcher) {
        val skippedId = UUID.randomUUID()
        val planWithSkippableStep = samplePlan().copy(
            steps = listOf(
                ExecutionStep(
                    id = skippedId,
                    semanticId = "optional-step",
                    type = ExecutionStepType.PREPARATION,
                    title = "",
                    estimatedDuration = Duration.ofMinutes(5),
                    priority = StepPriority.OPTIONAL,
                    skippable = true,
                    scheduledStart = Instant.parse("2026-08-10T08:00:00Z"),
                    completedAt = null
                )
            )
        )
        val option = RecoveryOption(
            id = UUID.randomUUID(),
            semanticAction = "skip_optional_steps",
            title = "",
            explanation = "",
            estimatedArrival = Instant.parse("2026-08-10T09:30:00Z"),
            skippedStepIds = listOf(skippedId)
        )
        val fakeStore = FakeAnalyticsStore()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(planWithSkippableStep) }
        val viewModel = RecoveryViewModel(
            recoveryEngine = FixedRecoveryEngine(RecoveryPlan(options = listOf(option))),
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC(),
            analyticsStore = fakeStore
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.useThisPlan(option.id)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "useThisPlan()はRECOVERY_SELECTEDをちょうど1回記録するべきです(T-P10-5)",
            1,
            fakeStore.recoverySelectedCalls.size
        )
        assertEquals("skip_optional_steps", fakeStore.recoverySelectedCalls.single().semanticAction)
        assertEquals(
            "skippedStepIdsが非空のときSTEP_SKIPPEDもちょうど1回記録するべきです(T-P10-3)",
            1,
            fakeStore.stepSkippedCalls.size
        )
    }

    // T-P10-3b: エッジケース - option.skippedStepIdsが空のときはSTEP_SKIPPEDを記録しない
    // （実際に何も削られていないため）。
    @Test
    fun tP10_3b_useThisPlan_withNoSkippedSteps_doesNotRecordStepSkipped() = runTest(testDispatcher) {
        val option = RecoveryOption(
            id = UUID.randomUUID(),
            semanticAction = "keep_all_steps",
            title = "",
            explanation = "",
            estimatedArrival = Instant.parse("2026-08-10T09:30:00Z"),
            skippedStepIds = emptyList()
        )
        val fakeStore = FakeAnalyticsStore()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(samplePlan()) }
        val viewModel = RecoveryViewModel(
            recoveryEngine = FixedRecoveryEngine(RecoveryPlan(options = listOf(option))),
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC(),
            analyticsStore = fakeStore
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.useThisPlan(option.id)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeStore.recoverySelectedCalls.size)
        assertTrue(
            "skippedStepIdsが空のときSTEP_SKIPPEDは記録しないべきです(T-P10-3b)",
            fakeStore.stepSkippedCalls.isEmpty()
        )
    }

    // T-P10-6: 正常 - AI応答がAppliedならAI_WORDING_OUTCOME(domain=RECOVERY, aiAdopted=true,
    // fallbackReason=null)を記録する。AiGatewayTestFixturesで実際のLocalAiRecoveryContextualizer
    // をfakeモデル経由で構築する（T-P9-31と同型パターン）。
    @Test
    fun tP10_6_refresh_aiApplied_recordsAiWordingOutcomeAdopted() = runTest(testDispatcher) {
        val fakeStore = FakeAnalyticsStore()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(samplePlan()) }
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.recoveryOptionsJson("keep_all_steps" to "Finish getting ready and leave when done.")
                )
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = AiGatewayTestFixtures.installedModelStorage(),
            modelVerifier = ModelVerifierImpl(),
            deviceCapability = AiGatewayTestFixtures.supportedDeviceCapability(),
            preferences = AiGatewayTestFixtures.preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP10_6")
        )
        val option = RecoveryOption(
            id = UUID.randomUUID(),
            semanticAction = "keep_all_steps",
            title = "",
            explanation = "",
            estimatedArrival = Instant.parse("2026-08-10T09:30:00Z"),
            skippedStepIds = emptyList()
        )
        RecoveryViewModel(
            recoveryEngine = FixedRecoveryEngine(RecoveryPlan(options = listOf(option))),
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC(),
            aiRecoveryContextualizer = LocalAiRecoveryContextualizer(gateway),
            analyticsStore = fakeStore
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "AI適用成功時はAI_WORDING_OUTCOMEをちょうど1回記録するべきです(T-P10-6)",
            1,
            fakeStore.aiWordingOutcomeCalls.size
        )
        val call = fakeStore.aiWordingOutcomeCalls.single()
        assertEquals(AnalyticsDomain.RECOVERY, call.domain)
        assertTrue("Applied時はaiAdopted=trueであるべきです(T-P10-6)", call.aiAdopted)
        assertEquals(null, call.fallbackReason)
    }

    // T-P10-7: 異常 - AI OFF（aiEnabled=false）ならAiFallbackReason.AI_DISABLEDでFallbackし、
    // AI_WORDING_OUTCOME(aiAdopted=false, fallbackReason="AI_DISABLED")を記録する。
    @Test
    fun tP10_7_refresh_aiDisabled_recordsAiWordingOutcomeNotAdopted() = runTest(testDispatcher) {
        val fakeStore = FakeAnalyticsStore()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(samplePlan()) }
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(outcomes = emptyList(), recoveryOutcomes = emptyList())
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = AiGatewayTestFixtures.installedModelStorage(),
            modelVerifier = ModelVerifierImpl(),
            deviceCapability = AiGatewayTestFixtures.supportedDeviceCapability(),
            // aiEnabled=falseで確実にAI_DISABLEDフォールバックを起こす（Respond不要）。
            preferences = AiGatewayTestFixtures.preferences(aiEnabled = false, prefsFileName = "test_ai_prefs_tP10_7")
        )
        val option = RecoveryOption(
            id = UUID.randomUUID(),
            semanticAction = "keep_all_steps",
            title = "",
            explanation = "",
            estimatedArrival = Instant.parse("2026-08-10T09:30:00Z"),
            skippedStepIds = emptyList()
        )
        RecoveryViewModel(
            recoveryEngine = FixedRecoveryEngine(RecoveryPlan(options = listOf(option))),
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC(),
            aiRecoveryContextualizer = LocalAiRecoveryContextualizer(gateway),
            analyticsStore = fakeStore
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeStore.aiWordingOutcomeCalls.size)
        val call = fakeStore.aiWordingOutcomeCalls.single()
        assertEquals(AnalyticsDomain.RECOVERY, call.domain)
        assertTrue("AI OFF時はaiAdopted=falseであるべきです(T-P10-7)", !call.aiAdopted)
        assertEquals("AI_DISABLED", call.fallbackReason)
    }

    private class FakeAnalyticsStore : AnalyticsStore {
        data class StepSkippedCall(val eventCategory: String, val semanticAction: String)
        data class DelayDetectedCall(val eventCategory: String)
        data class RecoverySelectedCall(val eventCategory: String, val semanticAction: String)
        data class AiWordingOutcomeCall(
            val domain: AnalyticsDomain,
            val eventCategory: String,
            val aiAdopted: Boolean,
            val fallbackReason: String?
        )

        val stepSkippedCalls = mutableListOf<StepSkippedCall>()
        val delayDetectedCalls = mutableListOf<DelayDetectedCall>()
        val recoverySelectedCalls = mutableListOf<RecoverySelectedCall>()
        val aiWordingOutcomeCalls = mutableListOf<AiWordingOutcomeCall>()

        override suspend fun recordStepDone(eventCategory: String, stepType: String, durationMs: Long?) = Unit

        override suspend fun recordStepSkipped(eventCategory: String, semanticAction: String) {
            stepSkippedCalls += StepSkippedCall(eventCategory, semanticAction)
        }

        override suspend fun recordDelayDetected(eventCategory: String) {
            delayDetectedCalls += DelayDetectedCall(eventCategory)
        }

        override suspend fun recordRecoverySelected(eventCategory: String, semanticAction: String) {
            recoverySelectedCalls += RecoverySelectedCall(eventCategory, semanticAction)
        }

        override suspend fun recordAiWordingOutcome(
            domain: AnalyticsDomain,
            eventCategory: String,
            aiAdopted: Boolean,
            fallbackReason: String?
        ) {
            aiWordingOutcomeCalls += AiWordingOutcomeCall(domain, eventCategory, aiAdopted, fallbackReason)
        }

        override suspend fun getProfile(eventCategory: String): com.actionstarter.domain.model.PersonalExecutionProfile? = null

        override suspend fun clearAll(): Result<Unit> = Result.success(Unit)
    }
}
