package com.actionstarter.features.execution

import androidx.compose.runtime.Composable

/**
 * 仕様§27-28・§35 Screen3準拠（NOW画面）。プロダクト最重要UI。
 * ONE ACTION ONLY原則（仕様§28）。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。
 * - [onNavigateToDeparture]：最終ステップDone相当の遷移（T-EXEC-4）。
 * - [onNavigateToRecovery]：Recovery割込相当の遷移。Phase 1ではU4（`docs/plans/
 *   phase1-ui-skeleton-domain.md`§10.4）に基づく`BuildConfig.DEBUG`ガード付き
 *   「Simulate delay (debug)」ボタンから呼ばれる（release非搭載。C4で実装）。
 * - [onNavigateToEventSelection]：プロセス再生成後の状態復元不能時の遷移
 *   （エラー＆レスキューマップ#8、T-EXEC-9）。
 *
 * 契約scaffold（C2）時点では本文は最小限のプレースホルダ（未実装）とする。
 * 描画ロジック（T-EXEC-1〜9が要求する表示要素・One Action制約）はC4で実装する。
 */
@Composable
fun ExecutionScreen(
    uiState: ExecutionUiState,
    onNavigateToDeparture: () -> Unit,
    onNavigateToRecovery: () -> Unit,
    onNavigateToEventSelection: () -> Unit
) {
    // C4で実装する（TODO）。
}
