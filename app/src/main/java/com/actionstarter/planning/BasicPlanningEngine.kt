package com.actionstarter.planning

import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.StepPriority
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 仕様§68 Phase 4「Basic Engine」・§13 Basic Engineの本番実装（F40〜F46）。
 * `mock/MockPlanFactory.kt`（Phase 1限定Mock、`docs/plans/phase1-ui-skeleton-domain.md`§8 U6）が
 * 実装していた§13計算式（`StartOfTransition = EventStart − ArrivalBuffer − TravelTime −
 * PreparationTime − TransitionTime`）を決定的な本番実装へ昇格させる
 * （`docs/plans/phase4-basic-engine.md`§1・§7.2 Mock昇格方針）。
 *
 * [PlanningEngine]契約（§44、`suspend fun createPlan(context: PlanningContext): ExecutionPlan`）は
 * 変更しない（実測M4-2、計画書§7.1）。移動時間未取得（[PlanningContext.travelEstimate]が`null`）
 * でも固定値で穴埋めせず、TRAVELステップなしでPlanを成立させる（F44、§9エラーマップ#1）。
 * `services/routing/`パッケージは一切importせず、`travelEstimate`の値のみで判定する（§7.4）。
 *
 * ステップ構築順は仕様§48 enum順（TRANSITION→PREPARATION→DEPARTURE→TRAVEL、G-6、ADR-0016）、
 * `id`は決定的生成（ADR-0017）、`title`は空文字＋UI層`semanticId`解決（G-4、ADR-0018）とする。
 * 既定値（transition/preparation/arrivalBuffer）は[BasicPlanningDefaults]へ隔離済み（G-1、ADR-0015）。
 *
 * P4-C3（本ファイル）で[createPlan]本体をGreen実装した（T-BPE-1〜27・29、
 * `docs/plans/phase4-basic-engine.md`§8.2）。`di/AppContainer.kt`には引き続き未接続
 * （`MockPlanFactory`をそのまま`planningEngine`実装として維持し、本クラスと並行稼働させる。
 * 計画書§7.2手順1〜2）。`AppContainer`接続はP4-C5統合ウィンドウで`MockPlanFactory()`から
 * 切り替える（ADR-0019）。
 */
class BasicPlanningEngine : PlanningEngine {
    override suspend fun createPlan(context: PlanningContext): ExecutionPlan {
        validateNonNegativeDurations(context)

        val event = context.event
        val travelDuration = context.travelEstimate ?: Duration.ZERO
        val transitionDuration = context.profile?.averageTransitionDuration
            ?: BasicPlanningDefaults.TRANSITION
        val preparationDuration = context.profile?.averagePreparationDuration
            ?: BasicPlanningDefaults.PREPARATION

        // §13: StartOfTransition = EventStart − ArrivalBuffer − TravelTime − PreparationTime − TransitionTime
        val transitionStart = event.startDate
            .minus(context.arrivalBuffer)
            .minus(travelDuration)
            .minus(preparationDuration)
            .minus(transitionDuration)

        // departureTime = EventStart − ArrivalBuffer − TravelTime
        val departureTime = event.startDate
            .minus(context.arrivalBuffer)
            .minus(travelDuration)

        // estimatedArrival = departureTime + TravelTime
        val estimatedArrival = departureTime.plus(travelDuration)

        // §7.4: travelEstimateがnull/ZEROのいずれもTRAVELステップを生成しない
        val generatesTravelStep = context.travelEstimate != null && travelDuration > Duration.ZERO

        val steps = buildList {
            // §7.3・§9エラーマップ#8: 0分ステップは生成しない（DEPARTUREのみ例外）
            if (transitionDuration > Duration.ZERO) {
                add(
                    buildStep(
                        eventId = event.id,
                        semanticId = "transition",
                        type = ExecutionStepType.TRANSITION,
                        duration = transitionDuration,
                        priority = StepPriority.IMPORTANT,
                        scheduledStart = transitionStart
                    )
                )
            }
            if (preparationDuration > Duration.ZERO) {
                add(
                    buildStep(
                        eventId = event.id,
                        semanticId = "preparation",
                        type = ExecutionStepType.PREPARATION,
                        duration = preparationDuration,
                        priority = StepPriority.IMPORTANT,
                        scheduledStart = transitionStart.plus(transitionDuration)
                    )
                )
            }
            // DEPARTUREは0分ステップ非生成の原則の唯一の例外として常に1件生成する（出発マーカー）
            add(
                buildStep(
                    eventId = event.id,
                    semanticId = "departure",
                    type = ExecutionStepType.DEPARTURE,
                    duration = Duration.ZERO,
                    priority = StepPriority.REQUIRED,
                    scheduledStart = departureTime
                )
            )
            if (generatesTravelStep) {
                add(
                    buildStep(
                        eventId = event.id,
                        semanticId = "travel",
                        type = ExecutionStepType.TRAVEL,
                        duration = travelDuration,
                        priority = StepPriority.REQUIRED,
                        scheduledStart = departureTime
                    )
                )
            }
        }

        return ExecutionPlan(
            event = event,
            steps = steps,
            transitionStart = transitionStart,
            departureTime = departureTime,
            estimatedArrival = estimatedArrival,
            arrivalBuffer = context.arrivalBuffer
        )
    }

