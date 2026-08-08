package com.actionstarter.features.execution

import com.actionstarter.domain.model.ExecutionStep

/**
 * 仕様§27-28・§35 Screen3準拠（ExecutionScreen）。One Action原則（仕様§28）により、
 * 同時に1ステップ（[currentStep]）のみを保持する（畳んだリスト・進捗プレビューは
 * 保持しない）。
 *
 * [currentStepIndex]は画面回転後も保持されるべき値（T-EXEC-7）で、プロセス再生成後は
 * `SavedStateHandle`から復元する（T-EXEC-8、C4で実装）。
 * [snackbarMessageResId]は復元不能時にeventSelectionへ遷移する際の通知
 * （エラー＆レスキューマップ#8、T-EXEC-9）に対応するための文字列リソースID
 * （UI文字列ハードコード禁止のためstring resource ID経由で保持する）。
 */
data class ExecutionUiState(
    val currentStep: ExecutionStep? = null,
    val currentStepIndex: Int = 0,
    val snackbarMessageResId: Int? = null
)
