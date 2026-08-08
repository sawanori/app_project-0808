package com.actionstarter.ai

/**
 * ADR-0005により仕様書未定義のため計画書§9.2で補完定義する型。
 * [LocalLanguageModel.generateRecovery]（仕様§16）の戻り値。仕様§32の
 * [com.actionstarter.domain.model.RecoveryOption]フィールド構成と、仕様§21の
 * `action_type`/`display_text`分離方針を統合した最小フィールド定義。
 *
 * 信頼境界（極めて重要）：本型はLocal LLMからの生の構造化出力を表し、
 * Schema Validation（仕様§20）を経る前の**未検証の外部入力**である（[AIPlanResponse]の
 * KDoc参照）。
 *
 * `estimatedArrival`（到着時刻）に相当するフィールドを意図的に含めない：仕様§15
 * 「LLMに禁止すること」は正確な移動時間・時刻演算・到着時刻演算をLLM出力に基づき
 * 直接実行することを禁止しており、到着時刻は常にKotlin側の決定的計算
 * （[com.actionstarter.services.routing.RoutingService]の実測値ベース）で算出する。
 * LLMの責務は代替案の自然言語説明と省略ステップの提案（仕様§14「Recovery候補生成」）に
 * 限定する。
 */
data class AIRecoveryResponse(
    val options: List<AIRecoveryOptionResponse>
)

/**
 * [AIRecoveryResponse.options]内の1候補。仕様§21の`action_type`/`display_text`分離方針に
 * より、内部意味を表す[actionType]とUI表示文言[displayText]を分離して保持する。
 * [skippedStepIds]はSchema Validation前の生文字列IDのリストとして扱い、
 * [java.util.UUID]への変換・妥当性検証はValidation実装（Phase 7）で行う。
 */
data class AIRecoveryOptionResponse(
    val actionType: String,
    val displayText: String,
    val explanation: String,
    val skippedStepIds: List<String>
)
