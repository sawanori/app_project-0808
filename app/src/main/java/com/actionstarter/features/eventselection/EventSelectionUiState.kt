package com.actionstarter.features.eventselection

import com.actionstarter.domain.model.ExecutionEvent

/**
 * 仕様§24 Phase A・§35 Screen1準拠（EventSelectionScreen）。
 *
 * エラー＆レスキューマップ#4「MockEventSourceからのイベント一覧取得で候補0件」に対応する
 * ため、例外ではなく[Empty]へ明示的に写像する設計とする（T-MOCK-2）。
 * ViewModelの初期状態は[Loading]とする（契約scaffold=C2時点ではMockEventSource未接続の
 * ため、C4で実データ接続するまでLoadingのまま遷移しない）。
 */
sealed interface EventSelectionUiState {
    data object Loading : EventSelectionUiState

    data class Content(val nextEvent: ExecutionEvent) : EventSelectionUiState

    data object Empty : EventSelectionUiState
}
