package com.actionstarter.services.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.actionstarter.persistence.SharedPreferencesExecutionScheduleStore
import com.actionstarter.services.permission.AndroidPermissionGate
import java.time.Duration
import java.time.Instant

/**
 * F53実装（計画書§6.1・§7.3・エラー&レスキューマップ#8）。アラーム発火→通知提示のみを行う
 * （位置取得を一切呼ばない。§95.1 While-in-use構造ガード。位置を使う再計算は通知タップでの
 * フォアグラウンド復帰後、またはExecution FGS継続中のみ行う）。
 *
 * **構造ガード（T-NOTIF-10、Gemini G1 CRITICAL反映）**: 本レシーバはForeground Serviceを
 * 起動しない（Android 14/15のBroadcastReceiver起因FGS起動制限に触れないため）。本クラスは
 * `startService`/`startForegroundService`を一切呼ばないコード経路として構造的にこれを満たす。
 *
 * **P5-C3実装（本サイクル）**: `BroadcastReceiver`は引数なしコンストラクタが必須でDI注入
 * 経路がないため（[ScheduleRestoreReceiver]と同型）、[NotificationPayload]の全フィールドを
 * [AlarmManagerAlarmScheduler]が発火Intentのextraへ埋め込んだもの（`internal`シームキー、
 * 同一ファイル内の[AlarmManagerAlarmScheduler]companion object参照）から直接再構成する。
 * ストア再読込に依存しないため、ストアが破損していても通知提示自体はブロックされない。
 * 信頼境界（Intent extraを検証なしに信頼しない）: 必須extraが1つでも欠けている場合は
 * 何もせず正常終了する（クラッシュしない。実運用ではアラームが必ず全extraを積むため
 * 通常発生しないが、防御的に扱う）。
 */
class NotificationTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(AlarmManagerAlarmScheduler.EXTRA_KIND)
            ?.let { runCatching { NotificationKind.valueOf(it) }.getOrNull() }
            ?: return
        val planId = intent.getStringExtra(AlarmManagerAlarmScheduler.EXTRA_PLAN_ID) ?: return
        val stepSemanticId = intent.getStringExtra(AlarmManagerAlarmScheduler.EXTRA_STEP_SEMANTIC_ID) ?: return
        if (!intent.hasExtra(AlarmManagerAlarmScheduler.EXTRA_REQUEST_CODE) ||
            !intent.hasExtra(AlarmManagerAlarmScheduler.EXTRA_EVENT_START_AT_EPOCH_MILLIS) ||
            !intent.hasExtra(AlarmManagerAlarmScheduler.EXTRA_ESTIMATED_ARRIVAL_AT_EPOCH_MILLIS) ||
            !intent.hasExtra(AlarmManagerAlarmScheduler.EXTRA_ARRIVAL_BUFFER_MILLIS)
        ) {
            return
        }

        val payload = NotificationPayload(
            planId = planId,
            requestCode = intent.getIntExtra(AlarmManagerAlarmScheduler.EXTRA_REQUEST_CODE, 0),
            stepSemanticId = stepSemanticId,
            eventStartAt = Instant.ofEpochMilli(
                intent.getLongExtra(AlarmManagerAlarmScheduler.EXTRA_EVENT_START_AT_EPOCH_MILLIS, 0L)
            ),
            estimatedArrivalAt = Instant.ofEpochMilli(
                intent.getLongExtra(AlarmManagerAlarmScheduler.EXTRA_ESTIMATED_ARRIVAL_AT_EPOCH_MILLIS, 0L)
            ),
            arrivalBuffer = Duration.ofMillis(
                intent.getLongExtra(AlarmManagerAlarmScheduler.EXTRA_ARRIVAL_BUFFER_MILLIS, 0L)
            )
        )

        buildNotificationService(context).notifyNow(kind, payload)
    }

    private fun buildNotificationService(context: Context): NotificationService {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            SharedPreferencesExecutionScheduleStore.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        return AndroidNotificationService(
            context = appContext,
            store = SharedPreferencesExecutionScheduleStore(preferences),
            alarmScheduler = AlarmManagerAlarmScheduler(appContext),
            contentBuilder = NotificationContentBuilder(appContext),
            permissionGate = AndroidPermissionGate(appContext)
        )
    }
}
