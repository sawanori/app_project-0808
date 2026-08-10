package com.actionstarter.ai

/**
 * ADR-0005により仕様書未定義のため計画書§9.2で補完定義する型。
 * [com.actionstarter.ai.schema.SchemaValidator.validate]（F95）が①形式検証＋パース後に
 * 返す構造（[com.actionstarter.ai.schema.SchemaValidationResult.Valid.response]）。
 *
 * **フィールド構成の契約変更（Fable 5裁定1、2026-08-10、ADR-0045。Semantic
 * Contextualization・品質ハーネス§0/§2/§13を統合）**: 旧設計は仕様§20のJSON構造例
 * （`estimated_minutes`／`priority`／`skippable`／`type`を含む）をそのまま反映していたが、
 * これらは全て**LLM出力から除去**し、Kotlin側の決定的計算（`action_type`からのマップ。
 * Phase 8 `LocalAIPlanningEngine`の責務、計画書§18申し送り）へ一本化した（§13「数値計算は
 * 必ず通常コード」・§15「時刻演算をLLMに渡さない」の徹底）。LLMが生成するのは
 * 「[eventType]（予定分類enum）」と「各stepの[AIPlanStepResponse.actionType]（行動種別enum）・
 * [AIPlanStepResponse.displayText]（**予定固有の文脈化された行動文**。Semantic
 * Contextualization）」のみである。
 *
 * 信頼境界（極めて重要）：本型はLocal LLMからの生の構造化出力を、
 * [com.actionstarter.ai.schema.SchemaValidator]による①形式検証（enum・件数・長さ・
 * `additionalProperties`）を経てパースした結果を表す。[eventType]／[AIPlanStepResponse.
 * actionType]をDomain enum（[com.actionstarter.domain.model.StepPriority]や
 * [com.actionstarter.domain.model.ExecutionStepType]相当）ではなく生の`String`のまま
 * 保持するのは、①検証後もそのままDomain Logicへ渡さない設計とするためである
 * （[com.actionstarter.domain.model.RecoveryOption.semanticAction]・
 * [com.actionstarter.domain.model.ExecutionStep.semanticId]と同型の「言語非依存の内部ID＝
 * String」という既存前例を踏襲、P7-C2完了記録の確定した論点）。①形式検証を通過後は
 * さらに[com.actionstarter.ai.schema.ContentSanityChecker]による②内容sanity検証を経る
 * （Fable 5裁定3・4）。Domain enumへの変換・数値/優先度/省略可否の決定的マップは
 * 本型より後段（Phase 8）の責務であり、Validation失敗時はretry 1回→Basic Engineへ
 * フォールバックする（仕様§20）。
 */
data class AIPlanResponse(
    val eventType: String,
    val steps: List<AIPlanStepResponse>
)

/**
 * [AIPlanResponse.steps]内の1ステップ。Fable 5裁定1により、LLM出力は[actionType]
 * （内部意味を表す言語非依存の英語ID、[com.actionstarter.ai.schema.PlanActionType]の
 * 確定7語彙・enum制約）と[displayText]（UI表示用の予定固有の文脈化された行動文。
 * Semantic Contextualization、enum非制約の自由文・ja/en）の2フィールドのみに最小化した
 * （§21の`action_type`/`display_text`分離方針を踏襲しつつ、旧`type`／`estimated_minutes`／
 * `priority`／`skippable`は完全に除去）。
 *
 * [displayText]は60字上限（[com.actionstarter.ai.schema.PlanJsonSchema.TEXT]の
 * `minLength`/`maxLength`制約、①形式検証で担保）。ステップ種別（transition/preparation/
 * departure/travel）・所要分・優先度・省略可否は[actionType]からKotlin側が決定的に導出する
 * （後段Phase 8の責務、[AIPlanResponse]のKDoc参照）ため、本型はそれらを保持しない。
 */
data class AIPlanStepResponse(
    val actionType: String,
    val displayText: String
)
