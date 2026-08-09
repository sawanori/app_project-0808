package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import java.time.Instant

/**
 * F29実装（計画書§6.1・§7.3・§8）。Routes APIキー未設定（`BuildConfig.ROUTES_API_KEY`が
 * 空文字）時に`AppContainer`（P3-C6で結線）が供給する[RoutingService]実装。常に
 * [RoutingException.NotConfigured]を送出し、固定20分などの偽ETAを返さない
 * （エラー＆レスキューマップ#19、T-CACHE-9・T-ROUTESVC-8。旧`mock/MockRoutingService`が
 * 抱えていた「キー未設定でも常に20分を返す」サイレント障害を解消する）。
 *
 * **本サイクル（P3-C1、TDD例外の対象外）で実装可**: 分岐・状態を持たない無条件送出の
 * 1行であり、計画書§12 P3-C1行が明記する「実装可」対象そのもの
 * （[ActivityLifecycleForegroundGate]と同じ「トリビアルで恣意的実装リスクがない」
 * 判断基準）。T-ROUTESVC-8（HTTPを1回も発行しないことの検証）はP3-C2/C4で別途固定する。
 */
class UnconfiguredRoutingService : RoutingService {
    override suspend fun estimateRoute(
        origin: Coordinate,
        destination: Coordinate,
        mode: TransportMode,
        departureDate: Instant
    ): RouteEstimate = throw RoutingException.NotConfigured()
}
