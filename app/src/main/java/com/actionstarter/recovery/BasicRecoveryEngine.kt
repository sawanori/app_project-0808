package com.actionstarter.recovery

import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.RecoveryPlan
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.services.routing.RoutingException
import com.actionstarter.services.routing.RoutingService
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 仕様§68 Phase 6「Recovery Basic」・§13 Basic Engineの本番実装（F70/F73/F74/F75、
 * 計画書§7.1、P6-C1 scaffold）。
 *
 * `mock/MockRecoveryFactory.kt`（Phase 1限定Mock）が実装していた3案固定生成を、§31〜§33から
 * 導出した4規則・カスケード型の完全決定的な候補生成（A: そのまま出発／B: OPTIONAL省略／
 * C: OPTIONAL+IMPORTANT省略／D: 移動手段変更）へ昇格させる（計画書§7.2〜§7.5）。
 *
 * [RecoveryEngine]契約（§45）は変更しない。[RecoveryContext]（§50）も変更しない。
 * `title`/`explanation` は常に空文字とし、`semanticAction` をUI層のlocalizationキーとして
 * 解決する（S-4、ADR-0018のRecoveryへの拡張。ADR起票候補は計画書§7.9参照。**DECISIONS.mdへの
 * 実際の起票はP6-C5統合ウィンドウで行う** — 本サイクルの制約と計画書§6.4の対象整理の詳細は
 * 本サイクル報告を参照）。
 *
 * `RecoveryOption.id` は `UUID.nameUUIDFromBytes` による決定的生成とし、idシードは
 * `"${event.id}:${semanticAction}:${skippedStepIds.sorted().joinToString(",")}"` とする
 * （ADR-0017のRecoveryへの拡張。`estimatedArrival`はシードに含めない。計画書§7.1）。
 *
 * [routingService] はDI供給インスタンス（`CachingRoutingService` または
 * `UnconfiguredRoutingService`）を必ず注入する。本クラス自身は `RoutesApiRoutingService` を
 * 直接生成しない（§95.2、T-BRE-18/19の構造ガード対象）。[currentTransportMode] は仕様§50に
 * `transportMode` フィールドが存在しないための代替供給経路（S-3・§4.2 U-4）で、既定値は
 * [BasicRecoveryDefaults.DEFAULT_TRANSPORT_MODE] を用いる。
 *
 * P6-C3実装（T-BRE-1〜31。T-BRE-32は構造ガードで別ファイル）。§7.2〜§7.3の計算式を
 * そのままコードへ写像する：
 * - `U`（残準備）＝TRANSITION/PREPARATIONかつ`completedAt == null`のステップ集合
 * - A案は無条件生成。B/C/Dは`!feasible(A)`のときのみ検討し、Cは`!feasible(B)`のときのみ、
 *   Dは`currentLocation`/`event.coordinates`が揃い`RoutingService`が短い代替所要を返した
 *   ときのみ生成する
 * - 並び順は `sortKey = (feasible?0:1, |skippedStepIds|, eta, ruleOrdinal)`
 *   （A=0,B=1,C=2,D=3）の昇順。上位3件を採用し、A案が含まれなければ末尾を落としてA案を追加する
 *   （§34：A案は必ず残す）
 */
