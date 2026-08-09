@file:OptIn(ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.features.departure.DepartureViewModel
import com.actionstarter.features.departure.EtaFailureReason
import com.actionstarter.features.departure.LocationPermissionState
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.services.location.GeocodeResult
import com.actionstarter.services.location.GeocodingService
import com.actionstarter.services.location.LocationResult
import com.actionstarter.services.location.LocationService
import com.actionstarter.services.routing.RoutingException
import com.actionstarter.services.routing.RoutingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * T-DEPVM-1〜9（計画書§9.7 `docs/plans/phase3-routing-location.md`、F26/F27/F28）。
 * Phase 3 C2後半（test-writer, 2026-08-09）が新規作成。Phase 4の[DepartureViewModelTest]
 * （T-P4DEP-1〜5、`SharedPlanViewModel.confirmedPlan`からの静的マッピングのみを検証）とは
 * 完全に分離し、本ファイルは「現在地取得→geocoding→Routes API」という**Phase 3が追加する
 * リアルタイム再計算**のみを対象にする。
 *
 * ## コンストラクタの不一致（担当プロンプトの注意書きどおり・重要）
 * [DepartureViewModel]の現行コンストラクタ（2026-08-09時点）は
 * `DepartureViewModel(sharedPlanViewModel: SharedPlanViewModel)`のみであり、
 * `LocationService`/`GeocodingService`/`RoutingService`を注入する口を持たない
 * （`DepartureViewModel.kt`の`TODO(P4-C5)`コメント参照。計画書§6.2「前提: Phase 4のDeparture
 * 結線完了」によりP3-C5はPhase 4完了後に着手する）。本タスクは「本番Kotlin変更禁止」の
 * 制約下にあるため、[DepartureViewModel]自体にサービス注入コンストラクタを追加することは
 * できない。
 *
 * そのため本ファイルは[createDepartureViewModel]・[DepartureViewModel.changeTransportModeForTest]を
 * 「将来のコンストラクタ／公開APIの形を前提とするヘルパー」として定義し、本文は常に
 * `TODO()`（`NotImplementedError`送出）とする。これにより：
 * - 本ファイルは実コンストラクタの型（[DepartureViewModel]・[SharedPlanViewModel]・
 *   [LocationService]・[GeocodingService]・[RoutingService]）を参照するため**コンパイルは
 *   成立する**（担当プロンプト「コンパイル不能なら該当ケースをスキップして報告」の対象外）。
 * - 各`@Test`メソッドは、ヘルパーが実装された後に必要な変更なしでそのまま意味のある
 *   Redテストとして機能する形（fakeサービスの構成→ヘルパー呼び出し→`uiState`アサーション）
 *   で記述している。
 * - 現時点で実行すると、すべてのテストが`createDepartureViewModel`呼び出し時点の
 *   `NotImplementedError`で失敗する（Red。ただし本サイクルは「検証はコンパイルのみ・実行禁止」
 *   のため実行はしていない）。P3-C5でヘルパーの中身を実コンストラクタ／実APIへ置き換えた
 *   時点で、各テストは元々意図した個別の期待値（§9.7の期待どおり）で初めて意味のある
 *   Red/Greenを示すようになる。
 *
 * ## 設計上の解釈（test-writerによる補完・計画書に明記なし）
 * F27（計画書§4）「`estimatedArrival = departureTime + duration`」の`departureTime`が
 * 「Basic Engine（Phase4）が計算した`ExecutionPlan.departureTime`」なのか「Phase 3の
 * 再計算時点（Leave nowの"now"）」なのかは計画書に明記がない。本ファイルは後者
 * （**再計算時点＝[Clock]注入によるテスト可能な"now"**）と解釈する。根拠：
 * (a) Departure画面のタイトルは"Leave now"（`departure_title`）であり、§29の完成条件は
 * 「現在地→予定先の所要時間が取れる」というリアルタイム性が本質であること、
 * (b) F27が「Instant演算のためDST安全」とわざわざ注記するのは、`departureTime`が
 * 任意の瞬間（DST切替瞬間を含む"now"）になり得るからこそ意味を持つこと。
 * `plan.departureTime`（Phase4出力）との関係はP3-C5実装時に確定させるべき残課題であり、
 * ここでは明示的な仮定として扱う（断定しない）。
 *
 * ## fakeサービスの設計
 * [FakeLocationService]/[FakeGeocodingService]/[FakeRoutingService]は結果差し替え（`var`）・
 * 呼出記録（`callCount`/`calls`）・[RoutingException]注入を提供する（担当プロンプトの要求
 * どおり）。
 */
@RunWith(AndroidJUnit4::class)
class DepartureRoutingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val defaultLocationSuccess = LocationResult.Success(
        coordinate = Coordinate(lat = 35.6586, lon = 139.7454),
        accuracyMeters = 10f,
        fixedAt = Instant.parse("2026-08-08T00:00:00Z")
    )

    private val defaultGeocodeSuccess = GeocodeResult.Success(Coordinate(lat = 35.6595, lon = 139.7005))

    private class FakeLocationService(var result: LocationResult) : LocationService {
        var callCount = 0
            private set

        override suspend fun currentLocation(timeout: Duration): LocationResult {
            callCount++
            return result
        }
    }

    private class FakeGeocodingService(var result: GeocodeResult) : GeocodingService {
        var callCount = 0
            private set
        val queries = mutableListOf<String>()

        override suspend fun geocode(locationName: String, timeout: Duration): GeocodeResult {
            callCount++
            queries += locationName
            return result
        }
    }

    private class FakeRoutingService(
        var result: RouteEstimate? = null,
        var exceptionToThrow: RoutingException? = null
    ) : RoutingService {
        var callCount = 0
            private set
        val calls = mutableListOf<Call>()

        data class Call(
            val origin: Coordinate,
            val destination: Coordinate,
            val mode: TransportMode,
            val departureDate: Instant
        )

        override suspend fun estimateRoute(
            origin: Coordinate,
            destination: Coordinate,
            mode: TransportMode,
            departureDate: Instant
        ): RouteEstimate {
            callCount++
            calls += Call(origin, destination, mode, departureDate)
            exceptionToThrow?.let { throw it }
            return result ?: error("FakeRoutingService: configure result or exceptionToThrow before calling estimateRoute")
        }
    }

    private fun sampleEvent(
        startDate: Instant,
        locationName: String? = "Shibuya Office"
    ): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = startDate,
        locationName = locationName,
        coordinates = null,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    /**
     * [event]を確定済みPlanとして保持する[SharedPlanViewModel]を返す。`steps`は空リストで
     * よい（T-DEPVM系は`plan.event.startDate`のみを参照し、Basic Engineが計算する
     * `steps`/`departureTime`/`estimatedArrival`/`arrivalBuffer`はT-P4DEP系の検証対象であり
     * 本ファイルの対象外のため）。
     */
    private fun confirmedSharedPlanViewModel(event: ExecutionEvent): SharedPlanViewModel {
        val plan = ExecutionPlan(
            event = event,
            steps = emptyList(),
            transitionStart = event.startDate.minus(Duration.ofMinutes(30)),
            departureTime = event.startDate.minus(Duration.ofMinutes(10)),
            estimatedArrival = event.startDate.minus(Duration.ofMinutes(5)),
            arrivalBuffer = Duration.ofMinutes(5)
        )
        return SharedPlanViewModel().apply { confirmPlan(plan) }
    }

    /**
     * P3-C5実装済み: [DepartureViewModel]の実コンストラクタ（計画書§9.7）へ委譲する。
     * 呼び出し側（各`@Test`）は本関数のシグネチャ変更なしにそのまま利用できる（Redフェーズ
     * 時点の設計意図どおり、本体のみを実コンストラクタ呼び出しへ置き換えた）。
     */
    private fun createDepartureViewModel(
        sharedPlanViewModel: SharedPlanViewModel,
        locationService: LocationService,
        geocodingService: GeocodingService,
        routingService: RoutingService,
        clock: Clock = Clock.systemUTC()
    ): DepartureViewModel = DepartureViewModel(
        sharedPlanViewModel = sharedPlanViewModel,
        locationService = locationService,
        geocodingService = geocodingService,
        routingService = routingService,
        clock = clock
    )

    /**
     * T-DEPVM-8用ヘルパー。P3-C5で追加された[DepartureViewModel.onTransportModeSelected]
     * （transportMode変更通知の公開API）へ委譲する。
     */
    private fun DepartureViewModel.changeTransportModeForTest(mode: TransportMode) {
        onTransportModeSelected(mode)
    }

    // T-DEPVM-1: 正常系 - 権限あり・キーあり（=RoutingServiceが正常値を返す）→ Loading → Content
    // （ETA・Buffer算出、§29/§35 Screen4）。
    @Test
    fun tDepVm1_permissionGrantedAndRoutingSucceeds_computesEtaFromNowPlusDuration() = runTest(testDispatcher) {
        val fixedNow = Instant.parse("2026-08-10T09:00:00Z")
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = sampleEvent(startDate = eventStart, locationName = "Shibuya Office")
        val sharedPlanViewModel = confirmedSharedPlanViewModel(event)

        val locationService = FakeLocationService(defaultLocationSuccess)
        val geocodingService = FakeGeocodingService(defaultGeocodeSuccess)
        val routeDuration = Duration.ofMinutes(25)
        val routingService = FakeRoutingService(
            result = RouteEstimate(duration = routeDuration, mode = TransportMode.TRANSIT, computedAt = fixedNow)
        )

        val viewModel =
            createDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService, clock)
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(LocationPermissionState.GRANTED, uiState.permissionState)
        assertNull(uiState.etaFailureReason)
        assertEquals(eventStart, uiState.eventStart)
        assertEquals(fixedNow.plus(routeDuration), uiState.estimatedArrival)
        assertEquals(1, locationService.callCount)
        assertEquals(1, geocodingService.callCount)
        assertEquals(1, routingService.callCount)
    }

    // T-DEPVM-2: 異常系 - 位置権限拒否 → 手動Travel Time入力状態（空表示にしない・§95.4）。
    @Test
    fun tDepVm2_locationPermissionDenied_mapsToManualTravelTimeFallbackState() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = sampleEvent(startDate = eventStart)
        val sharedPlanViewModel = confirmedSharedPlanViewModel(event)

        val locationService = FakeLocationService(LocationResult.PermissionDenied)
        val geocodingService = FakeGeocodingService(defaultGeocodeSuccess)
        val routingService = FakeRoutingService()

        val viewModel =
            createDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService)
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(LocationPermissionState.DENIED, uiState.permissionState)
        assertNull("no fabricated ETA when location permission is denied", uiState.estimatedArrival)
        assertEquals(
            "RoutingService must not be called without a location fix",
            0,
            routingService.callCount
        )
    }

    // T-DEPVM-3: 異常系 - 経路取得失敗かつキャッシュ無し → 精度低下明示＋手動入力導線（§95.6）。
    @Test
    fun tDepVm3_routingFailsWithNoCache_mapsToOfflineFailureWithoutFakeEta() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = sampleEvent(startDate = eventStart)
        val sharedPlanViewModel = confirmedSharedPlanViewModel(event)

        val locationService = FakeLocationService(defaultLocationSuccess)
        val geocodingService = FakeGeocodingService(defaultGeocodeSuccess)
        val routingService =
            FakeRoutingService(exceptionToThrow = RoutingException.Offline(IOException("no network")))

        val viewModel =
            createDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService)
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(EtaFailureReason.OFFLINE, uiState.etaFailureReason)
        assertNull("no fabricated ETA when routing fails and no cache exists", uiState.estimatedArrival)
    }

    // T-DEPVM-4: エッジケース - キャッシュ返却時 isEtaStale == true（`computedAt`由来、§95.6/§8）。
    @Test
    fun tDepVm4_routeEstimateOlderThanStaleThreshold_marksEtaAsStale() = runTest(testDispatcher) {
        val fixedNow = Instant.parse("2026-08-10T09:30:00Z")
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = sampleEvent(startDate = eventStart)
        val sharedPlanViewModel = confirmedSharedPlanViewModel(event)

        // 10分閾値（計画書§8）より古いcomputedAt。
        val staleComputedAt = fixedNow.minus(Duration.ofMinutes(20))
        val locationService = FakeLocationService(defaultLocationSuccess)
        val geocodingService = FakeGeocodingService(defaultGeocodeSuccess)
        val routingService = FakeRoutingService(
            result = RouteEstimate(duration = Duration.ofMinutes(15), mode = TransportMode.TRANSIT, computedAt = staleComputedAt)
        )

        val viewModel =
            createDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService, clock)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("stale RouteEstimate.computedAt must set isEtaStale=true", viewModel.uiState.value.isEtaStale)
    }

    // T-DEPVM-5: エッジケース - geocode `NoMatch`（会議室名等）→ エラー表示ではなく手動入力導線＋
    // 説明文言（計画書§5.3/§10#11）。
    @Test
    fun tDepVm5_geocodeNoMatch_doesNotSurfaceAsRoutingErrorAndSkipsRouting() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = sampleEvent(startDate = eventStart, locationName = "Zoom")
        val sharedPlanViewModel = confirmedSharedPlanViewModel(event)

        val locationService = FakeLocationService(defaultLocationSuccess)
        val geocodingService = FakeGeocodingService(GeocodeResult.NoMatch)
        val routingService = FakeRoutingService()

        val viewModel =
            createDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService)
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertNull("geocode NoMatch is not an error (計画書§5.3)", uiState.etaFailureReason)
        assertNull(uiState.estimatedArrival)
        assertEquals(
            "RoutingService must not be called without a resolved destination",
            0,
            routingService.callCount
        )
    }

    // T-DEPVM-6: エッジケース - `arrivalBuffer`が負 → 遅刻警告を表示するが自動補正しない
    // （§34ユーザー最終決定）。
    @Test
    fun tDepVm6_negativeArrivalBuffer_isNotClampedOrAutoCorrected() = runTest(testDispatcher) {
        val fixedNow = Instant.parse("2026-08-10T09:50:00Z")
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val eventStart = Instant.parse("2026-08-10T10:00:00Z") // now + 10分
        val event = sampleEvent(startDate = eventStart)
        val sharedPlanViewModel = confirmedSharedPlanViewModel(event)

        val locationService = FakeLocationService(defaultLocationSuccess)
        val geocodingService = FakeGeocodingService(defaultGeocodeSuccess)
        val routeDuration = Duration.ofMinutes(25) // now + 25分 > eventStart(now + 10分) → 遅刻
        val routingService = FakeRoutingService(
            result = RouteEstimate(duration = routeDuration, mode = TransportMode.TRANSIT, computedAt = fixedNow)
        )

        val viewModel =
            createDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService, clock)
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        val expectedBuffer = Duration.between(fixedNow.plus(routeDuration), eventStart) // -15分
        assertEquals(expectedBuffer, uiState.arrivalBuffer)
        assertTrue(
            "negative buffer must not be clamped to zero/positive",
            uiState.arrivalBuffer!!.isNegative
        )
    }

    // T-DEPVM-7: エッジケース - DST跨ぎETA。America/New_Yorkの切替日（2026-03-08、春の時刻繰上げ）
    // を跨ぐ予定でも、`Instant`演算によるETAが1時間ずれない（§6により日本＝DSTなしを前提にしない）。
    @Test
    fun tDepVm7_dstTransitionInstant_estimatedArrivalDoesNotShiftByOneHour() = runTest(testDispatcher) {
        // 2026-03-08 01:30 America/New_York (EST, UTC-5) = 2026-03-08T06:30:00Z。
        // 同日02:00 EST時点でクロックが03:00 EDTへ繰り上がる（米国のDST開始、2026-03-08）。
        val fixedNow = Instant.parse("2026-03-08T06:30:00Z")
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val eventStart = fixedNow.plus(Duration.ofHours(3))
        val event = sampleEvent(startDate = eventStart)
        val sharedPlanViewModel = confirmedSharedPlanViewModel(event)

        val locationService = FakeLocationService(defaultLocationSuccess)
        val geocodingService = FakeGeocodingService(defaultGeocodeSuccess)
        // 経過中にDST切替瞬間（06:00Z=02:00 EST → 07:00Z=03:00 EDT）を跨ぐ所要時間。
        val routeDuration = Duration.ofMinutes(45)
        val routingService = FakeRoutingService(
            result = RouteEstimate(duration = routeDuration, mode = TransportMode.TRANSIT, computedAt = fixedNow)
        )

        val viewModel =
            createDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService, clock)
        testDispatcher.scheduler.advanceUntilIdle()

        // Instant算術は常にDST安全であるため、ローカルタイムゾーン変換を経由せず厳密等価に
        // なることをロックする（万一ZonedDateTime/LocalDateTime経由の実装に置き換わった場合、
        // このアサーションが1時間ずれを検出する回帰ガードになる）。
        assertEquals(fixedNow.plus(routeDuration), viewModel.uiState.value.estimatedArrival)
    }

    // T-DEPVM-8: 正常系 - `TransportMode`変更で再計算が1回だけ走る（多重発火しない）。
    @Test
    fun tDepVm8_transportModeChange_triggersExactlyOneRecalculation() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = sampleEvent(startDate = eventStart)
        val sharedPlanViewModel = confirmedSharedPlanViewModel(event)

        val locationService = FakeLocationService(defaultLocationSuccess)
        val geocodingService = FakeGeocodingService(defaultGeocodeSuccess)
        val routingService = FakeRoutingService(
            result = RouteEstimate(
                duration = Duration.ofMinutes(20),
                mode = TransportMode.TRANSIT,
                computedAt = Instant.parse("2026-08-10T09:00:00Z")
            )
        )

        val viewModel =
            createDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService)
        testDispatcher.scheduler.advanceUntilIdle()
        val callCountAfterInitialLoad = routingService.callCount

        viewModel.changeTransportModeForTest(TransportMode.WALKING)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "changing transportMode once must recalculate exactly once (no duplicate / no missing dispatch)",
            callCountAfterInitialLoad + 1,
            routingService.callCount
        )
    }

    // T-DEPVM-9: 異常系 - `RoutingException`の全サブクラス（8種）がUI状態へ網羅的に写像される
    // （`when`にelseなし＝サイレント障害防止、計画書§5.4／`DepartureUiState.EtaFailureReason`の
    // KDoc対応表）。ブラックボックステストではソースの`when`にelse節が無いことそのものは
    // 検証できないため、8種すべてが個別に正しい`EtaFailureReason`へ写像されることを
    // 「網羅的ハンドリングの証拠」として固定する（いずれか1つでも`else`で握り潰されれば、
    // そのケースのアサーションが失敗する）。
    @Test
    fun tDepVm9_everyRoutingExceptionSubclass_mapsToDistinctEtaFailureReason() = runTest(testDispatcher) {
        val cases: List<Pair<RoutingException, EtaFailureReason>> = listOf(
            RoutingException.NotConfigured() to EtaFailureReason.NOT_CONFIGURED,
            RoutingException.Offline(IOException("offline")) to EtaFailureReason.OFFLINE,
            RoutingException.Timeout(IOException("timeout")) to EtaFailureReason.TIMEOUT,
            RoutingException.Unauthorized(401) to EtaFailureReason.UNAUTHORIZED,
            RoutingException.QuotaExceeded(429) to EtaFailureReason.QUOTA_EXCEEDED,
            RoutingException.ServerError(500) to EtaFailureReason.SERVER_ERROR,
            RoutingException.NoRoute() to EtaFailureReason.NO_ROUTE,
            RoutingException.MalformedResponse(IllegalStateException("bad json")) to EtaFailureReason.MALFORMED_RESPONSE
        )
        assertEquals("test fixture must cover all 8 RoutingException subclasses", 8, cases.size)

        for ((exception, expectedReason) in cases) {
            val eventStart = Instant.parse("2026-08-10T10:00:00Z")
            val event = sampleEvent(startDate = eventStart)
            val sharedPlanViewModel = confirmedSharedPlanViewModel(event)
            val locationService = FakeLocationService(defaultLocationSuccess)
            val geocodingService = FakeGeocodingService(defaultGeocodeSuccess)
            val routingService = FakeRoutingService(exceptionToThrow = exception)

            val viewModel =
                createDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "${exception::class.simpleName} must map to $expectedReason",
                expectedReason,
                viewModel.uiState.value.etaFailureReason
            )
        }
    }
}
