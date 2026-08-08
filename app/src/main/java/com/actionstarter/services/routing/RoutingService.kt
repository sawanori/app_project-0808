package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import java.time.Instant

/**
 * 仕様§46準拠（Routing abstraction）。ADR-0004によりシグネチャを確定
 * （旧仕様§9のコード例との不一致を解消し、`departureDate`引数ありの本シグネチャに統一）。
 *
 * `departureDate`が必要な理由（ADR-0004）：TRANSITモードの所要時間は出発時刻に依存し、
 * Departure Mode（仕様§29）・Reality Check（仕様§30）での再計算に出発時刻が必須のため。
 *
 * Google Maps Platform Routes APIを第一候補とし、Mapbox / HERE / OSM系（GraphHopper等）へ
 * 差し替え可能なProvider抽象とする（仕様§9）。
 *
 * 契約scaffold（C2）時点では宣言のみであり、実装は行わない。
 */
interface RoutingService {
    suspend fun estimateRoute(
        origin: Coordinate,
        destination: Coordinate,
        mode: TransportMode,
        departureDate: Instant
    ): RouteEstimate
}
