package com.actionstarter.services.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration

/**
 * T-ROUTEPARSE-1〜5（計画書§9.2）。対象は[RoutesApiResponseParser]（L1純粋関数）。契約は
 * `RoutesApiResponseParser.kt`のKDoc（P3-C1・P3-P6実測確定内容、出典
 * .../reference/rest/v2/TopLevel/computeRoutes）。現状（P3-C1契約scaffold）は
 * [RoutesApiResponseParser.parse]の本文が`TODO("P3-C4で実装")`のため、以下は全件
 * `NotImplementedError`によりRedになる（意図した失敗）。
 *
 * T-ROUTEPARSE-1はタスク申し送りに従い、整数秒（"1234s"）に加え小数秒（"3.5s"）ケースを
 * 追加する（`RoutesApiResponseParser.kt`KDoc: ドキュメント記載例は小数秒を含むため）。
 */
class RoutesApiResponseParserTest {

    // T-ROUTEPARSE-1: 正常 - duration: "1234s" → Duration.ofSeconds(1234)。
    // 加えて小数秒"3.5s" → 3.5秒相当のDurationも解析可能であることを固定する
    // （P3-C2申し送り事項、RoutesApiResponseParser.kt KDoc「ドキュメント上の例は小数秒を含む」）
    @Test
    fun parse_withIntegerAndDecimalSecondsDuration_returnsEquivalentJavaDuration() {
        val integerJson = """{"routes":[{"duration":"1234s"}]}"""
        assertEquals(Duration.ofSeconds(1234), RoutesApiResponseParser.parse(integerJson))

        val decimalJson = """{"routes":[{"duration":"3.5s"}]}"""
        assertEquals(Duration.ofMillis(3500), RoutesApiResponseParser.parse(decimalJson))
    }

    // T-ROUTEPARSE-2: 異常 - routes配列が空 → NoRoute（0秒へ潰さない）
    @Test
    fun parse_withEmptyRoutesArray_throwsNoRoute() {
        val json = """{"routes":[]}"""

        assertThrows(RoutingException.NoRoute::class.java) {
            RoutesApiResponseParser.parse(json)
        }
    }

    // T-ROUTEPARSE-3: 異常 - 不正JSON → MalformedResponse（例外を握り潰さない）
    @Test
    fun parse_withInvalidJson_throwsMalformedResponse() {
        val invalidJson = "{not valid json"

        assertThrows(RoutingException.MalformedResponse::class.java) {
            RoutesApiResponseParser.parse(invalidJson)
        }
    }

    // T-ROUTEPARSE-4: エッジ - durationフィールド欠損 → MalformedResponse
    @Test
    fun parse_withMissingDurationField_throwsMalformedResponse() {
        val json = """{"routes":[{"distanceMeters":1000}]}"""

        assertThrows(RoutingException.MalformedResponse::class.java) {
            RoutesApiResponseParser.parse(json)
        }
    }

    // T-ROUTEPARSE-5: エッジ - 想定外の余剰フィールドがあっても解析が継続する（前方互換）
    @Test
    fun parse_withUnexpectedExtraFields_stillParsesDuration() {
        val json = """
            {
              "routes": [
                {
                  "duration": "600s",
                  "distanceMeters": 5000,
                  "polyline": {"encodedPolyline": "abc123"},
                  "legs": [{"steps": []}],
                  "unknownFutureField": {"nested": true}
                }
              ],
              "unknownTopLevelField": "ignored"
            }
        """.trimIndent()

        assertEquals(Duration.ofSeconds(600), RoutesApiResponseParser.parse(json))
    }
}
