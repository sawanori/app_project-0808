package com.actionstarter.mock

import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.planning.PlanningEngine
import java.time.Duration
import java.util.UUID

/**
 * Phase 1限定のMock実装（計画書§8 U6）。仕様§13の決定的計算式
 * （`StartOfTransition = EventStart − ArrivalBuffer − TravelTime − PreparationTime − TransitionTime`、
 * `ARCHITECTURE.md`§5）に基づき[ExecutionPlan]を生成する。Phase 4（仕様§68 Basic Engine、
 * `docs/TEAMS.md`§5）でBasicPlanningEngine実装に置き換わり次第、本クラスは削除する。
 *
 * [PlanningEngine]を実装するのは、仕様書§7.1のレイヤー越境禁止規約（`features/`層は
 * `planning/`等の具象実装に直接依存してはならない）に従うため。これにより
 * `features/planreview/PlanReviewViewModel`が受け取る`PlanningEngine`型の実引数として、
 * C4/C5のDI結線時に本クラスをそのまま注入できる（コンストラクタシグネチャの変更が不要）。
 *
 * C4で実装。T-MOCK-4（場所情報なしのイベントはTRAVELステップを生成しない）・
 * T-MOCK-7（`transitionStart < now`でも生成を継続し自動省略しない。「isBehindSchedule」は
 * [ExecutionPlan]自体のフィールドではなく、呼び出し側が`transitionStart.isBefore(now)`から
 * 導出する想定。仕様§49にそのフィールドが定義されていないため）・T-MOCK-10（§13式との
 * 一致）を満たす。
 *
 * 決定的計算のみで構成する（仕様§13/§15：時刻演算はLLMに行わせない）。
 * [PlanningContext.profile]が`null`（初回利用時・学習データなし）の場合、および
 * [PlanningContext.travelEstimate]が`null`（場所情報のあるイベントだが移動時間見積り未取得）の
 * 場合は、Phase 1 Mock限定のフォールバック既定値（[DEFAULT_PREPARATION_DURATION]等）を用いる。
 * これらの既定値は仕様上の規定値ではなくMock実装のプレースホルダであり、Phase 4以降の
 * Basic/Local AI Engineでは[com.actionstarter.domain.model.PersonalExecutionProfile]の
 * 実測学習値・[com.actionstarter.services.routing.RoutingService]の実測値に置き換わる。
 */
class MockPlanFactory : PlanningEngine {
    override suspend fun createPlan(context: PlanningContext): ExecutionPlan {
        val event = context.event
        val hasLocation = event.locationName != null && event.coordinates != null

        val travelDuration = if (hasLocation) {
            context.travelEstimate ?: DEFAULT_TRAVEL_DURATION
        } else {
            Duration.ZERO
        }
        val preparationDuration = context.profile?.averagePreparationDuration ?: DEFAULT_PREPARATION_DURATION
        val transitionDuration = context.profile?.averageTransitionDuration ?: DEFAULT_TRANSITION_DURATION

        // §13: StartOfTransition = EventStart - ArrivalBuffer - TravelTime - PreparationTime - TransitionTime
        val transitionStart = event.startDate
            .minus(context.arrivalBuffer)
            .minus(travelDuration)
            .minus(preparationDuration)
            .minus(transitionDuration)

        val departureTime = event.startDate
            .minus(context.arrivalBuffer)
            .minus(travelDuration)

        val estimatedArrival = departureTime.plus(travelDuration)

        val steps = buildList {
            add(
                ExecutionStep(
                    id = UUID.randomUUID(),
                    semanticId = "transition",
                    type = ExecutionStepType.TRANSITION,
                    title = "Transition",
                    estimatedDuration = transitionDuration,
                    priority = StepPriority.IMPORTANT,
                    skippable = false,
                    scheduledStart = transitionStart,
                    completedAt = null
                )
            )
            add(
                ExecutionStep(
                    id = UUID.randomUUID(),
                    semanticId = "preparation",
                    type = ExecutionStepType.PREPARATION,
                    title = "Preparation",
                    estimatedDuration = preparationDuration,
                    priority = StepPriority.IMPORTANT,
                    skippable = false,
                    scheduledStart = transitionStart.plus(transitionDuration),
                    completedAt = null
                )
            )
            if (hasLocation) {
                add(
                    ExecutionStep(
                        id = UUID.randomUUID(),
                        semanticId = "travel",
                        type = ExecutionStepType.TRAVEL,
                        title = "Travel",
                        estimatedDuration = travelDuration,
                        priority = StepPriority.REQUIRED,
                        skippable = false,
                        scheduledStart = departureTime,
                        completedAt = null
                    )
                )
            }
            add(
                ExecutionStep(
                    id = UUID.randomUUID(),
                    semanticId = "departure",
                    type = ExecutionStepType.DEPARTURE,
                    title = "Departure",
                    estimatedDuration = Duration.ZERO,
                    priority = StepPriority.REQUIRED,
                    skippable = false,
                    scheduledStart = departureTime,
                    completedAt = null
                )
            )
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

    private companion object {
        val DEFAULT_TRAVEL_DURATION: Duration = Duration.ofMinutes(20)
        val DEFAULT_PREPARATION_DURATION: Duration = Duration.ofMinutes(15)
        val DEFAULT_TRANSITION_DURATION: Duration = Duration.ofMinutes(5)
    }
}
