package com.actionstarter.ai

import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryPlan

/**
 * Phase 9実装（計画書`docs/plans/phase9-recovery-ai.md`§3.1、§2「重要な発見1」、ADR-0063想定）。
 * `BasicRecoveryEngine`（`recovery/`、§13専任）が組み立てた[RecoveryPlan]の上に、Local AI
 * （[LocalAiGateway]）が生成した候補固有の説明文（`explanation`）だけを重ねる薄いoverlay
 * decorator。[com.actionstarter.recovery.RecoveryEngine]は実装しない——[LocalAiPlanContextualizer]
 * がPhase 8で確立した設計（B1裁定）をそのまま踏襲する：`createRecoveryPlan`は単一戻り値のため
 * 「Basic即時表示→AI後差し替え」という非同期UXを表現できず、`RecoveryEngine`の2実装が各々
 * planを組み立てると§13構造不変が構造的に保証できなくなるため（計画書§2「重要な発見1」）。
 *
 * **責務分界（§13厳守）**: 本クラスが書き換えるのは各[com.actionstarter.domain.model.
 * RecoveryOption.explanation]のみ。`semanticAction`・`skippedStepIds`・`estimatedArrival`・
 * 件数・順序は`BasicRecoveryEngine`専任のまま不変（[overlay]のKDoc参照）。
 *
 * Step 4（Green）で実装済み。
 */
class LocalAiRecoveryContextualizer(private val gateway: LocalAiGateway) {

    /**
     * [gateway]を呼び、成功時は[basePlan]へ[overlay]した結果を[RecoveryContextualizationResult.
     * Applied]で、フォールバック時は[basePlan]を無変更のまま[RecoveryContextualizationResult.
     * Unchanged]で返す（[LocalAiPlanContextualizer.contextualize]と同型）。例外は投げない
     * （[gateway]が全`Throwable`をFallbackへ写像済み。`kotlinx.coroutines.CancellationException`
     * のみ再送出）。
     */
    suspend fun contextualize(basePlan: RecoveryPlan, context: RecoveryContext): RecoveryContextualizationResult =
        when (val result = gateway.generateRecovery(context, basePlan.options)) {
            is AiResult.Success -> RecoveryContextualizationResult.Applied(
                plan = overlay(basePlan, result.value),
                response = result.value,
                metrics = result.metrics
            )
            is AiResult.Fallback -> RecoveryContextualizationResult.Unchanged(basePlan, result.reason)
        }
}

/**
 * [LocalAiRecoveryContextualizer.contextualize]の戻り値（[ContextualizationResult]と同型）。
 * `reason`は捨てず保持する（観測性＝サイレント障害防止、L5メトリクスと独立の観測経路）。
 */
sealed interface RecoveryContextualizationResult {
    /** [plan]は呼び出し時点の`basePlan`への[overlay]結果。[response]はoverlay前の生AI応答。 */
    data class Applied(val plan: RecoveryPlan, val response: AIRecoveryResponse, val metrics: AiMetrics) :
        RecoveryContextualizationResult

    /** [plan]は`basePlan`そのもの（無変更）。[reason]はgatewayが返した[AiFallbackReason]。 */
    data class Unchanged(val plan: RecoveryPlan, val reason: AiFallbackReason) : RecoveryContextualizationResult
}

/**
 * §13（純粋関数）。[ai]の`options`を`semanticAction`をキーとしたMapへ変換し、[base]の`options`を
 * 走査して一致する`semanticAction`があれば`explanation`のみ差し替える（計画書§3.1「Planの
 * overlayは型別キュー消費だがRecoveryはsemanticActionが重複しないため単純なMap引き当てで足りる」）。
 * マッチ不能（未知semanticAction／supply不足）の候補は`explanation`が空文字のまま＝既存の
 * `RecoveryOptionText`静的解決へper-option縮退する（計画書§4.4 L4）。
 *
 * `option.copy(explanation = …)`のみを行うため、`semanticAction`・`skippedStepIds`・
 * `estimatedArrival`・件数・順序は不変（§13）。
 *
 * 可視性: [LocalAiRecoveryContextualizer.contextualize]内部から呼べるよう`internal`とする
 * （[com.actionstarter.ai.overlay]と同じ規約）。
 */
internal fun overlay(base: RecoveryPlan, ai: AIRecoveryResponse): RecoveryPlan {
    val explanationBySemanticAction = ai.options.associate { it.semanticAction to it.explanation }
    val newOptions = base.options.map { option ->
        val explanation = explanationBySemanticAction[option.semanticAction]
        if (explanation != null) option.copy(explanation = explanation) else option
    }
    return base.copy(options = newOptions)
}
