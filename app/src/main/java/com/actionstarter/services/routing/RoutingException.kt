package com.actionstarter.services.routing

/**
 * F24実装（S-4裁定、計画書§5.4・§15 #2）。[RoutingService.estimateRoute]（仕様§46）は
 * `RouteEstimate`を非nullで返す契約のままシグネチャを変更せず、失敗はこのsealed例外
 * 階層で表現する。呼び出し側（`DepartureViewModel`）は`else`節なしの網羅`when`で
 * UI状態へ写像する（T-DEPVM-9、サイレント障害防止）。
 *
 * メッセージに座標・APIキー・イベント情報を含めない（§58/§60、T-ROUTESVC-10で検証）。
 *
 * 契約scaffold（P3-C1、TDD例外）時点では宣言のみ（コンストラクタで固定メッセージを
 * 組み立てるのみ）であり、各例外を実際に送出する実装（[RoutesApiRoutingService]・
 * [CachingRoutingService]）はP3-C4で行う。[UnconfiguredRoutingService]（本サイクルで
 * 実装済み）は例外。
 */
sealed class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : RoutingException("Routes API key is not configured")
    class Offline(cause: Throwable?) : RoutingException("Network unavailable", cause)
    class Timeout(cause: Throwable?) : RoutingException("Request timed out", cause)

    /** HTTP 401/403（APIキー無効・API未有効化）。 */
    class Unauthorized(val httpStatus: Int) : RoutingException("Rejected by Routes API")

    /** HTTP 429（クォータ超過）。 */
    class QuotaExceeded(val httpStatus: Int) : RoutingException("Quota exceeded")

    /** HTTP 5xx。 */
    class ServerError(val httpStatus: Int) : RoutingException("Routes API server error")

    /** レスポンスの`routes`配列が空。0秒へ潰さない（T-ROUTEPARSE-2）。 */
    class NoRoute : RoutingException("No route found")

    /** 不正JSON・想定フィールド欠損（T-ROUTEPARSE-3/4）。例外を握り潰さない。 */
    class MalformedResponse(cause: Throwable?) : RoutingException("Unparsable response", cause)
}
