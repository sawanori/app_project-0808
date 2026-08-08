package com.actionstarter.domain.valueobject

/**
 * ADR-0005により仕様書未定義のため計画書§9.2で補完定義する型。
 * 緯度経度座標。[com.actionstarter.services.routing.RoutingService.estimateRoute]
 * （仕様§46）の引数、[com.actionstarter.domain.model.ExecutionEvent.coordinates]
 * （仕様§47）等で使用する。
 *
 * 信頼境界（外部入力）：GPS・Geocoding API・カレンダー位置情報等、外部由来の値が
 * 入り得るため、範囲検証（緯度±90／経度±180）とNaN拒否を`init`で行う設計とする
 * （計画書§9.2）。
 *
 * C4でinit検証を実装（T-DM-1〜T-DM-5）。`data class`のまま`copy()`もコンストラクタを
 * 経由するため、`copy()`実行時にも同じ検証が再実行される（ADR-0010）。
 */
data class Coordinate(
    val lat: Double,
    val lon: Double
) {
    init {
        require(!lat.isNaN()) { "Coordinate.lat must not be NaN" }
        require(!lon.isNaN()) { "Coordinate.lon must not be NaN" }
        require(lat in -90.0..90.0) { "Coordinate.lat must be within -90..90, was $lat" }
        require(lon in -180.0..180.0) { "Coordinate.lon must be within -180..180, was $lon" }
    }
}
