package com.actionstarter.services.calendar

import android.database.MatrixCursor
import android.provider.CalendarContract
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.TimeZone
import java.util.UUID

/**
 * T-CALMAP-1〜19（計画書§10.2 F13/F15、写像・フィルタ規則は§9）。対象は
 * [CalendarInstanceMapper.isExcluded]／[CalendarInstanceMapper.map]の2純粋関数。
 * 現状（P2-C2契約scaffold）は両関数とも本文が`TODO("P2-C4で実装")`のため、以下は全件
 * `NotImplementedError`によりRedになる（意図した失敗）。`CalendarQuerySpec`の定数検証に
 * 依存する箇所は定数が既に実装済み（宣言のみでTODOなし）のため即Greenになりうるが、
 * これは回帰ガードとして許容する（オーケストレーター指示）。
 *
 * `android.database.MatrixCursor`はRobolectric経由（`android-all`の実AOSP実装）でのみ
 * 実際に動作するため（プレーンJVM単体テストのAndroid stub jarでは"not mocked"になる）、
 * 本クラスは[RobolectricTestRunner]で実行する（計画書§10.1「Robolectric、端末不要」）。
 *
 * 各行は[CalendarQuerySpec.PROJECTION]の14列（M-1実測順）で[MatrixCursor]を手組みする。
 * [isExcluded]/[map]はいずれもカーソルの「現在位置」の1行を読むだけで、`moveToNext()`は
 * 呼び出し側（[CalendarProviderCalendarService]、P2-C4）の責務であるため、各テストは
 * カーソルを`moveToFirst()`してから両関数を呼ぶ（複数行を扱うT-CALMAP-6/15/16を除く）。
 *
 * ## MockEventSourceTestからの移設対象（計画書§6.3・§10.2ヘッダ、T-MOCK-1/2/3/5/6）
 * `docs/plans/phase2-calendar.md`§6.3は`mock/MockEventSourceTest.kt`の7件のうち
 * T-MOCK-1/2/3/5/6の検証意図を本ファイル（および[CalendarProviderCalendarServiceTest]）へ
 * 1対1で引き継ぐと定める（T-MOCK-8/9はMock専用API`createEvent`のrequire検証のため
 * 廃止対象＝移設不要。既存`MockEventSourceTest.kt`自体はC5〔`mock/`削除サイクル〕まで
 * 削除・変更しない）。引き継ぎ先の対応は次のとおり：
 *
 * - **T-MOCK-1**（`nextEvent_withFutureEvent_returnsNonNull`：未来イベントで次イベントが
 *   非null）→ 本ファイル[T-CALMAP-1]（全列が揃った行が正しく写像される基本ケース）、および
 *   [CalendarProviderCalendarServiceTest]の T-CALSVC-2（`readUpcomingEvents`が`Success`で
 *   イベントを返す）。「次イベント」という単一概念はF18のUiState変更で「昇順一覧の先頭」に
 *   置き換わるため、対応先は「正しく写像され取得できること」に一般化される。
 * - **T-MOCK-2**（`upcomingEvents_withEmptyEventList_returnsEmptyList`：空リストで空）→
 *   [CalendarProviderCalendarServiceTest]の T-CALSVC-6（予定0件→`Success(emptyList())`。
 *   0件と失敗の区別も含め、サービス層の対応する等価ケース）。
 * - **T-MOCK-3**（`upcomingEvents_withAllPastEvents_returnsEmptyList`：全件過去→空）→
 *   本ファイル[T-CALMAP-13]（`begin <= from`の境界判定＝除外1）。「過去の予定を除外する」
 *   というMockEventSourceの規則が、CalendarProviderでは`isExcluded`の除外1として明文化・
 *   境界値まで精密化される。
 * - **T-MOCK-5**（`nextEvent_withTomorrowEvent_returnsThatEventWithCorrectStartDate`：
 *   翌日イベントの`startDate`が正しく取得できる）→ 本ファイル[T-CALMAP-1]
 *   （`startDate == Instant.ofEpochMilli(begin)`の正確性）。
 * - **T-MOCK-6**（`upcomingEvents_withEventStartingExactlyNow_excludesIt`：開始時刻が
 *   ちょうど`now`のイベントは除外）→ 本ファイル[T-CALMAP-13]（`begin == from`は除外、
 *   `MockEventSource`の`startDate.isAfter(now)`と同一セマンティクスを継承。§9除外1）。
 */
