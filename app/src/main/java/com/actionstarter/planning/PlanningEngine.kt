package com.actionstarter.planning

import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.PlanningContext

/**
 * 仕様§44準拠（PlanningEngine Interface）。
 *
 * カレンダー由来の[com.actionstarter.domain.model.ExecutionEvent]から実行計画
 * [ExecutionPlan]を生成する契約。Basic Engine（決定的計算のみ、Phase 4）と
 * Local AI Engine（端末内LLM、Phase 8）の両方がこのinterfaceを実装し、
 * 呼び出し側（画面ViewModel）からは交換可能に扱う（仕様§12）。
 *
 * 契約scaffold（C2）時点では宣言のみであり、実装は行わない。
 */
interface PlanningEngine {
    suspend fun createPlan(context: PlanningContext): ExecutionPlan
}
