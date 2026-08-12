@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.analytics.AnalyticsDomain
import com.actionstarter.analytics.AnalyticsStore
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.features.execution.ExecutionViewModel
import com.actionstarter.features.execution.computeClampedStepDurationMs
import com.actionstarter.navigation.SharedPlanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Phase 10 C2（計画書`docs/plans/phase10-behavior-log-profile.md`§3.2「STEP_DONE」、Step 3
 * Red）。`ExecutionViewModel.handleConfirmedPlanDone`から`AnalyticsStore.recordStepDone`が
 * 正しい引数で呼ばれることを、フェイク[AnalyticsStore]で検証する。この時点では
 * `handleConfirmedPlanDone`本体に記録呼び出しがまだ配線されていないためRedになる
 * （C2 Green実装で配線する）。
 */
@RunWith(AndroidJUnit4::class)
class ExecutionViewModelAnalyticsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // T-P10-2: 正常 - STEP_DONEログが正しいdomain="recovery"・eventCategory
    // （EventCategoryClassifier経由）・stepTypeで記録される。
    @Test
    fun tP10_2_handleConfirmedPlanDone_recordsStepDoneWithCategoryAndStepType() = runTest(testDispatcher) {
        val plan = buildConfirmedPlan()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(plan) }
        val fakeStore = FakeAnalyticsStore()
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = sharedPlanViewModel,
            analyticsStore = fakeStore
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.value.onDone?.invoke()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "handleConfirmedPlanDone()はrecordStepDone()をちょうど1回呼ぶべきです(T-P10-2)",
            1,
            fakeStore.stepDoneCalls.size
        )
        val call = fakeStore.stepDoneCalls.single()
        assertEquals(ExecutionStepType.PREPARATION.name, call.stepType)
    }

    // T-P10-2b: 異常（回帰ガード） - analyticsStoreがnull（既定値、既存の後方互換パターン）
    // でもhandleConfirmedPlanDone()はクラッシュせず通常どおり進行する。
    @Test
    fun tP10_2b_handleConfirmedPlanDone_withNullAnalyticsStore_doesNotCrash() = runTest(testDispatcher) {
        val plan = buildConfirmedPlan()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(plan) }
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = sharedPlanViewModel
            // analyticsStoreは既定null
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.value.onDone?.invoke()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentStepIndex)
    }

    // T-P10-durationClamp-1: 正常 - startedAtMillis非null・妥当な範囲内なら実測差分を返す。
    @Test
    fun computeClampedStepDurationMs_normalRange_returnsRawDifference() {
        assertEquals(60_000L, computeClampedStepDurationMs(startedAtMillis = 1_000L, completedAtMillis = 61_000L))
    }

    // T-P10-durationClamp-2: 異常 - startedAtMillisがnullならnullを返す（記録なし、通常
    // 発生しない想定だが防御的に扱う）。
    @Test
    fun computeClampedStepDurationMs_nullStart_returnsNull() {
        assertNull(computeClampedStepDurationMs(startedAtMillis = null, completedAtMillis = 61_000L))
    }

    // T-P10-durationClamp-3: エッジケース - 負の所要時間（時刻の巻き戻り等）はnullへクランプする。
    @Test
    fun computeClampedStepDurationMs_negativeDuration_clampsToNull() {
        assertNull(computeClampedStepDurationMs(startedAtMillis = 100_000L, completedAtMillis = 50_000L))
    }

    // T-P10-durationClamp-4: エッジケース - 24時間を超える非現実的な値（プロセス死から長時間
    // 経過後の復帰等）はnullへクランプする。
    @Test
    fun computeClampedStepDurationMs_exceedsPlausibleWindow_clampsToNull() {
        val twentyFiveHoursMs = 25L * 60 * 60 * 1000
        assertNull(computeClampedStepDurationMs(startedAtMillis = 0L, completedAtMillis = twentyFiveHoursMs))
    }

    // T-P10-durationClamp-5: エッジケース - ちょうど24時間はクランプされない境界値。
    @Test
    fun computeClampedStepDurationMs_exactlyAtWindow_isNotClamped() {
        val twentyFourHoursMs = 24L * 60 * 60 * 1000
        assertTrue(computeClampedStepDurationMs(startedAtMillis = 0L, completedAtMillis = twentyFourHoursMs) != null)
    }

    private class FakeAnalyticsStore : AnalyticsStore {
        data class StepDoneCall(val eventCategory: String, val stepType: String, val durationMs: Long?)

        val stepDoneCalls = mutableListOf<StepDoneCall>()

        override suspend fun recordStepDone(eventCategory: String, stepType: String, durationMs: Long?) {
            stepDoneCalls += StepDoneCall(eventCategory, stepType, durationMs)
        }

        override suspend fun recordStepSkipped(eventCategory: String, semanticAction: String) = Unit
        override suspend fun recordDelayDetected(eventCategory: String) = Unit
        override suspend fun recordRecoverySelected(eventCategory: String, semanticAction: String) = Unit
        override suspend fun recordAiWordingOutcome(
            domain: AnalyticsDomain,
            eventCategory: String,
            aiAdopted: Boolean,
            fallbackReason: String?
        ) = Unit

        override suspend fun getProfile(eventCategory: String): com.actionstarter.domain.model.PersonalExecutionProfile? = null

        override suspend fun clearAll(): Result<Unit> = Result.success(Unit)
    }

    /** [ExecutionOneActionTest.buildConfirmedPlan]と同型の最小フィクスチャ。 */
    private fun buildConfirmedPlan(): ExecutionPlan {
        val fixedNow = Instant.parse("2026-08-12T09:00:00Z")
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "Team sync",
            notes = null,
            startDate = fixedNow.plus(Duration.ofHours(1)),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "cal-1", displayName = "Work")
        )
        val steps = listOf(
            ExecutionStep(
                id = UUID.nameUUIDFromBytes("${event.id}:prep".toByteArray()),
                semanticId = "prep",
                type = ExecutionStepType.PREPARATION,
                title = "",
                estimatedDuration = Duration.ofMinutes(10),
                priority = StepPriority.IMPORTANT,
                skippable = true,
                scheduledStart = fixedNow,
                completedAt = null
            ),
            ExecutionStep(
                id = UUID.nameUUIDFromBytes("${event.id}:transition".toByteArray()),
                semanticId = "transition",
                type = ExecutionStepType.TRANSITION,
                title = "",
                estimatedDuration = Duration.ofMinutes(5),
                priority = StepPriority.REQUIRED,
                skippable = false,
                scheduledStart = fixedNow.plus(Duration.ofMinutes(10)),
                completedAt = null
            )
        )
        return ExecutionPlan(
            event = event,
            steps = steps,
            transitionStart = fixedNow.plus(Duration.ofMinutes(10)),
            departureTime = event.startDate.minus(Duration.ofMinutes(20)),
            estimatedArrival = event.startDate.minus(Duration.ofMinutes(5)),
            arrivalBuffer = Duration.ofMinutes(10)
        )
    }
}
