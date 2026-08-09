package com.actionstarter.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.BuildConfig
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.services.routing.RoutesApiRoutingService
import com.actionstarter.services.routing.UrlConnectionHttpPostClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * T-OPTIN-1（計画書§9.9 `docs/plans/phase3-routing-location.md`、F31）。Phase 3 C2後半
 * （test-writer, 2026-08-09）が新規作成。
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
 * ではなく環境依存のため断定しない。[RoutesApiRoutingService.estimateRoute]は現時点
 * （P3-C1 scaffold）で`TODO("P3-C4で実装")`のままであり、実行すれば`NotImplementedError`に
 * よりRedになるのが正しい（P3-C4で実装が完了して初めて意味のあるGreen/Redを示す）。
 */
@RunWith(AndroidJUnit4::class)
class RoutesApiLiveTest {

    // T-OPTIN-1: 正常系 - キーが設定されている場合のみ実行し、実Routes APIを1回呼び、
    // 200と正のDurationを確認する。キー未設定時はAssumeによりskipされる。
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
                mode = TransportMode.TRANSIT,
                departureDate = Instant.now()
            )
        }

        assertTrue(
            "Routes API duration should be positive, was ${estimate.duration}",
            !estimate.duration.isNegative && !estimate.duration.isZero
        )
    }
}
