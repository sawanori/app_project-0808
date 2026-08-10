package com.actionstarter.ai

import com.actionstarter.ai.schema.PlanActionType
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.PlanningContext

/**
 * Phase 8実装（計画書`docs/plans/phase8-ai-execution-wiring.md`§3・§7.1、G1通過・B1確定）。
 * `BasicPlanningEngine`（`planning/`、§13専任）が組み立てた[ExecutionPlan]の上に、Local AI
 * （[LocalAiGateway]）が生成した予定固有の行動文（`display_text`）だけを重ねる薄いoverlay
 * decorator。**`PlanningEngine`は実装しない**（§3.1表B・B1裁定）——`createPlan`は単一戻り値の
 * ため「Basic即時表示→AI後差し替え」という非同期UX（§4）を表現できず、また2つの
 * `PlanningEngine`実装が各々planを組み立てると§13構造不変が構造的に保証できなくなるため。
 *
 * **配置が`planning/`ではなく`ai/`である理由（B1裁定）**: `planning/`配下にAI参照クラスを
 * 置くと隔離ガードT-BPE-28（[com.actionstarter.planning.PlanningLlmIsolationTest]）が
 * 必ずRedになる。`ai/`配置ならBasic経路の純粋性を保てる（phase7 §18.1 R-10）。
 *
 * **責務分界（§2・§13厳守）**: 本クラスが書き換えるのは各[com.actionstarter.domain.model.
 * ExecutionStep.title]のみ。時刻・移動・優先度・skippable・種別・件数・順序は
 * `BasicPlanningEngine`専任のまま不変（[overlay]のKDoc参照）。
 */
class LocalAiPlanContextualizer(private val gateway: LocalAiGateway) {

    /**
     * [gateway]を呼び、成功時は[basePlan]へ[overlay]した結果を[ContextualizationResult.Applied]
     * で、フォールバック時は[basePlan]を無変更のまま[ContextualizationResult.Unchanged]で返す
     * （§7.1）。例外は投げない（[gateway]が全`Throwable`をFallbackへ写像済み。
     * `kotlinx.coroutines.CancellationException`のみ再送出＝gateway準拠、§4.3）。
     */
    suspend fun contextualize(basePlan: ExecutionPlan, context: PlanningContext): ContextualizationResult =
        when (val result = gateway.generatePlan(context)) {
            is AiResult.Success -> ContextualizationResult.Applied(
                plan = overlay(basePlan, result.value),
                response = result.value,
                metrics = result.metrics
            )
            is AiResult.Fallback -> ContextualizationResult.Unchanged(basePlan, result.reason)
        }
}

/**
 * [LocalAiPlanContextualizer.contextualize]の戻り値（§7.1）。`reason`は捨てず保持する
 * （観測性＝サイレント障害防止・将来Analytics、§9エラーマップ#1）。
 */
sealed interface ContextualizationResult {
    /**
     * [plan]は呼び出し時点の`basePlan`への[overlay]結果（単発利用向け）。[response]はoverlay前の
     * 生AI応答——呼び出し側（[com.actionstarter.features.planreview.PlanReviewViewModel]、§4）が
     * travel解決等でbaseが後から変わった際に[overlay]（公開関数）へ[response]を渡して
     * **再推論なしで**再overlayするために保持する（Gemini G1 CRITICAL②反映・stale-write構造
     * 防御の一部）。
     */
    data class Applied(val plan: ExecutionPlan, val response: AIPlanResponse, val metrics: AiMetrics) :
        ContextualizationResult

    /** [plan]は`basePlan`そのもの（無変更）。[reason]はgatewayが返した[AiFallbackReason]。 */
    data class Unchanged(val plan: ExecutionPlan, val reason: AiFallbackReason) : ContextualizationResult
}

/**
 * §7.2（純粋関数・§13不変の実体）。Gemini G1 CRITICAL①反映のキュー消費設計:
 * [ai]の`steps`をAI出力順に`ExecutionStepType`別のキューへ積み、[base]の`steps`を出現順に
 * 走査しながら対応する型のキューから**1件だけ**文言を消費して`title`へ割り当てる
 * （`base.steps.map { s -> queueByType[s.type]?.removeFirstOrNull()?.let { s.copy(title = it) }
 * ?: s }`という形。同一typeのbase step複数件でも各stepが異なるAI文言へ1対1対応する
 * （T-P8-25）。マッチ不能（未対応action_type／supply枯渇）のstepは`title=""`のまま=Basic固定
 * 文言へper-step縮退する（描画側`title.ifBlank{resolveStepTitle(...)}`が既に対応済み、§0）。
 *
 * `step.copy(title = aiText)`と`base.copy(steps = …)`のみを行うため、`title`以外の全フィールド
 * （時刻・所要分・優先度・skippable・件数・順序）は不変（T-P8-14）。[ExecutionPlan]コンストラクタは
 * `scheduledStart`昇順へ再正規化するが、overlayは`scheduledStart`を変えないため順序も不変。
 *
 * 可視性: [LocalAiPlanContextualizer.contextualize]内部と`PlanReviewViewModel`の`combine`
 * ブロック（§4.2、再overlay用）の両方から呼べるよう`internal`とする（`private`にしない）。
 */
internal fun overlay(base: ExecutionPlan, ai: AIPlanResponse): ExecutionPlan {
    val queueByType = mutableMapOf<ExecutionStepType, ArrayDeque<String>>()
    for (aiStep in ai.steps) {
        val type = mapActionType(aiStep.actionType) ?: continue
        queueByType.getOrPut(type) { ArrayDeque() }.addLast(aiStep.displayText)
    }

    val newSteps = base.steps.map { step ->
        val text = queueByType[step.type]?.removeFirstOrNull()
        if (text != null) step.copy(title = text) else step
    }

    return base.copy(steps = newSteps)
}

/**
 * [AIPlanStepResponse.actionType]（生の`String`、schema検証済みなら[PlanActionType.JSON_VALUES]の
 * いずれか）を[ExecutionStepType]へ解決する。schema検証を経ていない／未知の値は`null`
 * （overlay側でskipされる。§7.2「未対応(ARRIVE/未知)はnull→skip」）。
 */
private fun mapActionType(actionType: String): ExecutionStepType? =
    PlanActionType.entries.firstOrNull { it.name.equals(actionType, ignoreCase = true) }?.toExecutionStepTypeOrNull()

/**
 * §7.3（決定的・全7値網羅マッパ）。born-greenデータ——値が確定した契約は即座に確定させる
 * （[com.actionstarter.ai.AiFallbackReason]・[com.actionstarter.ai.SamplingPolicy]と同型の
 * パターン）。[ARRIVE]のみ対応stepが無い（到着はplanフィールド`estimatedArrival`であり
 * step化しないため`null`）。
 */
internal fun PlanActionType.toExecutionStepTypeOrNull(): ExecutionStepType? = when (this) {
    PlanActionType.FINISH_CURRENT_TASK -> ExecutionStepType.TRANSITION
    PlanActionType.PREPARE_ITEMS, PlanActionType.GET_READY, PlanActionType.GATHER_BELONGINGS ->
        ExecutionStepType.PREPARATION
    PlanActionType.LEAVE -> ExecutionStepType.DEPARTURE
    PlanActionType.COMMUTE -> ExecutionStepType.TRAVEL
    PlanActionType.ARRIVE -> null
}
