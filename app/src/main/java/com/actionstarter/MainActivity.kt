package com.actionstarter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.actionstarter.navigation.ActionStarterNavHost

/**
 * App entry point (real 5-screen navigation graph wired in C5). [MainScreen] below is the
 * original C1 bootstrap composable, kept unused by [onCreate] but still directly exercised by
 * `SmokeComposeTest` (must not be removed/changed — see that test's own scope).
 *
 * **通知タップ結線（F60、仕様§9エラーマップ#17/#18、P5-C6統合ウィンドウ）**: `AndroidManifest.xml`
 * で`android:launchMode="singleTop"`を宣言済みのため、アプリが既に起動中のとき通知タップは
 * 新規`onCreate`ではなく[onNewIntent]へ配送される。`services/notification/
 * AndroidNotificationService`がPendingIntentのextra（キー`"route"`。[ROUTE_EXTRA_KEY]で
 * 共有する契約）へ格納したルート文字列を[pendingNotificationRoute]（Compose Stateとして
 * 観測可能なActivity所有の状態）へ反映する。[ActionStarterNavHost]はこの値を
 * `pendingNotificationRoute`パラメータとして受け取り、消費後は
 * `onPendingNotificationRouteConsumed`コールバックでnullへ戻す（同一Intentでの
 * 二重ナビゲーション・再コンポーズ時の再発火を防ぐ）。信頼境界: `"route"` extraを検証なしに
 * 信頼しない——未知routeの扱いは[ActionStarterNavHost]側の責務（エラー&レスキューマップ#18、
 * 既定のExecutionへフォールバックしT-NAV-4ガードへ合流する）。
 */
class MainActivity : ComponentActivity() {

    private var pendingNotificationRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNotificationRoute = routeFromIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ActionStarterNavHost(
                        pendingNotificationRoute = pendingNotificationRoute,
                        onPendingNotificationRouteConsumed = { pendingNotificationRoute = null }
                    )
                }
            }
        }
    }

    /**
     * `singleTop`起動モード（エラー&レスキューマップ#17）により、アプリが既に起動中のとき
     * 通知タップは新規`onCreate`ではなく本メソッドへ配送される。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNotificationRoute = routeFromIntent(intent)
    }

    /**
     * 信頼境界（エラー&レスキューマップ#18）: Intent extraを検証なしに信頼しない。
     * `"route"`キーが存在しない通常起動・バックグラウンド復帰では`null`を返し、
     * [ActionStarterNavHost]側は何もしない。
     */
    private fun routeFromIntent(intent: Intent?): String? = intent?.getStringExtra(ROUTE_EXTRA_KEY)

    private companion object {
        /** `services/notification/AndroidNotificationService`と共有するIntent extraキー。 */
        const val ROUTE_EXTRA_KEY = "route"
    }
}

@Composable
fun MainScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = stringResource(id = R.string.app_name))
                Text(text = stringResource(id = R.string.hello_smoke))
            }
        }
    }
}
