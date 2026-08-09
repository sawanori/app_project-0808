package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * T-ROUTESVC-1〜10（計画書§9.5）。対象は[RoutesApiRoutingService]（L2、T-ROUTESVC-9のみ
 * [HttpPostClient]契約自体を対象とする。クラスKDoc「テスト化に関する注記」参照）。
 * 現状（P3-C1契約scaffold）は[RoutesApiRoutingService.estimateRoute]の本文が
 * `TODO("P3-C4で実装")`のため、T-ROUTESVC-1〜7・10は全件`NotImplementedError`によりRedに
 * なる（意図した失敗）。T-ROUTESVC-8は対象の[UnconfiguredRoutingService]が計画書§12
 * （P3-C1でトリビアル実装済み・TDD例外）により既に完全実装されているため、Redにはならず
 * 即Greenの回帰ガードとして機能する（タスク指示「定数検証など即Greenの回帰ガードは許容」
 * に該当。以下に明記）。
 *
 * [HttpPostClient]はfake実装（[RecordingHttpPostClient]）を注入する。url/headers/body/
 * timeoutを全呼び出し分記録し、応答は差し替え可能なコールバック、例外注入もコールバック内
 * で`throw`するだけで表現できる（記録型fake、タスク指示のとおり）。
 */
class RoutesApiRoutingServiceTest {

    // T-ROUTESVC-1: 正常 - HTTP 200 → RouteEstimate(duration, mode, computedAt)
    @Test
    fun estimateRoute_onHttp200_returnsRouteEstimateWithDurationModeAndComputedAt() = runTest {
        val httpPostClient = RecordingHttpPostClient { _, _, _, _ ->
            HttpTextResponse(statusCode = 200, body = """{"routes":[{"duration":"600s"}]}""")
        }
        val service = RoutesApiRoutingService(httpPostClient, apiKey = "test-api-key")

        val before = Instant.now()
        val result = service.estimateRoute(ORIGIN, DESTINATION, TransportMode.TRANSIT, DEPARTURE)
        val after = Instant.now()

        assertEquals(Duration.ofSeconds(600), result.duration)
        assertEquals(TransportMode.TRANSIT, result.mode)
        // RoutesApiRoutingServiceにはClock注入がないため（CachingRoutingServiceのみが
        // Clockを持つ、計画書§5.5・§6.1）、computedAtは呼び出し区間内であることのみを検証する。
        assertFalse("computedAt should not be before the call started", result.computedAt.isBefore(before))
        assertFalse("computedAt should not be after the call finished", result.computedAt.isAfter(after))
    }

    // T-ROUTESVC-2: 正常 - APIキーが認証ヘッダで送られ、リクエストbody・URLクエリに
    // 含まれない。加えてX-Goog-FieldMaskヘッダの存在も検証する（タスク指示・
    // RoutesApiRequestBuilder.kt KDoc P3-P6実測: FieldMaskヘッダは必須）
    @Test
    fun estimateRoute_sendsApiKeyViaHeaderOnlyAndIncludesFieldMaskHeader() = runTest {
        val apiKey = "AIzaSyTestSecretKey1234567890"
        val httpPostClient = RecordingHttpPostClient { _, _, _, _ ->
            HttpTextResponse(statusCode = 200, body = """{"routes":[{"duration":"60s"}]}""")
        }
        val service = RoutesApiRoutingService(httpPostClient, apiKey = apiKey)

        service.estimateRoute(ORIGIN, DESTINATION, TransportMode.DRIVING, DEPARTURE)

        assertEquals(1, httpPostClient.calls.size)
        val call = httpPostClient.calls.single()
        // P3-P6実測（RoutesApiRequestBuilder.kt KDoc）: 認証ヘッダ名はX-Goog-Api-Key
        assertEquals(apiKey, call.headers["X-Goog-Api-Key"])
        assertTrue("X-Goog-FieldMask header must be present", call.headers.containsKey("X-Goog-FieldMask"))
        assertFalse("API key must not appear in the request body", call.body.contains(apiKey))
        assertFalse("API key must not appear in the URL", call.url.contains(apiKey))
    }

    // T-ROUTESVC-3: 異常 - HTTP 403/401 → RoutingException.Unauthorized
    @Test
    fun estimateRoute_onHttp401Or403_throwsUnauthorized() = runTest {
        listOf(401, 403).forEach { statusCode ->
            val httpPostClient = RecordingHttpPostClient { _, _, _, _ -> HttpTextResponse(statusCode, "{}") }
            val service = RoutesApiRoutingService(httpPostClient, apiKey = "key")

            val thrown = assertThrowsSuspend<RoutingException.Unauthorized> {
                service.estimateRoute(ORIGIN, DESTINATION, TransportMode.WALKING, DEPARTURE)
            }

            assertEquals(statusCode, thrown.httpStatus)
        }
    }

