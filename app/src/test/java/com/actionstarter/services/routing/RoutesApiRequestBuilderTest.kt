package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

/**
 * T-ROUTEREQ-1〜3（計画書§9.2）。対象は[RoutesApiRequestBuilder]（L1純粋関数）。契約は
 * `RoutesApiRequestBuilder.kt`のKDoc（P3-C1・P3-P6実測確定内容、出典
 * developers.google.com/maps/documentation/routes/ 配下）。現状（P3-C1契約scaffold）は
 * [RoutesApiRequestBuilder.build]の本文が`TODO("P3-C4で実装")`のため、以下は全件
 * `NotImplementedError`によりRedになる（意図した失敗）。
 *
 * 計画書§9.1は本テスト群を「純粋ロジック…JUnit4（純JVM）」に分類しており、Robolectric
 * 無しのプレーンJUnit実行となる。org.jsonのAndroidスタブ実装に依存するリスクを避けるため
 * （P3-P4はRobolectric上でのorg.json動作可否を検証するprobeであり、本ファイルの実行系統
 * とは異なる）、org.json等のJSONパーサを使わず正規表現ベースの軽量抽出でアサーションする。
 */
class RoutesApiRequestBuilderTest {

    // T-ROUTEREQ-1: 正常 - origin/destination/mode/departureTimeがリクエストJSONへ
    // 正しく写像される（KDoc: origin.location.latLng.{latitude,longitude}、destinationも
    // 同型、travelMode/departureTimeはトップレベル。routingPreferenceはMVPでは指定しない）
    @Test
    fun build_mapsOriginDestinationModeAndDepartureTime() {
        val origin = Coordinate(lat = 35.681236, lon = 139.767125)
        val destination = Coordinate(lat = 34.733611, lon = 135.500138)
        val departureTime: Instant = Instant.parse("2026-08-09T01:23:45Z")

        val json = RoutesApiRequestBuilder.build(origin, destination, TransportMode.TRANSIT, departureTime)

        val originSeg = originSegment(json)
        val destSeg = destinationSegment(json)
        assertEquals(origin.lat, extractDouble(originSeg, "latitude"), 1e-6)
        assertEquals(origin.lon, extractDouble(originSeg, "longitude"), 1e-6)
        assertEquals(destination.lat, extractDouble(destSeg, "latitude"), 1e-6)
        assertEquals(destination.lon, extractDouble(destSeg, "longitude"), 1e-6)
        assertEquals("TRANSIT", extractString(json, "travelMode"))
        assertEquals("2026-08-09T01:23:45Z", extractString(json, "departureTime"))
        // MVPではrouting Preferenceを指定しない方針（§95.2コスト方針、KDoc）
        assertFalse("MVP must not set routingPreference", containsKey(json, "routingPreference"))
    }

    // T-ROUTEREQ-2: 正常 - TransportMode 4値すべてがRoutes APIのtravelMode値へ漏れなく
    // 写像される（KDoc実測: WALKING→WALK/DRIVING→DRIVE/TRANSIT→TRANSIT/CYCLING→BICYCLE。
    // DRIVING/WALKING/CYCLINGという綴りはRoutes API側に存在しない）
    @Test
    fun build_mapsAllFourTransportModesToRoutesApiTravelMode() {
        val origin = Coordinate(lat = 0.0, lon = 0.0)
        val destination = Coordinate(lat = 1.0, lon = 1.0)
        val departureTime = Instant.parse("2026-08-09T00:00:00Z")
        val expected = mapOf(
            TransportMode.WALKING to "WALK",
            TransportMode.DRIVING to "DRIVE",
            TransportMode.TRANSIT to "TRANSIT",
            TransportMode.CYCLING to "BICYCLE"
        )

        expected.forEach { (mode, travelMode) ->
            val json = RoutesApiRequestBuilder.build(origin, destination, mode, departureTime)
            assertEquals(
                "mode=$mode should map to travelMode=$travelMode",
                travelMode,
                extractString(json, "travelMode")
            )
        }
    }

    // T-ROUTEREQ-3: エッジ - departureDateが過去 → 規則どおりに処理される（規則を固定して
    // ロック）。RoutesApiRequestBuilderはCoordinate/TransportMode/Instantのみを受け取る
    // 純粋写像関数であり、計画書のどこにも「過去日付を拒否する」規則は存在しない
    // （バリデーションはCoordinateのinitのみが担う設計、domain/valueobject/Coordinate.kt）。
    // そのため過去日時でも例外を投げずRFC3339文字列へそのまま写像することを固定する。
    @Test
    fun build_withPastDepartureDate_mapsAsIsWithoutValidation() {
        val origin = Coordinate(lat = 35.0, lon = 139.0)
        val destination = Coordinate(lat = 36.0, lon = 140.0)
        val pastDepartureTime = Instant.parse("2020-01-01T00:00:00Z")

        val json = RoutesApiRequestBuilder.build(origin, destination, TransportMode.DRIVING, pastDepartureTime)

        assertEquals("2020-01-01T00:00:00Z", extractString(json, "departureTime"))
    }

