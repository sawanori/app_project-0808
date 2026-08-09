package com.actionstarter.services.location

import android.Manifest
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.services.permission.AndroidPermissionGate
import com.actionstarter.services.permission.PermissionGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant

/**
 * T-LOCSVC-1〜8（計画書§9.3、契約は§5.2・`FusedLocationService.kt`のKDoc）。対象は
 * [FusedLocationService]（L2）。現状（P3-C1契約scaffold）は
 * [FusedLocationService.currentLocation]の本文が`TODO("P3-C3で実装")`のため、以下は全件
 * `NotImplementedError`によりRedになる（意図した失敗）。
 *
 * [RawLocationSource]・[ForegroundGate]はfake実装（[FakeRawLocationSource]・
 * [FakeForegroundGate]）を注入する。[PermissionGate]のみタスク指示に従いRobolectric権限
 * shadow（`Shadows.shadowOf(application).grantPermissions/denyPermissions`、Context7で
 * `org.robolectric.shadows.ShadowContextWrapper`所属のAPIであることを実測確認済み）＋
 * 実装済みの[AndroidPermissionGate]を組み合わせて検証する（手書きfakeで代替せず、実際の
 * Android権限モデルとの結線を検証するため）。AndroidManifest.xmlには
 * `ACCESS_FINE_LOCATION`が既にP3-C1で宣言済み（実測確認）。
 *
 * [FusedLocationService.kt]のKDocが定める想定分岐順序（①Permission→②Foreground→
 * ③SecurityException→④fix null/不正）に沿って、Fusedを呼ばない早期リターン系
 * （T-LOCSVC-2/7）は[FakeRawLocationSource.callCount]で未呼び出しを検証する。
 */
@RunWith(RobolectricTestRunner::class)
class FusedLocationServiceTest {

    // T-LOCSVC-1: 正常 - fix取得 → Success(Coordinate, accuracy, fixedAt)
    @Test
    fun currentLocation_whenFixAvailable_returnsSuccessWithCoordinateAccuracyAndFixedAt() = runTest {
        val fixedAt = Instant.parse("2026-08-09T01:00:00Z")
        val fix = LocationFix(lat = 35.6812, lon = 139.7671, accuracyMeters = 12.5f, fixedAt = fixedAt)
        val rawLocationSource = FakeRawLocationSource { fix }
        val service = FusedLocationService(rawLocationSource, grantedPermissionGate(), FakeForegroundGate(allowed = true))

        val result = service.currentLocation()

        assertTrue("expected Success but was $result", result is LocationResult.Success)
        val success = result as LocationResult.Success
        assertEquals(Coordinate(35.6812, 139.7671), success.coordinate)
        assertEquals(12.5f, success.accuracyMeters)
        assertEquals(fixedAt, success.fixedAt)
    }

    // T-LOCSVC-2: 異常 - 権限未許可（denyPermissions）→ PermissionDenied。
    // RawLocationSourceを呼ばない
    @Test
    fun currentLocation_whenPermissionDenied_returnsPermissionDeniedWithoutCallingRawSource() = runTest {
        val rawLocationSource = FakeRawLocationSource { null }
        val service = FusedLocationService(rawLocationSource, deniedPermissionGate(), FakeForegroundGate(allowed = true))

        val result = service.currentLocation()

        assertEquals(LocationResult.PermissionDenied, result)
        assertEquals(0, rawLocationSource.callCount)
    }

    // T-LOCSVC-3: 異常 - SecurityException（実行中の剥奪）→ PermissionDenied
    // （例外を外へ漏らさない）
    @Test
    fun currentLocation_whenRawSourceThrowsSecurityException_returnsPermissionDeniedWithoutPropagating() = runTest {
        val rawLocationSource = FakeRawLocationSource { throw SecurityException("permission revoked mid-flight") }
        val service = FusedLocationService(rawLocationSource, grantedPermissionGate(), FakeForegroundGate(allowed = true))

        val result = service.currentLocation()

        assertEquals(LocationResult.PermissionDenied, result)
    }

    // T-LOCSVC-4: 異常 - fixがnull → Failure(UNAVAILABLE)（Coordinate(0,0)へ潰さない）
    @Test
    fun currentLocation_whenFixIsNull_returnsFailureUnavailable() = runTest {
        val rawLocationSource = FakeRawLocationSource { null }
        val service = FusedLocationService(rawLocationSource, grantedPermissionGate(), FakeForegroundGate(allowed = true))

        val result = service.currentLocation()

        assertTrue("expected Failure but was $result", result is LocationResult.Failure)
        assertEquals(LocationFailureReason.UNAVAILABLE, (result as LocationResult.Failure).reason)
    }

