package com.actionstarter.services.notification

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import com.actionstarter.persistence.ExecutionScheduleRecord
import com.actionstarter.persistence.ExecutionScheduleStore
import com.actionstarter.persistence.SharedPreferencesExecutionScheduleStore
import com.actionstarter.persistence.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * T-BOOT-1〜7（計画書§8.2「F54 — ScheduleRestoreReceiver」全7件、E2・Robolectric）。
 *
 * [ScheduleRestoreReceiver]は`BroadcastReceiver`であるため引数なしコンストラクタが必須で、
 * コンストラクタ注入によるfake差し替えができない。本ファイルは直接インスタンス化して
 * `onReceive(context, intent)`を呼ぶ方式（Manifest経由の配送はP5-C6統合ウィンドウまで
 * 未確定・意味のある実測ができないためP5-C1 probe P5-P5でも延期されている。計画書§10.3）。
 *
 * **【仮の取り決め・P5-C3実装整合確認事項、差し戻し事項】** T-BOOT-1〜4は「ストアに事前登録
 * された未完了レコードから再登録される」ことを検証するため、[ScheduleRestoreReceiver]が
 * 内部で解決するであろう[ExecutionScheduleStore]と**同一の永続化領域**へ事前にレコードを
 * 書き込む必要がある。P5-C1時点で`di/AppContainer.kt`はPhase 5未配線（統合ウィンドウ
 * P5-C6まで確定しない、計画書§6.3）であり、[SharedPreferencesExecutionScheduleStore]の
 * SharedPreferences名も契約scaffold未確定（同KDocは`NAME`をプレースホルダとして書くのみ）。
 * 本ファイルは[ASSUMED_SCHEDULE_STORE_PREFS_NAME]
 * （[com.actionstarter.services.notification.AlarmSchedulingTest]のT-ALARM-10と同一値）を
 * 仮に採用する。P5-C3実装者が異なる名前を採用した場合、T-BOOT-1〜4のRed理由は
 * `NotImplementedError`から「再登録件数が0のままの期待値差分」に変わりうるため、実装との
 * 整合を確認すること。T-BOOT-5/6/7はストアの中身に依存しないため本仮定の影響を受けない。
 *
 * 現時点（P5-C2）では[ScheduleRestoreReceiver.onReceive]・
 * [SharedPreferencesExecutionScheduleStore]の全メソッドが`TODO()`のため、以下は全件
 * `NotImplementedError`によりRedになる（意図した失敗）。
 */
@RunWith(RobolectricTestRunner::class)
class ScheduleRestoreReceiverTest {

    private fun context(): Application = RuntimeEnvironment.getApplication()

    private fun alarmManager(): AlarmManager =
        context().getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager

    private fun store(): ExecutionScheduleStore {
        val prefs = context().getSharedPreferences(ASSUMED_SCHEDULE_STORE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return SharedPreferencesExecutionScheduleStore(prefs)
    }

    private fun seedRecord(planId: String, triggerAtEpochMillis: Long, requestCode: Int, fired: Boolean = false) {
        store().save(
            ExecutionScheduleRecord(
                schemaVersion = ExecutionScheduleRecord.CURRENT_SCHEMA_VERSION,
                planId = planId,
                eventStartEpochMillis = triggerAtEpochMillis + Duration.ofHours(1).toMillis(),
                estimatedArrivalEpochMillis = triggerAtEpochMillis + Duration.ofMinutes(50).toMillis(),
                triggers = listOf(
                    Trigger(
                        kind = NotificationKind.DEPARTURE,
                        stepId = UUID.randomUUID(),
                        semanticId = "departure",
                        triggerAtEpochMillis = triggerAtEpochMillis,
                        requestCode = requestCode,
                        fired = fired
                    )
                )
            )
        )
    }

    // T-BOOT-1: 正常 - ACTION_BOOT_COMPLETEDでストアの未完了レコードからアラームが再登録される
    @Test
    fun onReceive_bootCompleted_reregistersAlarmsFromIncompleteStoredRecords() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val futureTrigger = Instant.now().plus(Duration.ofHours(2)).toEpochMilli()
        seedRecord(planId = "plan-boot", triggerAtEpochMillis = futureTrigger, requestCode = 901)

        ScheduleRestoreReceiver().onReceive(context(), Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(1, shadowOf(alarmManager()).scheduledAlarms.size)
    }

    // T-BOOT-2: 正常 - ACTION_TIME_CHANGED / ACTION_TIMEZONE_CHANGEDでも同様に再登録される
    @Test
    fun onReceive_timeOrTimezoneChanged_reregistersAlarmsFromIncompleteStoredRecords() {
        listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED).forEach { action ->
            ShadowAlarmManager.reset()
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val futureTrigger = Instant.now().plus(Duration.ofHours(3)).toEpochMilli()
            seedRecord(planId = "plan-time-$action", triggerAtEpochMillis = futureTrigger, requestCode = 902)

            ScheduleRestoreReceiver().onReceive(context(), Intent(action))

            assertEquals("action=$action must reregister the stored trigger", 1, shadowOf(alarmManager()).scheduledAlarms.size)
        }
    }

