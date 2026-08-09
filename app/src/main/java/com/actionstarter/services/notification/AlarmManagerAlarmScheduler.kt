package com.actionstarter.services.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.PendingIntentCompat

/**
 * exact alarmを現在OSが許可しているかどうかの単一判定源。[AlarmManagerAlarmScheduler.schedule]の
 * exact/inexact分岐と[AndroidNotificationService.isExactAlarmAvailable]の両方が本関数を呼ぶことで、
 * 判定ロジックを重複させない（P5-C3fix・Fable 5裁定2026-08-09、
 * `docs/plans/phase5-notification-execution.md`§10.7）。トップレベル関数（`internal`）として
 * 本パッケージ内から共有できる形にしたのは、[AndroidNotificationService]が[AlarmScheduler]の
 * fake実装（テスト）越しではなく実`AlarmManager`を直接照会する必要があり、かつ[AlarmScheduler]
 * 契約自体を変更するとテスト影響範囲が[NotificationService]のfakeに留まらなくなるため。
 */
internal fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
    AlarmManagerCompat.canScheduleExactAlarms(alarmManager)

/**
 * F50/F51実装（計画書§6.1・§7.3）。[AlarmScheduler]のAndroid実装。`AlarmManagerCompat`
 * 経由で`canScheduleExactAlarms`を判定したうえで`setExactAndAllowWhileIdle`／inexact
 * （`setAndAllowWhileIdle`）を選択する（P5-P3実測により、実行結果からの自動判別はできない
 * ため明示判定が必須。[AlarmScheduler]のKDoc参照）。`PendingIntent`の宛先は
 * [NotificationTriggerReceiver]とする（アラームの宛先はActivityでもServiceでもない。
 * 計画書§7.3「アラームの宛先はBroadcastReceiver」）。
 *
 * [schedule]／[cancel]本体はP5-C3で実装済み。
 *
 * **T-ALARM-9用の例外注入シーム（P5-C2b、Fable 5裁定・段階1スキャフォールド）**: P5-C1
 * probe P5-P3の実測により、Robolectric `ShadowAlarmManager`は`canScheduleExactAlarms=false`
 * の状態でも実`AlarmManager.setExactAndAllowWhileIdle`を例外なく受理し
 * `windowLengthMs=WINDOW_EXACT`のまま黙って記録することが確認されている（[AlarmScheduler]の
 * KDoc参照）。さらにP5-C2 test-writerが`shadows-framework-4.16.1.jar`の
 * `org.robolectric.shadows.ShadowAlarmManager`／`ShadowAlarmManager$ScheduledAlarm`を
 * `javap`した結果、`ShadowService.setThrowInStartForeground`に相当する例外注入用メソッドが
 * 一切存在しないことを実測確認済みである
 * （[AlarmSchedulingTest][com.actionstarter.services.notification.AlarmSchedulingTest]の
 * T-ALARM-9 KDoc参照）。したがって「実行時に許可が取り消され`SecurityException`が送出される」
 * （T-ALARM-9、エラー&レスキューマップ#2）という状況は、Robolectricの`ShadowAlarmManager`
 * だけでは再現できない。[scheduleExactAlarm]は、実`setExactAndAllowWhileIdle`呼び出しを
 * `internal`可視性の関数型引数として外出しすることで、JVMテストがShadow層を介さず直接
 * `SecurityException`を投げるフェイクを注入できるようにする（[CursorSource]
 * [com.actionstarter.services.calendar.CursorSource]／
 * [RawLocationSource][com.actionstarter.services.location.RawLocationSource]と同じ、
 * プラットフォームAPI呼び出しを注入可能な形へ切り出す家内様式。`internal`のため本モジュール
 * （`:app`）外からは差し替えられない）。P5-C3で[schedule]本体を実装し、exact許可時は
 * 本シーム経由で呼び出す形にした（T-ALARM-9はこのシームへ直接フェイクを注入して検証する）。
 *
 * @param context アラーム登録・`PendingIntent`構築に使う`applicationContext`。
 * @param scheduleExactAlarm exact alarm呼び出し（`AlarmManager.setExactAndAllowWhileIdle`）の
 *   シーム。既定値は実`AlarmManager`への委譲。テストは`SecurityException`を送出するフェイクを
 *   注入できる（T-ALARM-9）。
 */
