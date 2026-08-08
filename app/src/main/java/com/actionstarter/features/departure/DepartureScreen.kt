package com.actionstarter.features.departure

import androidx.compose.runtime.Composable

/**
 * 仕様§29・§35 Screen4準拠（Leave now画面）。
 *
 * Phase 1のUXフロー（`docs/plans/phase1-ui-skeleton-domain.md`§10.2）ではDeparture画面が
 * 一連の遷移の終端であり、「Start navigation」は未実装のため無効化される（T-DEP-4）。
 * そのため本画面はPhase 1時点で画面遷移ラムダを持たない（§10.6の疎結合規約は
 * NavController／NavHostを直接参照しないことを求めるが、遷移先自体が存在しない）。
 *
 * 契約scaffold（C2）時点では本文は最小限のプレースホルダ（未実装）とする。
 * 描画ロジック（T-DEP-1〜4が要求する表示要素）はC4でui-implementerが実装する。
 */
@Composable
fun DepartureScreen(
    uiState: DepartureUiState
) {
    // C4で実装する（TODO）。
}
