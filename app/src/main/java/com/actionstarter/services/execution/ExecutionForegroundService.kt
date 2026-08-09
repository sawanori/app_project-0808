package com.actionstarter.services.execution

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.actionstarter.R

/**
 * F56実装（計画書§6.1・§7.4）。Execution画面フォアグラウンドから起動される
 * Foreground Service。`android:foregroundServiceType="location"`単独宣言（S-3裁定。
 * Manifest宣言はP5-C6統合ウィンドウで追加、本サイクルでは対象外）。
 * `ServiceCompat.startForeground(this, id, notification, FOREGROUND_SERVICE_TYPE_LOCATION)`
 * を呼ぶ（実測: P5-C1 probe P5-P6、`Robolectric.buildService()`＋`ServiceCompat.
 * startForeground`＋実`Service.getForegroundServiceType()`の往復で正しくtypeが観測できる
 * ことを確認済み。計画書§10.2参照）。起動失敗時はbest-effort通知へ切替える
 * （エラー&レスキューマップ#6）。
 *
 * バインドしない（[onBind]は常に`null`。開始のみのService。分岐・判断を持たない
 * Android API規約上のボイラープレートであり、[UnconfiguredRoutingService]と同型の
 * トリビアル実装として直接記述する）。
 *
 * 契約scaffold（P5-C1、TDD例外）時点では[onStartCommand]は宣言のみであり、実装本体は
 * P5-C3で行う。
 */
class ExecutionForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * P5-C3実装。`FOREGROUND_SERVICE_TYPE_LOCATION`を明示的に渡してforegroundへ昇格する
     * （実測: P5-P2「`type=NONE`は無条件禁止」、`build/agent-logs/p5-probes-device.md`。
     * `0`/`FOREGROUND_SERVICE_TYPE_NONE`を渡すコードパスは作らない）。
     *
     * 起動失敗（`SecurityException`／`InvalidForegroundServiceTypeException`等、P5-P2実測。
     * テストは[RuntimeException]を注入するため`Exception`全体をcatchする）時は握り潰さず、
     * best-effort（非foreground）の通知へ切替えたうえで自己停止する（エラー&レスキュー
     * マップ#6。[ExecutionServiceController]側の[com.actionstarter.services.notification.
     * DegradationReason.FOREGROUND_SERVICE_UNAVAILABLE]写像はController側の権限事前チェック
     * （T-FGS-6）が主経路であり、本catchはOS側の予期しない拒否に対する最終防御である）。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.execution_now_label))
                .build()
        )
        val notification = buildNotification()

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (startForegroundRejected: Exception) {
            manager.notify(NOTIFICATION_ID, notification)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /** foreground昇格失敗時も`stopForeground`の呼び先が要る通知本体はここで共通化する。 */
    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.execution_now_label))
            .setContentText(getString(R.string.location_permission_rationale_message))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    /**
     * T-FGS-2: 停止時は明示的に`stopForeground`する。`Service.onDestroy()`は自動では
     * foreground通知を除去しないため（[ServiceCompat.stopForeground]を呼ばない限り
     * `ShadowService.isForegroundStopped`はfalseのまま）、本オーバーライドが必須。
     */
    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "channel_execution_foreground"
        internal const val NOTIFICATION_ID = 9001
    }
}
