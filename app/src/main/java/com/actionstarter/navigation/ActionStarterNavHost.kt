package com.actionstarter.navigation

import androidx.compose.runtime.Composable

/**
 * 仕様§35の5画面＋Recovery割込を結ぶNavHost（計画書§10.2グラフ構成）。
 *
 * ```
 * eventSelection → [Prepare] → planReview → [Start] → execution → [最終Done] → departure
 * execution → [割込] → recovery → [Use this plan] → execution
 * ```
 *
 * §10.6の疎結合規約により、各画面Composable（EventSelectionScreen等）は画面遷移を
 * ラムダ引数として受け取りNavControllerを直接参照しない。本Composable（NavHost本体）が
 * NavControllerを保持し、各画面へ実際のnavigate呼び出しを結線する唯一の場所となる。
 *
 * `docs/TEAMS.md`§5「共有ファイル所有権と統合オーナー」により、Navigation配線（NavHost本体）
 * の既定所有者はdomain-implementerであり、ui-implementerはC4の間本ファイルに触れない。
 *
 * 契約scaffold追補（C2b）時点では本文は未実装（TODO）。NavController生成・
 * `NavHost`／`composable{}`ブロックの配線・[SharedPlanViewModel]や各画面ViewModelとの
 * 結線はC5でintegration ownerが行う。
 */
@Composable
fun ActionStarterNavHost() {
    // C5で実装する（TODO）。
}