class AlarmManagerAlarmScheduler(
    private val context: Context,
    internal val scheduleExactAlarm: (AlarmManager, Int, Long, PendingIntent) -> Unit =
        { manager, type, triggerAtMillis, operation -> manager.setExactAndAllowWhileIdle(type, triggerAtMillis, operation) }
) : AlarmScheduler {

    /**
     * P5-C3実装。exact許可判定（[AlarmManagerCompat.canScheduleExactAlarms]）を明示的に行い、
     * 許可されていればexact（[scheduleExactAlarm]シーム経由）、されていなければ最初から
     * inexact（[AlarmManagerCompat.setAndAllowWhileIdle]）を選択する（P5-P3実測：OSシャドーは
     * 呼び出し結果から自動フォールバックしないため、実装側の明示判定が必須）。
     *
     * exact許可がある場合でも[scheduleExactAlarm]が`SecurityException`を投げた場合
     * （エラー&レスキューマップ#2「予約直前に許可が取り消された」想定、T-ALARM-9）は
     * 握り潰さずinexactへフォールバックする。
     */
    override fun schedule(trigger: AlarmTrigger): AlarmScheduleOutcome {
        val manager = alarmManager()
        val operation = buildTriggerPendingIntent(trigger.payload.requestCode, trigger.kind, trigger.payload)
        val triggerAtMillis = trigger.triggerAt.toEpochMilli()

        if (canScheduleExactAlarms(manager)) {
            try {
                scheduleExactAlarm(manager, AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                return AlarmScheduleOutcome.EXACT
            } catch (revokedAtCallTime: SecurityException) {
                // Error map #2: permission revoked between the canScheduleExactAlarms() check
                // and the actual call. Do not swallow -- fall through to the inexact retry below.
            }
        }

        AlarmManagerCompat.setAndAllowWhileIdle(manager, AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        return AlarmScheduleOutcome.DEGRADED_INEXACT
    }

    /**
     * [requestCode]に対応する既存アラームを取り消す。`FLAG_NO_CREATE`で既存の（等価な）
     * `PendingIntent`を検索し、存在すれば[AlarmManager.cancel]する。存在しない場合は
     * 何もしない（T-BOOT-5相当、契約どおり）。
     */
    override fun cancel(requestCode: Int) {
        val manager = alarmManager()
        val existing = PendingIntentCompat.getBroadcast(
            context,
            requestCode,
            triggerIntent(),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_UPDATE_CURRENT,
            false
        ) ?: return
        manager.cancel(existing)
        existing.cancel()
    }

    private fun alarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * [NotificationTriggerReceiver]宛てのbase intent（`action`＋component）。`PendingIntent`の
     * 同一性判定（`filterEquals`）は`requestCode`＋action＋component＋data＋categoriesで
     * 行われ、extrasは含まれないため、[cancel]は本メソッドが返す同一shapeのIntentへ
     * `FLAG_NO_CREATE`を渡すだけで[buildTriggerPendingIntent]が発行した既存の
     * `PendingIntent`を確実に取得できる。
     */
    private fun triggerIntent(): Intent =
        Intent(context, NotificationTriggerReceiver::class.java).setAction(ACTION_ALARM_TRIGGER)

    /**
     * [NotificationTriggerReceiver]が発火時に[NotificationPayload]を再構成できるよう、
     * ペイロード全フィールドをIntent extraへ埋め込む（受信側はDIを持たないbare
     * `BroadcastReceiver`のため、ストア再読込に頼らずここで完結させる）。フラグは
     * `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`相当（`PendingIntentCompat`が
     * `isMutable=false`でAPIレベルに応じた`FLAG_IMMUTABLE`を付与する。計画書§7.3
     * 「PendingIntent一意性の規約」）。
     */
    private fun buildTriggerPendingIntent(
        requestCode: Int,
        kind: NotificationKind,
        payload: NotificationPayload
    ): PendingIntent {
        val intent = triggerIntent().apply {
            putExtra(EXTRA_KIND, kind.name)
            putExtra(EXTRA_PLAN_ID, payload.planId)
            putExtra(EXTRA_REQUEST_CODE, payload.requestCode)
            putExtra(EXTRA_STEP_SEMANTIC_ID, payload.stepSemanticId)
            putExtra(EXTRA_EVENT_START_AT_EPOCH_MILLIS, payload.eventStartAt.toEpochMilli())
            putExtra(EXTRA_ESTIMATED_ARRIVAL_AT_EPOCH_MILLIS, payload.estimatedArrivalAt.toEpochMilli())
            putExtra(EXTRA_ARRIVAL_BUFFER_MILLIS, payload.arrivalBuffer.toMillis())
        }
        return checkNotNull(
            PendingIntentCompat.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                /* isMutable = */ false
            )
        ) { "PendingIntentCompat.getBroadcast unexpectedly returned null without FLAG_NO_CREATE" }
    }

    companion object {
        private const val ACTION_ALARM_TRIGGER = "com.actionstarter.action.ALARM_TRIGGER"

        /** [NotificationTriggerReceiver]が読み出すIntent extraキー（本クラスと共有する契約）。 */
        internal const val EXTRA_KIND = "com.actionstarter.extra.KIND"
        internal const val EXTRA_PLAN_ID = "com.actionstarter.extra.PLAN_ID"
        internal const val EXTRA_REQUEST_CODE = "com.actionstarter.extra.REQUEST_CODE"
        internal const val EXTRA_STEP_SEMANTIC_ID = "com.actionstarter.extra.STEP_SEMANTIC_ID"
        internal const val EXTRA_EVENT_START_AT_EPOCH_MILLIS =
            "com.actionstarter.extra.EVENT_START_AT_EPOCH_MILLIS"
        internal const val EXTRA_ESTIMATED_ARRIVAL_AT_EPOCH_MILLIS =
            "com.actionstarter.extra.ESTIMATED_ARRIVAL_AT_EPOCH_MILLIS"
        internal const val EXTRA_ARRIVAL_BUFFER_MILLIS = "com.actionstarter.extra.ARRIVAL_BUFFER_MILLIS"
    }
}
