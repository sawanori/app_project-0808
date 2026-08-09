@file:OptIn(ExperimentalCoroutinesApi::class)

package com.actionstarter.services.calendar

import android.database.Cursor
import android.database.CursorWrapper
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * T-CALSVC-1〜13（計画書§10.2 F12/F13/F14、契約は§7.2）。対象は[CalendarProviderCalendarService]
 * （L2）。現状（P2-C2契約scaffold）は[CalendarProviderCalendarService.readCalendars]／
 * [CalendarProviderCalendarService.readUpcomingEvents]とも本文が`TODO("P2-C4で実装")`のため、
 * 以下は全件`NotImplementedError`によりRedになる（意図した失敗）。
 *
 * [CursorSource]をfake実装（[FakeCursorSource]）に差し替えてコンストラクタ注入することで、
 * `ContentResolver`にもRobolectricの`ContentProvider`登録機構にも依存せずJVM/Robolectric上で
 * 決定的にテストする（計画書§8.3改訂・3層分割）。`android.database.MatrixCursor`／
 * `android.database.CursorWrapper`はRobolectric経由（`android-all`の実AOSP実装）でのみ実際に
 * 動作するため、本クラスは[RobolectricTestRunner]で実行する。
 *
 * ## readCalendars()のprojection列名について
 * [CalendarQuerySpec]は`Instances`クエリ（F13 Upcoming Events）の列・selection・sortOrderのみ
 * を宣言しており、`Calendars`テーブルへの問い合わせ（F14 Calendar List、[readCalendars]の実装）
 * が使う列集合は本スキャフォールドに宣言がなく、P2-C4実装者の裁量である。そのため
 * [FakeCursorSource]は特定の列名を決め打ちせず、呼び出し側が実際に要求した`projection`引数を
 * そのままエコーして応答する（[buildCursorEchoingProjection]）。フェイクが値を用意していない
 * 列が要求された場合はテスト設定漏れとして`require`で即座に失敗させる。
 *
 * ## MockEventSourceTestからの移設対象（計画書§6.3、T-MOCK-1/2/3/5/6）
 * 詳細な対応表は[CalendarInstanceMapperTest]のクラスKDocを参照。サービス層に関係するのは
 * 次の2件：T-MOCK-1（未来イベントが取得できる）→ 本ファイルT-CALSVC-2
 * （`readUpcomingEvents`が`Success`でイベントを返す）。T-MOCK-2（空リストで空）→
 * 本ファイルT-CALSVC-6（予定0件→`Success(emptyList())`、`Failure`ではないことの区別）。
 */
@RunWith(RobolectricTestRunner::class)
class CalendarProviderCalendarServiceTest {

    // ---- 共有フィクスチャ ---------------------------------------------------------------

    /**
     * [CursorSource]のfake実装。呼び出しを[calls]へ記録するspyを兼ね、各呼び出しへの応答は
     * [onQuery]へ委譲する（テストごとに固定値・例外・null・selection依存の挙動を切り替える）。
     */
    private class FakeCursorSource(
        private val onQuery: (QueryCall) -> Cursor?
    ) : CursorSource {

        data class QueryCall(
            val uri: Uri,
            val projection: Array<String>?,
            val selection: String?,
            val selectionArgs: Array<String>?,
            val sortOrder: String?
        )

        val calls = mutableListOf<QueryCall>()

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor? {
            val call = QueryCall(uri, projection, selection, selectionArgs, sortOrder)
            calls += call
            return onQuery(call)
        }
    }

