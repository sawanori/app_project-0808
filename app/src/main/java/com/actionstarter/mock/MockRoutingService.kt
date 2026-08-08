package com.actionstarter.mock

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.services.routing.RoutingService
import java.time.Duration
import java.time.Instant

/**
 * Phase 1限定のMock実装（計画書§8 U6）。[RoutingService]（仕様§46）のMock実装。
 *
 * C5（統合サイクル）で新規追加。`features/departure/DepartureViewModel`のコンストラクタは
 * C4時点で既に`RoutingService`型を要求しており、`di/AppContainer`経由のDI結線を成立させる
 * ためには何らかの実装が必要だが、Phase 1では実Routes API呼び出しを行わない
 * （厳守事項：契約interface 4本の実装はPhase 2以降）。本クラスは決定的な固定値のみを返す
 * 最小限のMockであり、LLM等は使用しない（仕様§13/§15）。Phase 2でGoogle Maps Platform
 * Routes API等の実Provider実装に置き換わり次第、本クラスは削除する。
 */
class MockRoutingService : RoutingService {
    override suspend fun estimateRoute(
        origin: Coordinate,
        destination: Coordinate,
        mode: TransportMode,
        departureDate: Instant
    ): RouteEstimate = RouteEstimate(
        duration = DEFAULT_DURATION,
        mode = mode,
        computedAt = departureDate
    )

    private companion object {
        val DEFAULT_DURATION: Duration = Duration.ofMinutes(20)
    }
}
