package com.actionstarter.features.planreview

import androidx.lifecycle.ViewModel
import com.actionstarter.planning.PlanningEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 仕様§26準拠（PlanReviewScreenのViewModel）。
 *
 * 契約scaffold（C2）時点では初期[PlanReviewUiState]（既定値＝未取得状態）のStateFlow公開
 * のみを行う。[planningEngine]はコンストラクタ注入の形で宣言するが、Plan取得・
 * Start／Edit操作ロジックは未実装（C4で実装する）。
 */
class PlanReviewViewModel(
    private val planningEngine: PlanningEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanReviewUiState())
    val uiState: StateFlow<PlanReviewUiState> = _uiState.asStateFlow()
}
