package com.actionstarter.services.calendar

import android.database.Cursor
import android.provider.CalendarContract
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import java.time.Instant
import java.util.UUID

/**
 * L1：`Instances`カーソルの1行 → [ExecutionEvent]の純粋写像（計画書§8.3改訂）。
 * `ContentResolver`にも[CursorSource]にも依存しない決定的処理のみで構成し（仕様§15、
 * LLM不使用）、`MatrixCursor`を手組みしたRobolectricテストで写像規則を網羅検証する
 * （T-CALMAP-1〜19）。
 *
 * [isExcluded]と[map]の2関数に分離した理由：計画書§9の写像・フィルタ規則の表自体が
 * 「除外1〜4」（フィルタ規則）と「id/title/startDate等」（フィールド写像規則）を別項目として
 * 記述しており、両者は独立にテスト可能な関心事であるため。呼び出し側
 * （[CalendarProviderCalendarService]、P2-C4で実装）は各カーソル行に対しまず[isExcluded]を
 * 評価し、`true`ならその行を[CalendarResult.Success.skippedRowCount]へ計上せずに読み飛ばす
 * （裁定B8：計画書§9のフィルタ除外は正常動作でありスキップ件数に含めない）。`false`の場合の
 * み[map]を呼び、結果が`null`であれば行データ不正（`begin`欠損等）とみなし
 * `skippedRowCount`へ計上する（T-CALMAP-7、裁定B8）。
 *
 * P2-C4で実装済み（T-CALMAP-1〜19）。
 */
class CalendarInstanceMapper {

    /**
     * 計画書§9 除外1〜4の判定：
     * - 除外1: `begin <= from`（未来のみ。境界の`begin == from`は除外、T-CALMAP-13）
     * - 除外2: `deleted != 0` または `eventStatus == STATUS_CANCELED`（`NULL`は除外しない。
     *   M-6実測・T-CALMAP-17）
     * - 除外3: `allDay == 1`（終日予定。裁定B5。`begin`のUTC深夜値ではなく`allDay`フラグ
     *   そのもので判定する。M-5実測・T-CALMAP-19）
     * - 除外4: `selfAttendeeStatus == ATTENDEE_STATUS_DECLINED`（自分が辞退済み）
     *
     * `true`を返す行は候補から除外するが、データ不正ではなく正常動作であるため呼び出し元は
     * `skippedRowCount`に計上しない（裁定B8）。`availability == AVAILABILITY_FREE`は
     * 意図的に非除外（T-CALMAP-12）。
     */
    fun isExcluded(cursor: Cursor, from: Instant): Boolean {
        // 除外1: begin <= from（未来のみ）。beginがNULLの行はここでは除外と判定しない
        // （map()側がnullを返し、呼び出し元がskippedRowCountへ計上する。裁定B8）。
        val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        if (!cursor.isNull(beginIndex) && cursor.getLong(beginIndex) <= from.toEpochMilli()) {
            return true
        }

        // 除外2: deleted != 0 または eventStatus == STATUS_CANCELED（NULLは除外しない）
        val deletedIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DELETED)
        if (cursor.getInt(deletedIndex) != 0) {
            return true
        }
        val statusIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.STATUS)
        if (!cursor.isNull(statusIndex) &&
            cursor.getInt(statusIndex) == CalendarContract.Instances.STATUS_CANCELED
        ) {
            return true
        }

        // 除外3: allDay == 1（終日予定。beginの時刻値ではなくフラグそのもので判定。M-5）
        val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
        if (cursor.getInt(allDayIndex) == 1) {
            return true
        }

        // 除外4: selfAttendeeStatus == ATTENDEE_STATUS_DECLINED（NULLは除外しない）
        val selfAttendeeStatusIndex =
            cursor.getColumnIndexOrThrow(CalendarContract.Instances.SELF_ATTENDEE_STATUS)
        if (!cursor.isNull(selfAttendeeStatusIndex) &&
            cursor.getInt(selfAttendeeStatusIndex) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        ) {
            return true
        }

        // availability == AVAILABILITY_FREE は意図的に非除外（読み取りも行わない）。
        return false
    }

    /**
     * 計画書§9 フィールド写像規則（id/externalCalendarId/title/startDate/locationName/
     * coordinates/notes/sourceCalendar）に基づき、[isExcluded]が`false`の行を
     * [ExecutionEvent]へ写像する。`begin`欠損等で写像不能な場合は例外を投げず`null`を返す
     * （呼び出し元が`skippedRowCount`へ計上する。T-CALMAP-7、裁定B8）。`title`が
     * null/空白の行は破棄せずそのまま写像する（UI側で代替文言、T-CALMAP-5）。
     *
     * projectionに存在しない列への`getColumnIndexOrThrow`アクセス等、行単位ではなく
     * クエリ構造自体の問題による例外は本関数内で捕捉せずそのまま伝播させる（呼び出し元で
     * `CalendarResult.Failure(CalendarFailureReason.QUERY_FAILED)`へ写像。T-CALMAP-8、裁定B8）。
     */
    fun map(cursor: Cursor): ExecutionEvent? {
        // begin欠損（NULL）は行データ不正とみなしnullを返す（例外を投げない。T-CALMAP-7）。
        // projectionにbegin列自体が無い場合はgetColumnIndexOrThrowがIllegalArgumentExceptionを
        // 投げ、そのまま伝播する（クエリ構造の問題。T-CALMAP-8、裁定B8）。
        val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        if (cursor.isNull(beginIndex)) {
            return null
        }
        val begin = cursor.getLong(beginIndex)

        val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
        val eventId = cursor.getLong(eventIdIndex)
        val externalCalendarId = "$eventId@$begin"

        // titleはnull/空白でも行を捨てない。ExecutionEvent.titleは非null Stringのため
        // null→""へ正規化する（UI側でstringResource(R.string.event_untitled)を表示）。
        val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val title = cursor.getString(titleIndex).orEmpty()

        val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        val locationName = cursor.getString(locationIndex)?.trim()?.takeIf { it.isNotEmpty() }

        // notesはあらゆるログレベルで出力禁止（§58／§60）。ここでは値の受け渡しのみ行う。
        val descriptionIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
        val notes = cursor.getString(descriptionIndex)

        val calendarIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
        val calendarId = cursor.getLong(calendarIdIndex)
        val calendarDisplayNameIndex =
            cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val calendarDisplayName = cursor.getString(calendarDisplayNameIndex).orEmpty()

        return ExecutionEvent(
            id = UUID.nameUUIDFromBytes(externalCalendarId.toByteArray()),
            externalCalendarId = externalCalendarId,
            title = title,
            notes = notes,
            startDate = Instant.ofEpochMilli(begin),
            locationName = locationName,
            coordinates = null,
            sourceCalendar = CalendarSource(id = calendarId.toString(), displayName = calendarDisplayName)
        )
    }
}
