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
 * 契約scaffold追補（C2b）時点では保持プロパティの宣言と初期値（いずれもnull＝未選択・
 * 未確定）のみを定義する。選択・確定操作のロジックは未実装（C4/C5で実装）。
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
}
