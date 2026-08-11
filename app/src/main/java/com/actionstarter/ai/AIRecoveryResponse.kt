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
 * LLMの責務は代替案の自然言語説明の生成（仕様§14「Recovery候補生成」）に限定する。
 *
 * **Phase 9でのフィールド再設計（計画書`docs/plans/phase9-recovery-ai.md`§3.3、ADR-0063想定）**:
 * Phase 7時点の定義は`actionType`/`displayText`/`explanation`/`skippedStepIds`の4フィールドを
 * 持っていたが、「AIが触れるのは説明文言のみ」（計画書§3.1・§13）の直接実装として
 * [AIRecoveryOptionResponse.skippedStepIds]・`displayText`を削除した。理由:
 * 1. `skippedStepIds`はUUID文字列のLLM生成を要し、[com.actionstarter.ai.schema.
 *    SchemaValidator]のKDocが指摘する「UUID変換を要する検証コスト」と、UUIDが不透明ゆえ
 *    誤り検出が難しい固有リスクを完全に除去する。
 * 2. `displayText`（title相当）は[com.actionstarter.features.recovery.RecoveryOptionText.
 *    resolveRecoveryOptionTitle]の既存静的解決（`semanticAction`キー4値、ADR-0018拡張）を
 *    維持し変更しない。
 * 3. [AIRecoveryOptionResponse.semanticAction]は`BasicRecoveryEngine`が既に決定した集合の
 *    echoであり、LLMが新規に発案するものではない（[com.actionstarter.ai.schema.
 *    RecoverySchemaValidator]のpairing検証と対になる）。
 */
data class AIRecoveryResponse(
    val options: List<AIRecoveryOptionResponse>
)

/**
 * [AIRecoveryResponse.options]内の1候補。Phase 9で[semanticAction]（`BasicRecoveryEngine`が
 * 既に決定した候補集合のecho、[com.actionstarter.domain.model.RecoveryOption.semanticAction]と
 * 同じ語彙）＋[explanation]（LLMが生成する唯一のフィールド）へ縮小した
 * （クラスKDoc「Phase 9でのフィールド再設計」参照）。
 */
data class AIRecoveryOptionResponse(
    val semanticAction: String,
    val explanation: String
)
