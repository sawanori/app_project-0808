package com.actionstarter.recovery

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-LATE-1〜10（計画書§8.3、`docs/plans/phase6-recovery-basic.md`）。
 *
 * [LatenessDetector.evaluate]は本ファイル作成時点（P6-C2）で本体が`TODO("P6-C3で実装")`のため、
 * 以下は原則すべて`NotImplementedError`によりRedになるのが正しい（意図した失敗。
 * [BasicRecoveryEngineTest]と同じ趣旨）。T-LATE-9（異常系）は`TODO()`自体が
 * `NotImplementedError`を送出するため`assertThrows(IllegalArgumentException::class.java)`は
 * 「期待する例外型と異なる」ことにより失敗し、これもRedとして扱う。
 *
 * 発火条件は`ETA(∅) > event.startDate`の1点のみ（計画書§7.6）。`currentTime >
 * plannedDepartureTime`は`WillMissEvent.behindSchedule`として情報のみ返し、トリガーには
 * しない（誤発火防止）。境界は等号込みで`OnTrack`。
 */
class LatenessDetectorTest {

    private val baseCurrentTime: Instant = Instant.parse("2026-08-10T09:00:00Z")
    private val baseEventStart: Instant = Instant.parse("2026-08-10T10:00:00Z")

    private fun sampleEvent(startDate: Instant = baseEventStart): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = startDate,
        locationName = "Shibuya",
        coordinates = Coordinate(lat = 35.6595, lon = 139.7005),
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun step(
        duration: Duration,
        type: ExecutionStepType = ExecutionStepType.PREPARATION,
        completedAt: Instant? = null
    ): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = "step",
        type = type,
        title = "",
        estimatedDuration = duration,
        priority = StepPriority.OPTIONAL,
        skippable = true,
        scheduledStart = baseCurrentTime,
        completedAt = completedAt
    )

    private fun context(
        currentTime: Instant = baseCurrentTime,
        event: ExecutionEvent = sampleEvent(),
        unfinishedSteps: List<ExecutionStep> = emptyList(),
        latestTravelEstimate: Duration = Duration.ofMinutes(20),
        plannedDepartureTime: Instant = baseEventStart.minus(Duration.ofMinutes(20))
    ): RecoveryContext = RecoveryContext(
        currentTime = currentTime,
        currentLocation = Coordinate(lat = 35.6586, lon = 139.7454),
        event = event,
        unfinishedSteps = unfinishedSteps,
        latestTravelEstimate = latestTravelEstimate,
        plannedDepartureTime = plannedDepartureTime
    )

    // T-LATE-1: 正常系 - ETA(A) < eventStart → OnTrack
    @Test
    fun tLate1_etaBeforeEventStart_returnsOnTrack() {
        val ctx = context(latestTravelEstimate = Duration.ofMinutes(20)) // ETA=09:20 < 10:00

        val verdict = LatenessDetector.evaluate(ctx)

        assertEquals(LatenessVerdict.OnTrack, verdict)
    }

    // T-LATE-2: 正常系 - ETA(A) > eventStart → WillMissEvent(delay = ETA - eventStart)
    @Test
    fun tLate2_etaAfterEventStart_returnsWillMissEventWithCorrectDelay() {
        val ctx = context(latestTravelEstimate = Duration.ofMinutes(90)) // ETA=10:30 > 10:00

        val verdict = LatenessDetector.evaluate(ctx)

        assertTrue(verdict is LatenessVerdict.WillMissEvent)
        val willMiss = verdict as LatenessVerdict.WillMissEvent
        assertEquals(Instant.parse("2026-08-10T10:30:00Z"), willMiss.projectedArrival)
        assertEquals(Duration.ofMinutes(30), willMiss.delay)
    }

    // T-LATE-3: エッジケース - ETA(A) == eventStart（境界）→ OnTrack（等号は成立扱い）
    @Test
    fun tLate3_etaExactlyEqualsEventStart_returnsOnTrack() {
        val ctx = context(latestTravelEstimate = Duration.ofMinutes(60)) // ETA=10:00 == E(10:00)

        val verdict = LatenessDetector.evaluate(ctx)

        assertEquals(LatenessVerdict.OnTrack, verdict)
    }

    // T-LATE-4: エッジケース - currentTime > plannedDepartureTime でもETA(A) <= eventStartなら
    // OnTrack（誤発火防止）
    @Test
    fun tLate4_behindPlannedDepartureButStillFeasible_returnsOnTrack() {
        val ctx = context(
            currentTime = Instant.parse("2026-08-10T09:45:00Z"), // plannedDepartureTime(09:40)超過
            latestTravelEstimate = Duration.ofMinutes(10) // ETA=09:55 <= 10:00
        )

        val verdict = LatenessDetector.evaluate(ctx)

        assertEquals(LatenessVerdict.OnTrack, verdict)
    }

    // T-LATE-5: 正常系 - WillMissEvent.behindScheduleがcurrentTime > plannedDepartureTimeと一致
    // （情報のみ・トリガーではない）
    @Test
    fun tLate5_behindScheduleFlag_matchesCurrentTimeAfterPlannedDepartureTime() {
        val infeasibleAndBehindCtx = context(
            currentTime = Instant.parse("2026-08-10T09:50:00Z"), // plannedDepartureTime(09:40)超過
            latestTravelEstimate = Duration.ofMinutes(90) // ETA=11:20 > E(10:00)
        )
        val infeasibleButNotBehindCtx = context(
            currentTime = Instant.parse("2026-08-10T09:00:00Z"), // plannedDepartureTime(09:40)未到達
            latestTravelEstimate = Duration.ofMinutes(90) // ETA=10:30 > E(10:00)
        )

        val behindVerdict = LatenessDetector.evaluate(infeasibleAndBehindCtx) as LatenessVerdict.WillMissEvent
        val notBehindVerdict = LatenessDetector.evaluate(infeasibleButNotBehindCtx) as LatenessVerdict.WillMissEvent

        assertTrue(behindVerdict.behindSchedule)
        assertTrue(!notBehindVerdict.behindSchedule)
    }

    // T-LATE-6: エッジケース - unfinishedStepsが空 → R_all = ZERO として判定
    @Test
    fun tLate6_emptyUnfinishedSteps_treatsRemainingPreparationAsZero() {
        val ctx = context(unfinishedSteps = emptyList(), latestTravelEstimate = Duration.ofMinutes(90))

        val verdict = LatenessDetector.evaluate(ctx) as LatenessVerdict.WillMissEvent

        // R_all=0のため projectedArrival = currentTime + 0 + 90分
        assertEquals(Instant.parse("2026-08-10T10:30:00Z"), verdict.projectedArrival)
    }

    // T-LATE-7: エッジケース - completedAt != null のステップはR_allから除外
    @Test
    fun tLate7_completedSteps_excludedFromRemainingPreparation() {
        val remaining = step(Duration.ofMinutes(10), completedAt = null)
        val done = step(Duration.ofMinutes(50), completedAt = baseCurrentTime)
        val ctx = context(
            unfinishedSteps = listOf(remaining, done),
            latestTravelEstimate = Duration.ofMinutes(20)
        )

        val verdict = LatenessDetector.evaluate(ctx)

        // R_all=10分のみ(doneの50分は除外)。ETA=09:00+10+20=09:30 <= E(10:00) → OnTrack。
        // doneを誤って含めるとETA=10:20となりWillMissEventに転じてしまう差分を利用する。
        assertEquals(LatenessVerdict.OnTrack, verdict)
    }

    // T-LATE-8: エッジケース - DST跨ぎで判定が1時間ずれない
    @Test
    fun tLate8_dstTransitionCrossingEventStart_doesNotShiftByOneHour() {
        val eventStart = Instant.parse("2026-03-08T10:00:00Z")
        val currentTime = Instant.parse("2026-03-08T09:00:00Z")
        val ctx = context(
            currentTime = currentTime,
            event = sampleEvent(startDate = eventStart),
            latestTravelEstimate = Duration.ofMinutes(20)
        )

        val verdict = LatenessDetector.evaluate(ctx)

        assertEquals(LatenessVerdict.OnTrack, verdict) // ETA=09:20 <= 10:00、1時間ずれれば逆転する
    }

    // T-LATE-9: 異常系 - latestTravelEstimateが負 → IllegalArgumentException
    @Test
    fun tLate9_negativeLatestTravelEstimate_throwsIllegalArgumentException() {
        val ctx = context(latestTravelEstimate = Duration.ofMinutes(-1))

        assertThrows(IllegalArgumentException::class.java) {
            LatenessDetector.evaluate(ctx)
        }
    }

    // T-LATE-10: 正常系 - 同一入力に対し常に同一判定（純関数・システム時刻非依存）
    @Test
    fun tLate10_sameInput_alwaysProducesSameVerdict() {
        val ctx = context(latestTravelEstimate = Duration.ofMinutes(90))

        val first = LatenessDetector.evaluate(ctx)
        val second = LatenessDetector.evaluate(ctx)

        assertEquals(first, second)
    }
}
