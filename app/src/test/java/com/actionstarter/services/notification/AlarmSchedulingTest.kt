package com.actionstarter.services.notification

import android.app.AlarmManager
import android.content.Context
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.persistence.ExecutionScheduleLoadResult
import com.actionstarter.persistence.ExecutionScheduleRecord
import com.actionstarter.persistence.ExecutionScheduleStore
import com.actionstarter.persistence.SharedPreferencesExecutionScheduleStore
import com.actionstarter.persistence.Trigger
import com.actionstarter.services.permission.PermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-ALARM-1〜10（計画書§8.2「F50/F51 — AlarmManagerAlarmScheduler」全10件、
 * `docs/plans/phase5-notification-execution.md`§6.1のファイル配置表どおり本ファイルへ集約）。
 *
 * **配置の注記（P5-C2、test-writer判断）**: T-ALARM-6/7/8/10は計画書の文面上L3
 * （[AlarmScheduler]）の挙動として書かれているが、P5-C1で確定済みの[AlarmScheduleOutcome]
 * （`EXACT`/`DEGRADED_INEXACT`の2値のみ、reason付きDegraded・Skipped・missed一覧を表現できない）
 * および[AndroidNotificationService]のKDoc（「計画書§7.3が挙げる`AlarmSchedulingCoordinator`
 * の責務は本クラス内へ折り込む」）と整合させるため、以下のとおり対象を割り付けた：
 * - T-ALARM-1/2/3/4/5: [AlarmManagerAlarmScheduler]（L3）を直接対象とする。
 * - T-ALARM-6/7/8: Plan→トリガー列導出・過去トリガー除外・RECOVERY除外はL3ではなく
 *   [AndroidNotificationService]（L1、`AlarmSchedulingCoordinator`の責務を折り込み済み）の
 *   責務であるため、[AndroidNotificationService.schedule]をfake[AlarmScheduler]注入で検証する。
 * - T-ALARM-10: `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`はF54
 *   （`ScheduleRestoreReceiver`、計画書§8.2見出し「BOOT/TIME/TIMEZONE/EXACT_ALARM権限変更」）が
 *   処理する4アクションの1つであり、T-BOOT-1〜7にはこのアクション専用ケースがないため、
 *   本ファイルの割当（T-ALARM-10）どおり[ScheduleRestoreReceiver]を対象に検証する。
 *
 * **T-ALARM-9（P5-C2bで追加。P5-C2時点の差し戻し理由と解消経緯）**: P5-C2時点では
 * `AlarmManagerAlarmScheduler`が`Context`のみを受け取り実`android.app.AlarmManager`を
 * 内部解決していたため、テストから`setExactAndAllowWhileIdle`の`SecurityException`送出を
 * 注入する経路がなく本ファイルでは作成できなかった（差し戻し事項）。P5-C1本サイクルで
 * `shadows-framework-4.16.1.jar`の`ShadowAlarmManager`／`ShadowAlarmManager$ScheduledAlarm`を
 * 実際に`javap`した結果（`org.robolectric.shadows.ShadowAlarmManager`javap出力）、
 * 例外注入用のメソッド（`ShadowService.setThrowInStartForeground`に相当するもの）は
 * 一切存在しないことを実測確認済みであり、P5-C1 probe P5-P3の実測（実
 * `setExactAndAllowWhileIdle`は`canScheduleExactAlarms=false`でも例外を投げない）と合わせ、
 * Robolectricの`ShadowAlarmManager`単体では`SecurityException`経路を組み立てられないことが
 * 確定していた。**P5-C2b（本サイクル）で`AlarmManagerAlarmScheduler`へ`internal`可視性の
 * 関数型シーム`scheduleExactAlarm`が追加された**（[AlarmManagerAlarmScheduler]のKDoc参照。
 * Fable 5裁定・ADR-0026）ため、本ファイル末尾の`schedule_whenSetExactAndAllowWhileIdleThrows
 * SecurityException_fallsBackToInexactAndReturnsDegraded`（T-ALARM-9）はこのシームへ直接
 * `SecurityException`を投げるフェイクを注入する形で作成した。
 *
 * 現時点（P5-C2b）では[AlarmManagerAlarmScheduler]・[AndroidNotificationService]・
 * [ScheduleRestoreReceiver]の全メソッドが`TODO()`のため、T-ALARM-9を含め以下は全件
 * `NotImplementedError`によりRedになる（意図した失敗。
 * [com.actionstarter.services.location.FusedLocationServiceTest]と同型の記述方針：完成後の
 * 期待値をそのままアサートし、現状はメソッド呼び出し自体が`NotImplementedError`を送出して
 * テスト失敗となる。`assertThrows`等での握り潰しはしない）。
 */
