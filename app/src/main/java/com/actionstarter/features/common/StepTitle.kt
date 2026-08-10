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
 *
 * **再デザインサイクル2（目的・UX合致サイクル）での文言改善**: `step_title_*`4キーの
 * *値*（本関数のマッピング・キー名・引数は無変更）を、内部ステップ種別名の直訳
 * （"Transition"/"Preparation"/"Departure"/"Travel"）から、仕様§27「Finish your current
 * task」「Get dressed」のような**具体的行動文**へ置換した（`res/values/strings.xml`・
 * `res/values-ja/strings.xml`の該当コメント参照）。背景：Execution画面（本関数の主要呼び出し元）
 * に内部用語がそのまま表示され「今何をすればいいか」が伝わらない問題が判明したため
 * （ユーザー指摘・目的合致レビュー）。
 *
 * 本来「具体的行動文の生成」（例：予定内容に応じて"Finish your current task"のように
 * 動的生成する）はLocal AI（Phase 7以降、仕様§14 Local AI Engine）の役割だが、仕様§78
 * 項目13「AI OFFでも成立」が要求するとおり、**Basic版（AI OFF）でも種別名の直訳ではなく
 * 意味の通る行動文言を出すべき**という判断により、本関数が返す4つの固定文言を
 * 「Basic版のデフォルト行動文言」として改善した。将来Local AIが有効な場合、予定内容
 * （タイトル・場所・時間帯等）に応じて動的に生成した行動文言が本関数の解決結果を
 * 上書き表示する想定（Local AI Engine側の責務・本関数のシグネチャ自体は変更不要）。
 * すなわち本関数は今後も「AI OFF時の最終フォールバック」として存続する。
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
