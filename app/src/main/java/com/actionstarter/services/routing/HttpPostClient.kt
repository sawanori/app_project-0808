package com.actionstarter.services.routing

import java.time.Duration

/**
 * L3境界（計画書§5.5）。`java.net.HttpURLConnection`型を[RoutesApiRoutingService]（L2）の
 * 外へ出さない層。実装は[UrlConnectionHttpPostClient]（L3、P3-C4）。JVMテストではfake
 * 実装を注入するため、実ネットワーク・`HttpURLConnection`に一切触れずにテストできる
 * （計画書§9.5 T-ROUTESVC-1〜10）。
 */
fun interface HttpPostClient {
    suspend fun post(url: String, headers: Map<String, String>, body: String, timeout: Duration): HttpTextResponse
}

/** 計画書§5.5。`HttpURLConnection`非依存の最小HTTPレスポンス表現。 */
data class HttpTextResponse(val statusCode: Int, val body: String)
