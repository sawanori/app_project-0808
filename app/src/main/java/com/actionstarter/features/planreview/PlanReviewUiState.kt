package com.actionstarter.features.planreview

import com.actionstarter.domain.model.ExecutionPlan

/**
 * 仕様§26・§35 Screen2準拠（PlanReviewScreen）。
 *
 * [plan]はnull許容とし、初期状態（ViewModel生成直後・Mock未接続の契約scaffold=C2時点）を
 * 「未取得」として表せるようにする。[isBehindSchedule]はtransitionStartが現在時刻より
 * 過去のとき警告表示に使う（仕様§63、エラー＆レスキューマップ#6、T-PLAN-5）。
 * [isEditEnabled]はPhase 1でEdit未実装のためfalse固定（T-PLAN-6）。
 * 準備ステップ0件でも[plan]は生成され、Start可能とする（U5、T-PLAN-4）。
 */
data class PlanReviewUiState(
    val plan: ExecutionPlan? = null,
    val isBehindSchedule: Boolean = false,
    val isEditEnabled: Boolean = false
)
