package com.actionstarter

import android.app.Application
import com.actionstarter.di.AppContainer
import com.actionstarter.services.location.ActivityLifecycleForegroundGate

/**
 * アプリ全体の`Application`クラス（計画書§8）。手動DI（§7.3、ADR-0003）の起点として
 * [AppContainer]を1個生成・保持する。`ActionStarterNavHost`は
 * `LocalContext.current.applicationContext`経由で本クラスから[appContainer]を取得する。
 *
 * `AndroidManifest.xml`の`<application android:name=".ActionStarterApplication">`で
 * 本クラスを指定する必要がある（Robolectricもマニフェストからこの指定を読み取り、
 * `src/test`実行時にも本クラスが使われる）。
 *
 * [AppContainer]は`applicationContext`を要求する（統合サイクルでの実結線、計画書§14
 * P2-C6／旧P2-C5行）。`CalendarProviderCalendarService`／`AndroidPermissionGate`が
 * `ContentResolver`／権限照会を解決するために使う。
 *
 * **Phase 3 P3-C1（計画書§6.4行6・F30・S-5裁定）**: [foregroundGate]
 * （[ActivityLifecycleForegroundGate]）を`registerActivityLifecycleCallbacks`で登録する。
 * `AppContainer`への配線（`locationService`への注入）はP3-C6で行う（§6.4行4）。
 * `foregroundGate`自体はカウンタ加減算のみのトリビアルな実装のため本サイクルで
 * 完成済み（[ActivityLifecycleForegroundGate]のKDoc参照。全画面へ無条件登録される
 * ため、コールバックが未実装のままでは既存の全Compose/Robolectricテストを壊す）。
 */
class ActionStarterApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    val foregroundGate: ActivityLifecycleForegroundGate = ActivityLifecycleForegroundGate()

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(applicationContext)
        registerActivityLifecycleCallbacks(foregroundGate)
    }
}