@RunWith(RobolectricTestRunner::class)
class AlarmSchedulingTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun alarmManager(): AlarmManager =
        context().getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun payload(requestCode: Int, semanticId: String = "transition"): NotificationPayload =
        NotificationPayload(
            planId = "plan-1",
            requestCode = requestCode,
            stepSemanticId = semanticId,
            eventStartAt = Instant.parse("2026-08-10T09:00:00Z"),
            estimatedArrivalAt = Instant.parse("2026-08-10T08:50:00Z"),
            arrivalBuffer = Duration.ofMinutes(10)
        )

    // T-ALARM-1: 正常 - exact許可時、RTC_WAKEUP・triggerAtTime一致・allowWhileIdle=true・
    // windowLength=WINDOW_EXACTのアラームが登録される
    @Test
    fun schedule_whenExactAlarmsPermitted_registersExactAlarmMatchingTrigger() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = AlarmManagerAlarmScheduler(context())
        val triggerAt = Instant.parse("2026-08-10T08:30:00Z")
        val trigger = AlarmTrigger(triggerAt = triggerAt, kind = NotificationKind.TRANSITION_START, payload = payload(101))

        val outcome = scheduler.schedule(trigger)

        assertEquals(AlarmScheduleOutcome.EXACT, outcome)
        val scheduled = shadowOf(alarmManager()).scheduledAlarms
        assertEquals(1, scheduled.size)
        val alarm = scheduled.first()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(triggerAt.toEpochMilli(), alarm.triggerAtTime)
        assertTrue(alarm.allowWhileIdle)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
    }

    // T-ALARM-2: 異常 - setCanScheduleExactAlarms(false)のときinexact
    // （windowLength=WINDOW_HEURISTIC）へフォールバックし、戻り値がDEGRADED_INEXACTになる。
    // 実測（P5-C1 probe P5-P3）: OSシャドーは自動フォールバックしないため、実装
    // （AlarmManagerAlarmScheduler）がcanScheduleExactAlarms()を明示チェックしてAPIを
    // 選択している必要がある——本テストはその判定ロジックそのものを固定する。
    @Test
    fun schedule_whenExactAlarmsNotPermitted_fallsBackToInexactAndReturnsDegraded() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduler = AlarmManagerAlarmScheduler(context())
        val triggerAt = Instant.parse("2026-08-10T08:30:00Z")
        val trigger = AlarmTrigger(triggerAt = triggerAt, kind = NotificationKind.TRANSITION_START, payload = payload(102))

        val outcome = scheduler.schedule(trigger)

        assertEquals(AlarmScheduleOutcome.DEGRADED_INEXACT, outcome)
        val scheduled = shadowOf(alarmManager()).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, scheduled.first().windowLengthMs)
    }

    // T-ALARM-3: 正常 - 1つのPlanからTRANSITION_STARTとDEPARTUREの2件が登録され、
    // requestCodeが相異なる（2件が互いを上書きせず独立して存在する）
    @Test
    fun schedule_forTransitionAndDepartureTriggers_registersTwoIndependentAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = AlarmManagerAlarmScheduler(context())
        val transitionTrigger = AlarmTrigger(
            triggerAt = Instant.parse("2026-08-10T08:00:00Z"),
            kind = NotificationKind.TRANSITION_START,
            payload = payload(requestCode = 201, semanticId = "transition")
        )
        val departureTrigger = AlarmTrigger(
            triggerAt = Instant.parse("2026-08-10T08:40:00Z"),
            kind = NotificationKind.DEPARTURE,
            payload = payload(requestCode = 202, semanticId = "departure")
        )

        scheduler.schedule(transitionTrigger)
        scheduler.schedule(departureTrigger)

        val scheduled = shadowOf(alarmManager()).scheduledAlarms
        assertEquals(2, scheduled.size)
        assertNotEquals(scheduled[0].operation, scheduled[1].operation)
        assertNotEquals(scheduled[0].triggerAtTime, scheduled[1].triggerAtTime)
    }

    // T-ALARM-4: エッジ - 同一Planでschedule()を2回呼んでもアラーム総数が増えない
    // （同一requestCodeで置換）
    @Test
    fun schedule_calledTwiceWithSameRequestCode_replacesRatherThanDuplicates() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = AlarmManagerAlarmScheduler(context())
        val firstAttempt = AlarmTrigger(
            triggerAt = Instant.parse("2026-08-10T08:00:00Z"),
            kind = NotificationKind.TRANSITION_START,
            payload = payload(requestCode = 301)
        )
        val secondAttempt = AlarmTrigger(
            triggerAt = Instant.parse("2026-08-10T08:05:00Z"),
            kind = NotificationKind.TRANSITION_START,
            payload = payload(requestCode = 301)
        )

        scheduler.schedule(firstAttempt)
        scheduler.schedule(secondAttempt)

        val scheduled = shadowOf(alarmManager()).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(secondAttempt.triggerAt.toEpochMilli(), scheduled.first().triggerAtTime)
    }

    // T-ALARM-5: 正常 - cancel(requestCode)後、該当requestCodeのアラームが0件
    // （L1 cancelAll(planId)が内部的に組み立てるべきL3操作の単位を検証する）
    @Test
    fun cancel_afterSchedule_removesTheAlarmForThatRequestCode() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = AlarmManagerAlarmScheduler(context())
        val trigger = AlarmTrigger(
            triggerAt = Instant.parse("2026-08-10T08:00:00Z"),
            kind = NotificationKind.TRANSITION_START,
            payload = payload(requestCode = 401)
        )
        scheduler.schedule(trigger)

        scheduler.cancel(401)

        assertEquals(0, shadowOf(alarmManager()).scheduledAlarms.size)
    }

    // T-ALARM-6: エッジ - triggerAt <= nowのトリガーは登録せず、未来のトリガーのみ登録される
    // （AndroidNotificationService.scheduleへ折り込まれたAlarmSchedulingCoordinatorの責務）
    @Test
    fun notificationServiceSchedule_withOnePastAndOneFutureTrigger_registersOnlyFutureOne() {
        val now = Instant.now()
        val spyScheduler = SpyAlarmScheduler()
        val service = buildNotificationService(spyScheduler)
        val plan = buildPlan(
            transitionStart = now.minus(Duration.ofDays(2)),
            departureTime = now.plus(Duration.ofDays(2))
        )

        service.schedule(plan)

        assertEquals(1, spyScheduler.scheduledTriggers.size)
        assertEquals(NotificationKind.DEPARTURE, spyScheduler.scheduledTriggers.first().kind)
    }

    // T-ALARM-7: 回帰ガード - RECOVERY種別のアラームが1件も作られない（3種以外を作らない）
    @Test
    fun notificationServiceSchedule_forOrdinaryPlan_neverSchedulesRecoveryKindAlarm() {
        val now = Instant.now()
        val spyScheduler = SpyAlarmScheduler()
        val service = buildNotificationService(spyScheduler)
        val plan = buildPlan(
            transitionStart = now.plus(Duration.ofHours(1)),
            departureTime = now.plus(Duration.ofHours(2))
        )

        service.schedule(plan)

        assertTrue(spyScheduler.scheduledTriggers.none { it.kind == NotificationKind.RECOVERY })
    }

    // T-ALARM-8: エッジ - すべてのトリガーが過去（イベント開始済みPlan）でもクラッシュせず
    // Skippedを返す
    @Test
    fun notificationServiceSchedule_whenAllTriggersArePast_returnsSkippedWithoutScheduling() {
        val now = Instant.now()
        val spyScheduler = SpyAlarmScheduler()
        val service = buildNotificationService(spyScheduler)
        val plan = buildPlan(
            transitionStart = now.minus(Duration.ofDays(3)),
            departureTime = now.minus(Duration.ofDays(2))
        )

        val result = service.schedule(plan)

        assertTrue("expected Skipped but was $result", result is ScheduleResult.Skipped)
        assertEquals(ScheduleSkipReason.NO_FUTURE_TRIGGERS, (result as ScheduleResult.Skipped).reason)
        assertTrue(spyScheduler.scheduledTriggers.isEmpty())
    }

    // T-ALARM-9（P5-C2bで追加）: 異常 - setExactAndAllowWhileIdleがSecurityExceptionを
    // 投げた場合（実行時に許可が取り消された想定）、握り潰さずinexactへ再試行し
    // DEGRADED_INEXACTを返す。P5-C1 probe P5-P3の実測（実setExactAndAllowWhileIdleは
    // canScheduleExactAlarms=false時も例外を投げない）／P5-C2の実測（ShadowAlarmManagerに
    // 例外注入用メソッドが存在しない）により、実ShadowAlarmManager単体ではこの経路を
    // 再現できないと確定している（本ファイル冒頭KDoc参照）。そのためP5-C2bで
    // AlarmManagerAlarmSchedulerへ追加されたinternalシーム`scheduleExactAlarm`へ直接
    // SecurityExceptionを投げるフェイクを注入して検証する。
    // canScheduleExactAlarms(true)（exact許可あり）の状態でもシーム呼び出し自体が
    // 例外を投げるケースを再現する——エラー&レスキューマップ#2「予約直前に許可が取り消され
    // SecurityExceptionが発生」の想定と一致する。
    @Test
    fun schedule_whenSetExactAndAllowWhileIdleThrowsSecurityException_fallsBackToInexactAndReturnsDegraded() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = AlarmManagerAlarmScheduler(
            context = context(),
            scheduleExactAlarm = { _, _, _, _ -> throw SecurityException("SCHEDULE_EXACT_ALARM revoked at call time") }
        )
        val triggerAt = Instant.parse("2026-08-10T08:30:00Z")
        val trigger = AlarmTrigger(triggerAt = triggerAt, kind = NotificationKind.TRANSITION_START, payload = payload(901))

        val outcome = scheduler.schedule(trigger)

        assertEquals(AlarmScheduleOutcome.DEGRADED_INEXACT, outcome)
        val scheduled = shadowOf(alarmManager()).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, scheduled.first().windowLengthMs)
    }

    // T-ALARM-10: 異常（仕様未記載の追加防御、M5-12） -
    // ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED受信で全アラームが再登録される。
    // F54（ScheduleRestoreReceiver、計画書§8.2見出し「BOOT/TIME/TIMEZONE/EXACT_ALARM権限変更」）
    // が処理する4アクションの1つ。
    //
    // 【差し戻し事項ではなく実装整合確認事項】ScheduleRestoreReceiverはBroadcastReceiverとして
    // 引数なしコンストラクタ必須のためDI注入経路がなく、P5-C1時点でdi/AppContainer.ktは
    // Phase 5未配線（統合ウィンドウP5-C6まで確定しない、計画書§6.3）。本テストは
    // SharedPreferencesExecutionScheduleStoreが将来ScheduleRestoreReceiver実装からも
    // 同一のSharedPreferences名で参照される、という仮の取り決め
    // （[ASSUMED_SCHEDULE_STORE_PREFS_NAME]）を前提に事前データを投入する。P5-C3実装時に
    // 実装側が異なる名前を採用した場合、本テストのRed理由はNotImplementedErrorから
    // 「再登録件数が0のままの期待値差分」に変わる可能性があるため、P5-C3実装者は本定数との
    // 整合を確認すること。
    @Test
    fun scheduleRestoreReceiver_onExactAlarmPermissionStateChanged_reregistersAllStoredTriggers() {
        val now = Instant.now()
        val prefs = context().getSharedPreferences(ASSUMED_SCHEDULE_STORE_PREFS_NAME, Context.MODE_PRIVATE)
        val store: ExecutionScheduleStore = SharedPreferencesExecutionScheduleStore(prefs)
        val record = ExecutionScheduleRecord(
            schemaVersion = ExecutionScheduleRecord.CURRENT_SCHEMA_VERSION,
            planId = "plan-exact-alarm-permission",
            eventStartEpochMillis = now.plus(Duration.ofHours(3)).toEpochMilli(),
            estimatedArrivalEpochMillis = now.plus(Duration.ofHours(2)).toEpochMilli(),
            triggers = listOf(
                Trigger(
                    kind = NotificationKind.DEPARTURE,
                    stepId = UUID.randomUUID(),
                    semanticId = "departure",
                    triggerAtEpochMillis = now.plus(Duration.ofHours(1)).toEpochMilli(),
                    requestCode = 1,
                    fired = false
                )
            )
        )
        store.save(record)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val receiver = ScheduleRestoreReceiver()
        receiver.onReceive(context(), android.content.Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))

        assertEquals(1, shadowOf(alarmManager()).scheduledAlarms.size)
    }

    // ---- 共有フィクスチャ ---------------------------------------------------------------

    /**
     * 【仮の取り決め・P5-C3実装整合確認事項】T-ALARM-10・T-BOOT-1〜4が共有する
     * SharedPreferences名。`SharedPreferencesExecutionScheduleStore`のKDocは
     * 「呼び出し側が`context.getSharedPreferences(NAME, MODE_PRIVATE)`で用意する」とのみ書き、
     * 実際の名称はP5-C1契約scaffold時点で確定していない。test-writer（P5-C2）が
     * ScheduleRestoreReceiverの復元テストを書くために暫定採用した値であり、確定した契約では
     * ない。[com.actionstarter.services.notification.ScheduleRestoreReceiverTest]と同一値を
     * 共有する。
     */
    private fun buildNotificationService(alarmScheduler: AlarmScheduler): AndroidNotificationService =
        AndroidNotificationService(
            context = context(),
            store = FakeExecutionScheduleStore(),
            alarmScheduler = alarmScheduler,
            contentBuilder = NotificationContentBuilder(context()),
            permissionGate = AlwaysGrantedPermissionGate()
        )

    private fun buildPlan(transitionStart: Instant, departureTime: Instant): ExecutionPlan {
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "Team sync",
            notes = null,
            startDate = departureTime.plus(Duration.ofMinutes(30)),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "cal-1", displayName = "Work")
        )
        val steps = listOf(
            ExecutionStep(
                id = UUID.nameUUIDFromBytes("${event.id}:transition".toByteArray()),
                semanticId = "transition",
                type = ExecutionStepType.TRANSITION,
                title = "",
                estimatedDuration = Duration.ofMinutes(5),
                priority = StepPriority.REQUIRED,
                skippable = false,
                scheduledStart = transitionStart,
                completedAt = null
            )
        )
        return ExecutionPlan(
            event = event,
            steps = steps,
            transitionStart = transitionStart,
            departureTime = departureTime,
            estimatedArrival = departureTime.plus(Duration.ofMinutes(20)),
            arrivalBuffer = Duration.ofMinutes(10)
        )
    }

    /** [AlarmScheduler]のspy fake。呼び出されたトリガーをすべて記録する。 */
    private class SpyAlarmScheduler : AlarmScheduler {
        val scheduledTriggers = mutableListOf<AlarmTrigger>()
        val cancelledRequestCodes = mutableListOf<Int>()

        override fun schedule(trigger: AlarmTrigger): AlarmScheduleOutcome {
            scheduledTriggers += trigger
            return AlarmScheduleOutcome.EXACT
        }

        override fun cancel(requestCode: Int) {
            cancelledRequestCodes += requestCode
        }
    }

    /** [ExecutionScheduleStore]のfake（T-ALARM-6/7/8はストア内容自体を検証しないため最小実装）。 */
    private class FakeExecutionScheduleStore : ExecutionScheduleStore {
        private var counter = 0
        private val records = mutableListOf<ExecutionScheduleRecord>()

        override fun nextRequestCode(): Int = counter++

        override fun save(record: ExecutionScheduleRecord) {
            records.removeAll { it.planId == record.planId }
            records += record
        }

        override fun loadAll(): ExecutionScheduleLoadResult = ExecutionScheduleLoadResult(records.toList(), 0)

        override fun clear(planId: String) {
            records.removeAll { it.planId == planId }
        }
    }

    private class AlwaysGrantedPermissionGate : PermissionGate {
        override fun isGranted(permission: String): Boolean = true
    }

    private companion object {
        const val ASSUMED_SCHEDULE_STORE_PREFS_NAME = "com.actionstarter.execution_schedule_store"
    }
}
