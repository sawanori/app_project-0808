package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate

/**
 * L1純粋関数（F25補助、計画書§6.1・§9.2 T-GEODIST-1〜3）。Haversine距離（メートル）。
 * [CachingRoutingService]の距離閾値判定（§95.2「例: 500m」）に使用する。
 *
 * 地球半径は計画書が具体値を指定しないため、Haversine実装で最も一般的な
 * R=6371000m（平均半径）を採用する（[GeoDistanceTest]・[CachingRoutingServiceTest]の
 * T-CACHE-3が同じRを仮定して500mちょうど境界の期待値を組み立てている）。
 *
 * 同一座標（Δφ=Δλ=0）では中心角の中間値`h`が理論上ちょうど0になるが、浮動小数点の
 * 丸めによりごく僅かに負値へぶれる可能性があるため、`asin`の定義域エラー（NaN化）を
 * 避けるために[0,1]へクランプしてから`sqrt`/`asin`する（T-GEODIST-2）。
 *
 * **緯度・経度差の計算順序（数値精度上の理由）**: 各点を先に`Math.toRadians`でラジアン化
 * してから減算する（例: `lat2Rad - lat1Rad`）。度領域で減算してから改めて`toRadians`する
 * 経路（例: `Math.toRadians(b.lat - a.lat)`）は、`cos(lat1)`/`cos(lat2)`項で既に必要な
 * ラジアン値を再利用せず、独立した丸めをもう1回追加することになる。500mのような極小
 * 距離ではこの追加の丸めが符号を左右し得ることを実測で確認した（R=6371000m・緯度のみ
 * 500m移動という構成で、度→ラジアン→度の往復パスは約-3.2e-10mの誤差を生み499.999…側へ
 * 倒れ、T-CACHE-3が要求する「500mちょうどは再計算（`< 500`で不成立）」を満たせなくなる。
 * 本実装のラジアン先行パスは同じ入力で約+6.9e-11m側へ倒れ、閾値判定と整合する）。
 */
object GeoDistance {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun meters(a: Coordinate, b: Coordinate): Double {
        val lat1Rad = Math.toRadians(a.lat)
        val lat2Rad = Math.toRadians(b.lat)
        val lon1Rad = Math.toRadians(a.lon)
        val lon2Rad = Math.toRadians(b.lon)
        val deltaLatRad = lat2Rad - lat1Rad
        val deltaLonRad = lon2Rad - lon1Rad

        val sinHalfDeltaLat = Math.sin(deltaLatRad / 2.0)
        val sinHalfDeltaLon = Math.sin(deltaLonRad / 2.0)

        // Haversine公式: h = sin²(Δφ/2) + cos(φ1)・cos(φ2)・sin²(Δλ/2)
        val h = sinHalfDeltaLat * sinHalfDeltaLat +
            Math.cos(lat1Rad) * Math.cos(lat2Rad) * sinHalfDeltaLon * sinHalfDeltaLon
        val clampedH = h.coerceIn(0.0, 1.0)
        val centralAngle = 2.0 * Math.asin(Math.sqrt(clampedH))

        return EARTH_RADIUS_METERS * centralAngle
    }
}
