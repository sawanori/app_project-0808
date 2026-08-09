package com.actionstarter.services.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * [HttpPostClient]の`java.net.HttpURLConnection`実装（L3、計画書§6.1・§7.1）。
 * OkHttp/Retrofit/Ktor等のSDKを追加せず、JDK/Frameworkの`HttpURLConnection`のみで
 * 実装する（新規依存ゼロ、計画書§7.1「単一エンドポイントへの単一POSTにSDKを1本足すのは
 * §88判定でNo」）。テストはinstrumented / opt-inのみ（計画書§6.1）。
 *
 * P3-P8（HttpURLConnectionのタイムアウト＋coroutineキャンセル協調）の実測結果は
 * 本ファイル作成時点のP3-C1で記録済み（本サイクルのprobe実測ログ参照。実装時に
 * `invokeOnCancellation { connection.disconnect() }`パターンの要否を反映する）。
 *
 * **P3-C4実装メモ**: `connection.disconnect()`を`try/finally`で必ず呼び、リクエストbody
 * 書き込み・レスポンス読み取りの各ストリームも`use{}`（＝`try/finally { close() }`）で
 * 確実にcloseする。これは[RoutesApiRoutingServiceTest]のT-ROUTESVC-9が固定した規律
 * （fakeによる実行可能仕様、クラスKDoc「テスト化に関する注記」参照）をこの実クラスでも
 * 満たすためだが、**このクラス自体はplain JUnitで直接検証されない**（instrumented /
 * opt-inのみ、計画書§6.1・P3-C8）。ブロッキングI/Oは`Dispatchers.IO`上で実行する
 * （P-2で解決済みの推移依存によりコルーチン側の追加依存は不要）。
 *
 * **未検証のまま残る事項（P3-P8、計画書§16）**: コルーチンのキャンセル（例:
 * [RoutesApiRoutingService]の`withTimeout`）が発火した際、`HttpURLConnection`の
 * ブロッキング呼び出し自体を`invokeOnCancellation { connection.disconnect() }`で強制的に
 * 中断させる協調は本実装では行わない。`connectTimeout`/`readTimeout`を`timeout`引数から
 * 設定することで多くの場合はソケットレベルのタイムアウトが先に効くが、
 * コルーチン側`withTimeout`と競合した場合の正確な挙動（`SocketTimeoutException`が
 * `IOException`として`Offline`に分類される可能性）は実機検証（P3-C8）が必要であり、
 * 本サイクルのJVMテスト（fake `HttpPostClient`使用）はこの経路を一切検証しない。
 */
class UrlConnectionHttpPostClient : HttpPostClient {
    override suspend fun post(url: String, headers: Map<String, String>, body: String, timeout: Duration): HttpTextResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = timeout.toSaturatedMillis()
                connection.readTimeout = timeout.toSaturatedMillis()
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                }

                val statusCode = connection.responseCode
                val responseBody = readResponseBody(connection, statusCode)
                HttpTextResponse(statusCode = statusCode, body = responseBody)
            } finally {
                connection.disconnect()
            }
        }

    private fun readResponseBody(connection: HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
    }

    private fun Duration.toSaturatedMillis(): Int {
        val millis = toMillis()
        return when {
            millis > Int.MAX_VALUE -> Int.MAX_VALUE
            millis < 0 -> 0
            else -> millis.toInt()
        }
    }
}
