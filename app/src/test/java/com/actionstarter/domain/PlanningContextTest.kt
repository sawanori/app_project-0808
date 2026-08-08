package com.actionstarter.domain

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.TransportMode
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * T-DM-10（計画書§11.2 F1）。[PlanningContext.travelEstimate]がnullでも構築が成功する
 * エッジケース（場所情報のないイベント、エラー＆レスキューマップ#5に対応）を検証する。
 *
 * [PlanningContext]は`travelEstimate`がもともと`Duration?`（null許容）であり、C2時点でも
 * これを妨げる検証は存在しないため、本ケースは現時点でも成功（Green）である見込みが高い。
 * 破壊されないことを保証する回帰防止テストとして維持する。
 */
class PlanningContextTest {

    // T-DM-10: PlanningContext.travelEstimate = nullでも生成成功
    @Test
    fun construct_nullTravelEstimate_succeeds() {
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "Product Shoot",
            notes = null,
            startDate = Instant.parse("2026-08-10T10:00:00Z"),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
        )

        val context = PlanningContext(
            event = event,
            now = Instant.parse("2026-08-10T07:00:00Z"),
            zoneId = ZoneId.of("UTC"),
            locale = Locale.US,
            transportMode = TransportMode.WALKING,
            travelEstimate = null,
            arrivalBuffer = Duration.ofMinutes(10),
            profile = null
        )

        assertNull(context.travelEstimate)
    }
}
