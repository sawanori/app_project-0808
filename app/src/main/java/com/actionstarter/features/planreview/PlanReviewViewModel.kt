package com.actionstarter.features.planreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.planning.PlanningEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 仕様§26準拠（PlanReviewScreenのViewModel）。
 *
 * C5（統合サイクル）で[planningEngine]によるPlan生成ロジックを実装した。
 * [sharedPlanViewModel.selectedEvent]（activity-scoped、計画書§10.1）を購読し、
 * イベントが選択されるたびに[PlanningEngine.createPlan]（決定的計算、Mock実装は
 * LLM不使用・仕様§13/§15）を呼び出してPlanを生成する。
 *
 * [arrivalBuffer]／[transportMode]はPhase 1のMock限定の既定値であり、仕様上の規定値では
 * ない（Personal Execution Profile・実測RoutingServiceに置き換わるのはPhase 4以降）。
 *
 * 「画面表示だけでは自動的にexecutionへ遷移しない」（T-PLAN-2、仕様§26）という制約は
 * 本ViewModelでは状態を更新するのみで満たされる：実際のNavigation呼び出しは
 * [confirmAndStart]を呼んだ`ActionStarterNavHost`側で行う（本ViewModelはNavController
 * を保持しない）。
 */
class PlanReviewViewModel(
    private val planningEngine: PlanningEngine,
    private val sharedPlanViewModel: SharedPlanViewModel
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanReviewUiState())
    val uiState: StateFlow<PlanReviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sharedPlanViewModel.selectedEvent.collect { event ->
                if (event != null) {
                    val plan = planningEngine.createPlan(buildPlanningContext(event))
                    val isBehindSchedule = plan.transitionStart.isBefore(Instant.now())
                    _uiState.value = PlanReviewUiState(
                        plan = plan,
                        isBehindSchedule = isBehindSchedule,
                        isEditEnabled = false
                    )
                }
            }
        }
    }

    /**
     * 「Start」タップ相当の操作（T-PLAN-3）。生成済みのPlanを
     * [SharedPlanViewModel.confirmPlan]で確定する。Planが未生成（`null`）の場合は
     * 何もしない（画面側のStartボタンはPlan生成後にのみ有効な文脈で呼ばれる想定）。
     */
    fun confirmAndStart() {
        val plan = _uiState.value.plan ?: return
        sharedPlanViewModel.confirmPlan(plan)
    }

    private fun buildPlanningContext(event: ExecutionEvent): PlanningContext = PlanningContext(
        event = event,
        now = Instant.now(),
        zoneId = ZoneId.systemDefault(),
        locale = Locale.getDefault(),
        transportMode = TransportMode.WALKING,
        travelEstimate = null,
        arrivalBuffer = DEFAULT_ARRIVAL_BUFFER,
        profile = null
    )

    private companion object {
        val DEFAULT_ARRIVAL_BUFFER: Duration = Duration.ofMinutes(10)
    }
}
