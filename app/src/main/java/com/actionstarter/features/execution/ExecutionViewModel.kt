package com.actionstarter.features.execution

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 仕様§27-28準拠（ExecutionScreenのViewModel）。
 *
 * 契約scaffold（C2）時点では初期[ExecutionUiState]（既定値）のStateFlow公開のみを行う。
 * Doneタップ・5 min laterのロジックは未実装（C4で実装する）。実行中のPlanはPlanReview画面
 * から確定済みのものを受け取る想定だが、その受け渡し経路（[com.actionstarter.navigation.SharedPlanViewModel]）
 * はNavigation統合（C5、`docs/plans/phase1-ui-skeleton-domain.md`§10.1）で結線するため、
 * C2b時点ではコンストラクタ依存として宣言しない。
 *
 * [savedStateHandle]は、画面回転後も`currentStepIndex`が保持されること（T-EXEC-7）、
 * プロセス再生成後に状態が復元されること（T-EXEC-8）、復元不能な場合はeventSelectionへ
 * 遷移しSnackbarで通知すること（T-EXEC-9、エラー＆レスキューマップ#8）の前提として、
 * 契約scaffold追補（C2b）でコンストラクタ注入の形で宣言する。本Viewの5画面中、
 * プロセス再生成からの復元を明示的に要求するテストケース（T-EXEC-7〜9）が存在するのは
 * ExecutionScreenのみであるため、`SavedStateHandle`を持つのは本ViewModelに限定する
 * （判断根拠はC2b完了報告を参照）。復元ロジック自体はC4で実装し、C2b時点では未使用。
 */
class ExecutionViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExecutionUiState())
    val uiState: StateFlow<ExecutionUiState> = _uiState.asStateFlow()
}
