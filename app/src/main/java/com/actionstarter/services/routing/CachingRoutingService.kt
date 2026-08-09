package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * F25実装（**§95.2の「義務」**、計画書§6.1・§8）。[RoutingService]デコレータ。
 * スロットリング（移動距離500m未満かつ経過10分未満はAPI未呼び出しでキャッシュ返却、
 * 計画書§8表）・retry 1回・並行呼び出しの`Mutex`直列化を行う。retry後も失敗した場合、
 * キャッシュがあればそれを返し`computedAt`を書き換えない（staleを新鮮と詐称しない、
 * T-CACHE-8）。キャッシュも無ければ[RoutingException]を再送出する（0分・固定20分等の
 * 偽値を返さない、T-CACHE-9）。
 *
 * [clock]は仮想時間注入用（計画書§9.1「キャッシュ／スロットル（仮想時間）」、
 * `TestScope`／注入した`Clock`でテストする）。
 *
 * **P3-C4実装メモ（§8の表を実装へ写像）**:
 * - キー`(destination, mode)`ごとに[Mutex]を割り当てる（`ConcurrentHashMap.computeIfAbsent`
 *   でget-or-create、生成自体もスレッドセーフ）。「キャッシュ鮮度判定 → 委譲呼び出し →
 *   キャッシュ更新」の一連を**丸ごとそのMutexで直列化**することで、同一キーへの並行呼び出し
 *   の2本目は1本目が更新した後のキャッシュを再判定でき、API発行が二重化しない（T-CACHE-11）。
 * - 再利用可否は§8の5条件のANDで判定する: 移動距離`< 500m`・経過`< 10分`・目的地一致・
 *   移動手段一致（いずれもマップキー一致で自動的に保証）・出発時刻差`< 10分`。**境界値は
 *   すべて狭義不等号**（500mちょうど・10分ちょうどは再計算、T-CACHE-3/4/7）。
 * - 委譲呼び出しが失敗したら1回だけretryする（T-CACHE-10で3回目が発生しないことを保証）。
 *   `CancellationException`はretry対象にせずそのまま再送出する（構造化並行性の取り消しを
 *   握り潰さない。`runCatching`等がこれを暗黙に飲み込む既知のアンチパターンを避けるため
 *   明示的に`catch`節を分ける）。
 * - retry後も失敗し、かつキャッシュが存在すれば**そのキャッシュ値をそのまま返す**
 *   （`computedAt`を書き換えない＝staleを新鮮と詐称しない、T-CACHE-8）。キャッシュが
 *   無ければ直近の例外を再送出する（0分等の偽値を返さない、T-CACHE-9）。
 *
 * 契約scaffold（P3-C1、TDD例外）時点では宣言のみであり、実装はP3-C4で行う。
 */
class CachingRoutingService(
    private val delegate: RoutingService,
    private val cache: RouteEstimateCache = RouteEstimateCache(),
    private val clock: Clock = Clock.systemUTC()
) : RoutingService {
    private data class Key(val destination: Coordinate, val mode: TransportMode)

    private val keyMutexes = ConcurrentHashMap<Key, Mutex>()

    override suspend fun estimateRoute(
        origin: Coordinate,
        destination: Coordinate,
        mode: TransportMode,
        departureDate: Instant
    ): RouteEstimate {
        val mutex = keyMutexes.computeIfAbsent(Key(destination, mode)) { Mutex() }

        return mutex.withLock {
            val cached = cache.get(destination, mode)
            if (cached != null && isReusable(cached, origin, departureDate)) {
                return@withLock cached.estimate
            }

            val fresh = callDelegateWithOneRetry(origin, destination, mode, departureDate, cached)
            cache.put(destination, mode, RouteEstimateCache.Entry(fresh, origin, departureDate))
            fresh
        }
    }

    /**
     * 委譲呼び出し（初回＋retry 1回）。両方失敗した場合、[cached]があればそれを返す
     * （呼び出し元が`cache.put`をスキップできるよう、成功時のみ結果を返す設計にはせず、
     * ここでは「staleキャッシュへのフォールバック」自体を例外制御で表現する）。
     */
    private suspend fun callDelegateWithOneRetry(
        origin: Coordinate,
        destination: Coordinate,
        mode: TransportMode,
        departureDate: Instant,
        cached: RouteEstimateCache.Entry?
    ): RouteEstimate {
        try {
            return delegate.estimateRoute(origin, destination, mode, departureDate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstFailure: Exception) {
            try {
                return delegate.estimateRoute(origin, destination, mode, departureDate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (secondFailure: Exception) {
                if (cached != null) {
                    return cached.estimate
                }
                throw secondFailure
            }
        }
    }

    /** §8の表: 5条件すべてが成立した場合のみ再利用可能（AND）。境界値はすべて狭義不等号。 */
    private fun isReusable(cached: RouteEstimateCache.Entry, newOrigin: Coordinate, newDepartureDate: Instant): Boolean {
        val movedMeters = GeoDistance.meters(cached.origin, newOrigin)
        if (movedMeters >= DISTANCE_THRESHOLD_METERS) return false

        val elapsed = Duration.between(cached.estimate.computedAt, clock.instant())
        if (elapsed >= CACHE_TTL) return false

        val departureDelta = absDuration(Duration.between(cached.departureDate, newDepartureDate))
        if (departureDelta >= DEPARTURE_TOLERANCE) return false

        return true
    }

    /**
     * [Duration.abs]（JDK 18〜）はminSdk 26のAndroidランタイムでは利用できないため使わない。
     * 符号反転で代替する。
     */
    private fun absDuration(duration: Duration): Duration =
        if (duration.isNegative) duration.negated() else duration

    private companion object {
        const val DISTANCE_THRESHOLD_METERS = 500.0
        val CACHE_TTL: Duration = Duration.ofMinutes(10)
        val DEPARTURE_TOLERANCE: Duration = Duration.ofMinutes(10)
    }
}
