package com.actionstarter.services.location

import java.time.Duration

/**
 * L3境界（計画書§5.5）。`android.location.Geocoder`型を[GeocodingService]（L2）の外へ
 * 出さない層。実装は[PlatformGeocoderSource]（L3、P3-C3）。
 *
 * **計画書§5.5からの軽微な訂正**: 計画書の当該コードブロックは`fun interface
 * GeocoderSource { fun isAvailable(): Boolean; suspend fun lookup(...) }`という
 * abstractメソッド2個の宣言を`fun interface`（Kotlin SAM、abstractメソッドは
 * 必ず1個のみ）としており、そのままでは
 * `Fun interfaces must have exactly one abstract method`でコンパイル不能である。
 * 「コンパイル可能なscaffold」というP3-C1完了条件（計画書§12）を満たすため、
 * `fun`キーワードを外した通常の`interface`として実装した（メソッド構成・シグネチャは
 * 計画書のまま変更していない）。
 */
interface GeocoderSource {
    fun isAvailable(): Boolean
    suspend fun lookup(query: String, maxResults: Int, timeout: Duration): List<LocationFix>
}
