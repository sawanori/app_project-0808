package com.actionstarter.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * F20 — instrumented E2E（実カレンダーseed経由、計画書§12／§12.1 Step 0〜7、
 * T-E2E2-1／T-E2E2-3／T-E2E2-4）。
 *
 * **作成のみ・実行しない**（担当プロンプトの明示指示。計画書§10.2末尾「E2E群は実行する
 * までpassとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ）」。
 * [MainUxFlowTest]と同じ制約）。実行はKVM解決後、quality-runnerが§12.1 Step 0〜7の手順で
 * `actionstarter_test`エミュレータへseedしたうえで`:app:connectedDebugAndroidTest`により行う。
 *
 * T-E2E2-2（権限拒否シナリオ）は裁定B14により本クラスとは独立実行させる必要があるため
 * [CalendarPermissionDeniedTest]へ分離してある（同一テストプロセス内で連続実行しない。
 * dumpsysによる権限状態の事前検証手順は[CalendarPermissionDeniedTest]のKDoc参照）。
 *
 * **前提（本クラスの実行前にquality-runnerが§12.1の手順で用意する）**:
 * 1. Step 0（裁定B10・エラーマップ#21）: `adb shell getprop ro.kernel.qemu`が`1`、かつ
 *    `ro.boot.qemu.avd_name`が`actionstarter_test`であることを確認するエミュレータ判定
 *    ガードを通過していること。不成立なら一切の書込を行わず中断する。
 * 2. Step 1〜4: コールドブート後、`account_type=LOCAL`のテストカレンダーへ通常予定／
 *    全日予定／繰り返し予定の3種を投入済みであること。全日予定は裁定B5により候補から
 *    除外されるため（§9除外3）、Upcoming一覧には通常予定・繰り返し予定由来のインスタンス
 *    のみが現れる。
 * 3. Step 5: `content://com.android.calendar/instances/when/<from>/<until>`への
 *    14列projection（M-1）で期待件数が返ることを事前検証済みであること。件数不一致の場合
 *    quality-runnerはテストを実行せず「実行不能」として報告する（推測でGreenと報告しない、
 *    エラーマップ#19）。
 * 4. READ_CALENDAR権限は`adb shell pm grant`で許可済みであること。本クラスは権限操作を
 *    一切行わない（テスト内`@Before`での権限操作は裁定B14により禁止）。
 *
 * **具体的なseedイベントのtitle/location文言には依存しない**（seed文言はquality-runner
 * 裁量のため）。本骨格は[EventSelectionListTest]で確立したtestTag契約
 * （"event_selection_row_<index>" / "event_selection_next_badge"）による構造検証に留める。
 *
 * ロケール切替（T-E2E2-4実装注記、[MainUxFlowTest]のT-E2E-3と同じ制約）: 1回の
 * インスツルメンテーション実行内でシステムロケールを動的切替することは困難なため、
 * quality-runnerがG4-Eでen/jaそれぞれ独立したテストランとして実行しスクリーンショットを
 * 取得する（`adb exec-out screencap`等）。本テストは1ロケール分の到達可能性のみを定義する。
 */
@RunWith(AndroidJUnit4::class)
class CalendarE2ETest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    // T-E2E2-1: 正常系 - seed済みの実カレンダー予定が一覧に表示され、
    // 選択→PlanReview→Execution→Departureが通しで成立する（§66完成条件・GOAL.md D(1)）。
    @Test
    fun tE2e2_1_seededCalendarEvents_selectionToDepartureFlowSucceeds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 「次の予定」バッジと先頭行の存在で、実カレンダーseedデータがUpcoming一覧へ
        // 反映されたことを確認する（testTag契約はEventSelectionListTest参照）。
        composeTestRule.onNodeWithTag("event_selection_next_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("event_selection_row_0").performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        // Fable 5承認済み更新（2026-08-10）: F58多段階遷移結線（ADR-0028）の帰結として
        // execution_done_buttonの1回タップだけではdeparture_titleへ到達しない
        // （BasicPlanningEngineが常にTRANSITION/PREPARATION/DEPARTUREの3ステップを生成するため、
        // G4-E実測。docs/plans/phase5-notification-execution.md§10.11）。ステップ数の
        // 環境依存変化（P4-C8の移動時間統合等）に備え、固定回数ではなくdeparture_title出現まで
        // タップし続ける有界ループヘルパーを使う（ExecutionDoneTapHelper.kt参照。テスト側の
        // 陳腐化でありアプリの欠陥ではないと診断済み）。
        composeTestRule.tapExecutionDoneUntilDepartureTitleShown(context)
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
    }

    // T-E2E2-3: エッジケース - seed済み予定が0件（§12.1 Step 2の冪等cleanup直後、Step 4の
    // イベント投入前の状態で起動した場合）でも空状態が表示されクラッシュしない。
    // 実行注記: quality-runnerは本ケースのみ他のT-E2E2ケースとは別のカレンダー状態
    // （cleanup直後）で起動し直して実行する必要がある。
    @Test
    fun tE2e2_3_zeroSeededEvents_showsEmptyStateWithoutCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_empty_title))
            .assertIsDisplayed()
    }

    // T-E2E2-4: 正常系 - ja/en両ロケールで全画面のスクリーンショットが取得できる
    // （GOAL.md D/E）。実際の2ロケール実行とスクリーンショット取得はG4-Eでquality-runnerが
    // 行う（本クラスKDoc参照）。本テストは1ロケール分の到達可能性のみを検証する。
    @Test
    fun tE2e2_4_allScreensFromCalendarFlow_reachableForScreenshotCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeTestRule.onNodeWithTag("event_selection_row_0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("event_selection_row_0").performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()
        // Fable 5承認済み更新（2026-08-10）: F58多段階遷移結線（ADR-0028）の帰結として
        // execution_done_buttonの1回タップだけではdeparture_titleへ到達しない
        // （BasicPlanningEngineが常にTRANSITION/PREPARATION/DEPARTUREの3ステップを生成するため、
        // G4-E実測。docs/plans/phase5-notification-execution.md§10.11）。ステップ数の
        // 環境依存変化（P4-C8の移動時間統合等）に備え、固定回数ではなくdeparture_title出現まで
        // タップし続ける有界ループヘルパーを使う（ExecutionDoneTapHelper.kt参照。テスト側の
        // 陳腐化でありアプリの欠陥ではないと診断済み）。
        composeTestRule.tapExecutionDoneUntilDepartureTitleShown(context)
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
    }
}