class BasicRecoveryEngine(
    private val routingService: RoutingService,
    private val defaults: BasicRecoveryDefaults = BasicRecoveryDefaults,
    private val currentTransportMode: () -> TransportMode = { BasicRecoveryDefaults.DEFAULT_TRANSPORT_MODE }
) : RecoveryEngine {
    override suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan {
        require(!context.latestTravelEstimate.isNegative) {
            "RecoveryContext.latestTravelEstimate must not be negative, was ${context.latestTravelEstimate}"
        }

        val remaining = remainingPreparationSteps(context)
        val rAll = remaining.sumDuration()
        val etaA = eta(context, rAll)
        val feasibleA = isFeasible(etaA, context.event.startDate)

        val candidates = mutableListOf(Candidate(SEMANTIC_KEEP_ALL_STEPS, emptyList(), etaA, RULE_ORDINAL_A))
        var routingFailureReason: String? = null

        // A案が成立していれば遅れていないためB/C/Dは一切生成しない（§33の直接の帰結、T-BRE-5/19）
        if (!feasibleA) {
            candidates += buildSkipCandidates(context, remaining, rAll)

            val (changeTransportModeCandidate, failureReason) = attemptChangeTransportModeCandidate(context, rAll)
            changeTransportModeCandidate?.let { candidates += it }
            routingFailureReason = failureReason
        }

        val options = sortAndTruncate(candidates, context.event.startDate).map { it.toRecoveryOption(context.event.id) }
        return RecoveryPlan(options = options, routingFailureReason = routingFailureReason)
    }

    /** U（§7.2）: TRANSITION/PREPARATIONのみ・`completedAt == null`のみ（DEPARTURE/TRAVELは移動側）。 */
    private fun remainingPreparationSteps(context: RecoveryContext): List<ExecutionStep> =
        context.unfinishedSteps.filter {
            it.completedAt == null &&
                (it.type == ExecutionStepType.TRANSITION || it.type == ExecutionStepType.PREPARATION)
        }

    /** `ETA(K) = currentTime + (R_all − dur(K)) + latestTravelEstimate`（§7.2）。 */
    private fun eta(context: RecoveryContext, remainingPreparation: Duration): Instant =
        context.currentTime.plus(remainingPreparation).plus(context.latestTravelEstimate)

    /**
     * B案（OPTIONAL省略）とC案（OPTIONAL+IMPORTANT省略、B案で足りないときだけ・過剰省略の禁止＝
     * §33）を構築する（§7.3）。生成条件を満たさなければ空リストを返す。
     */
    private fun buildSkipCandidates(
        context: RecoveryContext,
        remaining: List<ExecutionStep>,
        rAll: Duration
    ): List<Candidate> {
        val skippableOptional = remaining.filter { it.priority == StepPriority.OPTIONAL && it.skippable }
        val durB = skippableOptional.sumDuration()
        if (skippableOptional.isEmpty() || durB <= Duration.ZERO) return emptyList()

        val result = mutableListOf<Candidate>()
        val etaB = eta(context, rAll.minus(durB))
        val feasibleB = isFeasible(etaB, context.event.startDate)
        result += Candidate(SEMANTIC_SKIP_OPTIONAL_STEPS, skippableOptional.map { it.id }, etaB, RULE_ORDINAL_B)

        if (!feasibleB) {
            val skippableOptionalAndImportant = skippableOptional +
                remaining.filter { it.priority == StepPriority.IMPORTANT && it.skippable }
            val durC = skippableOptionalAndImportant.sumDuration()
            if (durC > durB) {
                val etaC = eta(context, rAll.minus(durC))
                result += Candidate(
                    SEMANTIC_SKIP_OPTIONAL_AND_IMPORTANT_STEPS,
                    skippableOptionalAndImportant.map { it.id },
                    etaC,
                    RULE_ORDINAL_C
                )
            }
        }
        return result
    }

    /**
     * D案（移動手段変更）を試みる（§7.3・§7.5）。`currentLocation`／`event.coordinates`の
     * どちらかが欠けていれば座標を捏造せず静かに何も生成しない（T-BRE-15/16）。
     * `RoutingException`はD案を落とすだけで握り潰さない：戻り値の第2要素（失敗理由）へ
     * 転記する（§9エラーマップ#4、Fable 5裁定1）。`CancellationException`は構造化並行性の
     * 取り消しを妨げないよう再送出する。
     *
     * @return (生成できたら候補、できなければ`null`) と (RoutingExceptionが発生していれば
     *   そのメッセージ、なければ`null`) の組。
     */
    private suspend fun attemptChangeTransportModeCandidate(
        context: RecoveryContext,
        rAll: Duration
    ): Pair<Candidate?, String?> {
        val origin = context.currentLocation ?: return null to null
        val destination = context.event.coordinates ?: return null to null
        val alternativeMode = defaults.alternativeTransportMode(currentTransportMode()) ?: return null to null

        return try {
            // §95.2: 1 createRecoveryPlan あたり最大1回のみ（代替手段も1つだけ試す）
            val estimate = routingService.estimateRoute(
                origin = origin,
                destination = destination,
                mode = alternativeMode,
                departureDate = context.currentTime.plus(rAll)
            )
            if (estimate.duration < context.latestTravelEstimate) {
                val etaD = context.currentTime.plus(rAll).plus(estimate.duration)
                Candidate(SEMANTIC_CHANGE_TRANSPORT_MODE, emptyList(), etaD, RULE_ORDINAL_D) to null
            } else {
                null to null
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (routingFailure: RoutingException) {
            null to routingFailure.message
        }
    }

    /**
     * §7.3優先順位規則: `sortKey = (feasible?0:1, |skippedStepIds|, eta, ruleOrdinal)`の昇順で
     * 上位3件を採用する。A案が含まれなければ末尾を落としてA案を追加する（§34：A案は必ず残す）。
     */
    private fun sortAndTruncate(candidates: List<Candidate>, eventStart: Instant): List<Candidate> {
        val sorted = candidates.sortedWith(
            compareBy(
                { candidate -> if (isFeasible(candidate.eta, eventStart)) 0 else 1 },
                { candidate -> candidate.skippedStepIds.size },
                { candidate -> candidate.eta },
                { candidate -> candidate.ruleOrdinal }
            )
        )
        val topThree = sorted.take(MAX_OPTIONS)
        if (topThree.any { it.semanticAction == SEMANTIC_KEEP_ALL_STEPS }) return topThree

        val keepAllStepsCandidate = sorted.first { it.semanticAction == SEMANTIC_KEEP_ALL_STEPS }
        return topThree.dropLast(1) + keepAllStepsCandidate
    }

    private fun isFeasible(eta: Instant, eventStart: Instant): Boolean = !eta.isAfter(eventStart)

    private fun List<ExecutionStep>.sumDuration(): Duration =
        fold(Duration.ZERO) { acc, step -> acc.plus(step.estimatedDuration) }

    /** 候補1件分の中間表現（sort・切り詰め後に[RecoveryOption]へ変換する）。 */
    private data class Candidate(
        val semanticAction: String,
        val skippedStepIds: List<UUID>,
        val eta: Instant,
        val ruleOrdinal: Int
    ) {
        /**
         * `id`は`UUID.nameUUIDFromBytes`による決定的生成（ADR-0017踏襲、計画書§7.1）。
         * シードは`"${event.id}:${semanticAction}:${skippedStepIds.sorted().joinToString(",")}"`
         * （T-BRE-25）。`estimatedArrival`（=[eta]）はシードに含めない
         * （RoutingService結果で揺れると決定性が壊れるため、構成のみで一意化する）。
         */
        fun toRecoveryOption(eventId: UUID): RecoveryOption {
            val seed = "$eventId:$semanticAction:${skippedStepIds.sorted().joinToString(",")}"
            return RecoveryOption(
                id = UUID.nameUUIDFromBytes(seed.toByteArray()),
                semanticAction = semanticAction,
                title = "",
                explanation = "",
                estimatedArrival = eta,
                skippedStepIds = skippedStepIds
            )
        }
    }

    private companion object {
        const val SEMANTIC_KEEP_ALL_STEPS = "keep_all_steps"
        const val SEMANTIC_SKIP_OPTIONAL_STEPS = "skip_optional_steps"
        const val SEMANTIC_SKIP_OPTIONAL_AND_IMPORTANT_STEPS = "skip_optional_and_important_steps"
        const val SEMANTIC_CHANGE_TRANSPORT_MODE = "change_transport_mode"
        const val RULE_ORDINAL_A = 0
        const val RULE_ORDINAL_B = 1
        const val RULE_ORDINAL_C = 2
        const val RULE_ORDINAL_D = 3
        const val MAX_OPTIONS = 3
    }
}
