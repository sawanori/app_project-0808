package com.actionstarter.features.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.actionstarter.R

/**
 * 仕様§48で定義済みの[com.actionstarter.domain.model.ExecutionStep.semanticId]を
 * localizationキーとして`stringResource`解決し、表示用文言を返す（F47、G-4、ADR-0018）。
 *
 * `BasicPlanningEngine`（`planning/`、P4-C3実装）は
 * [com.actionstarter.domain.model.ExecutionStep.title]を常に空文字で生成し、実際の表示文言
 * 解決はUI層である本関数に委ねる（仕様§7「UI文字列の直接ハードコード禁止」。Domain層が
 * Androidリソースへ依存しないためのレイヤー分離、計画書§7.1・§7.5）。
 * PlanReviewScreen・ExecutionScreen双方の画面が共通利用する（計画書§6.2・§7.5）。
 *
 * 想定される`semanticId`はF41テンプレート4種（`"transition"`／`"preparation"`／
 * `"departure"`／`"travel"`）だが、将来のテンプレート追加漏れ等で未知の`semanticId`が
 * 渡っても例外を投げずフォールバック文言を返し、クラッシュしない
 * （§9エラーマップ#12、T-P4UI-3）。
 *
 * P4-C4（ui-implementer、Green、T-P4UI-1〜5）で実装。既知の`semanticId`4種は
 * `res/values/strings.xml`／`res/values-ja/strings.xml`の`step_title_*`キーへ解決し、
 * 未知の`semanticId`は`step_title_fallback`（フォールバック文言）を返す（例外を投げない）。
 */
@Composable
fun resolveStepTitle(semanticId: String): String {
    return when (semanticId) {
        "transition" -> stringResource(R.string.step_title_transition)
        "preparation" -> stringResource(R.string.step_title_preparation)
        "departure" -> stringResource(R.string.step_title_departure)
        "travel" -> stringResource(R.string.step_title_travel)
        else -> stringResource(R.string.step_title_fallback)
    }
}
