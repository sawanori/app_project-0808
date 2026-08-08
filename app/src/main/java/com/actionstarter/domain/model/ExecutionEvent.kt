package com.actionstarter.domain.model

import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import java.time.Instant
import java.util.UUID

/**
 * 仕様§47準拠（Core Domain — Event）。ADR-0010によりval化（仕様書は元々全フィールドval）。
 *
 * カレンダー由来の1イベントを表すDomain Model。[coordinates]・[locationName]は
 * 場所情報がないイベント（仕様§13/エラー＆レスキューマップ#5）に対応するためnull許容。
 */
data class ExecutionEvent(
    val id: UUID,
    val externalCalendarId: String?,
    val title: String,
    val notes: String?,
    val startDate: Instant,
    val locationName: String?,
    val coordinates: Coordinate?,
    val sourceCalendar: CalendarSource
)
