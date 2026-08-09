package com.actionstarter.services.calendar

import android.net.Uri
import android.provider.CalendarContract
import java.time.Duration

/**
 * `CalendarContract.Instances`クエリの宣言的定数（計画書§6.1・§8.2・§9）。F13「Upcoming
 * Events」（仕様§66）が使う投影列・selection・sortOrder・時刻窓・既定件数を1箇所に集約する。
 *
 * 本オブジェクトは値の宣言のみで構成し、クエリの実行（[android.content.ContentResolver.query]
 * 呼び出し）やCursor走査は行わない（それは[CalendarProviderCalendarService]の責務。
 * P2-C4で`TODO()`を解消して実装する）。したがって本ファイルに`TODO()`は置かない
 * （宣言的定数のため。オーケストレーターP2-C2指示）。
 *
 * 列名・ステータス定数は[android.provider.CalendarContract]の型付き定数から組み立てる
 * （生の文字列リテラルを避け、プラットフォームAPIとの追従性を保つ）。列定数の参照元は
 * `CalendarContract.Instances`／`Events`／`Calendars`／`Attendees`（いずれも`public`
 * class）に統一し、`EventsColumns`／`CalendarColumns`／`SyncColumns`／`AttendeesColumns`
 * （これらのメンバーを実装先へ提供する側のinterface）を直接参照しない。後者はKotlinから
 * `protected`扱いとなりコンパイル不能なことを実測確認済み（compileDebugKotlin実測、
 * `it is protected in 'android.provider.CalendarContract'`）。`Instances`は`BaseColumns`/
 * `EventsColumns`/`CalendarColumns`を実装するため大半の列はここから参照できるが、`deleted`
 * （`SyncColumns`）のみ`Instances`が実装しないため[CalendarContract.Events]経由で参照する
 * （値は同一の"deleted"文字列。`deleted`列自体はM-1実測により`Instances`クエリの
 * projectionとして取得可能であることを確認済み。計画書§8.2）。
 */
object CalendarQuerySpec {

    /**
     * M-1実測済み（計画書§8.2）：`Instances`から取得可能な14列。
     * event_id / begin / end / title / eventLocation / calendar_id / allDay / eventStatus /
     * deleted / selfAttendeeStatus / description / calendar_displayName / eventTimezone /
     * availability の順。
     */
    val PROJECTION: Array<String> = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.CALENDAR_ID,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.STATUS,
        CalendarContract.Events.DELETED,
        CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        CalendarContract.Instances.DESCRIPTION,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        CalendarContract.Instances.EVENT_TIMEZONE,
        CalendarContract.Instances.AVAILABILITY
    )

    /**
     * 裁定B15・M-3実測（計画書§8.2）：`Instances`は`VISIBLE=0`（非表示）カレンダーを自動除外
     * しないため、可視カレンダー限定（F14）の実装として`VISIBLE=1`のselection付与を必須とする
     * （エラーマップ#22、T-CALSVC-13）。残る3条件（deleted/allDay/eventStatus/
     * selfAttendeeStatus）は計画書§9 除外2〜4のSQL側push-downであり、アプリ側
     * （[CalendarInstanceMapper]）での判定と重複しても構わない防御的定義とする
     * （§8.2「フィルタ規則をクエリ側へ押し下げる余地はある」）。
     */
    val SELECTION: String = listOf(
        "${CalendarContract.Calendars.VISIBLE} = 1",
        "${CalendarContract.Events.DELETED} = 0",
        "${CalendarContract.Instances.ALL_DAY} = 0",
        "(${CalendarContract.Instances.STATUS} IS NULL OR " +
            "${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED})",
        "(${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR " +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != " +
            "${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})"
    ).joinToString(separator = " AND ")

    /** 計画書§9 並び順規則：`begin ASC`、同時刻は`event_id ASC`でtie-break（表示順の決定性）。 */
    val SORT_ORDER: String =
        "${CalendarContract.Instances.BEGIN} ASC, ${CalendarContract.Instances.EVENT_ID} ASC"

    /**
     * 計画書§9 時刻窓：`from = now`、`until = now + 7日`。「now」自体は
     * [CalendarService.readUpcomingEvents]の呼び出し側が注入するため定数化しない
     * （仕様§15・計画書§7.2設計根拠：サービス内部で`Instant.now()`を読まず決定的処理を保つ）。
     * ここでは窓の「長さ」のみを定数化する。
     */
    val UPCOMING_WINDOW: Duration = Duration.ofDays(7)

    /**
     * 計画書§7.2既定件数（[CalendarService.readUpcomingEvents]の`limit`既定値）。
     * エラーマップ#8：数千件のカーソル全走査によるANR/OOMを構造的に防ぐ。
     */
    const val DEFAULT_EVENT_LIMIT: Int = 20

    /**
     * 計画書§8.2裁定B15点2・§12実測済み形式：`Instances.CONTENT_URI`へ`begin`/`end`
     * （エポックミリ秒）をURIパスとしてappendして構築する
     * （`content://com.android.calendar/instances/when/<begin>/<end>`）。
     * URIの組み立てのみを行う純粋関数であり、クエリ実行は行わない
     * （[CalendarProviderCalendarService]がP2-C4で`cursorSource.query(...)`に渡す）。
     */
    fun instancesUri(beginEpochMilli: Long, endEpochMilli: Long): Uri =
        CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(beginEpochMilli.toString())
            .appendPath(endEpochMilli.toString())
            .build()
}
