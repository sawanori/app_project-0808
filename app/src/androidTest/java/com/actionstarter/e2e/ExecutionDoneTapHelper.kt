package com.actionstarter.e2e

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.actionstarter.R

/**
 * G4-Efix（Fable 5承認済み更新、2026-08-10）— [BasicPlanE2ETest.tP4E2e2_departureScreen_reachableWithEstimatedArrivalLabelForEtaVerification]・
 * [CalendarE2ETest.tE2e2_1_seededCalendarEvents_selectionToDepartureFlowSucceeds]・
 * [CalendarE2ETest.tE2e2_4_allScreensFromCalendarFlow_reachableForScreenshotCapture]・
 * [RoutingLocationE2ETest.tE2e3_2_locationPermissionDenied_continuesWithManualTravelTimeInput]
 * （共有ヘルパー`navigateToDeparture`経由）の4テストで共用するExecution→Departure前進ヘルパー。
 *
 * ## 背景（`docs/plans/phase5-notification-execution.md`§10.11 G4-E実測）
 * 上記4テストは、F58（One Action多段階遷移。`ExecutionViewModel`が`execution_done_button`
 * タップのたびに`currentStepIndex`を1つずつ進めるのみで、直接departureへは遷移しない設計）が
 * 本番結線される前に書かれたまま更新されておらず、「Doneを1回タップすれば`departure_title`へ
 * 到達する」という古い前提を持っていた。G4-E実測により、現行本番実装は
 * `PlanReviewViewModel.buildPlanningContext`が`travelEstimate = null`固定のため
 * `BasicPlanningEngine`が常にTRANSITION／PREPARATION／DEPARTUREの3ステップを生成し、Doneを
 * **3回**タップしないとDeparture画面へ到達しないことが判明した（手動UI操作でも同一seed・
 * 同一手順で3回のDoneタップが必要なことを目視確認済み）。quality-runnerはこれをアプリの欠陥では
 * なくテスト側の陳腐化と診断し（アプリ本体は仕様どおり）、Fable 5が本ファイルによる修正方式を
 * 承認した。
 *
 * ## `repeat(3)`ではなく有界ループを採用する理由
 * [NotificationExecutionE2ETest]のtP5E2e2は`repeat(3)`で対応済みだが、これは同テストが
 * 専用seed・単独実行という決定的なfixtureに依存しているため妥当である（同ファイルKDoc参照。
 * 本修正では変更しない）。一方、本ヘルパーが対象とする4テストは近くP4-C8で移動時間
 * （TRAVELステップ）統合が入るとステップ数が3または4に環境依存で変わる見込みがあり、
 * 固定回数のままでは統合のたびに再び陳腐化する。そのため「`departure_title`が現れるまで
 * `execution_done_button`をタップし続ける」という、本番のステップ数そのものに依存しない
 * 形にする（Fable 5裁定）。
 *
 * 上限[MAX_EXECUTION_DONE_TAPS]（6回）は、想定される最大ステップ数（3〜4段階）に対して
 * 十分な余裕を持たせた安全弁であり、万一Executionフローが壊れて`departure_title`へ
 * 永久に到達しない場合でも無限ループには陥らない。ループを抜けた後も`departure_title`が
 * 現れていなければ、呼び出し側が既存どおり行う`assertIsDisplayed()`がそのまま失敗し、
 * 明確なCompose assertion失敗として報告される（本ヘルパー自体は成否を判定しない）。
 */
internal fun ComposeTestRule.tapExecutionDoneUntilDepartureTitleShown(context: Context) {
    val doneButtonLabel = context.getString(R.string.execution_done_button)
    val departureTitleLabel = context.getString(R.string.departure_title)

    repeat(MAX_EXECUTION_DONE_TAPS) {
        if (onAllNodesWithText(departureTitleLabel).fetchSemanticsNodes().isNotEmpty()) {
            return
        }
        onNodeWithText(doneButtonLabel).performClick()
        // 1タップごとにCompose側の再コンポーズ（次ステップ表示、またはdepartureへの遷移）が
        // 完了するまでポーリングする。次ステップも最終ステップもいずれかの文言が現れることが
        // 「UIが安定した」ことの目印になる。
        waitUntil(timeoutMillis = EXECUTION_STEP_ADVANCE_TIMEOUT_MS) {
            onAllNodesWithText(departureTitleLabel).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText(doneButtonLabel).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

/** 想定最大ステップ数（3〜4段階）に十分な余裕を持たせた有界ループの上限（Fable 5裁定）。 */
private const val MAX_EXECUTION_DONE_TAPS = 6

/** 1タップごとのCompose再コンポーズ待機タイムアウト。 */
private const val EXECUTION_STEP_ADVANCE_TIMEOUT_MS = 10_000L