    // T-LOCSVC-5: 異常 - タイムアウト超過 → Failure(TIMEOUT)
    @Test
    fun currentLocation_whenRawSourceExceedsTimeout_returnsFailureTimeout() = runTest {
        val shortTimeout = Duration.ofMillis(100)
        val rawLocationSource = FakeRawLocationSource {
            delay(shortTimeout.toMillis() * 100) // runTestの仮想時間で確実にtimeoutを超過させる
            LocationFix(lat = 0.0, lon = 0.0, accuracyMeters = null, fixedAt = Instant.EPOCH)
        }
        val service = FusedLocationService(rawLocationSource, grantedPermissionGate(), FakeForegroundGate(allowed = true))

        val result = service.currentLocation(timeout = shortTimeout)

        assertTrue("expected Failure but was $result", result is LocationResult.Failure)
        assertEquals(LocationFailureReason.TIMEOUT, (result as LocationResult.Failure).reason)
    }

    // T-LOCSVC-6: エッジ - coroutineキャンセル → CancellationTokenが発火し即中断
    @Test
    fun currentLocation_whenCoroutineCancelledMidFlight_stopsImmediatelyViaCancellation() = runTest {
        val fixStarted = CompletableDeferred<Unit>()
        val rawLocationSource = FakeRawLocationSource {
            fixStarted.complete(Unit)
            delay(Long.MAX_VALUE / 2)
            LocationFix(lat = 0.0, lon = 0.0, accuracyMeters = null, fixedAt = Instant.EPOCH)
        }
        val service = FusedLocationService(rawLocationSource, grantedPermissionGate(), FakeForegroundGate(allowed = true))

        val job = launch { service.currentLocation() }
        fixStarted.await()
        job.cancel()
        job.join()

        assertTrue("expected job to be cancelled", job.isCancelled)
    }

    // T-LOCSVC-7: 異常 - ForegroundGate.isLocationAccessAllowed() == false
    // （Activityフォアグラウンドでなく、かつExecution Service非稼働のときのみ）→
    // Failure(BACKGROUND_RESTRICTED)。Fusedを呼ばない（§95.1改訂・修正2）
    @Test
    fun currentLocation_whenForegroundGateDisallows_returnsFailureBackgroundRestrictedWithoutCallingRawSource() = runTest {
        val rawLocationSource = FakeRawLocationSource { null }
        val service = FusedLocationService(rawLocationSource, grantedPermissionGate(), FakeForegroundGate(allowed = false))

        val result = service.currentLocation()

        assertTrue("expected Failure but was $result", result is LocationResult.Failure)
        assertEquals(LocationFailureReason.BACKGROUND_RESTRICTED, (result as LocationResult.Failure).reason)
        assertEquals(0, rawLocationSource.callCount)
    }

    // T-LOCSVC-8: エッジ - fixの緯度経度が範囲外／NaN → Failure(UNAVAILABLE)
    // （Coordinateのinit例外を外へ漏らさない）
    @Test
    fun currentLocation_whenFixHasOutOfRangeOrNaNCoordinates_returnsFailureUnavailableWithoutLeakingInitException() = runTest {
        val invalidFixes = listOf(
            LocationFix(lat = Double.NaN, lon = 139.0, accuracyMeters = null, fixedAt = Instant.EPOCH),
            LocationFix(lat = 35.0, lon = Double.NaN, accuracyMeters = null, fixedAt = Instant.EPOCH),
            LocationFix(lat = 91.0, lon = 139.0, accuracyMeters = null, fixedAt = Instant.EPOCH),
            LocationFix(lat = 35.0, lon = 181.0, accuracyMeters = null, fixedAt = Instant.EPOCH)
        )

        invalidFixes.forEach { invalidFix ->
            val rawLocationSource = FakeRawLocationSource { invalidFix }
            val service = FusedLocationService(rawLocationSource, grantedPermissionGate(), FakeForegroundGate(allowed = true))

            val result = service.currentLocation()

            assertTrue("expected Failure for $invalidFix but was $result", result is LocationResult.Failure)
            assertEquals(LocationFailureReason.UNAVAILABLE, (result as LocationResult.Failure).reason)
        }
    }

    // ---- 共有フィクスチャ ---------------------------------------------------------------

    /** [RawLocationSource]のfake実装。呼び出し回数を記録するspyを兼ねる。 */
    private class FakeRawLocationSource(
        private val onCurrentFix: suspend (Duration) -> LocationFix?
    ) : RawLocationSource {
        var callCount = 0
            private set

        override suspend fun currentFix(timeout: Duration): LocationFix? {
            callCount++
            return onCurrentFix(timeout)
        }
    }

    private class FakeForegroundGate(private val allowed: Boolean) : ForegroundGate {
        override fun isLocationAccessAllowed(): Boolean = allowed
    }

    /** Robolectric権限shadowでACCESS_FINE_LOCATIONを許可した状態の[PermissionGate]を返す。 */
    private fun grantedPermissionGate(): PermissionGate {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        return AndroidPermissionGate(application)
    }

    /** Robolectric権限shadowでACCESS_FINE_LOCATIONを拒否した状態の[PermissionGate]を返す。 */
    private fun deniedPermissionGate(): PermissionGate {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        return AndroidPermissionGate(application)
    }
}
