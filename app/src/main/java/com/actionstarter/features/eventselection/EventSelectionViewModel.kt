package com.actionstarter.features.eventselection

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 仕様§24 Phase A準拠（EventSelectionScreenのViewModel）。
 *
 * 契約scaffold（C2）時点では初期[EventSelectionUiState]（[EventSelectionUiState.Loading]）の
 * StateFlow公開のみを行う。イベント取得ロジックは未実装（C4でMockEventSourceを
 * コンストラクタ注入して実装する。MockEventSourceはF3としてC4で新規作成されるため、
 * C2時点ではコンストラクタ依存として宣言しない）。
 */
class EventSelectionViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<EventSelectionUiState>(EventSelectionUiState.Loading)
    val uiState: StateFlow<EventSelectionUiState> = _uiState.asStateFlow()
}