@RunWith(RobolectricTestRunner::class)
class CalendarInstanceMapperTest {

    private val mapper = CalendarInstanceMapper()

    // ---- 共有フィクスチャ ---------------------------------------------------------------

    private fun buildCursor(rows: List<Map<String, Any?>>): MatrixCursor {
        val cursor = MatrixCursor(CalendarQuerySpec.PROJECTION)
        rows.forEach { row ->
            cursor.addRow(CalendarQuerySpec.PROJECTION.map { column -> row[column] }.toTypedArray())
        }
        return cursor
    }

    private fun singleRowCursor(row: Map<String, Any?>): MatrixCursor {
        val cursor = buildCursor(listOf(row))
        cursor.moveToFirst()
        return cursor
    }

    /**
     * §9の写像・フィルタ規則いずれにも抵触しない「正常・非除外・未来」の基準行。
     * 各テストは検証対象のフィールドのみを上書きする。
     */
    private fun defaultRow(
        eventId: Long = 501L,
        begin: Long? = BEGIN_DEFAULT.toEpochMilli(),
        end: Long? = (BEGIN_DEFAULT.toEpochMilli()) + 1_800_000L,
        title: String? = "Team Sync",
        eventLocation: String? = "Room 12",
        calendarId: Long = 1L,
        allDay: Int = 0,
        eventStatus: Int? = CalendarContract.Instances.STATUS_CONFIRMED,
        deleted: Int = 0,
        selfAttendeeStatus: Int? = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
        description: String? = "Weekly sync",
        calendarDisplayName: String? = "Work",
        eventTimezone: String? = "UTC",
        availability: Int = CalendarContract.Instances.AVAILABILITY_BUSY
    ): Map<String, Any?> = mapOf(
        CalendarContract.Instances.EVENT_ID to eventId,
        CalendarContract.Instances.BEGIN to begin,
        CalendarContract.Instances.END to end,
        CalendarContract.Instances.TITLE to title,
        CalendarContract.Instances.EVENT_LOCATION to eventLocation,
        CalendarContract.Instances.CALENDAR_ID to calendarId,
        CalendarContract.Instances.ALL_DAY to allDay,
        CalendarContract.Instances.STATUS to eventStatus,
        CalendarContract.Events.DELETED to deleted,
        CalendarContract.Instances.SELF_ATTENDEE_STATUS to selfAttendeeStatus,
        CalendarContract.Instances.DESCRIPTION to description,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME to calendarDisplayName,
        CalendarContract.Instances.EVENT_TIMEZONE to eventTimezone,
        CalendarContract.Instances.AVAILABILITY to availability
    )

    // ---- T-CALMAP-1〜8: フィールド写像規則 -----------------------------------------------

    // T-CALMAP-1: 全列が揃った行がExecutionEventへ写像され、
    // startDate == Instant.ofEpochMilli(begin)（§9写像規則の基本ケース。T-MOCK-1/5の移設先）
    @Test
    fun map_withFullyPopulatedRow_mapsAllFields() {
        val eventId = 501L
        val beginMillis = BEGIN_DEFAULT.toEpochMilli()
        val row = defaultRow(eventId = eventId, begin = beginMillis)

        val result = mapper.map(singleRowCursor(row))

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(Instant.ofEpochMilli(beginMillis), result.startDate)
        assertEquals("$eventId@$beginMillis", result.externalCalendarId)
        assertEquals(UUID.nameUUIDFromBytes("$eventId@$beginMillis".toByteArray()), result.id)
        assertEquals("Team Sync", result.title)
        assertEquals("Weekly sync", result.notes)
        assertEquals("Room 12", result.locationName)
        assertNull(result.coordinates)
        assertEquals(CalendarSource(id = "1", displayName = "Work"), result.sourceCalendar)
    }

    // T-CALMAP-2: eventLocationが非空 → locationNameに設定され、coordinates == null
    @Test
    fun map_withNonBlankEventLocation_setsLocationNameAndNullCoordinates() {
        val row = defaultRow(eventLocation = "123 Main St")

        val result = mapper.map(singleRowCursor(row))

        assertEquals("123 Main St", result?.locationName)
        assertNull(result?.coordinates)
    }

