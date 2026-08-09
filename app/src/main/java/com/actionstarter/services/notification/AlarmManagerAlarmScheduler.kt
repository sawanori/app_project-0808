package com.actionstarter.services.notification

import android.content.Context

/**
 * F50/F51実装（計画書§6.1・§7.3）。[AlarmScheduler]のAndroid実装。`AlarmManagerCompat`
 * 経由で`canScheduleExactAlarms`を判定したうえで`setExactAndAllowWhileIdle`／inexact
 * （`setAndAllowWhileIdle`）を選択する（P5-P3実測により、実行結果からの自動判別はできない
 * ため明示判定が必須。[AlarmScheduler]のKDoc参照）。`PendingIntent`の宛先は
 * [NotificationTriggerReceiver]とする（アラームの宛先はActivityでもServiceでもない。
 * 計画書§7.3「アラームの宛先はBroadcastReceiver」）。
 *
 * 契約scaffold（P5-C1、TDD例外）時点では宣言のみであり、実装本体はP5-C3で行う。
 *
 * @param context アラーム登録・`PendingIntent`構築に使う`applicationContext`。
 */
class AlarmManagerAlarmScheduler(
    private val context: Context
) : AlarmScheduler {
    override fun schedule(trigger: AlarmTrigger): AlarmScheduleOutcome = TODO()

    override fun cancel(requestCode: Int) {
        TODO()
    }
}
