package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * T-CACHE-1〜11（計画書§8・§9.6）。対象は[CachingRoutingService]（L2デコレータ）。現状
 * （P3-C1契約scaffold）は[CachingRoutingService.estimateRoute]の本文が
 * `TODO("P3-C4で実装")`のため、以下は全件`NotImplementedError`によりRedになる
 * （意図した失敗）。
 *
 * [RoutingService]の委譲先はfake実装（[RecordingRoutingService]）を注入する。[Clock]は
 * タスク指示のとおり注入した[MutableClock]（java.time.Clock固定進行）で§8の表の規則を
 * 検証する。[RouteEstimateCache]（[CachingRoutingService]のコンストラクタ既定引数）は
 * それ自体もTODOスタブだが、[CachingRoutingService.estimateRoute]自体が丸ごとTODOである
 * 現状ではどの道到達しないため、fakeへの差し替えは不要（デフォルトの
 * `RouteEstimateCache()`を使う。black-boxで[CachingRoutingService]の1本目の呼び出しで
 * キャッシュを「温め」、2本目の呼び出しで境界条件を検証する構成とした）。
 *
 * 距離境界（499m／500mちょうど）の座標は、緯度のみを動かし経度を固定する配置
 * （[meridianOffset]）を用いて手計算誤差を避けている。この配置ではHaversine公式が
 * 厳密に d = R × Δφ(rad) へ単純化されるため（[GeoDistanceTest]と同じ手法）。地球半径は
 * [GeoDistanceTest]と同じくR=6371000mを仮定する。P3-C4が大きく異なるRを採用した場合、
 * T-CACHE-3の500mちょうど境界の判定が本仮定に依存する点はP3-C2完了報告で申し送る。
 */
class CachingRoutingServiceTest {

    // T-CACHE-1: 正常 - 初回呼び出しは委譲先APIを呼び、結果を返しキャッシュする
    @Test
    fun estimateRoute_onFirstCall_delegatesToApiAndReturnsResult() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { _, _, _, mode, _ ->
            RouteEstimate(Duration.ofMinutes(20), mode, clock.instant())
        }
        val service = CachingRoutingService(delegate, clock = clock)

