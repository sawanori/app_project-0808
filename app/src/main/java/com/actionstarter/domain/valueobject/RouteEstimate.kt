package com.actionstarter.domain.valueobject

import java.time.Duration
import java.time.Instant

/**
 * ADR-0005により仕様書未定義のため計画書§9.2で補完定義する型。
 * [com.actionstarter.services.routing.RoutingService.estimateRoute]（仕様§46）の戻り値。
 *
 * [computedAt]はRoutes API呼び出し結果のキャッシュ・再計算判定
 * （仕様§95.2スロットリング要件）のために保持する。
 */
data class RouteEstimate(
    val duration: Duration,
    val mode: TransportMode,
    val computedAt: Instant
)