    // T-ROUTEREQ-4〜5（P3-C9、Fable 5裁定・curl実測2026-08-09）: DRIVE + departureTime
    // （routingPreference未指定）はHTTP 400「Timestamp cannot be set for TRAFFIC_UNAWARE
    // routing mode.」を返すことが実測確定した。DRIVE + departureTime +
    // "routingPreference":"TRAFFIC_AWARE"はHTTP 200（duration実測あり）。WALK/BICYCLEは
    // routingPreferenceなしでHTTP 200（実測あり）。routingPreference: TRAFFIC_AWAREは
    // travelModeがDRIVE（またはTWO_WHEELER、本プロジェクト未使用）の場合のみ指定可能という
    // ドキュメント記載（本ファイルKDoc P3-P6実測）とも整合する。したがってtravelModeがDRIVEの
    // 場合のみroutingPreference: TRAFFIC_AWAREをbodyへ追加する（WALK/BICYCLE/TRANSITには
    // 付与しない。departureTimeは全モード維持＝未来出発時刻のETAはMVPのコア機能）。

    // T-ROUTEREQ-4: 正常 - DRIVINGモードのときbodyに"routingPreference":"TRAFFIC_AWARE"が
    // 含まれる（TRAFFIC_UNAWARE既定値のままdepartureTimeを送るとHTTP 400になる実測を回避）
    @Test
    fun build_withDrivingMode_includesTrafficAwareRoutingPreference() {
        val origin = Coordinate(lat = 35.681236, lon = 139.767125)
        val destination = Coordinate(lat = 34.733611, lon = 135.500138)
        val departureTime = Instant.parse("2026-08-09T01:23:45Z")

        val json = RoutesApiRequestBuilder.build(origin, destination, TransportMode.DRIVING, departureTime)

        assertEquals("TRAFFIC_AWARE", extractString(json, "routingPreference"))
        assertEquals("DRIVE", extractString(json, "travelMode"))
    }

    // T-ROUTEREQ-5: 正常 - WALKING/TRANSIT/CYCLING（DRIVE以外の3モード）はbodyに
    // routingPreferenceキーを含まない（APIがDRIVE系以外へのrouting Preference指定を拒否する
    // ため）
    @Test
    fun build_withNonDrivingModes_omitsRoutingPreference() {
        val origin = Coordinate(lat = 35.681236, lon = 139.767125)
        val destination = Coordinate(lat = 34.733611, lon = 135.500138)
        val departureTime = Instant.parse("2026-08-09T01:23:45Z")
        val nonDrivingModes = listOf(TransportMode.WALKING, TransportMode.TRANSIT, TransportMode.CYCLING)

        nonDrivingModes.forEach { mode ->
            val json = RoutesApiRequestBuilder.build(origin, destination, mode, departureTime)
            assertFalse(
                "mode=$mode must not set routingPreference",
                containsKey(json, "routingPreference")
            )
        }
    }

    // ---- 共有ヘルパー（org.json非依存の正規表現ベース抽出。クラスKDoc参照） --------------

    private fun originSegment(json: String): String {
        val idx = json.indexOf("\"destination\"")
        require(idx >= 0) { "\"destination\" key not found in request JSON: $json" }
        return json.substring(0, idx)
    }

    private fun destinationSegment(json: String): String {
        val idx = json.indexOf("\"destination\"")
        require(idx >= 0) { "\"destination\" key not found in request JSON: $json" }
        return json.substring(idx)
    }

    private fun extractDouble(segment: String, key: String): Double {
        val match = Regex("\"$key\"\\s*:\\s*(-?[0-9]+(\\.[0-9]+)?)").find(segment)
            ?: error("field \"$key\" not found in segment: $segment")
        return match.groupValues[1].toDouble()
    }

    private fun extractString(json: String, key: String): String {
        val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
            ?: error("field \"$key\" not found in JSON: $json")
        return match.groupValues[1]
    }

    private fun containsKey(json: String, key: String): Boolean =
        Regex("\"$key\"\\s*:").containsMatchIn(json)
}
