package com.actionstarter.features.departure

import java.time.Duration
import java.time.Instant

/**
 * 仕様§29・§35 Screen4準拠（DepartureScreen）。
 *
 * [estimatedArrival]がnullのとき「移動時間未取得」と表示する（T-DEP-3、
 * エラー＆レスキューマップ#5）。[arrivalBuffer]が負値のとき、色とテキストの両方で
 * 明示する（T-DEP-2）。[isStartNavigationEnabled]はPhase 1で「Start navigation」が
 * 未実装のためfalse固定（T-DEP-4）。
 */
data class DepartureUiState(
    val estimatedArrival: Instant? = null,
    val eventStart: Instant? = null,
    val arrivalBuffer: Duration? = null,
    val isStartNavigationEnabled: Boolean = false
)
