package com.actionstarter.features.eventselection

import androidx.lifecycle.ViewModel
import com.actionstarter.mock.MockEventSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * 仕様§24 Phase A準拠（EventSelectionScreenのViewModel）。
 *
 * C5（統合サイクル）で[mockEventSource]をコンストラクタ注入し、イベント取得ロジックを
 * 実装した。エラー＆レスキューマップ#4に従い、次イベント候補が0件の場合は例外にせず
 * [EventSelectionUiState.Empty]へ明示的に写像する（T-MOCK-2）。[mockEventSource.nextEvent]は
 * 純粋な同期計算（決定的計算のみ、LLM不使用・仕様§13/§15）のため、`init`ブロック内で
 * 同期的に完了する。
 */
class EventSelectionViewModel(
    private val mockEventSource: MockEventSource
) : ViewModel() {

    private val _uiState = MutableStateFlow<EventSelectionUiState>(EventSelectionUiState.Loading)
    val uiState: StateFlow<EventSelectionUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * [mockEventSource]から最新の次イベント候補を再取得する。将来的な再訪問時の
     * リフレッシュ導線として公開メソッドにしている（Phase 1では`init`からのみ呼ばれる）。
     */
    fun refresh() {
        val nextEvent = mockEventSource.nextEvent(now = Instant.now())
        _uiState.value = if (nextEvent != null) {
            EventSelectionUiState.Content(nextEvent = nextEvent)
        } else {
            EventSelectionUiState.Empty
        }
    }
}
