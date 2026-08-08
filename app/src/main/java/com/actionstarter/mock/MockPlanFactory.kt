package com.actionstarter.mock

import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.planning.PlanningEngine

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
 * 契約scaffold追補（C2b）時点では本文は`TODO("C4で実装")`とし、ロジックは未実装。
 * T-MOCK-4（場所情報なしのイベントはTRAVELステップを生成せずETA未取得として扱う）・
 * T-MOCK-7（`transitionStart < now`でも生成を継続し自動省略しない。「isBehindSchedule」は
 * [ExecutionPlan]自体のフィールドではなく、呼び出し側が`transitionStart.isBefore(now)`から
 * 導出する想定。仕様§49にそのフィールドが定義されていないため）・T-MOCK-10（§13式との
 * 一致）はC4のGreen化で実装する。
 */
class MockPlanFactory : PlanningEngine {
    override suspend fun createPlan(context: PlanningContext): ExecutionPlan {
        TODO("C4で実装")
    }
}
