package com.actionstarter.ai

/**
 * ADR-0005により仕様書未定義のため計画書§9.2で補完定義する型。
 * [LocalLanguageModel.generatePlan]（仕様§16）の戻り値。仕様§20のJSON構造例
 * （`event_type`/`steps[].id`/`type`/`estimated_minutes`/`priority`/`skippable`）と、
 * 仕様§21のUI表示文言／Domain内部意味の分離方針（`action_type`/`display_text`）を
 * 統合した最小フィールド定義。
 *
 * 信頼境界（極めて重要）：本型はLocal LLMからの生の構造化出力を表し、
 * Schema Validation（仕様§20）を経る前の**未検証の外部入力**である。
 * `priority`／`type`をDomain enum（[com.actionstarter.domain.model.StepPriority]／
 * [com.actionstarter.domain.model.ExecutionStepType]）ではなく生の`String`とし、
 * `skippable`以外の値をそのままDomain Logicへ渡さない設計とするのは、LLM出力を
 * 検証なしに信頼しないためである。Validation失敗時はretry 1回→Basic Engineへ
 * フォールバックする（仕様§20）。完全スキーマ・Validation実装はPhase 7で行い、
 * 本型は契約scaffold（C2）時点の最小定義である。
 */
data class AIPlanResponse(
    val eventType: String,
    val steps: List<AIPlanStepResponse>
)

/**
 * [AIPlanResponse.steps]内の1ステップ。仕様§21の`action_type`/`display_text`分離方針に
 * より、内部意味を表す[actionType]（言語非依存の英語ID）とUI表示文言[displayText]を
 * 分離して保持する。[type]／[priority]の意味論はDomain enumと対応するが、Schema
 * Validation前の生文字列として扱う（[AIPlanResponse]のKDoc参照）。
 */
data class AIPlanStepResponse(
    val actionType: String,
    val displayText: String,
    val type: String,
    val estimatedMinutes: Int,
    val priority: String,
    val skippable: Boolean
)
