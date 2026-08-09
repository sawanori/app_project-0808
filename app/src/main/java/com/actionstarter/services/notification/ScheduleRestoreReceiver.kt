package com.actionstarter.services.notification

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.actionstarter.persistence.SharedPreferencesExecutionScheduleStore
import com.actionstarter.services.permission.AndroidPermissionGate

/**
 * F54実装（計画書§6.1・§7.3・エラー&レスキューマップ#9）。
 * `ACTION_BOOT_COMPLETED`／`ACTION_TIME_CHANGED`／`ACTION_TIMEZONE_CHANGED`／
 * `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`を受け、
 * [NotificationService.restoreFromStore]を呼んでアラームを再登録する。
 *
 * TIMEZONE_CHANGEDでは絶対時刻（epoch millis）を再計算しない（ADR-0008、T-BOOT-3）。
 * これは本レシーバがフィルタするアクション種別に関わらず[AndroidNotificationService.
 * restoreFromStore]が`triggerAtEpochMillis`を素通しするだけで実現される（ローカル壁時計を
 * 一切経由しない設計。内部Domainの時刻は`Instant`で絶対時刻のため、ローカル壁時計から
 * 再導出すると時差移動のたびに予定時刻がずれる重大バグになる）。想定外のactionは無視する
 * （信頼境界、T-BOOT-6）。
 *
 * **構造ガード（T-BOOT-7）**: 本レシーバはForeground Serviceを起動しない
 * （Android 14/15のBOOT_COMPLETED起因FGS起動制限に触れないため）。本クラスは
 * `startService`/`startForegroundService`を一切呼ばないコード経路として構造的にこれを満たす。
 *
 * **P5-C3実装（本サイクル）**: `BroadcastReceiver`は引数なしコンストラクタが必須でDI注入
 * 経路がないため（[NotificationTriggerReceiver]と同型）、[AndroidNotificationService]を
 * 直接組み立てる。[SharedPreferencesExecutionScheduleStore.PREFS_NAME]を経由することで、
 * `AndroidNotificationService.schedule`（Execution Plan確定時、統合ウィンドウでDI結線）が
 * 書き込んだのと同一の永続化領域を読む。
 */
class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RECOGNIZED_ACTIONS) return

        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            SharedPreferencesExecutionScheduleStore.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val service: NotificationService = AndroidNotificationService(
            context = appContext,
            store = SharedPreferencesExecutionScheduleStore(preferences),
            alarmScheduler = AlarmManagerAlarmScheduler(appContext),
            contentBuilder = NotificationContentBuilder(appContext),
            permissionGate = AndroidPermissionGate(appContext)
        )
        service.restoreFromStore()
    }

    private companion object {
        val RECOGNIZED_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    }
}
