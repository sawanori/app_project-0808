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
 * T-P4E2E-1〜2（計画書§8.2 F40/F47/F48、`docs/plans/phase4-basic-engine.md`）。
 *
 * **作成のみ・実行しない**（担当プロンプトの明示指示。計画書§8.2末尾「E2E群は実行するまで
 * passとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ。Phase 1・
 * Phase 2の先例踏襲）」。[MainUxFlowTest]・[CalendarE2ETest]と同じ制約）。実行はP4-C7
 * （薄いG4-E）でquality-runnerが`:app:connectedDebugAndroidTest`（AVD: `actionstarter_test`）
 * により行う。
 *
 * ロケール切替（[MainUxFlowTest]のT-E2E-3実装注記と同じ制約）: 1回のインスツルメンテーション
 * 実行内でシステムロケールを動的切替することは困難なため、quality-runnerがG4-Eでen/ja
 * それぞれ独立したテストランとして実行しスクリーンショットを取得する
 * （`adb exec-out screencap`等）。本テストは1ロケール分の到達可能性のみを定義する。
 *
 * T-P4E2E-2（Departure画面でTRAVELステップありのPlanのETA表示を確認する）は、実機／
 * エミュレータ上でTRAVELステップを含むPlanが生成される具体的な入力（seedするカレンダー
 * イベントの位置情報・Routing応答）に依存する。その具体的なseed手順・確認観点は
 * quality-runnerがG4-E実行時に確定する（[CalendarE2ETest]のKDocが定めるseed前提と同種の
 * 切り分け）。本骨格はDeparture画面への到達可能性とETA領域の表示要素の存在のみを定義する。
 */
@RunWith(AndroidJUnit4::class)
class BasicPlanE2ETest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    // T-P4E2E-1: 正常系 - PlanReview画面の実時刻表示をja/en両ロケールでスクリーンショット取得する
    // （実際の2ロケール実行とスクリーンショット取得はG4-Eでquality-runnerが行う。本テストは
    // 1ロケール分の到達可能性のみを検証する）。
    @Test
    fun tP4E2e1_planReviewScreen_reachableWithScheduledTimeElementsForScreenshotCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()
        // 各ステップ行の時刻表示要素の存在確認はtestTag "plan_review_step_time_text"
        // （com.actionstarter.features.PlanReviewStepDisplayTestが定める契約）をG4-E実行時に
        // quality-runnerが確認する。
    }

    // T-P4E2E-2: 正常系 - Departure画面でTRAVELステップありのPlanのETA表示を確認する
    // （TRAVELステップを含むPlanを生成する具体的なseedデータの用意はquality-runnerがG4-Eで
    // 行う。本テストはDeparture画面への到達可能性とETA表示ラベルの存在のみを定義する）。
    @Test
    fun tP4E2e2_departureScreen_reachableWithEstimatedArrivalLabelForEtaVerification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_estimated_arrival_label)).assertIsDisplayed()
    }
}
