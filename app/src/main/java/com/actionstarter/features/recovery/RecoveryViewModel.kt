package com.actionstarter.features.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.recovery.RecoveryEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * 仕様§31-34準拠（RecoveryScreenのViewModel）。
 *
 * C5（統合サイクル）で[recoveryEngine]によるRecovery候補生成ロジックを実装した。
 * [sharedPlanViewModel.confirmedPlan]（Execution画面到達時点で確定済みのはず。
 * T-NAV-4ガードにより`ActionStarterNavHost`側でnull時はexecutionへ到達させない）から
 * [RecoveryContext]を構築し、[RecoveryEngine.createRecoveryPlan]（決定的計算、Mock実装は
 * LLM不使用・仕様§13/§15）を呼び出す。
 *
 * 候補選択は自動適用されない（仕様§34、T-REC-5）: [RecoveryUiState.selectedOptionId]の
 * 「仮選択」状態自体はRecoveryScreen側（画面ローカルstate）が保持し、本ViewModelは
 * 候補一覧の供給のみを担当する。
 */
class RecoveryViewModel(
    private val recoveryEngine: RecoveryEngine,
    private val sharedPlanViewModel: SharedPlanViewModel
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val plan = sharedPlanViewModel.confirmedPlan.value ?: return@launch
            val recoveryPlan = recoveryEngine.createRecoveryPlan(buildRecoveryContext(plan))
            _uiState.value = RecoveryUiState(options = recoveryPlan.options, selectedOptionId = null)
        }
    }

    private fun buildRecoveryContext(plan: ExecutionPlan): RecoveryContext {
        val remainingTravel = Duration.between(plan.departureTime, plan.estimatedArrival).let {
            if (it.isNegative) Duration.ZERO else it
        }
        return RecoveryContext(
            currentTime = Instant.now(),
            currentLocation = plan.event.coordinates,
            event = plan.event,
            unfinishedSteps = plan.steps,
            latestTravelEstimate = remainingTravel,
            plannedDepartureTime = plan.departureTime
        )
    }
}
