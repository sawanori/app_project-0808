package com.actionstarter.services.location

import java.time.Duration
import java.time.Instant

/**
 * L3境界（計画書§5.5）。`services/location/`のうち、Play Services型（`Location`／
 * `FusedLocationProviderClient`等）を外へ出さない層。[LocationService]（L2）はこの
 * 契約越しにのみ現在地取得を行い、`com.google.android.gms.*`型を一切importしない。
 *
 * 実装は[FusedRawLocationSource]（L3、P3-C3）。JVMテストでは fake実装を注入するため、
 * `com.google.android.gms.*`にもAndroidフレームワーク型にも一切触れずにテストできる
 * （Phase 2の`CursorSource`3層分割と同一パターン、計画書§5.5）。
 */
fun interface RawLocationSource {
    /** @throws SecurityException 権限が実行中に剥奪された場合 */
    suspend fun currentFix(timeout: Duration): LocationFix?
}

/** 計画書§5.5。GMS非依存の最小位置fix表現。 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float?,
    val fixedAt: Instant
)
