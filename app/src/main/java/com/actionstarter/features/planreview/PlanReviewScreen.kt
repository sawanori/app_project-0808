package com.actionstarter.features.planreview

import androidx.compose.runtime.Composable

/**
 * 仕様§26・§35 Screen2準拠（Your plan画面）。AIが勝手に確定しない（画面表示だけでは
 * 自動的にexecutionへ遷移しない。T-PLAN-2）。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。[onNavigateToExecution]は「Start」タップ相当の遷移。
 *
 * 契約scaffold（C2）時点では本文は最小限のプレースホルダ（未実装）とする。
 * 描画ロジック（T-PLAN-1〜6が要求する表示要素）はC4でui-implementerが実装する。
 */
@Composable
fun PlanReviewScreen(
    uiState: PlanReviewUiState,
    onNavigateToExecution: () -> Unit
) {
    // C4で実装する（TODO）。
}
