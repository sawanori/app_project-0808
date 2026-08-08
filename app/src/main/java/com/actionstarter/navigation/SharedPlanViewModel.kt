package com.actionstarter.navigation

import androidx.lifecycle.ViewModel
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 計画書§10.1準拠：「選択されたイベント・確定済みPlanはactivity-scoped共有ViewModelが
 * 保持する」に対応するスタブ。`MainActivity`（Activity）スコープで1つ生成し、
 * `ActionStarterNavHost`配下の各画面ViewModelから参照させる想定（結線はC5）。
 *
 * T-NAV-4（Planが未確定のままexecutionへ到達しようとした場合、`popUpTo`でeventSelectionへ
 * 戻す）の前提条件判定は、本クラスの[confirmedPlan]がnullかどうかで行う想定
 * （NavHost側のガードロジックとしてC5で実装）。
 *
 * C5（統合サイクル）で選択・確定操作のロジックを実装した。選択・確定操作のロジックは
 * 単純な状態代入のみで構成し（決定的計算・LLM不使用、仕様§13/§15）、[ActionStarterNavHost]や
 * 各画面ViewModel（`PlanReviewViewModel`／`RecoveryViewModel`）から結線される。
 *
 * プロセス再生成時の復元は本クラスの責務としない：計画書§11.2のテストケースを確認した
 * 限り、プロセス死後の復元を明示的に要求するのは[com.actionstarter.features.execution.ExecutionViewModel]
 * （T-EXEC-8、`SavedStateHandle`経由）のみであり、本クラスへの`SavedStateHandle`注入は
 * 対象外と判断した（判断根拠は完了報告を参照）。
 */
class SharedPlanViewModel : ViewModel() {

    private val _selectedEvent = MutableStateFlow<ExecutionEvent?>(null)
    val selectedEvent: StateFlow<ExecutionEvent?> = _selectedEvent.asStateFlow()

    private val _confirmedPlan = MutableStateFlow<ExecutionPlan?>(null)
    val confirmedPlan: StateFlow<ExecutionPlan?> = _confirmedPlan.asStateFlow()

    /**
     * Event Selection画面での「Prepare this event」相当の操作（T-SEL-2、NavHost結線）。
     * 新しいイベントを選択した時点で、以前確定していたPlan（別イベント由来の可能性がある）
     * を無効化する（[confirmedPlan]をnullへ戻す。T-NAV-4ガードが正しく機能するための
     * 前提でもある）。
     */
    fun selectEvent(event: ExecutionEvent) {
        _selectedEvent.value = event
        _confirmedPlan.value = null
    }

    /**
     * Plan Review画面での「Start」相当の操作（T-PLAN-3、NavHost結線）。Planを確定させる。
     * T-NAV-4ガード（Plan未確定のままexecutionへ到達しようとした場合の防御）は
     * [confirmedPlan]のnull判定で行う。
     */
    fun confirmPlan(plan: ExecutionPlan) {
        _confirmedPlan.value = plan
    }
}
