package com.actionstarter.services.calendar

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import java.time.Instant

/**
 * 仕様§43準拠（Services/CalendarService）。ただしinterfaceシグネチャは仕様書に定義がない
 * （§44 PlanningEngine・§45 RecoveryEngine・§46 RoutingServiceと異なりコードブロックが
 * 与えられていない）ため、計画書§7.2の契約案をそのまま採用する（ADR記録トリガー②
 * 「仕様未定義箇所の補完」に該当。ADR自体はDECISIONS.mdへ別途記録する）。
 *
 * 仕様§66「Upcoming Events」「Calendar List」「Location付きイベント抽出」、§95.4
 * （READ_CALENDAR）を実装する契約。[readUpcomingEvents]の[from]/[until]を呼び出し側から
 * 注入する理由は、サービス内部で`Instant.now()`を読まないことで決定的処理を維持するため
 * （仕様§15、計画書§7.2設計根拠）。
 *
 * 契約scaffold（P2-C2、TDD例外・裁定B3の系）時点では宣言のみであり、実装は行わない
 * （P2-C4で[CalendarProviderCalendarService]として実装する）。
 */
interface CalendarService {

    /**
     * F14（Calendar List、仕様§66）：可視カレンダーの列挙。用途は表示名解決用に限定し、
     * フィルタUIはPhase 2に含めない（裁定B19）。
     */
    suspend fun readCalendars(): CalendarResult<List<CalendarSource>>

    /**
     * F13/F15（Upcoming Events／Location付きイベント抽出、仕様§66）。
     *
     * @param from 検索窓の開始（呼び出し側が`Instant.now()`等を注入する。仕様§15）
     * @param until 検索窓の終了（計画書§9 時刻窓：既定は[CalendarQuerySpec.UPCOMING_WINDOW]）
     * @param calendarIds 対象カレンダーID集合。`null`は全可視カレンダーを意味する。
     *   空集合（`emptySet()`）はクエリを発行せず`Success(emptyList())`を即返す
     *   （裁定B13、IN句構文エラー防止。T-CALSVC-11）
     * @param limit 最大取得件数（既定[CalendarQuerySpec.DEFAULT_EVENT_LIMIT]＝20。
     *   エラーマップ#8のANR/OOM防止）
     */
    suspend fun readUpcomingEvents(
        from: Instant,
        until: Instant,
        calendarIds: Set<String>? = null,
        limit: Int = CalendarQuerySpec.DEFAULT_EVENT_LIMIT
    ): CalendarResult<List<ExecutionEvent>>
}

/**
 * [CalendarService]の戻り値型（裁定B8で確定。計画書§7.2）。`SecurityException`や
 * `ContentResolver.query`のnull戻りをcatchして`emptyList()`へ潰す実装を型レベルで
 * 不可能にする（サイレント障害対策。`emptyList()`縮退は「予定が0件」と「読めなかった」を
 * 区別できないため）。
 */
sealed interface CalendarResult<out T> {

    /**
     * @param value 取得できた値
     * @param skippedRowCount 行単位の不正（`begin`欠損等）でスキップした件数（裁定B8）。
     *   計画書§9 除外1〜4によるフィルタ除外は正常動作でありここには計上しない。既定0。
     */
    data class Success<T>(val value: T, val skippedRowCount: Int = 0) : CalendarResult<T>

    /**
     * READ_CALENDARが拒否されている、または読取実行中にOS設定で剥奪された
     * （仕様§95.6第1行「またはOS設定で後から無効化される」）。
     */
    data object PermissionDenied : CalendarResult<Nothing>

    /**
     * クエリ構造自体が成立しない全体失敗（裁定B8）。行単位の不正は
     * [CalendarResult.Success.skippedRowCount]で表現し、本型には含めない。
     */
    data class Failure(val reason: CalendarFailureReason, val cause: Throwable?) : CalendarResult<Nothing>
}

/**
 * 裁定B8により`ROW_MALFORMED`を削除し2値へ確定（計画書§7.2）。行単位の不正は
 * [CalendarResult.Success.skippedRowCount]で表現するため、本enumはクエリ全体が
 * 失敗した場合の理由のみを表す。
 */
enum class CalendarFailureReason { PROVIDER_UNAVAILABLE, QUERY_FAILED }
