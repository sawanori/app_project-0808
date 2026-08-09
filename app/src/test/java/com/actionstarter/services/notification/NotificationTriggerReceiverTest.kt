package com.actionstarter.services.notification

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * T-NOTIF-10（Gemini G1 CRITICAL指摘の反映分、`docs/plans/phase5-notification-execution.md`
 * §8.2「F49/F52」表・§14申し送り参照。回帰ガード、E2・Robolectric）。
 *
 * **格納先ファイルについての判断（P5-C2、test-writer）**: T-NOTIF-10はG1レビューで追加された
 * ケースであり、計画書§6.1のファイル配置表には対応する行が存在しない（同表はG1着手前の
 * ファイル一覧のまま）。[NotificationTriggerReceiver]は独立したクラス
 * （`AndroidNotificationService`/`ScheduleRestoreReceiver`のいずれとも別ファイル）であり、
 * 既存のどのテストファイルにも一意に対応しないため、本ケース専用の新規ファイル
 * `NotificationTriggerReceiverTest.kt`をクラス名と1:1対応する形で新設した。この判断を
 * 計画書のP5-C2完了記録（§10.1行）に明記する。
 *
 * 検証内容: `NotificationTriggerReceiver`はアラーム発火時に通知を提示するのみで、
 * Foreground Serviceを一切起動しない（Android 14/15のBroadcastReceiver起因FGS起動制限に
 * 触れないための構造ガード。計画書§7.3「アラームの宛先はBroadcastReceiver」・
 * `NotificationTriggerReceiver.kt`のKDoc「本レシーバはForeground Serviceを起動しない」）。
 * [ShadowApplication]／[org.robolectric.shadows.ShadowContextWrapper]の
 * `peekNextStartedService()`（`startService`/`startForegroundService`のいずれもここに記録される）
 * で検証する（タスク指示どおり`ShadowApplication.getNextStartedService`系のAPIを使用）。
 *
 * 現時点（P5-C2）では[NotificationTriggerReceiver.onReceive]が全体`TODO()`のため、
 * 本テストは`NotImplementedError`によりRedになる（意図した失敗）。
 */
@RunWith(RobolectricTestRunner::class)
class NotificationTriggerReceiverTest {

    private fun context(): Application = RuntimeEnvironment.getApplication()

    // T-NOTIF-10: 回帰ガード - NotificationTriggerReceiverがForeground Serviceを起動しない
    @Test
    fun onReceive_alarmTrigger_neverStartsAForegroundService() {
        val receiver = NotificationTriggerReceiver()
        val triggerIntent = Intent(context(), NotificationTriggerReceiver::class.java)

        receiver.onReceive(context(), triggerIntent)

        assertNull(
            "NotificationTriggerReceiver must never start(ForegroundService)/startForegroundService " +
                "(§95.1, Android 14/15 BroadcastReceiver FGS start restriction)",
            shadowOf(context()).peekNextStartedService()
        )
    }
}
