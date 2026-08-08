package com.actionstarter.ai

import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext

/**
 * 仕様§16準拠（Local LLM Runtime）。Model Adapter方式の契約interface。
 *
 * 特定モデル（gguf / LiteRT等）依存コードをUIやDomain層へ漏らさないための境界。
 * モデルは技術検証で交換可能とする。戻り値の[AIPlanResponse]／[AIRecoveryResponse]は
 * Schema Validation前の生の構造化出力であり、Domain Logicへ直接使用してはならない
 * （仕様§20）。
 *
 * 契約scaffold（C2）時点では宣言のみであり、実装は行わない。
 */
interface LocalLanguageModel {
    val modelIdentifier: String

    suspend fun generatePlan(context: PlanningContext): AIPlanResponse

    suspend fun generateRecovery(context: RecoveryContext): AIRecoveryResponse
}
