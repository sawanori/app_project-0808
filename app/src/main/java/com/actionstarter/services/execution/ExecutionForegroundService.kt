package com.actionstarter.services.execution

import android.app.Service
import android.content.Intent
import android.os.IBinder

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = TODO()
}