    // T-ROUTESVC-4: 異常 - HTTP 429（quota超過）→ RoutingException.QuotaExceeded
    @Test
    fun estimateRoute_onHttp429_throwsQuotaExceeded() = runTest {
        val httpPostClient = RecordingHttpPostClient { _, _, _, _ -> HttpTextResponse(429, "{}") }
        val service = RoutesApiRoutingService(httpPostClient, apiKey = "key")

        val thrown = assertThrowsSuspend<RoutingException.QuotaExceeded> {
            service.estimateRoute(ORIGIN, DESTINATION, TransportMode.WALKING, DEPARTURE)
        }

        assertEquals(429, thrown.httpStatus)
    }

    // T-ROUTESVC-5: 異常 - HTTP 5xx → RoutingException.ServerError
    @Test
    fun estimateRoute_onHttp5xx_throwsServerError() = runTest {
        listOf(500, 502, 503).forEach { statusCode ->
            val httpPostClient = RecordingHttpPostClient { _, _, _, _ -> HttpTextResponse(statusCode, "{}") }
            val service = RoutesApiRoutingService(httpPostClient, apiKey = "key")

            val thrown = assertThrowsSuspend<RoutingException.ServerError> {
                service.estimateRoute(ORIGIN, DESTINATION, TransportMode.WALKING, DEPARTURE)
            }

            assertEquals(statusCode, thrown.httpStatus)
        }
    }

    // T-ROUTESVC-6: 異常 - IOException（オフライン）→ RoutingException.Offline
    @Test
    fun estimateRoute_whenHttpClientThrowsIOException_throwsOffline() = runTest {
        val httpPostClient = RecordingHttpPostClient { _, _, _, _ -> throw IOException("no network") }
        val service = RoutesApiRoutingService(httpPostClient, apiKey = "key")

        assertThrowsSuspend<RoutingException.Offline> {
            service.estimateRoute(ORIGIN, DESTINATION, TransportMode.WALKING, DEPARTURE)
        }
    }

    // T-ROUTESVC-7: 異常 - タイムアウト → RoutingException.Timeout
    @Test
    fun estimateRoute_whenHttpClientExceedsTimeout_throwsTimeout() = runTest {
        val shortTimeout = Duration.ofMillis(50)
        val httpPostClient = RecordingHttpPostClient { _, _, _, _ ->
            delay(shortTimeout.toMillis() * 100)
            HttpTextResponse(200, """{"routes":[{"duration":"1s"}]}""")
        }
        val service = RoutesApiRoutingService(httpPostClient, apiKey = "key", timeout = shortTimeout)

        assertThrowsSuspend<RoutingException.Timeout> {
            service.estimateRoute(ORIGIN, DESTINATION, TransportMode.WALKING, DEPARTURE)
        }
    }

    // T-ROUTESVC-8: 異常 - UnconfiguredRoutingService → RoutingException.NotConfigured。
    // HTTPを1回も発行しない。
    // 【即Green回帰ガード】UnconfiguredRoutingServiceは計画書§12 P3-C1で
    // 「分岐・状態を持たない無条件送出の1行」としてTDD例外の対象外で実装済み
    // （services/routing/UnconfiguredRoutingService.kt参照）であるため、本テストは
    // NotImplementedErrorによるRedにはならず、現時点で既にGreenになる回帰ガードである
    // （クラスKDoc・タスク指示「定数検証など即Greenの回帰ガードは許容」に該当）。
    // UnconfiguredRoutingServiceはHttpPostClientへの参照を一切持たない設計（コンストラクタ
    // に引数が存在しない）ため、「HTTPを1回も発行しない」は型構造で保証されており、
    // fakeでの呼び出し回数計測を要さない。
    @Test
    fun unconfiguredRoutingService_alwaysThrowsNotConfigured() = runTest {
        val service = UnconfiguredRoutingService()

        repeat(2) {
            assertThrowsSuspend<RoutingException.NotConfigured> {
                service.estimateRoute(ORIGIN, DESTINATION, TransportMode.WALKING, DEPARTURE)
            }
        }
    }

