package com.actionstarter.features.eventselection

import androidx.compose.runtime.Composable

/**
 * 仕様§24 Phase A・§35 Screen1準拠（Next Event画面）。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。[onNavigateToPlanReview]は「Prepare this event」タップ相当の遷移。
 *
 * 契約scaffold（C2）時点では本文は最小限のプレースホルダ（未実装）とする。
 * 描画ロジック（T-SEL-1〜7が要求する表示要素）はC4でui-implementerが実装する。
 * 本文を空のままにするのは、C3のRedテストがコンパイルエラーではなくアサーション
 * （期待要素が見つからないこと）によって意図どおり失敗するようにするため（仕様書§9.4）。
 */
@Composable
fun EventSelectionScreen(
    uiState: EventSelectionUiState,
    onNavigateToPlanReview: () -> Unit
) {
    // C4で実装する（TODO）。
}
