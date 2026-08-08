package com.actionstarter.domain.valueobject

/**
 * ADR-0005により仕様書未定義のため計画書§9.2で補完定義する型。
 * [com.actionstarter.domain.model.ExecutionEvent.sourceCalendar]（仕様§47）で使用する
 * イベントの取得元カレンダー。
 *
 * 仕様§6（Global-first設計）の禁止事項「日本固有・特定プロバイダ固有の生活前提を
 * 埋め込まない」に従い、enum（特定カレンダープロバイダの決め打ち）ではなくdata classと
 * する（計画書§9.2で明記）。[id]は[ExecutionEvent.externalCalendarId]と同様に
 * プロバイダ非依存な文字列表現とし、特定プラットフォーム（例：Android CalendarProviderの
 * Long型`_ID`）に決め打ちしない。
 */
data class CalendarSource(
    val id: String,
    val displayName: String
)
