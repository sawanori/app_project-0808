package com.actionstarter.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.BuildConfig
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.services.routing.RoutesApiRoutingService
import com.actionstarter.services.routing.RoutingException
import com.actionstarter.services.routing.UrlConnectionHttpPostClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * T-OPTIN-1・T-OPTIN-2（計画書§9.9 `docs/plans/phase3-routing-location.md`、F31）。Phase 3
 * C2後半（test-writer, 2026-08-09）が新規作成、P3-C9（domain-implementer, 2026-08-09）が
 * T-OPTIN-1の前提修正とT-OPTIN-2追加を行った。
 *
 * opt-in実API疎通ハーネス。`BuildConfig.ROUTES_API_KEY`が空文字の場合は
 * `Assume.assumeTrue`によりskipされ、CI／通常のG4-E実行では常にskipされる設計とする
 * （計画書§4 F31「CI/通常テストからは常に skip される設計」）。
 *
 * **作成のみ・実行しない**（担当プロンプトの明示指示）。実際に本テストが「実行された」と
 * 報告できるのは、実キーを用いてquality-runnerがG4-E補遺として実行した場合のみである
 * （計画書§9.9「E2E群・opt-inは実行するまでpassとして報告することを禁止し、G2／G3の証拠に
 * 含めない」）。
 *
 * 本ファイル作成時点でこの開発環境の`local.properties`には`MAPS_ROUTES_API_KEY`が設定済み
 * （`BuildConfig.ROUTES_API_KEY`が非空）であることを実測したが、値そのものはコミット対象
 * ではなく環境依存のため断定しない。
 *
 * **P3-C9実測確定内容（2026-08-09、Fable 5がcurlで本番同一FieldMask`routes.duration`を
 * 使用し実測）**: 東京タワー(35.6586,139.7454)→明治神宮(35.6595,139.7005)間のTRANSITは
 * HTTP 200・body`{}`（`routes`キー自体が省略される）。Google Routes APIは日本の公共交通
 * データを提供しておらず、同座標間でTRANSITモードは構造的に有効経路0件を返すため、
 * **正のDurationを検証するテスト（T-OPTIN-1）の対象モードとしてTRANSITは使用不能**と判明した。
 * 一方WALK/BICYCLE + departureTimeは同座標でHTTP 200・正のduration実測あり
 * （routingPreference指定なしで成立）。この実測に基づき、T-OPTIN-1のmodeをTRANSITからWALKへ
 * 変更し（正のDuration検証を可能にする）、TRANSITは新設のT-OPTIN-2（0件→[RoutingException.NoRoute]
 * への縮退を検証する回帰ロック）へ役割を分離した。
 *
 * **P3-C9追加実測（本サイクル、実機診断で発見・修正はテストのみ・スコープ外の申し送り事項）**:
 * `departureDate = Instant.now()`（バッファ0）をWALKで送信すると、HTTP 400
 * `"Timestamp must be set to a future time."`（`INVALID_ARGUMENT`）を実測した。host／
 * emulator／Google自身のサーバ（`curl -I https://www.google.com`のDateヘッダ）の3者の時計が
 * 数秒以内で一致していることを実測確認済みのため、環境の時計ズレが原因ではない。
 * ネットワーク往復＋サーバ処理の間に「送信時点のnow」が「サーバ評価時点のnow」より過去へ
 * ずれる構造的な競合状態と判断した。TRANSITは同じ`Instant.now()`（バッファ0）で本サイクルの
 * T-OPTIN-2が実測どおりPASSしており、この検証を（有効経路0件の応答経路が本チェックへ
 * 到達する前に短絡するためと推測されるが未確認）通過しない、またはより緩い可能性がある。
 * 本ファイルでは[FUTURE_DEPARTURE_BUFFER]（2分）を`departureDate`へ加算することで
 * このテスト固有の競合を回避した。**本アプリの実利用では`departureDate`はカレンダー予定の
 * 出発時刻から計算される値であり通常十分に未来のため、この競合が実運用の
 * `DepartureViewModel`経路で顕在化するかは本サイクルの範囲外（変更許可ファイル外）のため
 * 未検証のまま次サイクルへ申し送る**。
 */
@RunWith(AndroidJUnit4::class)
class RoutesApiLiveTest {

