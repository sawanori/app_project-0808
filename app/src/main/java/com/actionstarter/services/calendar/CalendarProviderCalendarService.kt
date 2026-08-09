package com.actionstarter.services.calendar

import android.provider.CalendarContract
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * L2：[CalendarService]のサービスロジック実装（計画書§8.3改訂・3層分割）。[cursorSource]を
 * コンストラクタ注入で受け取り、`ContentResolver`にもRobolectricの`ContentProvider`登録機構
 * にも直接依存しない（fakeの[CursorSource]へ差し替えてJVM/Robolectric上で決定的にテスト
 * する。T-CALSVC-1〜13）。列→[ExecutionEvent]の写像は[CalendarInstanceMapper]（L1）に委譲する。
 *
 * [ioDispatcher]はブロッキングIOである`ContentResolver.query`をメインスレッドから退避させる
 * ためコンストラクタ注入し、テストでは`kotlinx-coroutines-test`のテストディスパッチャへ
 * 差し替える（計画書§7.2設計根拠、T-CALSVC-9・T-CALSVC-10）。
 *
 * DI結線（`AppContainer`からの生成）は本サイクル（P2-C4）でも行わない（P2-C5で結線する）。
 *
 * P2-C4で実装済み（T-CALSVC-1〜13）：`SecurityException`→[CalendarResult.PermissionDenied]
 * （T-CALSVC-3）、`query`が`null`→`Failure(PROVIDER_UNAVAILABLE)`（T-CALSVC-4）、列アクセス例外→
 * `Failure(QUERY_FAILED, cause)`（T-CALSVC-5）、`calendarIds == emptySet()`→クエリ発行せず
 * `Success(emptyList())`即返却（裁定B13、T-CALSVC-11）、Cursorは`use { }`で必ずclose
 * （T-CALSVC-8）、coroutineキャンセル時は`ensureActive()`で中断（T-CALSVC-10）。
 */
class CalendarProviderCalendarService(
    private val cursorSource: CursorSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CalendarService {

    private val mapper = CalendarInstanceMapper()

    override suspend fun readCalendars(): CalendarResult<List<CalendarSource>> = withContext(ioDispatcher) {
        try {
            val cursor = cursorSource.query(
                CalendarContract.Calendars.CONTENT_URI,
                CALENDARS_PROJECTION,
                CALENDARS_SELECTION,
                null,
                null
            ) ?: return@withContext CalendarResult.Failure(CalendarFailureReason.PROVIDER_UNAVAILABLE, null)

            cursor.use { c ->
                val idIndex = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val displayNameIndex = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val calendars = mutableListOf<CalendarSource>()
                while (c.moveToNext()) {
                    ensureActive()
                    val id = c.getLong(idIndex)
                    val displayName = c.getString(displayNameIndex).orEmpty()
                    calendars += CalendarSource(id = id.toString(), displayName = displayName)
                }
                CalendarResult.Success(calendars)
            }
        } catch (e: SecurityException) {
            CalendarResult.PermissionDenied
        } catch (e: IllegalArgumentException) {
            CalendarResult.Failure(CalendarFailureReason.QUERY_FAILED, e)
        }
    }

    override suspend fun readUpcomingEvents(
        from: Instant,
        until: Instant,
        calendarIds: Set<String>?,
        limit: Int
    ): CalendarResult<List<ExecutionEvent>> = withContext(ioDispatcher) {
        // 裁定B13：calendarIdsが空集合の場合はクエリを発行せずSuccess(emptyList())を即返す
        // （IN句の構文エラー防止。T-CALSVC-11）。nullは「全可視カレンダー」を意味し通常どおり
        // クエリする。個別カレンダーID集合による絞り込みは本サイクルの契約・テストの対象外
        // （§7.2は空集合の防御のみを定義しており、非空集合の絞り込み方式は未規定）。
        if (calendarIds != null && calendarIds.isEmpty()) {
            return@withContext CalendarResult.Success(emptyList())
        }

        try {
            val uri = CalendarQuerySpec.instancesUri(from.toEpochMilli(), until.toEpochMilli())
            val cursor = cursorSource.query(
                uri,
                CalendarQuerySpec.PROJECTION,
                CalendarQuerySpec.SELECTION,
                null,
                CalendarQuerySpec.SORT_ORDER
            ) ?: return@withContext CalendarResult.Failure(CalendarFailureReason.PROVIDER_UNAVAILABLE, null)

            cursor.use { c ->
                val events = mutableListOf<ExecutionEvent>()
                var skippedRowCount = 0
                while (events.size < limit && c.moveToNext()) {
                    ensureActive()
                    if (mapper.isExcluded(c, from)) {
                        // フィルタ除外は正常動作でありskippedRowCountに計上しない（裁定B8）。
                        continue
                    }
                    val event = mapper.map(c)
                    if (event == null) {
                        // begin欠損等の行データ不正。全体はSuccessのままskippedRowCountへ計上する。
                        skippedRowCount++
                    } else {
                        events += event
                    }
                }
                CalendarResult.Success(events, skippedRowCount)
            }
        } catch (e: SecurityException) {
            CalendarResult.PermissionDenied
        } catch (e: IllegalArgumentException) {
            CalendarResult.Failure(CalendarFailureReason.QUERY_FAILED, e)
        }
    }

    private companion object {
        /**
         * F14「Calendar List」用projection。[CalendarQuerySpec]は`Instances`クエリ専用の宣言
         * であり`Calendars`テーブルの列集合を持たないため（同ファイルKDoc参照）、本クラス内に
         * 閉じたprivate定数として保持する。
         */
        val CALENDARS_PROJECTION: Array<String> = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.VISIBLE
        )

        /** 裁定B15・M-3実測：可視カレンダー限定（F14）にはVISIBLE=1のselectionが必須。 */
        val CALENDARS_SELECTION: String = "${CalendarContract.Calendars.VISIBLE} = 1"
    }
}
