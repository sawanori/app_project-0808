package com.actionstarter.features.eventselection

import com.actionstarter.domain.model.ExecutionEvent

/**
 * 仕様§24 Phase A・§35 Screen1準拠（EventSelectionScreen）。
 * Phase 2計画書`docs/plans/phase2-calendar.md`§7／§7.4（新EventSelectionUiState契約）に
 * 基づく契約（P2-C3前段scaffold・TDD例外）。
 *
 * エラー＆レスキューマップ#4「候補イベント0件」に対応するため、例外ではなく[Empty]へ
 * 明示的に写像する設計とする（T-MOCK-2）。[Content]は単一イベント保持から
 * `events: List<ExecutionEvent>`保持へ変更した（F18、Upcoming一覧化）。
 * [PermissionRequired]／[PermissionDenied]／[Error]はF16〜F18（権限UI・エラー表示）向けに
 * 新設した状態であり、本scaffold時点では[EventSelectionViewModel]からまだ発行されない
 * （権限・CalendarService結線はP2-C4以降で行う。描画もP2-C4以降で実装する）。
 */
sealed interface EventSelectionUiState {
    data object Loading : EventSelectionUiState

    data class Content(val events: List<ExecutionEvent>) : EventSelectionUiState

    data object Empty : EventSelectionUiState

    /** 権限未要求の初期状態（§7.4「初期・未要求」）。事前説明カード＋許可ボタンを表示する。 */
    data object PermissionRequired : EventSelectionUiState

    /** 権限拒否後の状態（§7.4）。手動入力フォーム＋Settings導線を表示する（F17）。 */
    data object PermissionDenied : EventSelectionUiState

    /** `CalendarResult.Failure`の写像先（T-SEL2-6）。[Empty]と区別し、再試行導線を出す。 */
    data object Error : EventSelectionUiState
}
