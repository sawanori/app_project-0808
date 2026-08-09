package com.actionstarter.e2e

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
 * F70〜F77 — Recovery Basic instrumented E2E（`docs/plans/phase6-recovery-basic.md`
 * §8.8「E2E」T-P6E2E-1〜2、担当プロンプトの明示指示）。
 *
 * **作成のみ・実行しない**（担当プロンプトの明示指示。計画書§8.8末尾「E2E群は実行するまで
 * passとして報告することを禁止し、G2／G3の証拠には含めない（実行はG4-Eのみ。Phase 1・
 * Phase 2・Phase 4の先例踏襲）」）。実行はP6-C7（「薄いG4-E」、計画書§10.1）で
 * quality-runnerが`:app:connectedDebugAndroidTest`（AVD: `actionstarter_test`）により行う。
 * [CalendarE2ETest]／[NotificationExecutionE2ETest]と同じ制約下にある。
 *
 * ## 本ファイル作成時点でのPhase 6統合状況（Read確認済み。並行agentが統合作業中）
 * 本クラスはPhase 6の**完成後**の期待挙動（計画書§8.8の定義どおり）に対して書く
 * （担当プロンプトの明示指示）。並行agentの統合は本タスク実行中も進行し続けており、本ファイル
 * 作成中に観測した状態は複数回変化した（コンパイル確認直前の最終Read確認、時刻順）:
 * - `AppContainer.kt:138`の`recoveryEngine`は**`BasicRecoveryEngine(routingService)`へ
 *   差替済み**（統合作業の途中で`MockRecoveryFactory()`から切り替わったことを確認。
 *   計画書§6.3統合ウィンドウ・P6-C5の対象）。
 * - 一方`ActionStarterNavHost.kt`の`composable(Destinations.Recovery.route)`ブロックは
 *   **本ファイル作成完了時点でもなお**`RecoveryScreen`へ`onUseThisPlan`を渡していない
 *   （`grep -n "onUseThisPlan" ActionStarterNavHost.kt`が0件。`RecoveryScreen`の
 *   `onUseThisPlan: (UUID?) -> Unit = {}`の既定no-opのまま）ため、「Use this plan」タップは
 *   `RecoveryViewModel.useThisPlan`を呼ばず、`onNavigateToExecution`（`popBackStack()`）のみが
 *   実行される。
 *
 * 統合は並行agentにより進行中の可変な状態であり、本テストが実際にG4-Eで実行される時点では
 * 上記いずれも解消されている可能性が高い。本タスクの指示（「両計画書の変更禁止」「Phase 6統合は
 * 並行agentが実施中」）によりここでは手を加えず、計画書§8.8の期待どおりの完成後挙動に対して
 * 書く（担当プロンプトの明示指示）。統合完了後にG4-Eで本ファイルを実行することが前提。
 *
 * ## Recovery到達経路（Phase 1由来のデバッグ導線、変更なし）
 * Recovery割込は本番のlateness detection（`LatenessDetector.evaluate`）ではなく、
 * `BuildConfig.DEBUG`ガード付き「Simulate delay (debug)」ボタン
 * （`execution_simulate_delay_debug_button`、`ExecutionScreen.kt:102-106`、U4/計画書§10.4）
 * から到達する。`LatenessDetector.evaluate()`のexecution route内呼び出しは
 * プレースホルダコメントのみで未配線（`ActionStarterNavHost.kt:245-249`、P6-C5が実配線予定）
 * なので、本テストは計画書§8.8の文言（「Simulate delay → Recovery画面到達」）どおりこの
 * デバッグボタン経由の到達を検証する。`:app:connectedDebugAndroidTest`は常にdebugビルド
 * バリアントを使うため、`BuildConfig.DEBUG`は常にtrueで本ボタンは描画される
 * （[NavigationFlowTest][com.actionstarter.navigation.NavigationFlowTest]のT-NAV-3・
 * [MainUxFlowTest]のtE2e2と同じ前提）。
 *
 * ## 実行前提条件
 * 1. Step 0（裁定B10、`docs/plans/phase2-calendar.md`§12.1）: エミュレータ判定ガードを
 *    通過していること。
 * 2. [CalendarE2ETest]と同じ「次の予定」seed（`event_selection_row_0`が表示される状態）が
 *    投入済みであること。本クラスの2テストは近未来タイミングを要さない
 *    （lateness detectionではなくデバッグボタン起点のため）ので、他のE2Eクラスと同一
 *    セッション内で実行してよい。
 * 3. READ_CALENDAR権限が許可済みであること（[CalendarE2ETest]と共通）。
 *
 * ## testTag／文字列リソースの実在確認（grep実施済み）
 * `recovery_title`・`recovery_use_this_plan_button`・`recovery_option_eta_label`・
 * `execution_simulate_delay_debug_button`・`execution_now_label`はいずれも`values/strings.xml`に
 * 実在確認済み。`recovery_option_item_<id>`テストタグは`RecoveryScreen.kt:101`で実装済み
 * （`recovery_option_eta_<id>`は同`:115`、`estimatedArrival != null`の候補のみ描画）。
 * いずれも既存の実装済みUI要素であり、本ファイルに未実装要素への依存はない
 * （[NotificationExecutionE2ETest]のtP5E2e3とは異なり、predicted testTagは使用していない）。
 *
 * ## ja/enロケール
 * [CalendarE2ETest]のT-E2E2-4等と同じ制約により、1回のインスツルメンテーション実行内での
 * ロケール動的切替は行わない。T-P6E2E-1が要求するja/enスクリーンショット取得は
 * quality-runnerがG4-Eでen/jaそれぞれ独立したテストランとして実行し取得する
 * （`adb exec-out screencap`等）。本テストは1ロケール分の到達可能性＋候補ETA表示のみを検証する。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryBasicE2ETest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    /** `"recovery_option_item_<id>"`（`RecoveryScreen.kt:101`）のprefixでRecovery候補行を拾う。 */
    private val recoveryOptionRowMatcher = SemanticsMatcher(
        "testTag starts with \"$RECOVERY_OPTION_ROW_TEST_TAG_PREFIX\""
    ) { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(RECOVERY_OPTION_ROW_TEST_TAG_PREFIX) == true
    }

    /** 「次の予定」→Start→Simulate delayでRecovery画面へ到達する（クラスKDoc参照）。 */
    private fun navigateToRecoveryViaSimulateDelay(context: Context) {
        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_simulate_delay_debug_button))
            .performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_title)).assertIsDisplayed()
    }

    /**
     * `RecoveryViewModel.init`は`viewModelScope.launch`内で`recoveryEngine.createRecoveryPlan`
     * （suspend）を呼ぶ非同期経路（`RecoveryViewModel.kt:72-87`）のため、候補が
     * `RecoveryUiState.options`へ反映されるまで即時ではない可能性がある。`waitUntil`で
     * ポーリングし、同期的な空チェックによる意図しないflakyを避ける。
     */
    private fun waitForAtLeastOneRecoveryOption() {
        composeTestRule.waitUntil(timeoutMillis = RECOVERY_OPTIONS_WAIT_TIMEOUT_MS) {
            composeTestRule.onAllNodes(recoveryOptionRowMatcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // T-P6E2E-1: 正常系（計画書§8.8） - Simulate delay → Recovery画面到達、候補1件以上、
    // 各候補にETA表示（§70完成条件）。ja/enスクリーンショット取得はクラスKDoc「ja/enロケール」
    // 参照、G4-Eでquality-runnerが行う。
    @Test
    fun tP6E2e1_simulateDelay_reachesRecoveryWithAtLeastOneOptionShowingEta() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        navigateToRecoveryViaSimulateDelay(context)
        waitForAtLeastOneRecoveryOption()

        // 候補1件以上（recovery_option_item_<id>行の存在で確認、waitForAtLeastOneRecoveryOption
        // が既にpollingしているため再取得のみ）。
        val optionRowCount = composeTestRule.onAllNodes(recoveryOptionRowMatcher).fetchSemanticsNodes().size
        check(optionRowCount >= 1) {
            "Expected at least one recovery_option_item_<id> row after Simulate delay, found $optionRowCount."
        }

        // 各候補にETA表示（recovery_option_eta_label、RecoveryScreen.kt:112、
        // estimatedArrival != nullの候補のみ描画される。T-RECUI-8/§32）。
        val etaLabel = context.getString(R.string.recovery_option_eta_label)
        composeTestRule.onAllNodesWithText(etaLabel, substring = true).onFirst().assertIsDisplayed()
    }

    // T-P6E2E-2: エッジケース（計画書§8.8） - 「Use this plan」→ Execution復帰、
    // Recoveryへ再ループしない。
    //
    // 注記（クラスKDoc「本ファイル作成時点でのPhase 6統合状況」参照）:
    // 作成時点でNavHostは`onUseThisPlan`を`RecoveryViewModel.useThisPlan`へ結線していない
    // ため、本テストは統合完了後の期待挙動を検証する。「Recoveryへ再ループしない」側は
    // LatenessDetectorのexecution route配線（P6-C5）が完了しても、
    // `RecoveryViewModel.isPlanApplied`のone-shotガード（T-RECVM-8）が正しく機能する限り
    // 成立し続けるべき回帰ガードである。
    @Test
    fun tP6E2e2_useThisPlan_returnsToExecutionWithoutLoopingBackToRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        navigateToRecoveryViaSimulateDelay(context)
        waitForAtLeastOneRecoveryOption()

        // 候補を1件選択してから「Use this plan」をタップする（未選択のままだと
        // RecoveryViewModel.useThisPlanがno-opになる、T-RECVM-7）。
        composeTestRule.onAllNodes(recoveryOptionRowMatcher).onFirst().performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_use_this_plan_button)).performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_title)).assertDoesNotExist()
    }

    private companion object {
        private const val RECOVERY_OPTIONS_WAIT_TIMEOUT_MS = 15_000L
        private const val RECOVERY_OPTION_ROW_TEST_TAG_PREFIX = "recovery_option_item_"
    }
}
