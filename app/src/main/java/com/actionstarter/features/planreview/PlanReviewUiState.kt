package com.actionstarter.features.planreview

import com.actionstarter.ai.AiFallbackReason
import com.actionstarter.domain.model.ExecutionPlan

/**
 * 仕様§26・§35 Screen2準拠（PlanReviewScreen）。
 *
 * [plan]はnull許容とし、初期状態（ViewModel生成直後・Mock未接続の契約scaffold=C2時点）を
 * 「未取得」として表せるようにする。[isBehindSchedule]はtransitionStartが現在時刻より
 * 過去のとき警告表示に使う（仕様§63、エラー＆レスキューマップ#6、T-PLAN-5）。
 * [isEditEnabled]はPhase 1でEdit未実装のためfalse固定（T-PLAN-6）。
 * 準備ステップ0件でも[plan]は生成され、Start可能とする（U5、T-PLAN-4）。
 *
 * **[aiState]追加（Phase 8、計画書§4.4、末尾・既定[AiContextualizationState.Idle]）**:
 * Basic→AI文脈化の遷移をテストで観測可能にする目的が主（将来Analyticsにも使う）。
 * `combine`（[com.actionstarter.features.planreview.PlanReviewViewModel]§4.2）の再計算のたびに
 * 導出される値であり、失敗（[AiContextualizationState.FellBack]）でも画面上は無表現
 * （Basic固定文言が残るだけ、§5.2「AI失敗専用バナーは作らない」）。
 */
data class PlanReviewUiState(
    val plan: ExecutionPlan? = null,
    val isBehindSchedule: Boolean = false,
    val isEditEnabled: Boolean = false,
    val aiState: AiContextualizationState = AiContextualizationState.Idle
)

/**
 * Phase 8（計画書§4.4）。PlanReview画面のAI文脈化フェーズの観測用状態。
 * [FellBack]のみ理由（[AiFallbackReason]）を保持するため`enum`ではなく`sealed interface`とする
 * （[com.actionstarter.ai.AiResult]・[com.actionstarter.ai.ContextualizationResult]と同型の
 * 「理由付き失敗はdata classで運ぶ」パターン）。
 */
sealed interface AiContextualizationState {
    /** [com.actionstarter.features.planreview.PlanReviewViewModel]がAI非活性（後方互換、T-P8-13）のときの唯一の状態。 */
    data object Idle : AiContextualizationState

    /** base（Basic、travel解決含む）は表示済みだがAI応答が未到達（当該eventId分はまだ）。 */
    data object InProgress : AiContextualizationState

    /** AI応答が適用され、[PlanReviewUiState.plan]がoverlay済み。 */
    data object Applied : AiContextualizationState

    /** AIがフォールバックした（5系統いずれか）。[PlanReviewUiState.plan]はBasicのまま。 */
    data class FellBack(val reason: AiFallbackReason) : AiContextualizationState
}