    // T-CALMAP-3: eventLocationが"   "（空白のみ）→ locationName == null に正規化される
    @Test
    fun map_withBlankEventLocation_normalizesToNullLocationName() {
        val row = defaultRow(eventLocation = "   ")

        val result = mapper.map(singleRowCursor(row))

        assertNotNull(result)
        assertNull(result?.locationName)
    }

    // T-CALMAP-4: eventLocationがNULL → locationName == null（例外を投げない）
    @Test
    fun map_withNullEventLocation_returnsNullLocationNameWithoutThrowing() {
        val row = defaultRow(eventLocation = null)

        val result = mapper.map(singleRowCursor(row))

        assertNotNull(result)
        assertNull(result?.locationName)
    }

    // T-CALMAP-5: titleがNULL/空文字 → 行は破棄されず、titleは空のまま写像される
    // （UI側で代替文言。ExecutionEvent.titleは非null Stringのためnull→""へ正規化される）
    @Test
    fun map_withNullOrEmptyTitle_doesNotDiscardRowAndUsesEmptyTitle() {
        val nullTitleResult = mapper.map(singleRowCursor(defaultRow(title = null)))
        val emptyTitleResult = mapper.map(singleRowCursor(defaultRow(title = "")))

        assertNotNull(nullTitleResult)
        assertEquals("", nullTitleResult?.title)
        assertNotNull(emptyTitleResult)
        assertEquals("", emptyTitleResult?.title)
    }

    // T-CALMAP-6: 同一event_idでbeginが異なる2行（繰り返し）→ idが互いに異なり、
    // 各idは再実行でも同値（安定性）
    @Test
    fun map_withSameEventIdDifferentBegin_producesDistinctButStableIds() {
        val occurrence1 = defaultRow(eventId = 501L, begin = FROM.plusSeconds(3_600).toEpochMilli())
        val occurrence2 = defaultRow(eventId = 501L, begin = FROM.plusSeconds(90_000).toEpochMilli())

        val id1 = mapper.map(singleRowCursor(occurrence1))?.id
        val id2 = mapper.map(singleRowCursor(occurrence2))?.id
        // 再実行（同一データで新規カーソルを組み直す）しても同じidになること（安定性）。
        val id1Again = mapper.map(singleRowCursor(occurrence1))?.id

        assertNotNull(id1)
        assertNotNull(id2)
        assertNotEquals(id1, id2)
        assertEquals(id1, id1Again)
    }

    // T-CALMAP-7: beginがNULLの行 → nullを返して呼び出し側でスキップし、
    // Success.skippedRowCountへ計上する（例外で全件を落とさない。裁定B8で契約改訂）。
    // skippedRowCountの集計自体はCalendarProviderCalendarServiceの責務であり
    // CalendarProviderCalendarServiceTestで検証する。本テストはそれを可能にするmapper契約
    // （例外を投げずnullを返す）のみを検証する。
    @Test
    fun map_withNullBegin_returnsNullWithoutThrowing() {
        val row = defaultRow(begin = null)

        val result = mapper.map(singleRowCursor(row))

        assertNull(result)
    }

    // T-CALMAP-8: projectionに無い列をgetColumnIndexOrThrowした場合 → 例外がそのまま伝播し、
    // 呼び出し元（CalendarProviderCalendarService）でFailure(QUERY_FAILED)へ写像される
    // （握り潰されない。裁定B8）。本テストはmapper側で例外が伝播すること自体を検証する。
    @Test
    fun map_withProjectionMissingRequiredColumn_propagatesException() {
        val columns = CalendarQuerySpec.PROJECTION
            .filterNot { it == CalendarContract.Instances.TITLE }
            .toTypedArray()
        val row = defaultRow()
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { row[it] }.toTypedArray())
        cursor.moveToFirst()

