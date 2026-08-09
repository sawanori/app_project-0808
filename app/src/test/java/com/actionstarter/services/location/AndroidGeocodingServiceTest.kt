package com.actionstarter.services.location

import com.actionstarter.domain.valueobject.Coordinate
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * T-GEO-1〜8・T-GEO-10〜11（計画書§9.4。T-GEO-10/11はP3-C6・integration ownerが追加した
 * Fable 5承認2026-08-09の計画書§9.4追補分）。対象は[AndroidGeocodingService]（L2）。
 * [AndroidGeocodingService.geocode]は実装済み（P3-C3）のため以下は全件Greenで実行できる。
 * T-GEO-9は対象が[PlatformGeocoderSource]（L3）であるため[PlatformGeocoderSourceTest]に
 * 分離する（計画書§9.4の対象列参照）。
 *
 * [GeocoderSource]はfake実装（[FakeGeocoderSource]）を注入する。[AndroidGeocodingService]は
 * 正規化（[LocationNameNormalizer]）にも依存する。T-GEO-1〜8の入力文字列はいずれも正規化を
 * 素通りする通常の住所文字列を用いる（空文字・URIスキームは使わない。単体の正規化規則自体は
 * [LocationNameNormalizerTest]の担当）。T-GEO-10/11は逆に、[AndroidGeocodingService]が
 * [LocationNameNormalizer]の`Invalid`／`UriScheme`判定を受けて[GeocoderSource]を一切
 * 呼ばずに早期returnするという**呼び出し側の結線**を検証するため、あえて空文字・URIスキーム
 * 入力を用いる（[LocationNameNormalizerTest]は正規化の分類自体のみを検証し、
 * [GeocoderSource]非呼出という結線側の契約は検証しないため、本ファイルでの追加が必要）。
 */
class AndroidGeocodingServiceTest {

    // T-GEO-1: 正常 - 住所文字列 → Success(Coordinate)
    @Test
    fun geocode_withAddressString_returnsSuccessWithCoordinate() = runTest {
        val fix = LocationFix(lat = 35.6595, lon = 139.7005, accuracyMeters = null, fixedAt = Instant.EPOCH)
        val geocoderSource = FakeGeocoderSource { _, _, _ -> listOf(fix) }
        val service = AndroidGeocodingService(geocoderSource)

        val result = service.geocode("東京都渋谷区渋谷2-24-1")

        assertEquals(GeocodeResult.Success(Coordinate(35.6595, 139.7005)), result)
    }

    // T-GEO-2: エッジ - 複数候補 → 先頭を採用し、同入力で常に同結果（決定性）
    @Test
    fun geocode_withMultipleCandidates_picksFirstDeterministically() = runTest {
        val first = LocationFix(lat = 35.0, lon = 139.0, accuracyMeters = null, fixedAt = Instant.EPOCH)
        val second = LocationFix(lat = 36.0, lon = 140.0, accuracyMeters = null, fixedAt = Instant.EPOCH)
        // 2つの独立インスタンスで検証することで、T-GEO-7のキャッシュ機構に依らず
        // 「同入力なら常に先頭候補に決定的に収束する」ことを確認する。
        val service1 = AndroidGeocodingService(FakeGeocoderSource { _, _, _ -> listOf(first, second) })
        val service2 = AndroidGeocodingService(FakeGeocoderSource { _, _, _ -> listOf(first, second) })

        val result1 = service1.geocode("Ambiguous Place A")
        val result2 = service2.geocode("Ambiguous Place A")

        assertEquals(GeocodeResult.Success(Coordinate(35.0, 139.0)), result1)
        assertEquals(result1, result2)
    }

    // T-GEO-3: 異常 - 候補0件 → NoMatch（Failureではない）
    @Test
    fun geocode_withNoCandidates_returnsNoMatchNotFailure() = runTest {
        val geocoderSource = FakeGeocoderSource { _, _, _ -> emptyList() }
        val service = AndroidGeocodingService(geocoderSource)

        val result = service.geocode("Zoom Meeting Room")

        assertEquals(GeocodeResult.NoMatch, result)
    }

    // T-GEO-4: 異常 - IOException（ネットワーク断）→ Failure(NETWORK)
    @Test
    fun geocode_whenLookupThrowsIOException_returnsFailureNetwork() = runTest {
        val geocoderSource = FakeGeocoderSource { _, _, _ -> throw IOException("offline") }
        val service = AndroidGeocodingService(geocoderSource)

        val result = service.geocode("渋谷駅")

        assertTrue("expected Failure but was $result", result is GeocodeResult.Failure)
        assertEquals(GeocodeFailureReason.NETWORK, (result as GeocodeResult.Failure).reason)
    }

    // T-GEO-5: 異常 - isAvailable() == false（Geocoder.isPresent()相当）→
    // Failure(GEOCODER_UNAVAILABLE)
    @Test
    fun geocode_whenGeocoderSourceUnavailable_returnsFailureGeocoderUnavailableWithoutLookup() = runTest {
        val geocoderSource = FakeGeocoderSource(available = false) { _, _, _ -> emptyList() }
        val service = AndroidGeocodingService(geocoderSource)

        val result = service.geocode("渋谷駅")

        assertTrue("expected Failure but was $result", result is GeocodeResult.Failure)
        assertEquals(GeocodeFailureReason.GEOCODER_UNAVAILABLE, (result as GeocodeResult.Failure).reason)
        assertEquals(0, geocoderSource.lookupCallCount)
    }

