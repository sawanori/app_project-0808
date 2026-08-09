package com.actionstarter.services.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * F53実装（計画書§6.1・§7.3・エラー&レスキューマップ#8）。アラーム発火→通知提示のみを行う
 * （位置取得を一切呼ばない。§95.1 While-in-use構造ガード。位置を使う再計算は通知タップでの
 * フォアグラウンド復帰後、またはExecution FGS継続中のみ行う）。
 *
 * **構造ガード（T-NOTIF-10、Gemini G1 CRITICAL反映）**: 本レシーバはForeground Serviceを
 * 起動しない（Android 14/15のBroadcastReceiver起因FGS起動制限に触れないため）。
 *
 * 契約scaffold（P5-C1、TDD例外）時点では宣言のみであり、実装はP5-C3で行う。
 * Manifestへの`<receiver>`宣言はP5-C6統合ウィンドウで行う（本サイクルでは対象外）。
 */
class NotificationTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TODO()
    }
}
