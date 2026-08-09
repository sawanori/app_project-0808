@file:OptIn(ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.features.eventselection.EventSelectionUiState
import com.actionstarter.features.eventselection.EventSelectionViewModel
import com.actionstarter.services.calendar.CalendarQuerySpec
import com.actionstarter.services.calendar.CalendarResult
import com.actionstarter.services.calendar.CalendarService
import com.actionstarter.services.permission.PermissionGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * F16/F17/F18 — 権限連携とEvent Selection ViewModel（計画書§7.2／§7.3／§7.4／§10.2、
 * T-PERM-3／5／6／7）。
 *
 * [EventSelectionPermissionTest]・[EventSelectionListTest]（P2-C3後半）のクラスKDocが
 * 明記するとおり、T-PERM-3／5／6／7は実際の[CalendarService]呼出・ON_RESUME相当の
 * 再チェックを伴うためP2-C4冒頭（本ファイル）へ移動された。本ファイルは[EventSelectionScreen]
 * を経由せず[EventSelectionViewModel]を直接インスタンス化し、[CalendarService]／
 * [PermissionGate]をFake実装へ差し替えて検証する（Compose Test Ruleは使用しない）。
 *
 * ## Fakeの設計
 * - [FakeCalendarService]: [readUpcomingEventsResult]（`var`）で`readUpcomingEvents`の
 *   戻り値をテストごとに差し替えられる。全呼出しの引数（`from`/`until`/`calendarIds`/
 *   `limit`）を[upcomingEventsCalls]へ記録し、呼出回数・引数の検証に使う（T-PERM-3／7）。
 *   [suspendGate]（既定`null`）を設定すると、`readUpcomingEvents`は引数を記録した直後に
 *   そのDeferredの完了までsuspendする。これによりテスト側が「読込がまだ完了していない
 *   状態」を`advanceUntilIdle()`で決定的に作り出せる（T-PERM-7の「回転相当の再チェックが
 *   前回の読込未完了中に発生する」状況の再現に必須）。
 * - [FakePermissionGate]: `granted`（`var`）で許可状態を可変にする。[EventSelectionViewModel]
 *   はコンストラクタ注入のみを受け付けるため、同一インスタンスの`granted`をテスト内で
 *   書き換えることで「拒否→許可への変化」（T-PERM-5）を表現する。
 *
 * ## コルーチン制御
 * [EventSelectionViewModel.refresh]は`viewModelScope.launch`（`Dispatchers.Main.immediate`）を
 * 使うため、`kotlinx-coroutines-test`の`StandardTestDispatcher`を`Dispatchers.setMain`で
 * インストールし、`runTest`にも同一ディスパッチャを渡して仮想時刻を同期させる
 * （`testDispatcher.scheduler.advanceUntilIdle()`で`init`のrefresh()を含む起動済み
 * コルーチンを進行させる）。
 *
 * ## T-PERM-7の解釈（計画書§10.2「権限状態のチェックがON_RESUMEごとに行われ、画面回転では
 * 二重読込しない」）
 * [EventSelectionViewModel]はON_RESUME専用の公開APIを持たず、ON_RESUME相当の再チェックは
 * 呼び出し側（Screen／NavHost、P2-C4本番配線）が[EventSelectionViewModel.refresh]を
 * 再度呼ぶ形で行われる想定である（クラスKDoc「将来のON_RESUME再チェック（T-PERM-5/7、
 * P2-C4で結線）…から呼ばれる想定」）。「二重読込」を最も厳密に読むと、前回の`refresh()`が
 * まだ完了していない間に2回目の`refresh()`が発生した場合（回転時の短時間での複数
 * ON_RESUME発火を含む）でも、[CalendarService.readUpcomingEvents]の実呼出しは1回に
 * 収束すべきという要求である。本テストは[FakeCalendarService.suspendGate]で
 * 「1回目が完了する前に2回目のrefresh()を呼ぶ」状況を決定的に作り、呼出回数が1のままで
 * あることを検証する。現状の[EventSelectionViewModel.refresh]は呼ばれるたびに無条件で
 * `viewModelScope.launch`するため、本テストは呼出回数2で失敗するのが正しい（Red）。
 */
@RunWith(AndroidJUnit4::class)
class EventSelectionViewModelPermissionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** [CalendarService]のFake実装。呼出しごとの引数記録と結果の差し替え、任意のsuspend制御を提供する。 */
    private class FakeCalendarService : CalendarService {

        data class UpcomingEventsCall(
            val from: Instant,
            val until: Instant,
            val calendarIds: Set<String>?,
            val limit: Int
        )

        var upcomingEventsResult: CalendarResult<List<ExecutionEvent>> = CalendarResult.Success(emptyList())
        var calendarsResult: CalendarResult<List<CalendarSource>> = CalendarResult.Success(emptyList())

        /** 非nullのとき、呼出しを記録した直後にこのDeferredの完了までsuspendする（T-PERM-7）。 */
        var suspendGate: CompletableDeferred<Unit>? = null

        private val _upcomingEventsCalls = mutableListOf<UpcomingEventsCall>()
        val upcomingEventsCalls: List<UpcomingEventsCall> get() = _upcomingEventsCalls

        override suspend fun readCalendars(): CalendarResult<List<CalendarSource>> = calendarsResult

        override suspend fun readUpcomingEvents(
            from: Instant,
            until: Instant,
            calendarIds: Set<String>?,
            limit: Int
        ): CalendarResult<List<ExecutionEvent>> {
            _upcomingEventsCalls += UpcomingEventsCall(from, until, calendarIds, limit)
            suspendGate?.await()
            return upcomingEventsResult
        }
    }

    /** [PermissionGate]のFake実装。[granted]を書き換えることで許可状態の変化を表現する（T-PERM-5）。 */
    private class FakePermissionGate(granted: Boolean) : PermissionGate {
        var granted: Boolean = granted
        override fun isGranted(permission: String): Boolean = granted
    }

    private fun sampleEvent(title: String = "Studio call"): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = "1@1000",
        title = title,
        notes = null,
        startDate = Instant.parse("2026-08-10T01:00:00Z"),
        locationName = null,
        coordinates = null,
        sourceCalendar = CalendarSource(id = "1", displayName = "Work")
    )

    // T-PERM-3: 正常系 - 権限許可済みで表示 → CalendarServiceが呼ばれContentが表示される。
    // 呼出引数はfrom≒now（テスト実行時刻との誤差5秒以内）／until=from+7日（UPCOMING_WINDOW）
    // であることを検証する。
    @Test
    fun tPerm3_permissionGranted_refreshQueriesNowToPlusSevenDaysAndShowsContent() = runTest(testDispatcher) {
        val event = sampleEvent()
        val calendarService = FakeCalendarService().apply {
            upcomingEventsResult = CalendarResult.Success(listOf(event))
        }
        val permissionGate = FakePermissionGate(granted = true)
        val before = Instant.now()

        val viewModel = EventSelectionViewModel(calendarService, permissionGate, SavedStateHandle())
        testDispatcher.scheduler.advanceUntilIdle()
        val after = Instant.now()

        val state = viewModel.uiState.value
        assertTrue("expected Content but was $state", state is EventSelectionUiState.Content)
        assertEquals(listOf(event), (state as EventSelectionUiState.Content).events)

        assertEquals(1, calendarService.upcomingEventsCalls.size)
        val call = calendarService.upcomingEventsCalls.single()
        assertFalse(
            "from (${call.from}) should not be earlier than the test window start",
            call.from.isBefore(before.minusSeconds(5))
        )
        assertFalse(
            "from (${call.from}) should not be later than the test window end",
            call.from.isAfter(after.plusSeconds(5))
        )
        assertEquals(call.from.plus(CalendarQuerySpec.UPCOMING_WINDOW), call.until)
    }

    // T-PERM-5: エッジケース - 拒否（PermissionRequired）→許可へ変化→onRetry（ON_RESUME相当）
    // →自動でContentへ復帰する（§95.4「Settingsから再許可すると自動連携に復帰する」）。
    @Test
    fun tPerm5_deniedThenGrantedOnRetry_autoRecoversToContent() = runTest(testDispatcher) {
        val event = sampleEvent()
        val calendarService = FakeCalendarService().apply {
            upcomingEventsResult = CalendarResult.Success(listOf(event))
        }
        val permissionGate = FakePermissionGate(granted = false)

        val viewModel = EventSelectionViewModel(calendarService, permissionGate, SavedStateHandle())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(EventSelectionUiState.PermissionRequired, viewModel.uiState.value)
        assertTrue(
            "expected no CalendarService call while permission is not granted",
            calendarService.upcomingEventsCalls.isEmpty()
        )

        permissionGate.granted = true
        viewModel.onRetry() // ON_RESUME相当（EventSelectionViewModelはON_RESUME専用APIを
        // 持たないため、将来ON_RESUMEから呼ばれる想定の既存公開メソッドで代替する）
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("expected Content but was $state", state is EventSelectionUiState.Content)
        assertEquals(listOf(event), (state as EventSelectionUiState.Content).events)
        assertEquals(1, calendarService.upcomingEventsCalls.size)
    }

    // T-PERM-6: 異常系 - 権限ゲートはtrueを返す（一見許可済み）が、読取実行中に
    // CalendarServiceがPermissionDenied（実行中の剥奪。§95.6第1行「またはOS設定で後から
    // 無効化される」相当）を返す → 空表示（Empty）ではなくPermissionDenied状態へ遷移する。
    @Test
    fun tPerm6_serviceReturnsPermissionDeniedDuringRead_transitionsToPermissionDeniedNotEmpty() =
        runTest(testDispatcher) {
            val calendarService = FakeCalendarService().apply {
                upcomingEventsResult = CalendarResult.PermissionDenied
            }
            val permissionGate = FakePermissionGate(granted = true)

            val viewModel = EventSelectionViewModel(calendarService, permissionGate, SavedStateHandle())
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(EventSelectionUiState.PermissionDenied, state)
            assertNotEquals(
                "mid-flight permission revocation must not be shown as an empty list",
                EventSelectionUiState.Empty,
                state
            )
        }

    // T-PERM-7: エッジケース - 権限状態のチェックはON_RESUME相当のrefresh()呼出のたびに
    // 行われるが、前回の読込がまだ完了していない間に発生した再チェック（画面回転時の
    // 短時間での複数ON_RESUME発火を想定）は、CalendarServiceへの2重の実呼出しを
    // 発生させてはならない（計画書§10.2「画面回転では二重読込しない」）。
    @Test
    fun tPerm7_refreshCalledWhileLoadInFlight_doesNotIssueSecondCalendarServiceCall() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val event = sampleEvent()
        val calendarService = FakeCalendarService().apply {
            upcomingEventsResult = CalendarResult.Success(listOf(event))
            suspendGate = gate
        }
        val permissionGate = FakePermissionGate(granted = true)

        val viewModel = EventSelectionViewModel(calendarService, permissionGate, SavedStateHandle())
        // initのrefresh()がgate.await()で止まっている状態まで進める。
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, calendarService.upcomingEventsCalls.size)

        // 画面回転相当の再チェック（ON_RESUME相当）：前回の読込がまだ完了していない間に発生させる。
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        // 前回（および、ガードがない現状の実装では2回目も）の読込を完了させる。
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "in-flight refresh() must not cause a duplicate CalendarService call (no double load on rotation)",
            1,
            calendarService.upcomingEventsCalls.size
        )
        assertTrue(viewModel.uiState.value is EventSelectionUiState.Content)
    }
}