    // T-GEO-6: 異常 - タイムアウト → Failure(TIMEOUT)
    @Test
    fun geocode_whenLookupExceedsTimeout_returnsFailureTimeout() = runTest {
        val shortTimeout = Duration.ofMillis(100)
        val geocoderSource = FakeGeocoderSource { _, _, _ ->
            delay(shortTimeout.toMillis() * 100)
            listOf(LocationFix(lat = 0.0, lon = 0.0, accuracyMeters = null, fixedAt = Instant.EPOCH))
        }
        val service = AndroidGeocodingService(geocoderSource)

        val result = service.geocode("渋谷駅", timeout = shortTimeout)

        assertTrue("expected Failure but was $result", result is GeocodeResult.Failure)
        assertEquals(GeocodeFailureReason.TIMEOUT, (result as GeocodeResult.Failure).reason)
    }

    // T-GEO-7: エッジ - 同一文字列2回目 → キャッシュ命中でGeocoderSourceを呼ばない
    @Test
    fun geocode_calledTwiceWithSameInput_hitsCacheOnSecondCallWithoutInvokingSource() = runTest {
        val fix = LocationFix(lat = 35.0, lon = 139.0, accuracyMeters = null, fixedAt = Instant.EPOCH)
        val geocoderSource = FakeGeocoderSource { _, _, _ -> listOf(fix) }
        val service = AndroidGeocodingService(geocoderSource)

        val result1 = service.geocode("渋谷駅")
        val result2 = service.geocode("渋谷駅")

        assertEquals(result1, result2)
        assertEquals(1, geocoderSource.lookupCallCount)
    }

    // T-GEO-8: エッジ - NoMatchもキャッシュされ、2回目に再照会しない（無駄なI/O抑止）
    @Test
    fun geocode_whenNoMatchOnFirstCall_cachesAndDoesNotRequeryOnSecondCall() = runTest {
        val geocoderSource = FakeGeocoderSource { _, _, _ -> emptyList() }
        val service = AndroidGeocodingService(geocoderSource)

        val result1 = service.geocode("Zoom Meeting")
        val result2 = service.geocode("Zoom Meeting")

        assertEquals(GeocodeResult.NoMatch, result1)
        assertEquals(GeocodeResult.NoMatch, result2)
        assertEquals(1, geocoderSource.lookupCallCount)
    }

    // T-GEO-10: 異常 - 空文字入力 → Failure(INVALID_INPUT)（計画書§9.4追補、Fable 5承認
    // 2026-08-09）。LocationNameNormalizer.normalize("")がNormalizedLocationName.Invalidを
    // 返し（LocationNameNormalizerTest T-GEONORM-2と同じ正規化規則）、AndroidGeocodingService
    // はGeocoderSourceを一切呼ばずに早期returnする（AndroidGeocodingService.kt分岐①）。
    @Test
    fun geocode_withEmptyStringInput_returnsFailureInvalidInputWithoutInvokingGeocoderSource() = runTest {
        val geocoderSource = FakeGeocoderSource { _, _, _ -> emptyList() }
        val service = AndroidGeocodingService(geocoderSource)

        val result = service.geocode("")

        assertTrue("expected Failure but was $result", result is GeocodeResult.Failure)
        assertEquals(GeocodeFailureReason.INVALID_INPUT, (result as GeocodeResult.Failure).reason)
        assertEquals(
            "GeocoderSource must not be invoked for structurally invalid input",
            0,
            geocoderSource.lookupCallCount
        )
    }

    // T-GEO-11: エッジ - "https://"入力 → NoMatch（計画書§9.4追補、Fable 5承認2026-08-09）。
    // LocationNameNormalizer.normalize("https://")がNormalizedLocationName.UriSchemeを返し
    // （LocationNameNormalizerTest T-GEONORM-3と同じURIスキーム判定）、AndroidGeocodingService
    // はGeocoderSourceを一切呼ばずGeocodeResult.NoMatchへ早期returnする
    // （AndroidGeocodingService.kt分岐①、計画書§5.3「異常ではない」正常系）。
    @Test
    fun geocode_withBareUriSchemeInput_returnsNoMatchWithoutInvokingGeocoderSource() = runTest {
        val geocoderSource = FakeGeocoderSource { _, _, _ -> emptyList() }
        val service = AndroidGeocodingService(geocoderSource)

        val result = service.geocode("https://")

        assertEquals(GeocodeResult.NoMatch, result)
        assertEquals(
            "GeocoderSource must not be invoked for an unresolvable URI-scheme string",
            0,
            geocoderSource.lookupCallCount
        )
    }

    // ---- 共有フィクスチャ ---------------------------------------------------------------

    /** [GeocoderSource]のfake実装。呼び出し回数と問い合わせ文字列を記録するspyを兼ねる。 */
    private class FakeGeocoderSource(
        private val available: Boolean = true,
        private val onLookup: suspend (query: String, maxResults: Int, timeout: Duration) -> List<LocationFix>
    ) : GeocoderSource {
        var lookupCallCount = 0
            private set
        val lookupQueries = mutableListOf<String>()

        override fun isAvailable(): Boolean = available

        override suspend fun lookup(query: String, maxResults: Int, timeout: Duration): List<LocationFix> {
            lookupCallCount++
            lookupQueries += query
            return onLookup(query, maxResults, timeout)
        }
    }
}
