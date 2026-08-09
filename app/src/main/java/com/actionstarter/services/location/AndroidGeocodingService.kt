package com.actionstarter.services.location

import com.actionstarter.domain.valueobject.Coordinate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Duration

/**
 * [GeocodingService]のL2実装（F23、計画書§6.1）。正規化（[LocationNameNormalizer]）＋
 * キャッシュ（プロセス内メモリのみ、計画書§8「永続化しない」と同方針）＋結果写像を行う。
 * JVMテスト対象（fakeの[GeocoderSource]で検証、計画書§9.4 T-GEO-1〜8）。
 *
 * 想定挙動: 同一文字列2回目はキャッシュ命中で[GeocoderSource]を呼ばない（T-GEO-7）。
 * [GeocodeResult.NoMatch]も含めてキャッシュする（T-GEO-8、無駄なI/O抑止）。
 *
 * P3-C3で実装済み（T-GEO-1〜8）: 分岐順序は①[LocationNameNormalizer.normalize]
 * （[NormalizedLocationName.Invalid]→[GeocodeFailureReason.INVALID_INPUT]、
 * [NormalizedLocationName.UriScheme]→[GeocodeResult.NoMatch]、いずれも[GeocoderSource]
 * を呼ばない）、②正規化済み文字列をキーとしたキャッシュ照会（[GeocodeResult.Success]・
 * [GeocodeResult.NoMatch]のみキャッシュし、[GeocodeResult.Failure]は一時的な障害の
 * 可能性があるためキャッシュしない＝次回再試行できる）、③[GeocoderSource.isAvailable]
 * が偽なら[GeocodeFailureReason.GEOCODER_UNAVAILABLE]（[GeocoderSource.lookup]を呼ばない、
 * T-GEO-5）、④[GeocoderSource.lookup]を[withTimeout]で包み[TimeoutCancellationException]
 * のみ[GeocodeFailureReason.TIMEOUT]へ、[java.io.IOException]は
 * [GeocodeFailureReason.NETWORK]へ写像する。候補は常に先頭（`maxResults = 1`で要求）を
 * 採用し0件は[GeocodeResult.NoMatch]とする（T-GEO-2/3、`GeocoderSource`が返す順序に
 * 従うのみで本クラス側に非決定要素がないため決定的）。
 */
class AndroidGeocodingService(
    private val geocoderSource: GeocoderSource
) : GeocodingService {

    /** プロセス内メモリのみ（計画書§8）。[GeocodeResult.Success]／[GeocodeResult.NoMatch]のみ格納する。 */
    private val cache = mutableMapOf<String, GeocodeResult>()

    override suspend fun geocode(locationName: String, timeout: Duration): GeocodeResult {
        val normalizedQuery = when (val normalized = LocationNameNormalizer.normalize(locationName)) {
            is NormalizedLocationName.Invalid ->
                return GeocodeResult.Failure(GeocodeFailureReason.INVALID_INPUT, null)
            is NormalizedLocationName.UriScheme ->
                return GeocodeResult.NoMatch
            is NormalizedLocationName.Normalized -> normalized.value
        }

        cache[normalizedQuery]?.let { cached -> return cached }

        if (!geocoderSource.isAvailable()) {
            return GeocodeResult.Failure(GeocodeFailureReason.GEOCODER_UNAVAILABLE, null)
        }

        val result: GeocodeResult = try {
            val candidates = withTimeout(timeout.toMillis()) {
                geocoderSource.lookup(normalizedQuery, MAX_RESULTS, timeout)
            }
            candidates.firstOrNull()
                ?.let { fix -> GeocodeResult.Success(Coordinate(fix.lat, fix.lon)) }
                ?: GeocodeResult.NoMatch
        } catch (e: TimeoutCancellationException) {
            return GeocodeResult.Failure(GeocodeFailureReason.TIMEOUT, e)
        } catch (e: IOException) {
            return GeocodeResult.Failure(GeocodeFailureReason.NETWORK, e)
        }

        cache[normalizedQuery] = result
        return result
    }

    private companion object {
        /** 先頭候補のみ採用する方針（T-GEO-2）のため、要求段階から1件に絞り無駄な取得を避ける。 */
        const val MAX_RESULTS = 1
    }
}