        assertThrows(IllegalArgumentException::class.java) {
            mapper.map(cursor)
        }
    }

    // ---- T-CALMAP-9〜13: isExcludedのフィルタ規則 -----------------------------------------

    // T-CALMAP-9: allDay == 1 の行は結果に含まれない（フィルタ除外は正常動作であり
    // skippedRowCountには計上しない。裁定B5・B8）
    @Test
    fun isExcluded_withAllDayEvent_returnsTrue() {
        val row = defaultRow(allDay = 1)

        assertTrue(mapper.isExcluded(singleRowCursor(row), FROM))
    }

    // T-CALMAP-10: deleted == 1 / eventStatus == STATUS_CANCELED の行が除外される
    @Test
    fun isExcluded_withDeletedOrCanceledEvent_returnsTrue() {
        val deletedRow = defaultRow(deleted = 1)
        val canceledRow = defaultRow(eventStatus = CalendarContract.Instances.STATUS_CANCELED)

        assertTrue(mapper.isExcluded(singleRowCursor(deletedRow), FROM))
        assertTrue(mapper.isExcluded(singleRowCursor(canceledRow), FROM))
    }

    // T-CALMAP-11: selfAttendeeStatus == DECLINED の行が除外される
    @Test
    fun isExcluded_withDeclinedSelfAttendeeStatus_returnsTrue() {
        val row = defaultRow(selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)

        assertTrue(mapper.isExcluded(singleRowCursor(row), FROM))
    }

    // T-CALMAP-12: availability == FREE の行は除外されない（意図的に非フィルタ）
    @Test
    fun isExcluded_withFreeAvailability_returnsFalse() {
        val row = defaultRow(availability = CalendarContract.Instances.AVAILABILITY_FREE)

        assertFalse(mapper.isExcluded(singleRowCursor(row), FROM))
    }

    // T-CALMAP-13: begin == from（境界ちょうど）は除外、begin == from + 1msは含む
    // （T-MOCK-3/6の移設先：MockEventSourceの`startDate.isAfter(now)`と同一セマンティクス）
    @Test
    fun isExcluded_atFromBoundary_excludesEqualIncludesOneMillisecondLater() {
        val atBoundary = defaultRow(begin = FROM.toEpochMilli())
        val justAfter = defaultRow(begin = FROM.toEpochMilli() + 1)

        assertTrue(mapper.isExcluded(singleRowCursor(atBoundary), FROM))
        assertFalse(mapper.isExcluded(singleRowCursor(justAfter), FROM))
    }

    // ---- T-CALMAP-14〜19: 時刻・順序・境界のエッジケース -----------------------------------

    // T-CALMAP-14: DST切替を跨ぐ窓でも、Instant基準の窓判定が1時間ずれない
    @Test
    fun isExcluded_acrossDstTransition_boundaryUnaffectedByLocalTimeZone() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            // 2026-03-08T07:00:00Z: America/New_York DST開始瞬間（02:00 EST → 03:00 EDT）。
            val from = Instant.parse("2026-03-08T06:59:00Z")
            val begin = from.plusSeconds(60)
            val cursor = singleRowCursor(defaultRow(begin = begin.toEpochMilli()))

            assertFalse(mapper.isExcluded(cursor, from))
            assertEquals(begin, mapper.map(cursor)?.startDate)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    // T-CALMAP-15: 並び順がbegin ASC、同時刻はevent_id ASCで決定的（§9並び順規則）。
    // 実際のSQLソート実行はCalendarQuerySpec.SORT_ORDERの宣言的責務であり
    // （プロバイダ側がbegin ASC, event_id ASCで返す。§8.2 M-2）、本テストはプロバイダが
    // 既にその順で返した行を、mapperが順序を乱さず素直に写像することを保証する。
    @Test
    fun map_withRowsInProviderSortedOrder_preservesBeginAscendingEventIdTieBreakOrder() {
        val t0 = FROM.plusSeconds(60).toEpochMilli()
        val t1 = FROM.plusSeconds(120).toEpochMilli()
        val rows = listOf(
            defaultRow(eventId = 10L, begin = t0),
            defaultRow(eventId = 5L, begin = t1),
            defaultRow(eventId = 20L, begin = t1)
        )
        val cursor = buildCursor(rows)

        val mapped = mutableListOf<ExecutionEvent>()
        while (cursor.moveToNext()) {
            mapper.map(cursor)?.let { mapped += it }
        }

        assertEquals(listOf(t0, t1, t1), mapped.map { it.startDate.toEpochMilli() })
        assertEquals(listOf("10@$t0", "5@$t1", "20@$t1"), mapped.map { it.externalCalendarId })
    }

    // T-CALMAP-16: limit=20に対し行が50件 → 先頭20件のみが処理され、以降のCursor走査を
    // 打ち切る。isExcluded/map自体はlimitを認識しない2関数（§8.3改訂のシグネチャ）のため、
    // limitの強制打ち切りは呼び出し側（CalendarProviderCalendarService、P2-C4実装）の責務。
    // 本テストはその呼び出し側が踏襲すべき「moveToNext→isExcluded→map、収集件数がlimitに
    // 達したら打ち切る」というパターンをここで実行し、(a) mapperが50行すべてで例外なく
    // 動作すること、(b) そのパターンに従えば20件到達時点でcursor走査が実際に止まることを
    // 検証する。打ち切りロジックそのものがCalendarProviderCalendarService内に実装されて
    // いることの検証（Successの件数）はCalendarProviderCalendarServiceTestの責務。
    @Test
    fun mapAndIsExcluded_with50RowsAndLimit20_stopsCursorTraversalAtLimit() {
        val limit = 20
        val rows = (1..50).map { i ->
            defaultRow(eventId = i.toLong(), begin = FROM.plusSeconds(i * 60L).toEpochMilli())
        }
        val cursor = buildCursor(rows)
        val results = mutableListOf<ExecutionEvent>()

        // Fable 5承認済み修正(2026-08-09): オペランド順バグ。実装報告p2c4d参照
        while (results.size < limit && cursor.moveToNext()) {
            if (!mapper.isExcluded(cursor, FROM)) {
                mapper.map(cursor)?.let { results += it }
            }
        }

        assertEquals(20, results.size)
        assertEquals(19, cursor.position)
    }

    // T-CALMAP-17: eventStatusがNULLの行は除外されない（未設定時はNULLになるが、
    // キャンセル扱いにしない。M-6実測済み）
    @Test
    fun isExcluded_withNullEventStatus_returnsFalse() {
        val row = defaultRow(eventStatus = null)

        assertFalse(mapper.isExcluded(singleRowCursor(row), FROM))
    }

    // T-CALMAP-18: 端末のタイムゾーン設定を切り替えてもstartDate（Instant）の値が変化
    // しない（Instantはタイムゾーンに依存しないエポック値であることの回帰確認）
    @Test
    fun map_afterChangingDefaultTimeZone_keepsStartDateUnchanged() {
        val originalTimeZone = TimeZone.getDefault()
        val beginMillis = BEGIN_DEFAULT.toEpochMilli()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val startDateInUtc = mapper.map(singleRowCursor(defaultRow(begin = beginMillis)))?.startDate

            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")) // UTC+14
            val startDateInKiritimati = mapper.map(singleRowCursor(defaultRow(begin = beginMillis)))?.startDate

            assertNotNull(startDateInUtc)
            assertEquals(startDateInUtc, startDateInKiritimati)
            assertEquals(Instant.ofEpochMilli(beginMillis), startDateInKiritimati)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    // T-CALMAP-19: 全日イベント（allDay==1）のbeginがUTC深夜値で格納されている場合でも、
    // 除外規則（除外3）がbeginの時刻値ではなくallDayフラグに基づいて正しく機能する
    // （M-5実測済み）
    @Test
    fun isExcluded_withAllDayEventAtUtcMidnightBegin_excludesBasedOnFlagNotClockValue() {
        val utcMidnightBegin = Instant.parse("2026-08-10T00:00:00Z").toEpochMilli()
        val allDayRow = defaultRow(allDay = 1, begin = utcMidnightBegin)
        val timedRowSameBegin = defaultRow(allDay = 0, begin = utcMidnightBegin)

        assertTrue(mapper.isExcluded(singleRowCursor(allDayRow), FROM))
        assertFalse(mapper.isExcluded(singleRowCursor(timedRowSameBegin), FROM))
    }

    private companion object {
        private val FROM: Instant = Instant.parse("2026-08-09T00:00:00Z")
        private val BEGIN_DEFAULT: Instant = FROM.plusSeconds(3_600)
    }
}