    /** [Cursor.close]の呼び出し回数を記録するテスト用wrapper（T-CALSVC-8）。 */
    private class CloseTrackingCursor(delegate: Cursor) : CursorWrapper(delegate) {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
            super.close()
        }
    }

    /**
     * [moveToNext]呼び出し回数が[cancelAfterCallCount]へ達した時点で[onCancelTrigger]を実行する
     * テスト用wrapper（T-CALSVC-10：coroutineキャンセル時の走査中断検証）。
     */
    private class CancelingCursor(
        delegate: Cursor,
        private val cancelAfterCallCount: Int,
        private val onCancelTrigger: () -> Unit
    ) : CursorWrapper(delegate) {
        var moveToNextCallCount = 0
            private set

        override fun moveToNext(): Boolean {
            moveToNextCallCount++
            if (moveToNextCallCount == cancelAfterCallCount) {
                onCancelTrigger()
            }
            return super.moveToNext()
        }
    }

    /** [dispatch]呼び出し回数を記録するテスト用CoroutineDispatcher（T-CALSVC-9）。 */
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher
    ) : CoroutineDispatcher() {
        var dispatchInvocations = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchInvocations++
            delegate.dispatch(context, block)
        }
    }

    private fun instanceRow(
        eventId: Long,
        begin: Long,
        calendarId: Long = 1L,
        calendarDisplayName: String? = "Work",
        title: String? = "Team Sync",
        eventLocation: String? = null,
        end: Long = begin + 1_800_000L,
        allDay: Int = 0,
        eventStatus: Int? = CalendarContract.Instances.STATUS_CONFIRMED,
        deleted: Int = 0,
        selfAttendeeStatus: Int? = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
        description: String? = null,
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

    private fun instancesCursor(rows: List<Map<String, Any?>>): MatrixCursor {
        val cursor = MatrixCursor(CalendarQuerySpec.PROJECTION)
        rows.forEach { row ->
            cursor.addRow(CalendarQuerySpec.PROJECTION.map { column -> row[column] }.toTypedArray())
        }
        return cursor
    }

    private fun calendarRow(id: Long, displayName: String?, visible: Int = 1): Map<String, Any?> = mapOf(
        CalendarContract.Calendars._ID to id,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to displayName,
        CalendarContract.Calendars.VISIBLE to visible
    )

    /**
     * 呼び出し側が要求したprojection列名をそのまま反映してMatrixCursorを組み立てる
     * （本ファイルKDoc「readCalendars()のprojection列名について」参照）。
     */
    private fun buildCursorEchoingProjection(
        projection: Array<String>?,
        rowValues: List<Map<String, Any?>>
    ): MatrixCursor {
        val columns = projection ?: rowValues.firstOrNull()?.keys?.toTypedArray() ?: emptyArray()
        val cursor = MatrixCursor(columns)
        rowValues.forEach { row ->
            val values = columns.map { column ->
                require(row.containsKey(column)) {
                    "fakeが値を用意していない列がprojectionで要求された: $column"
                }
                row[column]
            }
            cursor.addRow(values.toTypedArray())
        }
        return cursor
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> assertSuccessValue(result: CalendarResult<T>): T {
        assertTrue("expected Success but was $result", result is CalendarResult.Success<*>)
        return (result as CalendarResult.Success<T>).value
    }

    private fun assertSuccessSkippedRowCount(result: CalendarResult<*>): Int {
        assertTrue("expected Success but was $result", result is CalendarResult.Success<*>)
        return (result as CalendarResult.Success<*>).skippedRowCount
    }

    private fun assertPermissionDenied(result: CalendarResult<*>) {
        assertTrue("expected PermissionDenied but was $result", result is CalendarResult.PermissionDenied)
    }

    private fun assertFailureReason(result: CalendarResult<*>, expectedReason: CalendarFailureReason): Throwable? {
        assertTrue("expected Failure but was $result", result is CalendarResult.Failure)
        val failure = result as CalendarResult.Failure
        assertEquals(expectedReason, failure.reason)
        return failure.cause
    }

    // ---- T-CALSVC-1/7: readCalendars ------------------------------------------------------

    // T-CALSVC-1: 可視カレンダー2件 → Success(List<CalendarSource>)が2件、
    // displayNameが保持される
    @Test
    fun readCalendars_withTwoVisibleCalendars_returnsSuccessWithBothDisplayNames() = runTest {
        val rows = listOf(
            calendarRow(id = 1L, displayName = "Work"),
            calendarRow(id = 2L, displayName = "Personal")
        )
        val cursorSource = FakeCursorSource { call -> buildCursorEchoingProjection(call.projection, rows) }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readCalendars()

        val value = assertSuccessValue(result)
        assertEquals(
            listOf(CalendarSource(id = "1", displayName = "Work"), CalendarSource(id = "2", displayName = "Personal")),
            value
        )
    }

    // T-CALSVC-7: カレンダーが1件も存在しない → Success(emptyList())
    @Test
    fun readCalendars_withNoCalendars_returnsSuccessEmptyList() = runTest {
        val cursorSource = FakeCursorSource { call -> buildCursorEchoingProjection(call.projection, emptyList()) }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readCalendars()

        assertEquals(emptyList<CalendarSource>(), assertSuccessValue(result))
    }

    // ---- T-CALSVC-2〜6/8〜13: readUpcomingEvents -------------------------------------------

    // T-CALSVC-2: readUpcomingEventsがSuccessで昇順のイベントを返す（T-MOCK-1の移設先）
    @Test
    fun readUpcomingEvents_withThreeRows_returnsSuccessInAscendingOrder() = runTest {
        val t0 = FROM.plusSeconds(60).toEpochMilli()
        val t1 = FROM.plusSeconds(3_600).toEpochMilli()
        val t2 = FROM.plusSeconds(7_200).toEpochMilli()
        val rows = listOf(
            instanceRow(eventId = 1L, begin = t0),
            instanceRow(eventId = 2L, begin = t1),
            instanceRow(eventId = 3L, begin = t2)
        )
        val cursorSource = FakeCursorSource { instancesCursor(rows) }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readUpcomingEvents(FROM, UNTIL)

        val value = assertSuccessValue(result)
        assertEquals(listOf(t0, t1, t2), value.map { it.startDate.toEpochMilli() })
        assertEquals(0, assertSuccessSkippedRowCount(result))
    }

    // T-CALSVC-3: queryがSecurityExceptionを投げる → PermissionDenied
    // （例外は外に漏れない/emptyListへ潰さない）
    @Test
    fun readUpcomingEvents_whenQueryThrowsSecurityException_returnsPermissionDenied() = runTest {
        val cursorSource = FakeCursorSource { throw SecurityException("READ_CALENDAR revoked") }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readUpcomingEvents(FROM, UNTIL)

        assertPermissionDenied(result)
    }

    // T-CALSVC-4: queryがnullを返す → Failure(PROVIDER_UNAVAILABLE)
    @Test
    fun readUpcomingEvents_whenQueryReturnsNull_returnsFailureProviderUnavailable() = runTest {
        val cursorSource = FakeCursorSource { null }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readUpcomingEvents(FROM, UNTIL)

        assertFailureReason(result, CalendarFailureReason.PROVIDER_UNAVAILABLE)
    }

    // T-CALSVC-5: queryがIllegalArgumentException（列不在）→ Failure(QUERY_FAILED, cause保持)
    @Test
    fun readUpcomingEvents_whenQueryThrowsIllegalArgumentException_returnsFailureQueryFailedWithCause() = runTest {
        val thrown = IllegalArgumentException("Invalid column foo")
        val cursorSource = FakeCursorSource { throw thrown }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readUpcomingEvents(FROM, UNTIL)

        val cause = assertFailureReason(result, CalendarFailureReason.QUERY_FAILED)
        assertEquals(thrown, cause)
    }

    // T-CALSVC-6: 予定0件 → Success(emptyList())（Failureではない。0件と失敗を区別する。
    // T-MOCK-2の移設先）
    @Test
    fun readUpcomingEvents_withNoRows_returnsSuccessEmptyList() = runTest {
        val cursorSource = FakeCursorSource { instancesCursor(emptyList()) }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readUpcomingEvents(FROM, UNTIL)

        assertEquals(emptyList<ExecutionEvent>(), assertSuccessValue(result))
    }

    // T-CALSVC-8: 例外発生時もCursorがcloseされる（useの検証。fake providerのcloseカウンタ）
    @Test
    fun readUpcomingEvents_whenRowProcessingThrows_stillClosesCursor() = runTest {
        // T-CALMAP-8と同型のセットアップ：TITLE列を欠いたカーソルでgetColumnIndexOrThrowが
        // 例外を投げる状況を再現する。close追跡はCursorWrapperで行う。
        val columns = CalendarQuerySpec.PROJECTION
            .filterNot { it == CalendarContract.Instances.TITLE }
            .toTypedArray()
        val row = instanceRow(eventId = 1L, begin = FROM.plusSeconds(60).toEpochMilli())
        val baseCursor = MatrixCursor(columns)
        baseCursor.addRow(columns.map { row[it] }.toTypedArray())
        val trackingCursor = CloseTrackingCursor(baseCursor)
        val cursorSource = FakeCursorSource { trackingCursor }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readUpcomingEvents(FROM, UNTIL)

        assertFailureReason(result, CalendarFailureReason.QUERY_FAILED)
        assertEquals(1, trackingCursor.closeCount)
    }

    // T-CALSVC-9: 呼び出しがテスト用ディスパッチャ上で実行される（メインスレッドで
    // ブロッキングIOしない。withContext(ioDispatcher)使用の検証）
    @Test
    fun readUpcomingEvents_usesInjectedIoDispatcher() = runTest {
        val recordingDispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
        val cursorSource = FakeCursorSource { instancesCursor(emptyList()) }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = recordingDispatcher)

        service.readUpcomingEvents(FROM, UNTIL)

        assertTrue(
            "expected at least one dispatch via the injected ioDispatcher",
            recordingDispatcher.dispatchInvocations > 0
        )
    }

    // T-CALSVC-10: coroutineキャンセル時に走査が中断される（ensureActiveの検証。
    // エラーマップ#10）
    @Test
    fun readUpcomingEvents_whenCancelledMidIteration_stopsProcessingRemainingRows() = runTest {
        val totalRows = 10
        val cancelAfter = 3
        lateinit var job: Job
        val rows = (1..totalRows).map { i ->
            instanceRow(eventId = i.toLong(), begin = FROM.plusSeconds(i * 60L).toEpochMilli())
        }
        val cancelingCursor = CancelingCursor(
            delegate = instancesCursor(rows),
            cancelAfterCallCount = cancelAfter
        ) { job.cancel() }
        val cursorSource = FakeCursorSource { cancelingCursor }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        job = launch { service.readUpcomingEvents(FROM, UNTIL) }
        job.join()

        assertTrue("expected job to be cancelled", job.isCancelled)
        assertTrue(
            "expected traversal to stop before visiting all $totalRows rows, " +
                "but moveToNext was called ${cancelingCursor.moveToNextCallCount} times",
            cancelingCursor.moveToNextCallCount < totalRows
        )
    }

    // T-CALSVC-11: calendarIds = emptySet() → クエリを発行せずSuccess(emptyList())を
    // 即座に返す（IN句の構文エラー防止。裁定B13）
    @Test
    fun readUpcomingEvents_withEmptyCalendarIds_returnsSuccessEmptyListWithoutQuerying() = runTest {
        // 誤ってqueryが呼ばれた場合に備え、非空の結果を返すfakeにしておく
        // （calls.isEmpty()の失敗で確実に検知できるようにするため）。
        val cursorSource = FakeCursorSource {
            instancesCursor(listOf(instanceRow(eventId = 1L, begin = FROM.plusSeconds(60).toEpochMilli())))
        }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readUpcomingEvents(FROM, UNTIL, calendarIds = emptySet())

        assertEquals(emptyList<ExecutionEvent>(), assertSuccessValue(result))
        assertTrue("expected cursorSource.query to never be invoked", cursorSource.calls.isEmpty())
    }

    // T-CALSVC-12: 2件以上のカレンダーを横断してbegin ASC（同時刻event_id ASC）で
    // マージ・整列され、各イベントのsourceCalendarが元のカレンダーと正しく対応する
    @Test
    fun readUpcomingEvents_acrossMultipleCalendars_mergesInOrderWithCorrectSourceCalendar() = runTest {
        val t0 = FROM.plusSeconds(60).toEpochMilli()
        val t1 = FROM.plusSeconds(3_600).toEpochMilli()
        // プロバイダ側で既にbegin ASC・同時刻event_id ASCへ整列済み（§8.2 M-2）という前提で
        // 行を投入する: Workの event 10(t0) → Personalの event 5(t1) → Workの event 20
        // （t1、event_id ASCタイブレークでevent 5の次）。
        val rows = listOf(
            instanceRow(eventId = 10L, begin = t0, calendarId = 1L, calendarDisplayName = "Work"),
            instanceRow(eventId = 5L, begin = t1, calendarId = 2L, calendarDisplayName = "Personal"),
            instanceRow(eventId = 20L, begin = t1, calendarId = 1L, calendarDisplayName = "Work")
        )
        val cursorSource = FakeCursorSource { instancesCursor(rows) }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readUpcomingEvents(FROM, UNTIL)

        val value = assertSuccessValue(result)
        assertEquals(3, value.size)
        assertEquals(listOf(t0, t1, t1), value.map { it.startDate.toEpochMilli() })
        assertEquals(
            listOf(
                CalendarSource(id = "1", displayName = "Work"),
                CalendarSource(id = "2", displayName = "Personal"),
                CalendarSource(id = "1", displayName = "Work")
            ),
            value.map { it.sourceCalendar }
        )
        assertEquals(listOf("10@$t0", "5@$t1", "20@$t1"), value.map { it.externalCalendarId })
    }

    // T-CALSVC-13: Calendars.VISIBLE = 0のカレンダーに属する予定が結果に混入しない
    // （InstancesはVISIBLE=0を自動除外しないため、selectionでの除外が必須。M-3実測）
    @Test
    fun readUpcomingEvents_excludesEventsFromHiddenCalendarsViaSelection() = runTest {
        val visibleEvent = instanceRow(
            eventId = 1L,
            begin = FROM.plusSeconds(60).toEpochMilli(),
            calendarId = 1L,
            calendarDisplayName = "Work"
        )
        val hiddenCalendarEvent = instanceRow(
            eventId = 2L,
            begin = FROM.plusSeconds(120).toEpochMilli(),
            calendarId = 99L,
            calendarDisplayName = "Hidden"
        )
        // VISIBLEはInstances行自体には現れない列（Calendarsテーブル側の列）であるため、
        // fake側でselection文字列を検査し、CalendarQuerySpec.SELECTIONが要求する
        // VISIBLE=1述語が実際にcursorSource.queryへ渡されたときのみhiddenCalendarEventを
        // 除外することで、本物のプロバイダのselection評価を模擬する（M-3実測の代替検証）。
        val visibilityPredicate = "${CalendarContract.Calendars.VISIBLE} = 1"
        val cursorSource = FakeCursorSource { call ->
            val respectsVisibility = call.selection.orEmpty().contains(visibilityPredicate)
            val rows = if (respectsVisibility) listOf(visibleEvent) else listOf(visibleEvent, hiddenCalendarEvent)
            instancesCursor(rows)
        }
        val service = CalendarProviderCalendarService(cursorSource, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = service.readUpcomingEvents(FROM, UNTIL)

        val value = assertSuccessValue(result)
        assertEquals(1, value.size)
        assertEquals("1@${FROM.plusSeconds(60).toEpochMilli()}", value.first().externalCalendarId)
    }

    private companion object {
        private val FROM: Instant = Instant.parse("2026-08-09T00:00:00Z")
        private val UNTIL: Instant = FROM.plus(CalendarQuerySpec.UPCOMING_WINDOW)
    }
}
