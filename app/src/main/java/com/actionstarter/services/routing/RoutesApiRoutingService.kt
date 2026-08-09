package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * [RoutingService]（仕様§46、シグネチャ不変）のL2実装（F24、計画書§6.1）。
 * [HttpPostClient]を注入し、[RoutesApiRequestBuilder]／[RoutesApiResponseParser]（L1）を
 * 介してComputeRoutesを呼ぶ。JVMテスト対象（fakeの[HttpPostClient]で検証、
 * 計画書§9.5 T-ROUTESVC-1〜10）。APIキーは認証ヘッダ（`X-Goog-Api-Key`、P3-P6実測）で
 * 送信し、リクエストbody・URLクエリには含めない（T-ROUTESVC-2）。
 *
 * **P3-C4実装メモ**: fakeの[HttpPostClient]（テストの`RecordingHttpPostClient`）は
 * `timeout`引数を自ら強制しない（コールバック内で無条件に`delay`するだけ）ため、
 * タイムアウトは本クラス側で`kotlinx.coroutines.withTimeout`によりコルーチンレベルで
 * 強制する（T-ROUTESVC-7）。`IOException`は[RoutingException.Offline]へ、
 * `TimeoutCancellationException`は[RoutingException.Timeout]へ変換する。HTTPステータスは
 * 200→成功、401/403→[RoutingException.Unauthorized]、429→[RoutingException.QuotaExceeded]、
 * 5xx→[RoutingException.ServerError]。**契約の未定義域**: 200/401/403/429/5xx以外の
 * ステータス（例: 404/400）は計画書§10のエラー＆レスキューマップに行が存在せず未定義
 * のため、`ServerError`扱いへ寄せる（データを偽装せず・クラッシュもしない安全側の選択。
 * いずれのJVMテストもこの分岐を検証しない）。
 */
class RoutesApiRoutingService(
    private val httpPostClient: HttpPostClient,
    private val apiKey: String,
    private val timeout: Duration = DEFAULT_TIMEOUT
) : RoutingService {
    override suspend fun estimateRoute(
        origin: Coordinate,
        destination: Coordinate,
        mode: TransportMode,
        departureDate: Instant
    ): RouteEstimate {
        val requestBody = RoutesApiRequestBuilder.build(origin, destination, mode, departureDate)
        // §95.2/§58/§60: 座標・移動手段・出発時刻のみを送信する。認証はヘッダのみ
        // （body・URLクエリにAPIキーを含めない、T-ROUTESVC-2）。
        val headers = mapOf(
            "X-Goog-Api-Key" to apiKey,
            "X-Goog-FieldMask" to FIELD_MASK,
            "Content-Type" to "application/json; charset=utf-8"
        )

        val response = try {
            withTimeout(timeout.toMillis()) {
                httpPostClient.post(COMPUTE_ROUTES_URL, headers, requestBody, timeout)
            }
        } catch (e: TimeoutCancellationException) {
            throw RoutingException.Timeout(e)
        } catch (e: IOException) {
            throw RoutingException.Offline(e)
        }

        return when (response.statusCode) {
            200 -> RouteEstimate(
                duration = RoutesApiResponseParser.parse(response.body),
                mode = mode,
                computedAt = Instant.now()
            )
            401, 403 -> throw RoutingException.Unauthorized(response.statusCode)
            429 -> throw RoutingException.QuotaExceeded(response.statusCode)
            else -> throw RoutingException.ServerError(response.statusCode)
        }
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

        // P3-P6実測確定（RoutesApiRequestBuilder.kt KDoc）。
        const val COMPUTE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"

        // X-Goog-FieldMaskは必須ヘッダ（P3-P6実測）。durationのみが必要なため最小値を使う。
        const val FIELD_MASK = "routes.duration"
    }
}