    companion object {
        /**
         * P3-C9追加実測（クラスKDoc参照）: `Instant.now()`をバッファ無しでdepartureTimeへ
         * 使うと、ネットワーク往復＋サーバ処理の間に過去時刻へずれ「Timestamp must be set to
         * a future time.」（HTTP 400）を引き起こす競合状態を実測した。opt-inテストの実行環境
         * （エミュレータ〜実サーバ間のRTT）に対して十分な安全マージンとして2分を採用する。
         */
        private val FUTURE_DEPARTURE_BUFFER: Duration = Duration.ofMinutes(2)
    }

    // T-OPTIN-1: 正常系 - キーが設定されている場合のみ実行し、実Routes APIを1回呼び、
    // 200と正のDurationを確認する。キー未設定時はAssumeによりskipされる。
    // 【P3-C9でmodeをTRANSIT→WALKへ変更】TRANSITは東京タワー→明治神宮間で構造的に
    // 有効経路0件（HTTP 200・body`{}`、クラスKDoc「P3-C9実測確定内容」参照）を返すため、
    // 正のDuration検証には使用できないと実測確定した。WALKは同座標でHTTP 200・正のduration
    // が実測されている（クラスKDoc参照）ため、本テストの検証モードとして採用する。
    @Test
    fun tOptin1_apiKeyConfigured_estimateRouteReturnsPositiveDuration() {
        assumeTrue(
            "ROUTES_API_KEY未設定のためスキップ（計画書§9.9、opt-in実API疎通。" +
                "F31「CI/通常テストからは常にskipされる設計」）",
            BuildConfig.ROUTES_API_KEY.isNotEmpty()
        )

        val routingService = RoutesApiRoutingService(
            httpPostClient = UrlConnectionHttpPostClient(),
            apiKey = BuildConfig.ROUTES_API_KEY
        )

        val estimate = runBlocking {
            routingService.estimateRoute(
                origin = Coordinate(lat = 35.6586, lon = 139.7454), // 東京タワー付近（実在の任意点）
                destination = Coordinate(lat = 35.6595, lon = 139.7005), // 明治神宮付近（実在の任意点）
                mode = TransportMode.WALKING,
                departureDate = Instant.now().plus(FUTURE_DEPARTURE_BUFFER)
            )
        }

        assertTrue(
            "Routes API duration should be positive, was ${estimate.duration}",
            !estimate.duration.isNegative && !estimate.duration.isZero
        )
    }

    // T-OPTIN-2（P3-C9新設）: 異常系 - キーが設定されている場合のみ実行し、TRANSIT・
    // 東京タワー→明治神宮間の同座標で実Routes APIを1回呼び、[RoutingException.NoRoute]が
    // 送出されることを確認する。「日本TRANSIT=API非提供→NoRoute縮退」の回帰ロックとして
    // 機能する（クラスKDoc「P3-C9実測確定内容」参照。HTTP 200・body`{}`＝routesキー省略を
    // RoutesApiResponseParserがNoRouteへ正しくマップすることを実機で検証する）。
    // キー未設定時はAssumeによりskipされる。
    @Test
    fun tOptin2_apiKeyConfigured_transitInJapanThrowsNoRoute() {
        assumeTrue(
            "ROUTES_API_KEY未設定のためスキップ（計画書§9.9、opt-in実API疎通。" +
                "F31「CI/通常テストからは常にskipされる設計」）",
            BuildConfig.ROUTES_API_KEY.isNotEmpty()
        )

        val routingService = RoutesApiRoutingService(
            httpPostClient = UrlConnectionHttpPostClient(),
            apiKey = BuildConfig.ROUTES_API_KEY
        )

        assertThrows(RoutingException.NoRoute::class.java) {
            runBlocking {
                routingService.estimateRoute(
                    origin = Coordinate(lat = 35.6586, lon = 139.7454), // 東京タワー付近（実在の任意点）
                    destination = Coordinate(lat = 35.6595, lon = 139.7005), // 明治神宮付近（実在の任意点）
                    mode = TransportMode.TRANSIT,
                    departureDate = Instant.now().plus(FUTURE_DEPARTURE_BUFFER)
                )
            }
        }
    }
}
