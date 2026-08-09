package com.actionstarter.services.location

/**
 * F30実装（計画書§5.5・§6.1・S-5裁定）。§95.1「While-in-use制約」を構造的に回避するための
 * ガード契約。`LocationService`実装（[FusedLocationService]）はFusedを呼ぶ前に本ゲートで
 * 事前判定し、偽の場合は[LocationFailureReason.BACKGROUND_RESTRICTED]を返してFusedを
 * 呼ばない（エラー＆レスキューマップ#10）。
 *
 * **判定式（2026-08-09 Fable 5裁定・Gemini G1 CRITICAL対応・修正2、計画書§5.5改訂版）**:
 * 実装（[ActivityLifecycleForegroundGate]）は`isLocationAccessAllowed()`を
 * `isAppInForeground() || isExecutionServiceRunning()`として実装する。前者は
 * `Application.ActivityLifecycleCallbacks`ベースの起動中Activityカウント判定、後者は
 * §95.1(b)「フォアグラウンドで開始したExecution Mode中のForeground Serviceの継続中」を
 * 許可するための条件であり、Phase 5でForeground Serviceが導入されるまで常にfalseを返す
 * 注入フックとする（§15 #10、Phase 5で実配線）。
 */
fun interface ForegroundGate {
    fun isLocationAccessAllowed(): Boolean
}
