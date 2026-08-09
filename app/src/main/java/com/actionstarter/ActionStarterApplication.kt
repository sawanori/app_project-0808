package com.actionstarter

import android.app.Application
import com.actionstarter.di.AppContainer

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
 */
class ActionStarterApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(applicationContext)
    }
}
