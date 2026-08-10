@file:OptIn(ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.ai.AiGatewayTestFixtures
import com.actionstarter.ai.LocalAiPlanContextualizer
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.features.planreview.AiContextualizationState
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
import kotlinx.coroutines.delay
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
import java.util.Locale
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
    private lateinit var originalDefaultLocale: Locale

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Phase 8（T-P8-3/4/19/26）: PlanReviewViewModel.buildPlanningContextはLocale.getDefault()
        // をPlanningContext.localeへそのまま使う。本ファイルのAI経由テストは日本語の
        // display_textフィクスチャを使うため、ContentSanityChecker.isLocaleConsistent
        // （§15逸脱の二重防御）と整合させるためJVMの既定localeをJapanへ固定する
        // （実行環境依存の既定localeに結果が左右されないようにする）。BasicPlanningEngineは
        // localeを一切参照しないため、既存のtP4c8_*テストへの影響はない。
        originalDefaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalDefaultLocale)
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
        var exceptionToThrow: RoutingException? = null,
        // Phase 8（T-P8-26、新規・末尾・既定0）: travel解決のタイミングを人為的に制御するために追加。
        // 既定0のため既存の全呼び出し（delayMillisを渡さない）は挙動不変。
        var delayMillis: Long = 0L
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
            if (delayMillis > 0) {
                delay(delayMillis)
            }
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
        permissionGate: PermissionGate? = null,
        // Phase 8（新規・末尾・既定null）: 省略時は既存呼び出しと完全に同じ挙動（AIフェーズskip）。
        aiPlanContextualizer: LocalAiPlanContextualizer? = null
    ): PlanReviewViewModel = PlanReviewViewModel(
        planningEngine = BasicPlanningEngine(),
        sharedPlanViewModel = sharedPlanViewModel,
        geocodingService = geocodingService,
        locationService = locationService,
        routingService = routingService,
        permissionGate = permissionGate,
        aiPlanContextualizer = aiPlanContextualizer
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

    // ------------------------------------------------------------------
    // Phase 8（計画書`docs/plans/phase8-ai-execution-wiring.md`§8）。ViewModelレベルのT-P8-*
    // （3・4・13・19・22・26、6件）。gateway単体のT-P8-1/2/5〜12/14〜18/20/21/23/25は
    // `com.actionstarter.ai.LocalAiPlanContextualizerTest`、T-P8-24は`ai.model.ModelStorageTest`。
    // ------------------------------------------------------------------

    // T-P8-3: 非同期状態遷移（combine由来、§4改訂）— event選択直後はBasic(IN_PROGRESS)、
    // AI Success到達後はAPPLIEDへ遷移する。いずれもcombineの自動再emitであり、_uiState.valueへの
    // 命令的代入はしない（Gemini G1 CRITICAL②反映）。
    @Test
    fun tP8_3_asyncStateTransition_startsInProgressThenBecomesAppliedAfterAiSuccess() = runTest(testDispatcher) {
        val event = sampleEvent(startDate = Instant.parse("2026-08-10T10:00:00Z"), locationName = null)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "保険証を持って行く")
                )
            ),
            delayMillisPerCall = 5_000L
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_3")

        val viewModel = createPlanReviewViewModel(
            sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event),
            aiPlanContextualizer = LocalAiPlanContextualizer(gateway)
        )
        testDispatcher.scheduler.runCurrent()

        val inProgress = viewModel.uiState.value
        assertEquals(
            "AI応答未到達の間はaiState=IN_PROGRESSであるべきです(T-P8-3)",
            AiContextualizationState.InProgress,
            inProgress.aiState
        )
        assertNotNull("Basic(travel未解決)のplanは即時表示されているべきです", inProgress.plan)

        testDispatcher.scheduler.advanceUntilIdle()

        val applied = viewModel.uiState.value
        assertEquals(AiContextualizationState.Applied, applied.aiState)
        assertEquals(
            "保険証を持って行く",
            applied.plan!!.steps.single { it.type == ExecutionStepType.PREPARATION }.title
        )
    }

    // T-P8-4: 確定伝播 — Start後、Execution向けconfirmedPlan.steps[i].titleがAI文言を反映する
    // （Execution無改修で透過。T-P8-4）。
    @Test
    fun tP8_4_confirmAndStart_propagatesAiOverlaidTitles_toConfirmedPlan() = runTest(testDispatcher) {
        val event = sampleEvent(startDate = Instant.parse("2026-08-10T10:00:00Z"), locationName = null)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "保険証を持って行く")
                )
            )
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_4")
        val sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event)

        val viewModel = createPlanReviewViewModel(
            sharedPlanViewModel = sharedPlanViewModel,
            aiPlanContextualizer = LocalAiPlanContextualizer(gateway)
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(AiContextualizationState.Applied, viewModel.uiState.value.aiState)

        viewModel.confirmAndStart()

        val confirmedPlan = sharedPlanViewModel.confirmedPlan.value
        assertNotNull("confirmAndStart()はplanを確定させるはずです", confirmedPlan)
        assertEquals(
            "保険証を持って行く",
            confirmedPlan!!.steps.single { it.type == ExecutionStepType.PREPARATION }.title
        )
    }

    // T-P8-13: aiPlanContextualizer=null（旧2引数構築）→ AIフェーズskip・Basic（後方互換）
    @Test
    fun tP8_13_aiPlanContextualizerNull_legacyConstruction_aiPhaseSkipped_aiStateStaysIdle() = runTest(testDispatcher) {
        val event = sampleEvent(startDate = Instant.parse("2026-08-10T10:00:00Z"))

        // 旧2引数構築（aiPlanContextualizerを一切渡さない）。
        val viewModel = PlanReviewViewModel(BasicPlanningEngine(), sharedPlanViewModelWithSelectedEvent(event))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            "aiPlanContextualizer=nullの間はaiStateが常にIDLEであるべきです(T-P8-13)",
            AiContextualizationState.Idle,
            state.aiState
        )
        assertNotNull(state.plan)
        assertTrue(state.plan!!.steps.none { it.type == ExecutionStepType.TRAVEL })
    }

    // T-P8-19: stale（推論中に別イベント選択）— 旧イベント宛てAI応答（またはその可能性）が
    // 新イベントのuiStateへ混入せず、最終状態は新イベント自身のAI結果のみを反映する。
    @Test
    fun tP8_19_staleAiResponseAfterEventSwitch_finalUiStateReflectsOnlyNewEvent() = runTest(testDispatcher) {
        val event1 = sampleEvent(startDate = Instant.parse("2026-08-10T10:00:00Z"), locationName = null)
        val event2 = sampleEvent(startDate = Instant.parse("2026-08-11T10:00:00Z"), locationName = null)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "旧予定の文言")
                ),
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "新予定の文言")
                )
            ),
            delayMillisPerCall = 10_000L
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_19")
        val sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event1)

        val viewModel = createPlanReviewViewModel(
            sharedPlanViewModel = sharedPlanViewModel,
            aiPlanContextualizer = LocalAiPlanContextualizer(gateway)
        )
        testDispatcher.scheduler.runCurrent()
        assertEquals(AiContextualizationState.InProgress, viewModel.uiState.value.aiState)

        // event1のAI推論完了前に別イベントを選択する。
        sharedPlanViewModel.selectEvent(event2)
        testDispatcher.scheduler.advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertEquals(
            "最終planはevent2のものであるべきです(T-P8-19)",
            event2.id,
            finalState.plan?.event?.id
        )
        assertEquals(AiContextualizationState.Applied, finalState.aiState)
        assertEquals(
            "旧イベント(event1)宛てのAI文言が新イベントのplanへ混入してはいけません(T-P8-19)",
            "新予定の文言",
            finalState.plan?.steps?.single { it.type == ExecutionStepType.PREPARATION }?.title
        )
    }

    // T-P8-22: キャンセル — 別イベント選択によりcollectLatestが旧イベントのAI推論coroutineを
    // 構造的にキャンセルする（CancellationExceptionは再送出でスコープ健全、§4.3）。
    @Test
    fun tP8_22_eventSwitch_cancelsInFlightAiInference_viaCollectLatestStructuredConcurrency() = runTest(testDispatcher) {
        val event1 = sampleEvent(startDate = Instant.parse("2026-08-10T10:00:00Z"), locationName = null)
        val event2 = sampleEvent(startDate = Instant.parse("2026-08-11T10:00:00Z"), locationName = null)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "X"))),
            delayMillisPerCall = 10_000L
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_22")
        val sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event1)

        createPlanReviewViewModel(
            sharedPlanViewModel = sharedPlanViewModel,
            aiPlanContextualizer = LocalAiPlanContextualizer(gateway)
        )
        testDispatcher.scheduler.runCurrent()
        assertEquals("切り替え前はまだキャンセルされていないはずです", 0, model.cancelledCount)

        sharedPlanViewModel.selectEvent(event2)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "別イベント選択でcollectLatestが旧イベントのAI推論coroutineを構造的にキャンセルする" +
                "はずです(T-P8-22、§4.3)",
            1,
            model.cancelledCount
        )
    }

    // T-P8-26: travel解決とAI解決の到達順序非依存 — (a)travelが先に解決→AIが後、
    // (b)AIが先に解決→travelが後、いずれも最終uiState（plan・aiState）が一致する。
    @Test
    fun tP8_26_travelAndAiResolutionOrderIndependence_finalUiStateMatchesRegardlessOfArrivalOrder() =
        runTest(testDispatcher) {
            val eventStart = Instant.parse("2026-08-10T10:00:00Z")
            val event = sampleEvent(startDate = eventStart, locationName = "Shibuya Office")
            val permissionGate = FakePermissionGate(granted = true)

            // シナリオ(a): travelが先に解決（短遅延）→AIが後（長遅延）
            val routingA = FakeRoutingService(
                result = RouteEstimate(duration = Duration.ofMinutes(25), mode = TransportMode.TRANSIT, computedAt = eventStart),
                delayMillis = 100L
            )
            val modelA = AiGatewayTestFixtures.FakeLocalLanguageModel(
                listOf(
                    AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                        AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "保険証を持って行く")
                    )
                ),
                delayMillisPerCall = 5_000L
            )
            val viewModelA = createPlanReviewViewModel(
                sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event),
                geocodingService = FakeGeocodingService(GeocodeResult.Success(defaultDestination)),
                locationService = FakeLocationService(
                    LocationResult.Success(coordinate = defaultOrigin, accuracyMeters = 10f, fixedAt = eventStart)
                ),
                routingService = routingA,
                permissionGate = permissionGate,
                aiPlanContextualizer = LocalAiPlanContextualizer(
                    AiGatewayTestFixtures.readyGateway(modelA, prefsFileName = "p8_26_a")
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()
            val finalA = viewModelA.uiState.value

            // シナリオ(b): AIが先に解決（短遅延）→travelが後（長遅延）
            val routingB = FakeRoutingService(
                result = RouteEstimate(duration = Duration.ofMinutes(25), mode = TransportMode.TRANSIT, computedAt = eventStart),
                delayMillis = 5_000L
            )
            val modelB = AiGatewayTestFixtures.FakeLocalLanguageModel(
                listOf(
                    AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                        AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "保険証を持って行く")
                    )
                ),
                delayMillisPerCall = 100L
            )
            val viewModelB = createPlanReviewViewModel(
                sharedPlanViewModel = sharedPlanViewModelWithSelectedEvent(event),
                geocodingService = FakeGeocodingService(GeocodeResult.Success(defaultDestination)),
                locationService = FakeLocationService(
                    LocationResult.Success(coordinate = defaultOrigin, accuracyMeters = 10f, fixedAt = eventStart)
                ),
                routingService = routingB,
                permissionGate = permissionGate,
                aiPlanContextualizer = LocalAiPlanContextualizer(
                    AiGatewayTestFixtures.readyGateway(modelB, prefsFileName = "p8_26_b")
                )
            )
            testDispatcher.scheduler.advanceUntilIdle()
            val finalB = viewModelB.uiState.value

            assertEquals("到達順序に関わらずplanは一致するはずです(T-P8-26)", finalA.plan, finalB.plan)
            assertEquals(AiContextualizationState.Applied, finalA.aiState)
            assertEquals(AiContextualizationState.Applied, finalB.aiState)
            val planA = finalA.plan
            assertNotNull(planA)
            assertTrue(
                "travel適用(TRAVELステップ)が両シナリオで反映されているはずです",
                planA!!.steps.any { it.type == ExecutionStepType.TRAVEL }
            )
            assertEquals(
                "保険証を持って行く",
                planA.steps.single { it.type == ExecutionStepType.PREPARATION }.title
            )
        }
}
