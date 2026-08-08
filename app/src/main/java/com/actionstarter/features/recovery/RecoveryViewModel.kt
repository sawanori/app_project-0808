package com.actionstarter.features.recovery

import androidx.lifecycle.ViewModel
import com.actionstarter.recovery.RecoveryEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 仕様§31-34準拠（RecoveryScreenのViewModel）。
 *
 * 契約scaffold（C2）時点では初期[RecoveryUiState]（既定値＝候補0件）のStateFlow公開のみを
 * 行う。[recoveryEngine]はコンストラクタ注入の形で宣言するが、Recovery候補取得・選択・
 * 確定ロジック（仕様§34）は未実装（C4で実装する）。
 */
class RecoveryViewModel(
    private val recoveryEngine: RecoveryEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState.asStateFlow()
}