    /**
     * §9エラーマップ#2〜#4: `travelEstimate`／`arrivalBuffer`／`profile`所要時間（本Engineが
     * 実際に読む2フィールド、`averageTransitionDuration`・`averagePreparationDuration`のみ）の
     * 負値を拒否する。オーバーフロー（#5、T-BPE-23）はここでは検知せず、後続の`Instant`/
     * `Duration`演算が送出する例外をそのまま呼び出し元へ伝播させる（握り潰さない）。
     * 例外メッセージはフィールド名とDuration値のみとし、イベント本文（title/notes/
     * locationName）は含めない（§9エラーマップ#18、仕様§58 Privacy）。
     */
    private fun validateNonNegativeDurations(context: PlanningContext) {
        context.travelEstimate?.let { travelEstimate ->
            require(!travelEstimate.isNegative) {
                "PlanningContext.travelEstimate must not be negative, was $travelEstimate"
            }
        }
        require(!context.arrivalBuffer.isNegative) {
            "PlanningContext.arrivalBuffer must not be negative, was ${context.arrivalBuffer}"
        }
        context.profile?.let { profile ->
            require(!profile.averageTransitionDuration.isNegative) {
                "PersonalExecutionProfile.averageTransitionDuration must not be negative, " +
                    "was ${profile.averageTransitionDuration}"
            }
            require(!profile.averagePreparationDuration.isNegative) {
                "PersonalExecutionProfile.averagePreparationDuration must not be negative, " +
                    "was ${profile.averagePreparationDuration}"
            }
        }
    }

    /**
     * §7.3テンプレート共通。`id`は決定的生成（`UUID.nameUUIDFromBytes("$eventId:$semanticId")`、
     * ADR-0017、§9エラーマップ#10）、`title`は空文字＋UI層`StepTitle.kt`が`semanticId`から
     * 解決する（G-4、§9エラーマップ#11）。`skippable`は本Phaseのテンプレートでは常に`false`
     * （§7.3表）。
     */
    private fun buildStep(
        eventId: UUID,
        semanticId: String,
        type: ExecutionStepType,
        duration: Duration,
        priority: StepPriority,
        scheduledStart: Instant
    ): ExecutionStep = ExecutionStep(
        id = UUID.nameUUIDFromBytes("$eventId:$semanticId".toByteArray()),
        semanticId = semanticId,
        type = type,
        title = "",
        estimatedDuration = duration,
        priority = priority,
        skippable = false,
        scheduledStart = scheduledStart,
        completedAt = null
    )
}