    // T-BOOT-3: エッジ - TIMEZONE_CHANGEDでtriggerAtの絶対時刻が変化しない（Instant基準・
    // ローカル壁時計から再導出しない、ADR-0008）
    @Test
    fun onReceive_timezoneChanged_preservesExactAbsoluteTriggerInstant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val originalEpochMillis = Instant.now().plus(Duration.ofHours(4)).toEpochMilli()
        seedRecord(planId = "plan-tz", triggerAtEpochMillis = originalEpochMillis, requestCode = 903)

        ScheduleRestoreReceiver().onReceive(context(), Intent(Intent.ACTION_TIMEZONE_CHANGED))

        val scheduled = shadowOf(alarmManager()).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(
            "TIMEZONE_CHANGED must reregister using the exact same epoch millis, not a recalculated local-time value",
            originalEpochMillis,
            scheduled.first().triggerAtTime
        )
    }

    // T-BOOT-4: エッジ - 再登録は既存アラームをcancelしてから行い、重複が発生しない
    @Test
    fun onReceive_calledTwice_doesNotDuplicateAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val futureTrigger = Instant.now().plus(Duration.ofHours(5)).toEpochMilli()
        seedRecord(planId = "plan-dup", triggerAtEpochMillis = futureTrigger, requestCode = 904)

        val receiver = ScheduleRestoreReceiver()
        receiver.onReceive(context(), Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context(), Intent(Intent.ACTION_TIME_CHANGED))

        assertEquals(
            "second restore must cancel-then-reschedule, not accumulate a duplicate alarm",
            1,
            shadowOf(alarmManager()).scheduledAlarms.size
        )
    }

    // T-BOOT-5: エッジ - ストアが空のとき何もせずクラッシュしない
    @Test
    fun onReceive_bootCompletedWithEmptyStore_doesNothingWithoutCrashing() {
        ScheduleRestoreReceiver().onReceive(context(), Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(shadowOf(alarmManager()).scheduledAlarms.isEmpty())
    }

    // T-BOOT-6: 異常（信頼境界） - 想定外のactionを無視する（action文字列を検証せずに動かない）
    @Test
    fun onReceive_unrecognizedAction_ignoresItAndDoesNotReregister() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val futureTrigger = Instant.now().plus(Duration.ofHours(6)).toEpochMilli()
        seedRecord(planId = "plan-unknown-action", triggerAtEpochMillis = futureTrigger, requestCode = 905)

        ScheduleRestoreReceiver().onReceive(context(), Intent("com.actionstarter.SOME_UNRECOGNIZED_ACTION"))

        assertTrue(
            "an unrecognized action must not trigger restoration (trust boundary: don't act on unvalidated Intent action)",
            shadowOf(alarmManager()).scheduledAlarms.isEmpty()
        )
    }

    // T-BOOT-7: 回帰ガード - ScheduleRestoreReceiverがForeground Serviceを起動しない
    // （Android 14/15のBOOT_COMPLETED起因FGS起動制限への構造ガード）
    @Test
    fun onReceive_bootCompletedWithStoredRecords_neverStartsAForegroundService() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val futureTrigger = Instant.now().plus(Duration.ofHours(7)).toEpochMilli()
        seedRecord(planId = "plan-fgs-guard", triggerAtEpochMillis = futureTrigger, requestCode = 906)

        ScheduleRestoreReceiver().onReceive(context(), Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(
            "ScheduleRestoreReceiver must never start(ForegroundService)/startForegroundService " +
                "(§95.1, Android 14/15 BOOT_COMPLETED FGS start restriction)",
            shadowOf(context()).peekNextStartedService()
        )
    }

    private companion object {
        /** [com.actionstarter.services.notification.AlarmSchedulingTest]と同一値を共有する。 */
        const val ASSUMED_SCHEDULE_STORE_PREFS_NAME = "com.actionstarter.execution_schedule_store"
    }
}
