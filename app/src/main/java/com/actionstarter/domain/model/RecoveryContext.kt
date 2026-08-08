package com.actionstarter.domain.model

import com.actionstarter.domain.valueobject.Coordinate
import java.time.Duration
import java.time.Instant

/**
 * 仕様§50準拠（Recovery Context）。ADR-0010によりval化（仕様書は元々全フィールドval）。
 *
 * [RecoveryEngine.createRecoveryPlan]（仕様§45）の入力。Reality Check（仕様§30）で
 * 比較した`planned state` vs `actual state`をRecovery生成へ渡すための型。
 * [currentLocation]は位置情報未取得時に対応するためnull許容。
 */
data class RecoveryContext(
    val currentTime: Instant,
    val currentLocation: Coordinate?,
    val event: ExecutionEvent,
    val unfinishedSteps: List<ExecutionStep>,
    val latestTravelEstimate: Duration,
    val plannedDepartureTime: Instant
)
