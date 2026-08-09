@file:OptIn(ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.features.departure.DepartureUiState
import com.actionstarter.features.departure.DepartureViewModel
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.services.location.GeocodeResult
import com.actionstarter.services.location.GeocodingService
import com.actionstarter.services.location.LocationResult
import com.actionstarter.services.location.LocationService
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-P4DEP-1〜5（計画書§8.2 F48・§7.5、`docs/plans/phase4-basic-engine.md`）。
 *
 * [DepartureViewModel]は`SharedPlanViewModel.confirmedPlan`の購読・[DepartureUiState]への
 * 計画時点マッピング（[applyPlanBaseline][com.actionstarter.features.departure.DepartureViewModel]、
 * P4-C5実装）を、確定済みPlanの受信直後に同期的に行う。T-P4DEP-1〜4は確定済みPlanの実値が
 * UiStateへ反映されることを検証する。
 *
 * **T-P4DEP-5に関する留意**: 「Planが未確定のままでもクラッシュせず初期DepartureUiState()の
 * まま留まる」という不変条件は回帰防止ガードとして機能する。
 *
 * **P3-C5追補（ui-implementer、`docs/plans/phase3-routing-location.md`§9.7・§6.2）**:
 * [DepartureViewModel]のコンストラクタはPhase 3で`LocationService`／`GeocodingService`／
 * `RoutingService`（＋任意の`PermissionGate`／`Clock`）を追加注入する形へ拡張された。
 * 本ファイルはPhase 4時点のマッピング検証にのみ関心があり、Phase 3のリアルタイム再計算
 * （`DepartureRoutingViewModelTest`が対象）を検証しないため、[newDepartureViewModel]で
 * 「位置権限拒否」の固定fakeを注入し、[DepartureViewModel]の再計算パイプラインが
 * 早期に手を引く（`estimatedArrival`/`arrivalBuffer`を計画時点マッピングのまま残す）
 * 従来どおりの挙動を再現する（構築箇所のみの機械的追随。アサーションは無変更）。
 *
 * [DepartureViewModel]は`viewModelScope.launch`（`Dispatchers.Main.immediate`）でPlanを
 * 購読する想定（P4-C5実装）のため、`kotlinx-coroutines-test`の`StandardTestDispatcher`を
 * `Dispatchers.setMain`でインストールする
 * （[com.actionstarter.features.EventSelectionViewModelPermissionTest]と同じ構成）。
 */
@RunWith(AndroidJUnit4::class)
class DepartureViewModelTest {

    /**
     * P3-C5追補: 位置権限拒否を常に返すfake（[DepartureViewModel]の再計算パイプラインが
     * `estimatedArrival`/`arrivalBuffer`を上書きしないことを保証し、Phase 4のマッピングのみを
     * 検証する本ファイルの対象を維持する）。
     */
    private val deniedLocationService = object : LocationService {
        override suspend fun currentLocation(timeout: Duration): LocationResult = LocationResult.PermissionDenied
    }

    /** P3-C5追補: geocodeは成功させ、位置権限拒否の分岐まで到達させる（現実的な経路の再現）。 */
    private val alwaysResolvableGeocodingService = object : GeocodingService {
        override suspend fun geocode(locationName: String, timeout: Duration): GeocodeResult =
            GeocodeResult.Success(Coordinate(lat = 35.0, lon = 139.0))
    }

    /**
     * P3-C5追補: [deniedLocationService]により位置権限拒否分岐で処理が止まるため、本fakeが
     * 呼ばれることはない想定。万一呼ばれた場合は「Phase 4静的マッピングのみを検証する」という
     * 本ファイルの前提が崩れたことを示すため、サイレントに偽値を返さず例外で失敗させる。
     */
    private val unreachedRoutingService = object : RoutingService {
        override suspend fun estimateRoute(
            origin: Coordinate,
            destination: Coordinate,
            mode: TransportMode,
            departureDate: Instant
        ): RouteEstimate = error(
            "RoutingService must not be called when location permission is denied " +
                "(Phase 4 static-mapping regression guard, DepartureViewModelTest)"
        )
    }

    /** P3-C5追補: 新コンストラクタへの構築箇所の機械的追随（計画書§9.7・§6.2）。 */
    private fun newDepartureViewModel(sharedPlanViewModel: SharedPlanViewModel): DepartureViewModel =
        DepartureViewModel(
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = deniedLocationService,
            geocodingService = alwaysResolvableGeocodingService,
            routingService = unreachedRoutingService
        )

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleEvent(startDate: Instant): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = startDate,
        locationName = "Shibuya",
        coordinates = null,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun departureStep(scheduledStart: Instant): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = "departure",
        type = ExecutionStepType.DEPARTURE,
        title = "",
        estimatedDuration = Duration.ZERO,
        priority = StepPriority.REQUIRED,
        skippable = false,
        scheduledStart = scheduledStart,
        completedAt = null
    )

    private fun travelStep(scheduledStart: Instant, duration: Duration): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = "travel",
        type = ExecutionStepType.TRAVEL,
        title = "",
        estimatedDuration = duration,
        priority = StepPriority.REQUIRED,
        skippable = false,
        scheduledStart = scheduledStart,
        completedAt = null
    )

    // T-P4DEP-1: 正常系 - 確定済みExecutionPlanのマッピング(eventStart/estimatedArrival/
    // arrivalBuffer)が§7.5の規則どおりにDepartureUiStateへ反映される
    @Test
    fun tP4Dep1_confirmedPlanWithTravel_mapsEventStartEstimatedArrivalAndArrivalBufferPerSection75() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val estimatedArrival = eventStart.minus(Duration.ofMinutes(20))
        val departureTime = estimatedArrival.minus(Duration.ofMinutes(30))
        val event = sampleEvent(eventStart)
        val plan = ExecutionPlan(
            event = event,
            steps = listOf(departureStep(departureTime), travelStep(departureTime, Duration.ofMinutes(30))),
            transitionStart = departureTime.minus(Duration.ofMinutes(20)),
            departureTime = departureTime,
            estimatedArrival = estimatedArrival,
            arrivalBuffer = Duration.ofMinutes(20)
        )
        val sharedPlanViewModel = SharedPlanViewModel()
        sharedPlanViewModel.confirmPlan(plan)

        val viewModel = newDepartureViewModel(sharedPlanViewModel)
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(eventStart, uiState.eventStart)
        assertEquals(estimatedArrival, uiState.estimatedArrival)
        assertEquals(Duration.between(estimatedArrival, eventStart), uiState.arrivalBuffer)
    }

    // T-P4DEP-2: 正常系 - 「実現余裕」(eventStart - estimatedArrival)が期待値と一致する(G-5)。
    // Planの「希望余裕」フィールド(arrivalBuffer=20分)とはあえて異なる実現余裕(8分)になるよう
    // 構成し、DepartureViewModelがplan.arrivalBufferをそのまま転記するのではなく
    // eventStart-estimatedArrivalから導出することを検証する。
    @Test
    fun tP4Dep2_realizedArrivalBuffer_derivedFromEventStartMinusEstimatedArrival_notFromPlanDesiredBuffer() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val estimatedArrival = eventStart.minus(Duration.ofMinutes(8)) // 実現余裕=8分
        val departureTime = estimatedArrival.minus(Duration.ofMinutes(15))
        val event = sampleEvent(eventStart)
        val plan = ExecutionPlan(
            event = event,
            steps = listOf(departureStep(departureTime), travelStep(departureTime, Duration.ofMinutes(15))),
            transitionStart = departureTime.minus(Duration.ofMinutes(20)),
            departureTime = departureTime,
            estimatedArrival = estimatedArrival,
            arrivalBuffer = Duration.ofMinutes(20) // 希望余裕=20分。実現余裕(8分)とは意図的に不一致
        )
        val sharedPlanViewModel = SharedPlanViewModel()
        sharedPlanViewModel.confirmPlan(plan)

        val viewModel = newDepartureViewModel(sharedPlanViewModel)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(Duration.ofMinutes(8), viewModel.uiState.value.arrivalBuffer)
    }

    // T-P4DEP-3: エッジケース - TRAVELステップが存在しないPlan(travelEstimateがnull/ZERO相当)
    // → estimatedArrival == null
    @Test
    fun tP4Dep3_planWithoutTravelStep_estimatedArrivalIsNull() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val departureTime = eventStart.minus(Duration.ofMinutes(10))
        val event = sampleEvent(eventStart)
        val plan = ExecutionPlan(
            event = event,
            steps = listOf(departureStep(departureTime)), // TRAVELなし
            transitionStart = departureTime.minus(Duration.ofMinutes(20)),
            departureTime = departureTime,
            estimatedArrival = departureTime, // ExecutionPlan契約上は非null(§7.4)だがTRAVELなしのためUI層はnull化する
            arrivalBuffer = Duration.ofMinutes(10)
        )
        val sharedPlanViewModel = SharedPlanViewModel()
        sharedPlanViewModel.confirmPlan(plan)

        val viewModel = newDepartureViewModel(sharedPlanViewModel)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.estimatedArrival)
    }

    // T-P4DEP-4: エッジケース - estimatedArrival == nullのときarrivalBufferもnullになる
    @Test
    fun tP4Dep4_estimatedArrivalNull_arrivalBufferIsAlsoNull() = runTest(testDispatcher) {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val departureTime = eventStart.minus(Duration.ofMinutes(10))
        val event = sampleEvent(eventStart)
        val plan = ExecutionPlan(
            event = event,
            steps = listOf(departureStep(departureTime)), // TRAVELなし
            transitionStart = departureTime.minus(Duration.ofMinutes(20)),
            departureTime = departureTime,
            estimatedArrival = departureTime,
            arrivalBuffer = Duration.ofMinutes(10)
        )
        val sharedPlanViewModel = SharedPlanViewModel()
        sharedPlanViewModel.confirmPlan(plan)

        val viewModel = newDepartureViewModel(sharedPlanViewModel)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.arrivalBuffer)
    }

    // T-P4DEP-5: エッジケース - Planが未確定(sharedPlanViewModelの確定済みPlanが未設定)の状態でも
    // クラッシュせず初期DepartureUiState()のまま留まる
    @Test
    fun tP4Dep5_noConfirmedPlan_staysAtInitialDepartureUiStateWithoutCrashing() = runTest(testDispatcher) {
        val sharedPlanViewModel = SharedPlanViewModel() // confirmedPlanは未設定(null)のまま

        val viewModel = newDepartureViewModel(sharedPlanViewModel)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DepartureUiState(), viewModel.uiState.value)
    }
}
