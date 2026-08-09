package com.actionstarter.services.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * F54実装（計画書§6.1・§7.3・エラー&レスキューマップ#9）。
 * `ACTION_BOOT_COMPLETED`／`ACTION_TIME_CHANGED`／`ACTION_TIMEZONE_CHANGED`／
 * `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`を受け、
 * [NotificationService.restoreFromStore]を呼んでアラームを再登録する。
 *
 * TIMEZONE_CHANGEDでは絶対時刻（epoch millis）を再計算しない（ADR-0008、T-BOOT-3。
 * 内部Domainの時刻は`Instant`で絶対時刻のため、ローカル壁時計から再導出すると時差移動の
 * たびに予定時刻がずれる重大バグになる）。想定外のactionは無視する（信頼境界、T-BOOT-6）。
 *
 * **構造ガード（T-BOOT-7）**: 本レシーバはForeground Serviceを起動しない
 * （Android 14/15のBOOT_COMPLETED起因FGS起動制限に触れないため）。
 *
 * 契約scaffold（P5-C1、TDD例外）時点では宣言のみであり、実装はP5-C3で行う。
 * Manifestへの`<receiver>`宣言はP5-C6統合ウィンドウで行う（本サイクルでは対象外）。
 */
class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TODO()
    }
}
