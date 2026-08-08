package com.actionstarter.features.execution

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.util.UUID

/**
 * 仕様§27-28準拠（ExecutionScreenのViewModel）。
 *
 * [savedStateHandle]は、画面回転後も`currentStepIndex`が保持されること（T-EXEC-7）、
 * プロセス再生成後に状態が復元されること（T-EXEC-8）、復元不能な場合はeventSelectionへ
 * 遷移しSnackbarで通知すること（T-EXEC-9、エラー＆レスキューマップ#8）を満たすため
 * コンストラクタ注入の形で保持する。SavedStateHandleキー規約は`"currentStepIndex"`
 * （[KEY_CURRENT_STEP_INDEX]）に固定する（C3テストと共有する規約）。
 *
 * 既知の設計（C5裁定・存置確定。完了報告「タスク7の判断結果」参照）: 本ViewModelは
 * 実行中のPlan（[com.actionstarter.domain.model.ExecutionPlan]）を注入されず、
 * [currentStepIndex]が有効範囲（[0, PLACEHOLDER_STEP_COUNT)）にある間は常にプレースホルダの
 * [ExecutionStep]を用いて[ExecutionUiState.currentStep]を構成する（`title`は空文字とし、
 * Screen側で[R.string.execution_placeholder_step_title]にフォールバック表示する。ハード
 * コードUI文字列を持ち込まないため）。NavHost（[com.actionstarter.navigation.
 * ActionStarterNavHost]）は実行時、本ViewModelのコンストラクタ契約（`SavedStateHandle`
 * のみ。C4の`ExecutionViewModelTest`／`ExecutionScreenTest`に束縛されているため変更不可）
 * を維持したまま、本ViewModelを経由せず[com.actionstarter.navigation.SharedPlanViewModel.
 * confirmedPlan]から直接[ExecutionUiState]を構築する設計をC5で確定している。本ViewModel
 * のプレースホルダ挙動は単体テスト（T-EXEC-3/6/7/8/9）で引き続き検証されるため存置する。
 */
class ExecutionViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(stateForIndex(restoredIndex()))
    val uiState: StateFlow<ExecutionUiState> = _uiState.asStateFlow()

    private fun restoredIndex(): Int = savedStateHandle.get<Int>(KEY_CURRENT_STEP_INDEX) ?: 0

    /**
     * [index]から[ExecutionUiState]を構成する。
     * - `index < 0` … 復元不能（破損）状態。eventSelectionへ遷移しSnackbar通知する
     *   契約を表すため[ExecutionUiState.snackbarMessageResId]を設定する（T-EXEC-9）。
     * - `index >= PLACEHOLDER_STEP_COUNT` … プレースホルダのステップを使い切った正常終了。
     *   Screen側は`currentStep == null && snackbarMessageResId == null`をもって
     *   departureへの直行と解釈する（T-EXEC-4／T-EXEC-5と同一の契約）。
     * - それ以外 … 有効なステップindex。[ExecutionUiState.onDone]／[ExecutionUiState.onPostpone]
     *   を本インスタンスのメソッドへ束縛して公開する。
     */
    private fun stateForIndex(index: Int): ExecutionUiState = when {
        index < 0 -> ExecutionUiState(
            currentStep = null,
            currentStepIndex = index,
            snackbarMessageResId = R.string.execution_restored_snackbar_message
        )

        index >= PLACEHOLDER_STEP_COUNT -> ExecutionUiState(
            currentStep = null,
            currentStepIndex = index,
            snackbarMessageResId = null
        )

        else -> ExecutionUiState(
            currentStep = placeholderStep(index),
            currentStepIndex = index,
            onDone = ::handleDone,
            onPostpone = ::handlePostpone
        )
    }

    private fun handleDone() {
        val next = _uiState.value.currentStepIndex + 1
        savedStateHandle[KEY_CURRENT_STEP_INDEX] = next
        _uiState.value = stateForIndex(next)
    }

    private fun handlePostpone() {
        val current = _uiState.value.currentStep ?: return
        val postponed = current.copy(scheduledStart = current.scheduledStart?.plus(POSTPONE_DURATION))
        _uiState.value = _uiState.value.copy(currentStep = postponed)
    }

    private fun placeholderStep(index: Int): ExecutionStep = ExecutionStep(
        id = PLACEHOLDER_STEP_IDS[index],
        semanticId = "execution_placeholder_step_$index",
        type = ExecutionStepType.PREPARATION,
        title = "",
        estimatedDuration = Duration.ZERO,
        priority = StepPriority.OPTIONAL,
        skippable = true,
        scheduledStart = null,
        completedAt = null
    )

    private companion object {
        const val KEY_CURRENT_STEP_INDEX = "currentStepIndex"
        const val PLACEHOLDER_STEP_COUNT = 3
        val POSTPONE_DURATION: Duration = Duration.ofMinutes(5)
        val PLACEHOLDER_STEP_IDS: List<UUID> = List(PLACEHOLDER_STEP_COUNT) { index ->
            UUID.nameUUIDFromBytes("execution-placeholder-step-$index".toByteArray())
        }
    }
}
