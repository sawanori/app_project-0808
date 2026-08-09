package com.actionstarter.services.calendar

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * DI結線（`AppContainer`からの生成）は本サイクル（P2-C2）では行わない（P2-C5で結線する）。
 * 本体は本サイクル（P2-C2、契約scaffold・TDD例外）では`TODO()`とし、P2-C4で実装する。
 * 実装時は次を満たすこと：`SecurityException`→[CalendarResult.PermissionDenied]
 * （T-CALSVC-3）、`query`が`null`→`Failure(PROVIDER_UNAVAILABLE)`（T-CALSVC-4）、列アクセス例外→
 * `Failure(QUERY_FAILED, cause)`（T-CALSVC-5）、`calendarIds == emptySet()`→クエリ発行せず
 * `Success(emptyList())`即返却（裁定B13、T-CALSVC-11）、Cursorは`use { }`で必ずclose
 * （T-CALSVC-8）、coroutineキャンセル時は`ensureActive()`で中断（T-CALSVC-10）。
 */
class CalendarProviderCalendarService(
    private val cursorSource: CursorSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CalendarService {

    override suspend fun readCalendars(): CalendarResult<List<CalendarSource>> {
        TODO("P2-C4で実装")
    }

    override suspend fun readUpcomingEvents(
        from: Instant,
        until: Instant,
        calendarIds: Set<String>?,
        limit: Int
    ): CalendarResult<List<ExecutionEvent>> {
        TODO("P2-C4で実装")
    }
}