        val result = service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        assertEquals(RouteEstimate(Duration.ofMinutes(20), TransportMode.TRANSIT, T0), result)
        assertEquals(1, delegate.callCount)
    }

    // T-CACHE-2: エッジ - 移動499m・経過9分 → API未呼び出しでキャッシュ返却
    // （スロットリング境界・両閾値未満）
    @Test
    fun estimateRoute_whenMovedLessThan500mAndLessThan10Minutes_returnsCachedWithoutCallingDelegate() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { _, _, _, mode, _ ->
            RouteEstimate(Duration.ofMinutes(20), mode, clock.instant())
        }
        val service = CachingRoutingService(delegate, clock = clock)
        val firstResult = service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        clock.advanceTo(T0.plus(Duration.ofMinutes(9)))
        val secondResult = service.estimateRoute(ORIGIN_499M_AWAY, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        assertEquals(1, delegate.callCount)
        // computedAtも含めて全く同一の値が返る（stale詐称なし、T-CACHE-8と対の確認）
        assertEquals(firstResult, secondResult)
    }

    // T-CACHE-3: エッジ - 移動500mちょうど → API呼び出し（境界の包含規則を固定）
    @Test
    fun estimateRoute_whenMovedExactly500m_recomputesViaDelegate() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { _, _, _, mode, _ ->
            RouteEstimate(Duration.ofMinutes(20), mode, clock.instant())
        }
        val service = CachingRoutingService(delegate, clock = clock)
        service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        clock.advanceTo(T0.plus(Duration.ofMinutes(1))) // 時間側閾値には抵触させない
        service.estimateRoute(ORIGIN_500M_AWAY, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        assertEquals(2, delegate.callCount)
    }

    // T-CACHE-4: エッジ - 経過10分ちょうど → API呼び出し（キャッシュ有効期限境界）
    @Test
    fun estimateRoute_whenElapsedExactly10Minutes_recomputesViaDelegate() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { _, _, _, mode, _ ->
            RouteEstimate(Duration.ofMinutes(20), mode, clock.instant())
        }
        val service = CachingRoutingService(delegate, clock = clock)
        service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        clock.advanceTo(T0.plus(Duration.ofMinutes(10))) // 距離側は0mのまま、時間のみ境界
        service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        assertEquals(2, delegate.callCount)
    }

    // T-CACHE-5: エッジ - 目的地が異なる → キャッシュ不使用
    @Test
    fun estimateRoute_whenDestinationDiffers_doesNotUseCache() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { _, _, _, mode, _ ->
            RouteEstimate(Duration.ofMinutes(20), mode, clock.instant())
        }
        val service = CachingRoutingService(delegate, clock = clock)
        service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        clock.advanceTo(T0.plus(Duration.ofMinutes(1)))
        val otherDestination = Coordinate(lat = DESTINATION.lat + 1.0, lon = DESTINATION.lon)
        service.estimateRoute(BASE_ORIGIN, otherDestination, TransportMode.TRANSIT, DEPARTURE_0)

        assertEquals(2, delegate.callCount)
    }

    // T-CACHE-6: エッジ - TransportModeが異なる → キャッシュ不使用
    @Test
    fun estimateRoute_whenTransportModeDiffers_doesNotUseCache() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { _, _, _, mode, _ ->
            RouteEstimate(Duration.ofMinutes(20), mode, clock.instant())
        }
        val service = CachingRoutingService(delegate, clock = clock)
        service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        clock.advanceTo(T0.plus(Duration.ofMinutes(1)))
        service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.WALKING, DEPARTURE_0)

        assertEquals(2, delegate.callCount)
    }

    // T-CACHE-7: エッジ - departureDate差が10分以上 → キャッシュ不使用
    // （TRANSITの出発時刻依存性、§8表・ADR-0004）
    @Test
    fun estimateRoute_whenDepartureDateDiffersByAtLeast10Minutes_doesNotUseCache() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { _, _, _, mode, _ ->
            RouteEstimate(Duration.ofMinutes(20), mode, clock.instant())
        }
        val service = CachingRoutingService(delegate, clock = clock)
        service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        clock.advanceTo(T0.plus(Duration.ofMinutes(1))) // now側の10分閾値には抵触させない
        val laterDeparture = DEPARTURE_0.plus(Duration.ofMinutes(10)) // departureDate差はちょうど10分
        service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, laterDeparture)

        assertEquals(2, delegate.callCount)
    }

    // T-CACHE-8: 異常 - API失敗 → retry 1回 → なお失敗ならキャッシュ返却。
    // computedAtは古い値のまま（staleを新鮮と詐称しない）
    @Test
    fun estimateRoute_whenApiFailsAfterRetryButCacheExists_returnsStaleCachedValueWithUnchangedComputedAt() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { callNumber, _, _, mode, _ ->
            if (callNumber == 1) {
                RouteEstimate(Duration.ofMinutes(20), mode, clock.instant())
            } else {
                throw RoutingException.Offline(IOException("simulated failure"))
            }
        }
        val service = CachingRoutingService(delegate, clock = clock)
        val firstResult = service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        clock.advanceTo(T0.plus(Duration.ofMinutes(20))) // キャッシュ鮮度を切らせ、再取得を誘発する
        val secondResult = service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)

        assertEquals(firstResult, secondResult)
        assertEquals(firstResult.computedAt, secondResult.computedAt)
        // 1回目成功(1) + 2回目試行(失敗, 2) + retry 1回(失敗, 3) = 合計3回
        assertEquals(3, delegate.callCount)
    }

    // T-CACHE-9: 異常 - API失敗かつキャッシュ無し → RoutingExceptionを再送出（偽値を返さない）
    @Test
    fun estimateRoute_whenApiFailsWithNoCacheEntry_rethrowsRoutingException() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { _, _, _, _, _ ->
            throw RoutingException.Offline(IOException("simulated failure"))
        }
        val service = CachingRoutingService(delegate, clock = clock)

        assertThrowsSuspend<RoutingException.Offline> {
            service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)
        }
    }

    // T-CACHE-10: エッジ - retryは1回だけ（3回目の呼び出しが発生しない）
    @Test
    fun estimateRoute_retriesExactlyOnceAndNeverAttemptsAThirdCall() = runTest {
        val clock = MutableClock(T0)
        val delegate = RecordingRoutingService { _, _, _, _, _ ->
            throw RoutingException.Offline(IOException("always failing"))
        }
        val service = CachingRoutingService(delegate, clock = clock)

        assertThrowsSuspend<RoutingException.Offline> {
            service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0)
        }

        // 初回 + retry 1回 = 合計2回。3回目は発生しない
        assertEquals(2, delegate.callCount)
    }

    // T-CACHE-11: エッジ - 同一キーへの並行呼び出しでAPI発行が二重化しない（Mutex）
    @Test
    fun estimateRoute_concurrentCallsForSameKey_issuesOnlyOneApiCallViaMutex() = runTest {
        val clock = MutableClock(T0)
        val delegateEntered = CompletableDeferred<Unit>()
        val releaseDelegate = CompletableDeferred<Unit>()
        val delegate = RecordingRoutingService { callNumber, _, _, mode, _ ->
            if (callNumber == 1) {
                delegateEntered.complete(Unit)
                releaseDelegate.await()
            }
            RouteEstimate(Duration.ofMinutes(20), mode, clock.instant())
        }
        val service = CachingRoutingService(delegate, clock = clock)

        val firstCaller = async { service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0) }
        delegateEntered.await() // 1本目がAPI呼び出し中であることを保証してから2本目を発行する
        val secondCaller = async { service.estimateRoute(BASE_ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE_0) }
        releaseDelegate.complete(Unit)

        val firstResult = firstCaller.await()
        val secondResult = secondCaller.await()

        assertEquals(1, delegate.callCount)
        assertEquals(firstResult, secondResult)
    }

    // ---- 共有フィクスチャ ---------------------------------------------------------------

    /**
     * [RoutingService]のfake実装（記録型）。[callNumber]（1始まり）をコールバックへ渡すため、
     * 「1回目は成功・2回目以降は失敗」のようなretryシナリオ（T-CACHE-8/10/11）を1つの
     * クラスで表現できる。
     */
    private class RecordingRoutingService(
        private val onEstimateRoute: suspend (
            callNumber: Int,
            origin: Coordinate,
            destination: Coordinate,
            mode: TransportMode,
            departureDate: Instant
        ) -> RouteEstimate
    ) : RoutingService {
        var callCount = 0
            private set

        override suspend fun estimateRoute(
            origin: Coordinate,
            destination: Coordinate,
            mode: TransportMode,
            departureDate: Instant
        ): RouteEstimate {
            callCount++
            return onEstimateRoute(callCount, origin, destination, mode, departureDate)
        }
    }

    /** テスト側で任意の時刻へ進行させられる[Clock]実装（タスク指示: 注入Clock固定進行）。 */
    private class MutableClock(
        private var currentInstant: Instant,
        private val zoneId: ZoneId = ZoneOffset.UTC
    ) : Clock() {
        fun advanceTo(newInstant: Instant) {
            currentInstant = newInstant
        }

        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)
        override fun instant(): Instant = currentInstant
    }

    /** [block]を実行し、[T]型の例外が送出されることを検証してその例外を返す（suspend版assertThrows）。 */
    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(
        crossinline block: suspend () -> Unit
    ): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError(
                "expected ${T::class.qualifiedName} but caught ${e::class.qualifiedName}: ${e.message}",
                e
            )
        }
        throw AssertionError("expected ${T::class.qualifiedName} to be thrown but the call completed normally")
    }

    private companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private val T0: Instant = Instant.parse("2026-08-09T09:00:00Z")
        private val DEPARTURE_0: Instant = Instant.parse("2026-08-09T10:00:00Z")
        private val DESTINATION = Coordinate(lat = 35.6812, lon = 139.7671)
        private val BASE_ORIGIN = Coordinate(lat = 35.0, lon = 139.0)
        private val ORIGIN_499M_AWAY = meridianOffset(BASE_ORIGIN, 499.0)
        private val ORIGIN_500M_AWAY = meridianOffset(BASE_ORIGIN, 500.0)

        /** [from]と同経度のまま、北へ[meters]分だけ緯度をずらした座標を返す（クラスKDoc参照）。 */
        private fun meridianOffset(from: Coordinate, meters: Double): Coordinate {
            val deltaLatDegrees = Math.toDegrees(meters / EARTH_RADIUS_METERS)
            return Coordinate(lat = from.lat + deltaLatDegrees, lon = from.lon)
        }
    }
}
