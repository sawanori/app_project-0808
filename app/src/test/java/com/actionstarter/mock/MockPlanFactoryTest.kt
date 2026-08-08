package com.actionstarter.mock

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.PersonalExecutionProfile
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * T-MOCK-4／7／10（計画書§11.2 F3）。[MockPlanFactory.createPlan]は契約scaffold追補
 * （C2b）時点で本文が`TODO("C4で実装")`のため、以下は全件`NotImplementedError`により
 * Redになる（意図した失敗）。suspend関数のため`kotlinx-coroutines-test`の`runTest`を使う。
 */
class MockPlanFactoryTest {

    private val zoneId = ZoneId.of("UTC")
    private val locale = Locale.US

    // prep/transitionのデフォルトフォールバック値はC4実装依存で未確定のため、
    // T-MOCK-7／T-MOCK-10の期待値計算を一意にするために明示的なprofileを与える。
    private val profile = PersonalExecutionProfile(
        eventCategory = "default",
        averageTransitionDuration = Duration.ofMinutes(10),
        averagePreparationDuration = Duration.ofMinutes(20),
        averageResponseDelay = Duration.ofMinutes(2),
        averageDepartureDelay = Duration.ofMinutes(2),
        preferredArrivalBuffer = Duration.ofMinutes(10)
    )

    private fun event(
        locationName: String? = "Shibuya",
        coordinates: Coordinate? = Coordinate(lat = 35.6595, lon = 139.7005),
        startDate: Instant = Instant.parse("2026-08-10T10:00:00Z")
    ) = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = startDate,
        locationName = locationName,
        coordinates = coordinates,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    // T-MOCK-4: 場所情報なしのイベントはTRAVELステップを生成せず、ETA未取得として扱う。
    // 「ETA未取得」はExecutionPlan.estimatedArrivalが非null型（Instant、null許容ではない）
    // のため値としては表現不能。ここではTRAVELステップが生成されないことのみを検証する
    // （estimatedArrivalの具体的なフォールバック値はC4実装依存であり本ケースの対象外）。
    @Test
    fun createPlan_eventWithoutLocation_generatesNoTravelStep() = runTest {
        val context = PlanningContext(
            event = event(locationName = null, coordinates = null),
            now = Instant.parse("2026-08-10T07:00:00Z"),
            zoneId = zoneId,
            locale = locale,
            transportMode = TransportMode.WALKING,
            travelEstimate = null,
            arrivalBuffer = Duration.ofMinutes(10),
            profile = profile
        )

        val plan = MockPlanFactory().createPlan(context)

        assertTrue(plan.steps.none { it.type == ExecutionStepType.TRAVEL })
    }

    // T-MOCK-7: transitionStart < now でもPlanは生成され、stepsは自動省略されない（§33/34）。
    // ExecutionPlanに`isBehindSchedule`フィールドは存在しない（仕様§49準拠・UI側派生値。
    // 担当タスク指示のとおりの対応）。ここでは
    //   (a) plan.transitionStart.isBefore(now)が真であること
    //   (b) 余裕があるケース（roomy）と比べてstep数が変わらない（自動省略されない）こと
    // を検証する。event/buffer/travel/profileは固定し、§13の式で一意に決まる
    // transitionStartを基準にnowだけを前後にずらして両ケースを作る。
    @Test
    fun createPlan_transitionStartBeforeNow_keepsAllStepsAndTransitionStartIsBeforeNow() = runTest {
        val fixedEvent = event(startDate = Instant.parse("2026-08-10T10:00:00Z"))
        val travelEstimate = Duration.ofMinutes(20)
        val arrivalBuffer = Duration.ofMinutes(10)
        val expectedTransitionStart = fixedEvent.startDate
            .minus(arrivalBuffer)
            .minus(travelEstimate)
            .minus(profile.averagePreparationDuration)
            .minus(profile.averageTransitionDuration)

        fun contextAt(now: Instant) = PlanningContext(
            event = fixedEvent,
            now = now,
            zoneId = zoneId,
            locale = locale,
            transportMode = TransportMode.WALKING,
            travelEstimate = travelEstimate,
            arrivalBuffer = arrivalBuffer,
            profile = profile
        )

        val roomyContext = contextAt(now = expectedTransitionStart.minus(Duration.ofHours(1)))
        val behindContext = contextAt(now = expectedTransitionStart.plus(Duration.ofHours(1)))

        val roomyPlan = MockPlanFactory().createPlan(roomyContext)
        val behindPlan = MockPlanFactory().createPlan(behindContext)

        assertFalse(roomyPlan.transitionStart.isBefore(roomyContext.now))
        assertTrue(behindPlan.transitionStart.isBefore(behindContext.now))
        assertEquals(roomyPlan.steps.size, behindPlan.steps.size)
    }

    // T-MOCK-10: 生成される時刻計算が仕様§13の式と一致する。
    // StartOfTransition = EventStart - ArrivalBuffer - TravelTime - PreparationTime - TransitionTime
    @Test
    fun createPlan_transitionStart_matchesSection13Formula() = runTest {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val travelEstimate = Duration.ofMinutes(52)
        val arrivalBuffer = Duration.ofMinutes(8)

        val context = PlanningContext(
            event = event(startDate = eventStart),
            now = eventStart.minus(Duration.ofHours(3)),
            zoneId = zoneId,
            locale = locale,
            transportMode = TransportMode.WALKING,
            travelEstimate = travelEstimate,
            arrivalBuffer = arrivalBuffer,
            profile = profile
        )

        val expectedTransitionStart = eventStart
            .minus(arrivalBuffer)
            .minus(travelEstimate)
            .minus(profile.averagePreparationDuration)
            .minus(profile.averageTransitionDuration)

        val plan = MockPlanFactory().createPlan(context)

        assertEquals(expectedTransitionStart, plan.transitionStart)
    }
}
