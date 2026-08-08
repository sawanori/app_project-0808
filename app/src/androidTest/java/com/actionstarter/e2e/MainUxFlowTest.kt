package com.actionstarter.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.actionstarter.MainActivity
import com.actionstarter.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E — 主要UX一気通貫フロー（計画書§11.2「E2E」節、T-E2E-1〜3）。
 *
 * **作成のみ・実行しない**（担当プロンプトの明示指示。計画書§11末尾の注記「E2E群は実行する
 * までpassとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ）」）。
 * 実行はKVM解決後、`:app:connectedDebugAndroidTest`（AVD: `actionstarter_test`）でのみ行う。
 *
 * 既知の前提ギャップ（C3b完了報告を参照）: 本ファイル作成時点で`app/build.gradle.kts`の
 * `androidTest`ソースセットには`androidTestImplementation`依存関係が一切設定されていない
 * （JUnit4／Compose UI Test／androidx.testのいずれも未解決）。担当スコープは
 * `strings.xml`のみへの変更に限定されており（本タスクの指示）、`build.gradle.kts`は
 * `docs/TEAMS.md`§5により共有ファイルとしてdomain-implementer所有と定められているため、
 * 本ファイルでは依存関係を追加していない。したがって現時点では
 * `:app:compileDebugAndroidTestKotlin`は本ファイル由来ではなく依存未解決により失敗しうる
 * （C3b完了報告に実測ログを記載）。androidTestImplementation側の依存追加は
 * domain-implementer／Fable 5への申し送り事項とする。
 *
 * ロケール切替（T-E2E-3実装注記、A4・計画書§11）:
 * 1回のインスツルメンテーション実行内でシステムロケールを動的切替することは困難なため、
 * 以下のいずれかの方式でen/jaそれぞれ独立して実行し、2回に分けてスクリーンショットを
 * 取得する（quality-runnerがG4-Eで選択・実施する）。
 *   (a) Instrumentation Runner引数を用いてロケール別にテストランを分ける方式
 *   (b) per-app locale API（`androidx.appcompat.app.AppCompatDelegate.setApplicationLocales()`）を
 *       各テスト実行前に明示的に設定してからロケールごとに実行する方式
 * AppCompatはPhase 1の依存関係（`gradle/libs.versions.toml`）に含まれていないため、
 * 本ファイルでは(b)のAPI呼び出しを実装せずコメントに留める（未解決参照を避けるため）。
 * ロケール別スクリーンショット取得自体は`adb exec-out screencap`等でquality-runnerが
 * G4-E実行時に行う（`docs/TEAMS.md`§2チーム表#7）。
 */
@RunWith(AndroidJUnit4::class)
class MainUxFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    // T-E2E-1: 正常系 - 主要UXが一気通貫で動作する（Selection→Review→Execution→Departure）
    @Test
    fun tE2e1_mainUxFlow_selectionToReviewToExecutionToDeparture_endToEnd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
    }

    // T-E2E-2: エッジケース - Recovery割込からの復帰が成立する
    @Test
    fun tE2e2_recoveryInterrupt_resumesExecutionAfterUseThisPlan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        // BuildConfig.DEBUGガード付き「Simulate delay (debug)」ボタン（計画書§10.4、U4）でRecovery割込を発生させる
        composeTestRule.onNodeWithText(context.getString(R.string.execution_simulate_delay_debug_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_title)).assertIsDisplayed()

        composeTestRule.onNodeWithText(context.getString(R.string.recovery_use_this_plan_button)).performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()
    }

    // T-E2E-3: 正常系 - ja/en両ロケールで全画面のスクリーンショットが取得できる
    // ロケール切替方針は本クラスKDoc参照。本テストは1ロケール分のスモーク経路のみを
    // 定義し、実際の2ロケール実行とスクリーンショット取得はG4-Eでquality-runnerが行う。
    @Test
    fun tE2e3_allFiveScreens_reachableForScreenshotCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
    }
}
