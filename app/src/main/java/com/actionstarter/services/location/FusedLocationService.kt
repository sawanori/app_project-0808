package com.actionstarter.services.location

import android.Manifest
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.services.permission.PermissionGate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.Duration

/**
 * [LocationService]のL2実装（F22、計画書§5.2・§6.1）。[RawLocationSource]・
 * [PermissionGate]・[ForegroundGate]をコンストラクタ注入する。JVMテスト対象
 * （fakeの[RawLocationSource]／[ForegroundGate]、Robolectricの権限shadowで検証。
 * 計画書§9.3 T-LOCSVC-1〜8）。
 *
 * 想定する分岐順序（T-LOCSVC-2/7の期待どおり、Fusedを呼ばない早期リターンを優先）:
 * ①[PermissionGate.isGranted]（[Manifest.permission.ACCESS_FINE_LOCATION]）が偽なら
 * [LocationResult.PermissionDenied]、②[ForegroundGate.isLocationAccessAllowed]が偽なら
 * [LocationResult.Failure]([LocationFailureReason.BACKGROUND_RESTRICTED])、
 * ③[RawLocationSource.currentFix]が[SecurityException]を送出したら
 * [LocationResult.PermissionDenied]（実行中の権限剥奪、例外を外へ漏らさない）、
 * ④fixがnullまたは座標不正なら[LocationResult.Failure]([LocationFailureReason.UNAVAILABLE])。
 *
 * P3-C3で実装済み（T-LOCSVC-1〜8）: [RawLocationSource.currentFix]を[withTimeout]で包み、
 * [TimeoutCancellationException]のみを捕捉して[LocationFailureReason.TIMEOUT]へ写像する
 * （素の[kotlinx.coroutines.CancellationException]は再送出され、外部からのcoroutine
 * キャンセル（T-LOCSVC-6）は握り潰さず即座に伝播する。`withTimeout`自身が発火させた
 * タイムアウトのみが[TimeoutCancellationException]という別のサブ型になるため両者は
 * 型で区別できる）。fixのnullは[LocationFailureReason.UNAVAILABLE]、[Coordinate]の
 * `init`が送出する[IllegalArgumentException]（緯度経度範囲外／NaN、T-LOCSVC-8）も
 * 同じく[LocationFailureReason.UNAVAILABLE]へ捕捉して写像する（例外を外へ漏らさない）。
 */
class FusedLocationService(
    private val rawLocationSource: RawLocationSource,
    private val permissionGate: PermissionGate,
    private val foregroundGate: ForegroundGate
) : LocationService {
    override suspend fun currentLocation(timeout: Duration): LocationResult {
        if (!permissionGate.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return LocationResult.PermissionDenied
        }
        if (!foregroundGate.isLocationAccessAllowed()) {
            return LocationResult.Failure(LocationFailureReason.BACKGROUND_RESTRICTED, null)
        }

        val fix: LocationFix = try {
            withTimeout(timeout.toMillis()) {
                rawLocationSource.currentFix(timeout)
            } ?: return LocationResult.Failure(LocationFailureReason.UNAVAILABLE, null)
        } catch (e: TimeoutCancellationException) {
            return LocationResult.Failure(LocationFailureReason.TIMEOUT, e)
        } catch (e: SecurityException) {
            return LocationResult.PermissionDenied
        }

        return try {
            LocationResult.Success(Coordinate(fix.lat, fix.lon), fix.accuracyMeters, fix.fixedAt)
        } catch (e: IllegalArgumentException) {
            LocationResult.Failure(LocationFailureReason.UNAVAILABLE, e)
        }
    }
}
