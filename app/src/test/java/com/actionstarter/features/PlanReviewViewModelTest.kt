@file:OptIn(ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.features.planreview.PlanReviewViewModel
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.planning.BasicPlanningEngine
import com.actionstarter.services.location.GeocodeResult
import com.actionstarter.services.location.GeocodingService
import com.actionstarter.services.location.LocationResult
import com.actionstarter.services.location.LocationService
import com.actionstarter.services.permission.PermissionGate
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-P4C8-1〜5（P4-C8「Plan構築時の実移動時間統合」、`docs/plans/phase4-basic-engine.md`P4-C8行、
 * 仕様§13）。
 *
 * **背景**: [PlanReviewViewModel]（P4-C5〜C6時点）の`buildPlanningContext`は
 * `travelEstimate = null`をハードコードしており、仕様§13の式（`StartOfTransition =
 * EventStart − ArrivalBuffer − TravelTime − PreparationTime − TransitionTime`）から
 * TravelTime項が常に脱落していた。F44（`travelEstimate == null`でもTRAVELステップなしで
 * Planが成立するフォールバック）自体は正しいが、それを取得しにいく主経路（Phase 3の実サービス
 * [GeocodingService]／[LocationService]／[RoutingService]）がPlan構築へ一切配線されていなかった
 * （計画の谷間の統合漏れ）。
 *
 * 本ファイルのfake群は[com.actionstarter.features.DepartureRoutingViewModelTest]の流儀
 * （結果差し替え可能な`var`・呼出記録`callCount`/`calls`・[RoutingException]注入）を踏襲する。
 *
 * **Red化の設計**: 本テスト作成時点で[PlanReviewViewModel]は[GeocodingService]等の新規4引数を
 * 一切受け付けないため、以下はコンパイルが通らないことによりRedになる（Kotlinの静的型付けに
 * おける最も基本的なRed）。CLAUDE.md dev-workflowの「Red: 本番コードはまだ書かない」を厳守する
 * ため、本サイクルのRedフェーズでは`PlanReviewViewModel.kt`／`AppContainer.kt`を一切変更して
 * いない（P4-C3のBasicPlanningEngine契約scaffoldのような別サイクルでの事前scaffoldも行わない
 * ——本タスクは1サイクルでRed→Greenを完結させる指示のため）。
 */
@RunWith(AndroidJUnit4::class)
class PlanReviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val defaultOrigin = Coordinate(lat = 35.6586, lon = 139.7454)
    private val defaultDestination = Coordinate(lat = 35.6595, lon = 139.7005)

    private class FakeGeocodingService(var result: GeocodeResult) : GeocodingService {
        var callCount = 0
            private set

        override suspend fun geocode(locationName: String, timeout: Duration): GeocodeResult {
            callCount++
            return result
        }
    }

    private class FakeLocationService(var result: LocationResult) : LocationService {
        var callCount = 0
            private set

        override suspend fun currentLocation(timeout: Duration): LocationResult {
            callCount++
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

    /**
     * 本番既定`AndroidPermissionGate`相当を模したfake（[com.actionstarter.features.
     * DepartureRoutingViewModelTest.FakePermissionGate]と同型）。
     */
    private class FakePermissionGate(private val granted: Boolean) : PermissionGate {
        override fun isGranted(permission: String): Boolean = granted
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

    private fun sharedPlanViewModelWithSelectedEvent(event: ExecutionEvent): SharedPlanViewModel =
        SharedPlanViewModel().apply { selectEvent(event) }

    private fun createPlanReviewViewModel(
        sharedPlanViewModel: SharedPlanViewModel,
        geocodingService: GeocodingService? = null,
        locationService: LocationService? = null,
        routingService: RoutingService? = null,
        permissionGate: PermissionGate? = null
    ): PlanReviewViewModel = PlanReviewViewModel(
        planningEngine = BasicPlanningEngine(),
        sharedPlanViewModel = sharedPlanViewModel,
        geocodingService = geocodingService,
        locationService = locationService,
        routingService = routingService,
        permissionGate = permissionGate
    )

    // T-P4C8-1: 正常系 - サービス4種すべて揃い・権限あり・geocode/locate/route成功
    // → travelEstimateが注入値になり、TRAVELステップがPlanに現れ、StartOfTransitionが
    // 移動時間ぶんだけ早まる（サービス未配線のベースラインPlanとの差分で直接証明する）。
    @Test
    fun tP4c8_1_servicesPermissionAndRouteSucceed_travelEstimateAppliedAndTransitionStartMovesEarlierByExactTravelDuration() =
        runTest(testDispatcher) {
            val eventStart = Instant.parse("2026-08-10T10:00:00Z")
            val event = sampleEvent(startDate = eventStart, locationName = "Shibuya Office")

            val geocodingService = FakeGeocodingService(GeocodeResult.Success(defaultDestination))
            val locationService = FakeLocationService(
                LocationResult.Success(coordinate = defaultOrigin, accuracyMeters = 10f, fixedAt = eventStart)
            )
            val travelDuration = Duration.ofMinutes(25)
            val routingService = FakeRoutingService(
                result = RouteEstimate(duration = travelDuration, mode = TransportMode.TRANSIT, computedAt = eventStart)
            )
            val permissionGate = FakePermissionGate(granted = true)

            val viewModelWithServices = createPlanReviewViewModel(
                sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event),
                geocodingService = geocodingService,
                locationService = locationService,
                routingService = routingService,
                permissionGate = permissionGate
            )
            testDispatcher.scheduler.advanceUntilIdle()

            // ベースライン（サービス未配線＝Phase 4までの挙動そのもの）: travelEstimateは常にnull。
            val viewModelWithoutServices = createPlanReviewViewModel(
                sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event)
            )
            testDispatcher.scheduler.advanceUntilIdle()

            val planWithTravel = viewModelWithServices.uiState.value.plan
            val planWithoutTravel = viewModelWithoutServices.uiState.value.plan
            assertNotNull(planWithTravel)
            assertNotNull(planWithoutTravel)

            assertTrue(
                "TRAVEL step must appear once a real travel estimate is obtained",
                planWithTravel!!.steps.any { it.type == ExecutionStepType.TRAVEL }
            )
            assertTrue(
                "baseline (no services wired) plan must not contain a TRAVEL step",
                planWithoutTravel!!.steps.none { it.type == ExecutionStepType.TRAVEL }
            )

            assertEquals(
                "StartOfTransition (spec §13) must move earlier by exactly the fetched travel duration",
                planWithoutTravel.transitionStart.minus(travelDuration),
                planWithTravel.transitionStart
            )

            assertEquals(1, geocodingService.callCount)
            assertEquals(1, locationService.callCount)
            assertEquals(1, routingService.callCount)
            val call = routingService.calls.single()
            assertEquals(defaultOrigin, call.origin)
            assertEquals(defaultDestination, call.destination)
            assertEquals(
                "mode must default to DepartureUiState's default (TRANSIT)",
                TransportMode.TRANSIT,
                call.mode
            )
        }

    // T-P4C8-2: 異常系 - RoutingServiceが例外を送出 → travelEstimateはnullへフォールバックし、
    // TRANSITION/PREPARATION/DEPARTUREの3ステップPlanが成立する（F44の回帰ロック）。
    @Test
    fun tP4c8_2_routingThrowsException_fallsBackToNullTravelEstimateAndThreeStepPlan() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = sampleEvent(startDate = eventStart, locationName = "Shibuya Office")

        val geocodingService = FakeGeocodingService(GeocodeResult.Success(defaultDestination))
        val locationService = FakeLocationService(
            LocationResult.Success(coordinate = defaultOrigin, accuracyMeters = 10f, fixedAt = eventStart)
        )
        val routingService = FakeRoutingService(exceptionToThrow = RoutingException.Offline(IOException("no network")))
        val permissionGate = FakePermissionGate(granted = true)

        val viewModel = createPlanReviewViewModel(
            sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event),
            geocodingService = geocodingService,
            locationService = locationService,
            routingService = routingService,
            permissionGate = permissionGate
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val plan = viewModel.uiState.value.plan
        assertNotNull(plan)
        assertTrue(
            "no fabricated travel step when RoutingService fails (F44)",
            plan!!.steps.none { it.type == ExecutionStepType.TRAVEL }
        )
        assertEquals(
            setOf(ExecutionStepType.TRANSITION, ExecutionStepType.PREPARATION, ExecutionStepType.DEPARTURE),
            plan.steps.map { it.type }.toSet()
        )
        assertEquals(3, plan.steps.size)
    }

    // T-P4C8-3: 異常系 - 位置権限なし → geocode/location/routingのいずれも呼ばれず
    // travelEstimateはnullへフォールバックする（DepartureViewModelのP3-C8fixと同型の事前チェック）。
    @Test
    fun tP4c8_3_locationPermissionDenied_skipsAllServiceCallsAndFallsBackToNullTravelEstimate() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = sampleEvent(startDate = eventStart, locationName = "Shibuya Office")

        val geocodingService = FakeGeocodingService(GeocodeResult.Success(defaultDestination))
        val locationService = FakeLocationService(
            LocationResult.Success(coordinate = defaultOrigin, accuracyMeters = 10f, fixedAt = eventStart)
        )
        val routingService = FakeRoutingService(
            result = RouteEstimate(duration = Duration.ofMinutes(25), mode = TransportMode.TRANSIT, computedAt = eventStart)
        )
        val deniedPermissionGate = FakePermissionGate(granted = false)

        val viewModel = createPlanReviewViewModel(
            sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event),
            geocodingService = geocodingService,
            locationService = locationService,
            routingService = routingService,
            permissionGate = deniedPermissionGate
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("geocode must not be called without location permission", 0, geocodingService.callCount)
        assertEquals("LocationService must not be called without location permission", 0, locationService.callCount)
        assertEquals("RoutingService must not be called without location permission", 0, routingService.callCount)

        val plan = viewModel.uiState.value.plan
        assertNotNull(plan)
        assertTrue(plan!!.steps.none { it.type == ExecutionStepType.TRAVEL })
    }

    // T-P4C8-4: エッジケース - event.locationNameがnull/blank → geocodeは呼ばれず
    // travelEstimateはnullへフォールバックする（手がかりが無い。§9エラーマップ#1と同型の思想）。
    @Test
    fun tP4c8_4_blankOrNullLocationName_skipsGeocodeAndFallsBackToNullTravelEstimate() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val permissionGate = FakePermissionGate(granted = true)

        for (locationName in listOf(null, "   ")) {
            val event = sampleEvent(startDate = eventStart, locationName = locationName)
            val geocodingService = FakeGeocodingService(GeocodeResult.Success(defaultDestination))
            val locationService = FakeLocationService(
                LocationResult.Success(coordinate = defaultOrigin, accuracyMeters = 10f, fixedAt = eventStart)
            )
            val routingService = FakeRoutingService(
                result = RouteEstimate(duration = Duration.ofMinutes(25), mode = TransportMode.TRANSIT, computedAt = eventStart)
            )

            val viewModel = createPlanReviewViewModel(
                sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event),
                geocodingService = geocodingService,
                locationService = locationService,
                routingService = routingService,
                permissionGate = permissionGate
            )
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "geocode must not be called when locationName is \"$locationName\"",
                0,
                geocodingService.callCount
            )
            assertEquals(0, locationService.callCount)
            assertEquals(0, routingService.callCount)

            val plan = viewModel.uiState.value.plan
            assertNotNull(plan)
            assertTrue(plan!!.steps.none { it.type == ExecutionStepType.TRAVEL })
        }
    }

    // T-P4C8-5: 回帰ガード - 新規4引数を一切使わない旧2引数構築（旧`AppContainer`呼び出し形状）は
    // 引き続きコンパイル・動作し、travelEstimateは常にnull（Phase 4までの挙動を維持）。
    @Test
    fun tP4c8_5_legacyTwoArgumentConstruction_stillCompilesAndYieldsNullTravelEstimate() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = sampleEvent(startDate = eventStart, locationName = "Shibuya Office")

        // 名前付き引数を一切使わない、Phase 4までの呼び出し形状そのもの。
        val viewModel = PlanReviewViewModel(BasicPlanningEngine(), sharedPlanViewModelWithSelectedEvent(event))
        testDispatcher.scheduler.advanceUntilIdle()

        val plan = viewModel.uiState.value.plan
        assertNotNull(plan)
        assertTrue(
            "legacy 2-argument construction must keep behaving exactly as before P4-C8 (no services => no travel)",
            plan!!.steps.none { it.type == ExecutionStepType.TRAVEL }
        )
    }
}