    // T-ROUTESVC-9: エッジ - 例外発生時もHTTP接続／ストリームが確実にcloseされる
    // （UrlConnectionHttpPostClient fake検証）。
    //
    // 【テスト化に関する注記】対象として指定されているUrlConnectionHttpPostClient（L3）
    // 自体は、計画書§6.1・自身のKDoc（services/routing/UrlConnectionHttpPostClient.kt）に
    // より「テストはinstrumented / opt-inのみ」と明記されており、plain JUnit（src/test）で
    // 実HttpURLConnectionに対する検証はできない（真の検証はP3-C8のinstrumented工程で行う。
    // P3-P8のprobe対象でもある）。そのため本テストは、HttpPostClient契約が要求する
    // 「例外発生時も接続/ストリームをcloseする」という規律を、fakeによる実行可能仕様として
    // src/test側に固定する代替策とした。UrlConnectionHttpPostClientの実装（P3-C4）はこの
    // 規律をHttpURLConnection.disconnect()／InputStream.close()で満たす必要がある。
    // 本テストは特定のTODOスタブに依存しないため現時点でもGreenであり、
    // 「即Greenの回帰ガード」に該当する（クラスKDoc・タスク指示に明記）。
    @Test
    fun httpPostClient_whenPostThrowsPartway_stillClosesUnderlyingConnection() = runTest {
        var connectionClosed = false
        val closeTrackingHttpPostClient = HttpPostClient { _, _, _, _ ->
            try {
                throw IOException("simulated read failure after connect")
            } finally {
                connectionClosed = true
            }
        }

        try {
            closeTrackingHttpPostClient.post(
                url = "https://routes.googleapis.com/directions/v2:computeRoutes",
                headers = emptyMap(),
                body = "{}",
                timeout = Duration.ofSeconds(10)
            )
            fail("expected IOException to propagate")
        } catch (e: IOException) {
            // expected
        }

        assertTrue("connection/stream must be closed even when post() throws", connectionClosed)
    }

    // T-ROUTESVC-10: エッジ - 例外メッセージ・ログにAPIキー・座標・イベント情報が
    // 含まれない（§58/§60/§95.2）
    @Test
    fun estimateRoute_exceptionMessageNeverContainsApiKeyOrCoordinates() = runTest {
        val sensitiveApiKey = "AIzaSyDSecretKeyDoNotLeak000111"
        val origin = Coordinate(35.123456, 139.654321)
        val destination = Coordinate(34.111111, 135.222222)
        val httpPostClient = RecordingHttpPostClient { _, _, _, _ -> HttpTextResponse(401, "{}") }
        val service = RoutesApiRoutingService(httpPostClient, apiKey = sensitiveApiKey)

        val thrown = assertThrowsSuspend<RoutingException.Unauthorized> {
            service.estimateRoute(origin, destination, TransportMode.DRIVING, DEPARTURE)
        }

        val message = thrown.message.orEmpty()
        assertFalse("exception message must not contain the API key", message.contains(sensitiveApiKey))
        assertFalse("exception message must not contain origin latitude", message.contains(origin.lat.toString()))
        assertFalse("exception message must not contain origin longitude", message.contains(origin.lon.toString()))
        assertFalse(
            "exception message must not contain destination latitude",
            message.contains(destination.lat.toString())
        )
        assertFalse(
            "exception message must not contain destination longitude",
            message.contains(destination.lon.toString())
        )
    }

    // ---- 共有フィクスチャ ---------------------------------------------------------------

    /**
     * [HttpPostClient]のfake実装（記録型）。呼び出しを[calls]へ全件記録し、応答は
     * [onPost]コールバックへ委譲する（固定値・例外いずれもコールバック内で表現できる）。
     */
    private class RecordingHttpPostClient(
        private val onPost: suspend (url: String, headers: Map<String, String>, body: String, timeout: Duration) -> HttpTextResponse
    ) : HttpPostClient {
        data class Call(val url: String, val headers: Map<String, String>, val body: String, val timeout: Duration)

        val calls = mutableListOf<Call>()

        override suspend fun post(url: String, headers: Map<String, String>, body: String, timeout: Duration): HttpTextResponse {
            calls += Call(url, headers, body, timeout)
            return onPost(url, headers, body, timeout)
        }
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
        private val ORIGIN = Coordinate(lat = 35.681236, lon = 139.767125)
        private val DESTINATION = Coordinate(lat = 34.733611, lon = 135.500138)
        private val DEPARTURE: Instant = Instant.parse("2026-08-09T01:00:00Z")
    }
}
