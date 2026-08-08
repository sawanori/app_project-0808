package com.actionstarter.mock

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-MOCK-1／2／3／5／6／8／9（計画書§11.2 F3）。[MockEventSource]の各メソッドは
 * 契約scaffold追補（C2b）時点で本文が`TODO("C4で実装")`のため、以下は全件
 * `NotImplementedError`によりRedになる（意図した失敗）。
 */
class MockEventSourceTest {

    private val now = Instant.parse("2026-08-10T07:00:00Z")

    private fun event(
        title: String = "Product Shoot",
        startDate: Instant,
        sourceCalendar: CalendarSource = CalendarSource(id = "mock", displayName = "Mock Calendar")
    ) = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = title,
        notes = null,
        startDate = startDate,
        locationName = null,
        coordinates = null,
        sourceCalendar = sourceCalendar
    )

    // T-MOCK-1: MockEventSourceが返す次イベントが非null
    @Test
    fun nextEvent_withFutureEvent_returnsNonNull() {
        val source = MockEventSource(events = listOf(event(startDate = now.plusSeconds(3600))))

        assertNotNull(source.nextEvent(now))
    }

    // T-MOCK-2: 予定が空リストのとき、例外ではなく空として扱われる（UI Empty）
    @Test
    fun upcomingEvents_withEmptyEventList_returnsEmptyList() {
        val source = MockEventSource(events = emptyList())

        assertTrue(source.upcomingEvents(now).isEmpty())
    }

    // T-MOCK-3: 全予定が過去日時のとき候補0件になる
    @Test
    fun upcomingEvents_withAllPastEvents_returnsEmptyList() {
        val source = MockEventSource(
            events = listOf(
                event(startDate = now.minusSeconds(3600)),
                event(startDate = now.minusSeconds(7200))
            )
        )

        assertTrue(source.upcomingEvents(now).isEmpty())
    }

    // T-MOCK-5: 翌日のイベントが正しく取得できる。
    // 「日付表示に反映される」の表示検証自体はUI層（T-SEL系、Robolectric担当）の範囲であり、
    // 本テスト（純JVM）ではMockEventSourceが正しいstartDateのイベントを返すことのみを検証する。
    @Test
    fun nextEvent_withTomorrowEvent_returnsThatEventWithCorrectStartDate() {
        val tomorrow = now.plus(Duration.ofDays(1))
        val tomorrowEvent = event(title = "Tomorrow Event", startDate = tomorrow)
        val source = MockEventSource(events = listOf(tomorrowEvent))

        val result = source.nextEvent(now)

        assertEquals(tomorrowEvent.id, result?.id)
        assertEquals(tomorrow, result?.startDate)
    }

    // T-MOCK-6: 進行中（開始済み・未終了）のイベントは候補から除外される。
    // MockEventSourceのKDoc注記どおり、startDate <= nowを「進行中／過去」の同一除外条件と
    // 扱う想定のため、startDate == nowのイベントで検証する。
    @Test
    fun upcomingEvents_withEventStartingExactlyNow_excludesIt() {
        val startedEvent = event(startDate = now)
        val source = MockEventSource(events = listOf(startedEvent))

        assertTrue(source.upcomingEvents(now).isEmpty())
    }

    // T-MOCK-8: title=""だとファクトリがrequire例外を送出する
    @Test
    fun createEvent_emptyTitle_throwsIllegalArgumentException() {
        val source = MockEventSource()

        assertThrows(IllegalArgumentException::class.java) {
            source.createEvent(title = "", startDate = now.plusSeconds(3600))
        }
    }

    // T-MOCK-9: startDate=Instant.MINだと例外
    @Test
    fun createEvent_startDateAtInstantMin_throwsIllegalArgumentException() {
        val source = MockEventSource()

        assertThrows(IllegalArgumentException::class.java) {
            source.createEvent(title = "Valid Title", startDate = Instant.MIN)
        }
    }

    /**
     * 実装先行で発見・修正されたソート欠落（bd30158後の+6/-1修正）の回帰ロック。
     * TDD例外としてFable 5が承認。
     */
    // T-MOCK-11（回帰・Fable 5裁定 2026-08-09）
    @Test
    fun upcomingEvents_withUnorderedEventList_returnsListSortedByStartDateAscending() {
        val first = event(title = "First", startDate = now.plusSeconds(3600))
        val second = event(title = "Second", startDate = now.plusSeconds(7200))
        val third = event(title = "Third", startDate = now.plusSeconds(10800))
        // 時系列順でない投入順（second, third, first）でソート漏れを検出する。
        val source = MockEventSource(events = listOf(second, third, first))

        val result = source.upcomingEvents(now)

        assertEquals(listOf(first.startDate, second.startDate, third.startDate), result.map { it.startDate })
    }
}
