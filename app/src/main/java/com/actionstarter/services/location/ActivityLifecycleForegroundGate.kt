package com.actionstarter.services.location

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ForegroundGate]のAndroid実装（F30、計画書§5.5・§6.1・S-5裁定）。
 * `Application.ActivityLifecycleCallbacks`で起動中Activity数をカウントし、
 * `isAppInForeground()`（カウント > 0）を判定する。新規依存ゼロ（S-5の推奨根拠どおり）。
 *
 * **P3-C1（本サイクル）で完全実装する理由（[UnconfiguredRoutingService]と同様の
 * トリビアル実装扱い・TDD例外の対象外）**: 本クラスは`ActionStarterApplication.onCreate()`
 * （共有ファイル#6、計画書§6.4行6）で`registerActivityLifecycleCallbacks`により**全画面へ
 * 無条件登録**される。もし本クラスのライフサイクルコールバック（[onActivityStarted]／
 * [onActivityStopped]）が`TODO()`のまま登録されると、Robolectric／Compose Testが
 * Activityを起動するすべての既存テスト（`SmokeComposeTest`等）で`NotImplementedError`が
 * 送出され、回帰なし要件（P3-C1完了条件）を構造的に満たせなくなる。カウンタ加減算のみの
 * 単純なロジックであり、[UnconfiguredRoutingService]（常時例外送出の1行）と同様に
 * 分岐・判断を持たないため、TDD Red-Firstを経ずに実装しても「テストを通すための
 * 恣意的な実装」のリスクが実質的にない。`isLocationAccessAllowed()`の網羅的な検証
 * （T-LOCSVC-7相当）は[FusedLocationService]経由でP3-C3にてfakeの[ForegroundGate]を
 * 用いて行う。
 *
 * `Application.ActivityLifecycleCallbacks`の7メソッドは意図的に全て明示オーバーライドする
 * （一部のみオーバーライドしてデフォルト実装に委ねる書き方は、当該デフォルト実装が
 * 導入されたAPIレベルより古い実機（本プロジェクトのminSdk 26）でAbstractMethodErrorを
 * 起こすリスクがあるため避ける）。
 */
class ActivityLifecycleForegroundGate : ForegroundGate, Application.ActivityLifecycleCallbacks {

    private val startedActivityCount = AtomicInteger(0)

    /**
     * §95.1(b)「フォアグラウンドで開始したExecution Mode中のForeground Serviceの継続中」を
     * 許可するための注入フック。Phase 5でForeground Serviceが実装されるまでは常にfalseを
     * 返す（§15 #10、Phase 5で実配線）。
     */
    var isExecutionServiceRunning: () -> Boolean = { false }

    fun isAppInForeground(): Boolean = startedActivityCount.get() > 0

    override fun isLocationAccessAllowed(): Boolean =
        isAppInForeground() || isExecutionServiceRunning()

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount.decrementAndGet()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
