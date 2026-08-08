package com.actionstarter.features.recovery

import androidx.compose.runtime.Composable

/**
 * 仕様§31-34・§35 Screen5準拠（Plan updated画面）。最大の独自価値。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。[onNavigateToExecution]は「Use this plan」タップ相当の遷移
 * （`docs/plans/phase1-ui-skeleton-domain.md`§10.2）。「Other options」／
 * 「See alternatives」は画面内表示切替であり、[RecoveryUiState.options]に含まれる候補内で
 * 完結するため遷移ラムダを持たない。
 *
 * 契約scaffold（C2）時点では本文は最小限のプレースホルダ（未実装）とする。
 * 描画ロジック（T-REC-1〜6が要求する表示要素）はC4でui-implementerが実装する。
 */
@Composable
fun RecoveryScreen(
    uiState: RecoveryUiState,
    onNavigateToExecution: () -> Unit
) {
    // C4で実装する（TODO）。
}
