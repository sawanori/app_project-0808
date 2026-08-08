package com.actionstarter.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * F9 — Navigation（計画書§11.2、T-NAV-1〜5）。
 *
 * ActionStarterNavHostは現時点（C2b契約scaffold）で本文が空実装（TODO）であり、
 * NavController生成・composable{}によるルート配線が一切ないため、以下のテストは
 * いずれも「最初に期待する画面要素が見つからない」ことで失敗するのが正しい
 * （意図したRed。計画書§15 C3行、担当プロンプト「NavHost未配線のため失敗するのが正しい」）。
 *
 * ActionStarterNavHost()はNavControllerを外部へ公開しないため、本テストは実際の
 * ComponentActivityへホストして（戻る操作の検証のためcreateAndroidComposeRuleを使用）、
 * 画面遷移に伴って表示されるはずのUI文言の出現／消失のみで各シナリオを検証する。
 */
@RunWith(AndroidJUnit4::class)
class NavigationFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // T-NAV-1: 正常系 - Selection→Review→Execution→Departureの一連の遷移が通しで成立する
    @Test
    fun tNav1_selectionToReviewToExecutionToDeparture_fullFlowSucceeds() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
    }

    // T-NAV-2: 正常系 - 戻る操作（back）が各画面で妥当に動作する
    @Test
    fun tNav2_backPress_returnsToPreviousScreen() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()

        composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).assertIsDisplayed()
    }

    // T-NAV-3: 正常系 - recoveryから「Use this plan」でexecutionへ戻る
    @Test
    fun tNav3_recoveryUseThisPlan_returnsToExecution() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        // BuildConfig.DEBUGガード付き「Simulate delay (debug)」ボタン（計画書§10.4、U4）でRecovery割込を発生させる
        composeTestRule.onNodeWithText(context.getString(R.string.execution_simulate_delay_debug_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_title)).assertIsDisplayed()

        composeTestRule.onNodeWithText(context.getString(R.string.recovery_use_this_plan_button)).performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()
    }

    // T-NAV-4: 異常系（前提検証） - Planが未確定のままexecutionへ到達しようとした場合、
    // popUpToでeventSelectionへ戻しSnackbarで通知する。
    // ActionStarterNavHost()はNavControllerを外部へ公開しないため、本テストは
    // 「起動直後はeventSelection画面の要素が表示され、Execution画面固有の要素（NOWラベル）
    // は現れない」というガード結果の整合性を検証する（前提検証）。
    @Test
    fun tNav4_unconfirmedPlan_guardKeepsUserOnEventSelection() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertDoesNotExist()
    }

    // T-NAV-5: エッジケース - プロセス再生成後もdestinationが復元される
    // recreate()は素のComponentActivityホストでは再setContent経路がなく構造的に失敗するため、
    // Fable 5裁定（2026-08-08）によりStateRestorationTester方式へ修繕。
    // ケース意図は計画書§11.2 T-NAV-5のまま（プロセス再生成後もdestinationが復元されることの検証）。
    @Test
    fun tNav5_processRecreation_restoresDestination() {
        val context = RuntimeEnvironment.getApplication()
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent { ActionStarterNavHost() }

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()
    }
}
