package com.actionstarter.mock

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import java.time.Instant
import java.util.UUID

/**
 * Phase 1限定のMockイベント供給源（計画書§8 U6）。Phase 2（仕様§66 CalendarProvider）で
 * 実カレンダー読取実装に置き換わり次第、本クラスは削除する。
 *
 * どのinterface（[com.actionstarter.planning.PlanningEngine]等）にも対応しないのは、
 * Phase 1時点でイベント取得を抽象化する契約interfaceが仕様上定義されていないため
 * （CalendarService相当のinterfaceはPhase 2で新設）。[events]をコンストラクタで
 * 差し替え可能にしているのはT-MOCK-2（空リスト）／T-MOCK-3（全件過去）／T-MOCK-5
 * （翌日イベント）／T-MOCK-6（進行中イベント）等、`now`との相対関係で分岐するテスト
 * シナリオをテスト側で構築可能にするため。
 *
 * 契約scaffold追補（C2b）時点では全メソッドの本文は`TODO("C4で実装")`とし、
 * ロジックは未実装。
 */
class MockEventSource(
    private val events: List<ExecutionEvent> = emptyList()
) {

    /**
     * T-MOCK-2／T-MOCK-3／T-MOCK-5／T-MOCK-6が要求する「イベントリスト供給」操作。
     * [now]を基準に、未来かつ未開始の候補イベント一覧を返す設計とする
     * （開始済み・過去のイベントは除外。§48にはイベント終了時刻の概念がないため、
     * 「進行中」と「過去」は`startDate <= now`として同一の除外条件で扱う想定）。
     */
    fun upcomingEvents(now: Instant): List<ExecutionEvent> {
        TODO("C4で実装")
    }

    /**
     * T-MOCK-1／T-MOCK-3／T-MOCK-5／T-MOCK-6が要求する「次イベント取得」操作。
     * 候補が0件の場合は例外にせずnullを返す（UI側は[com.actionstarter.features.eventselection.EventSelectionUiState.Empty]
     * へ写像する。エラー＆レスキューマップ#4）。
     */
    fun nextEvent(now: Instant): ExecutionEvent? {
        TODO("C4で実装")
    }

    /**
     * T-MOCK-8／T-MOCK-9が対象とする「MockEventSourceのイベント生成」操作。
     * [title]が空文字列、または[startDate]が`Instant.MIN`の場合は`require()`により
     * 即時失敗する設計とする（エラー＆レスキューマップ#3：不正なMockデータの握り潰し禁止）。
     */
    fun createEvent(
        title: String,
        startDate: Instant,
        id: UUID = UUID.randomUUID(),
        externalCalendarId: String? = null,
        notes: String? = null,
        locationName: String? = null,
        coordinates: Coordinate? = null,
        sourceCalendar: CalendarSource = CalendarSource(id = "mock", displayName = "Mock Calendar")
    ): ExecutionEvent {
        TODO("C4で実装")
    }
}
