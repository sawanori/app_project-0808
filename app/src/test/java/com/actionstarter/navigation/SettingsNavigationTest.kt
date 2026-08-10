package com.actionstarter.navigation

import android.Manifest
import android.content.pm.ProviderInfo
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * P7-C6（計画書§7.2フットプリント「Settings route 1本追加」、R-8「既存T-NAV-*が壊れないことの
 * 再実測を必須にする」）。EventSelection画面からSettings画面への導線とその往復のみを対象とする
 * 新規テスト（既存[NavigationFlowTest]は無変更のまま再実行して回帰確認する。詳細は
 * [NavigationFlowTest]のKDoc「§15(e)解消」参照）。
 *
 * [NavigationFlowTest.FakeCalendarContentProvider]をそのまま再利用する
 * （同クラスのKDoc「`private`にしない理由」に明記のとおり、`NavigationFlowTest.
 * FakeCalendarContentProvider`経由での外部参照が設計意図）。
 *
 * Settings導線自体は`EventSelectionScreen`ではなく`ActionStarterNavHost`の
 * `EventSelectionRoute`が保持する（§10.6疎結合規約に沿い、ナビゲーション関心を画面
 * Composable自身へ持ち込まない設計判断。詳細は`ActionStarterNavHost.kt`のKDoc参照）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUpCalendarEnvironment() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CALENDAR)

        val providerInfo = ProviderInfo().apply { authority = CalendarContract.AUTHORITY }
        Robolectric.buildContentProvider(NavigationFlowTest.FakeCalendarContentProvider::class.java).create(providerInfo)
    }

    private fun waitForEventSelectionContent() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(context.getString(R.string.event_selection_prepare_button))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    // 正常系 - EventSelectionの設定導線タップでSettings画面（AIトグル）が表示される。
    @Test
    fun openSettingsFromEventSelection_navigatesToSettingsScreen() {
        composeTestRule.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()

        composeTestRule.onNodeWithTag("event_selection_open_settings_button").performClick()

        composeTestRule.onNodeWithTag("settings_ai_toggle").assertIsDisplayed()
    }

    // 正常系 - Settings画面の戻る操作でEventSelectionへ復帰する（既存導線を壊さない、R-8）。
    @Test
    fun navigatingBackFromSettings_returnsToEventSelection() {
        composeTestRule.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("event_selection_open_settings_button").performClick()
        composeTestRule.onNodeWithTag("settings_back_button").performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).assertIsDisplayed()
    }
}
