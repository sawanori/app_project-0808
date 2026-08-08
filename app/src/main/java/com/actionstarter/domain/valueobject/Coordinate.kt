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
 * 契約scaffold（C2）時点では上記`init`検証は未実装。C3のRedテスト作成後、C4で実装する
 * （C2で実装するとC3のRedが成立しなくなるため）。
 */
data class Coordinate(
    val lat: Double,
    val lon: Double
)
