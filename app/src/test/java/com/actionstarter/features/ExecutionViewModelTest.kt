package com.actionstarter.features

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.features.execution.ExecutionViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F6 — Execution ViewModel単体テスト（計画書§11.2、T-EXEC-7〜9）。
 *
 * 実装注記（T-EXEC-8、A4・計画書§11）: プロセス再生成の検証はRobolectric単体テストとして
 * 分離し、ViewModelへSavedStateHandleを手動注入して復元状態をアサートする方式で行う
 * （Compose UI経由の実プロセスKillは模擬しない）。T-EXEC-7（画面回転）も同一の手法で扱う
 * （回転単体では通常ViewModelインスタンス自体は破棄されないが、本テストではワーストケースの
 * 「新規インスタンス＋SavedStateHandle復元」経路を検証することで両ケースを包含する）。
 *
 * ExecutionViewModelは現時点（C2契約scaffold）でsavedStateHandleを一切参照せず、常に
 * 既定値のExecutionUiState()を公開するため、以下のテストはすべて「復元されるべき値と
 * 実際の既定値が異なる」ことによりRedになるのが正しい。
 *
 * 前提（本ファイル内で共有するSavedStateHandleキー規約。C4実装時の採用を期待するが、
 * 未採用の場合はC4実装に合わせてキー名を更新する）:
 * - "currentStepIndex": Int … 現在のステップindex
 */
@RunWith(AndroidJUnit4::class)
class ExecutionViewModelTest {

    // T-EXEC-7: エッジケース - 画面回転後もcurrentStepIndexが保持される
    @Test
    fun tExec7_rotation_preservesCurrentStepIndex() {
        val restoredHandle = SavedStateHandle(mapOf(KEY_CURRENT_STEP_INDEX to 2))
        val viewModel = ExecutionViewModel(restoredHandle)

        assertEquals(2, viewModel.uiState.value.currentStepIndex)
    }

    // T-EXEC-8: エッジケース - プロセス再生成後、SavedStateHandleから状態が復元される
    @Test
    fun tExec8_processRecreation_restoresCurrentStepIndexFromSavedStateHandle() {
        val restoredHandle = SavedStateHandle(mapOf(KEY_CURRENT_STEP_INDEX to 1))
        val viewModel = ExecutionViewModel(restoredHandle)

        assertEquals(1, viewModel.uiState.value.currentStepIndex)
    }

    // T-EXEC-9: 異常系 - 復元不能な場合はeventSelectionへ遷移しSnackbarで通知する（空画面を出さない）
    // ExecutionViewModel自体はNavigationを行わない（NavHost結線はC5）ため、本テストは
    // ViewModelが観測可能な契約であるsnackbarMessageResIdが非nullになること、および
    // 復元不能時にcurrentStepが空のまま提示されない（＝currentStep=nullで「空画面」に
    // ならないようUI側がsnackbarMessageResIdを見て遷移する契約になっていること）を検証する
    // （実際の画面遷移はT-NAV／T-E2E側で担保する）。
    @Test
    fun tExec9_unrecoverableState_setsSnackbarMessageForEventSelectionRedirect() {
        val corruptHandle = SavedStateHandle(mapOf(KEY_CURRENT_STEP_INDEX to -1))
        val viewModel = ExecutionViewModel(corruptHandle)

        assertEquals(R.string.execution_restored_snackbar_message, viewModel.uiState.value.snackbarMessageResId)
        assertNull(viewModel.uiState.value.currentStep)
    }

    private companion object {
        const val KEY_CURRENT_STEP_INDEX = "currentStepIndex"
    }
}
