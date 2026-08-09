package com.actionstarter.features

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.features.departure.CoarsePrecisionNotice
import com.actionstarter.features.departure.DepartureScreen
import com.actionstarter.features.departure.DepartureUiState
import com.actionstarter.features.departure.DepartureViewModel
import com.actionstarter.features.departure.LocationPermissionRationaleCard
import com.actionstarter.features.departure.LocationPermissionState
import com.actionstarter.features.departure.TRAVEL_TIME_INPUT_TEST_TAG
import com.actionstarter.features.departure.TransportModeSelector
import com.actionstarter.features.departure.TravelTimeInput
import com.actionstarter.features.departure.transportModeSelectorTestTag
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.services.location.GeocodeResult
import com.actionstarter.services.location.GeocodingService
import com.actionstarter.services.location.LocationResult
import com.actionstarter.services.location.LocationService
import com.actionstarter.services.routing.RoutingService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * T-DEP2-1〜6・T-PERM3-1〜5（計画書§9.7 `docs/plans/phase3-routing-location.md`、
 * F26/F27/F28、S-1裁定）。Phase 3 C2後半（test-writer, 2026-08-09）が新規作成。
 * 既存の[DepartureScreenTest]（Phase 1のT-DEP-1〜4）とは分離する。
 *
 * ## T-DEP2系（対象: [DepartureScreen]／[TravelTimeInput]／[TransportModeSelector]）
 * これらは拡張済みの[DepartureUiState]（`transportMode`/`isEtaStale`/`etaFailureReason`/
 * `permissionState`/`manualTravelMinutes`）を実際に[DepartureScreen]へ渡して描画検証する、
 * またはT-DEP2-3/4の「対象」列が指定するとおり[TravelTimeInput]/[TransportModeSelector]を
 * 直接検証する（いずれも実プロダクションコード、fakeやスタブを介さない）。
 * [DepartureScreen]は現時点で`estimatedArrival`/`eventStart`/`arrivalBuffer`/
 * `isStartNavigationEnabled`のみを参照し、新規5フィールドを一切参照しないため、
 * T-DEP2-2はRedになるのが正しい。[TravelTimeInput]/[TransportModeSelector]は空実装
 * （契約scaffold）のため、T-DEP2-3/4もRedになるのが正しい。T-DEP2-1/6は既存3要素
 * （ETA/Event/Buffer）の回帰ガードであり、拡張UiStateを渡しても壊れないことを確認する
 * 目的のため、現時点でGreenの見込みが高い（[DepartureViewModelTest]のT-P4DEP-5と同種の
 * 性質。回帰防止ガードとして機能する）。T-DEP2-5は現行strings.xml（P3-C1で追加済み＋
 * 本サイクルで追加した`location_permission_coarse_only_notice`）を直接検証するため、
 * これもGreenの見込みが高い。
 *
 * ## T-PERM3系（コンストラクタ／シグネチャの不一致・重要）
 * [DepartureScreen]は2026-08-09時点で`uiState`単一引数のみを持ち、画面遷移・権限要求ラムダを
 * 一切持たない（`DepartureScreen.kt`のKDoc「画面遷移ラムダを持たない」参照）。位置権限launcher・
 * ON_RESUME再チェックの結線は`ActionStarterNavHost`側がP3-C6で行う予定であり
 * （計画書§6.4#5）、現時点ではDeparture route自体がまだ結線されていない
 * （`ActionStarterNavHost.kt`のDeparture composableは`DepartureScreen(uiState = uiState)`の
 * みで権限launcherを持たない）。
 *
 * 「本番Kotlin変更禁止」の制約下にあるため、[DepartureScreen]へラムダ引数を追加すること
 * はできない。T-PERM3-3（拒否時の描画）は新規ラムダを必要としないため実[DepartureScreen]を
 * 対象にできるが、T-PERM3-2（ボタンタップ→ラムダ1回）・T-PERM3-5（COARSE精度注記）は
 * ラムダ／未定義の状態フィールドを必要とするため、本ファイル内のローカルstub Composable
 * （[DepartureLocationPermissionRationaleCardStub]・[DepartureCoarsePrecisionNoticeStub]、
 * いずれも[TravelTimeInput]/[TransportModeSelector]自身の契約scaffold規約＝意図的な空実装を
 * 踏襲）を対象にする。T-PERM3-4（ON_RESUME復帰）は[DepartureViewModel]側の再チェックAPI
 * および`ActionStarterNavHost`のライフサイクル結線の両方が未実装のため、
 * [DepartureRoutingViewModelTest]と同じ「Redフェーズ・ヘルパー（`TODO()`）」規約を
 * このファイル内に独立して定義する（fakeの重複はEventSelectionViewModelPermissionTest等、
 * 本コードベースの既存規約＝テストファイルごとに閉じたfakeを持つ、に従う）。
 */
@RunWith(AndroidJUnit4::class)
class DepartureRoutingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-08T00:00:00Z")

    // T-DEP2-1: 正常系 - ETA・Event start・Bufferが表示される（§35 Screen4の3要素）。拡張された
    // DepartureUiState（transportMode等の新規フィールドを含む）を渡しても、既存3要素の表示が
    // 壊れないことを確認する回帰ガード（T-DEP-1と同種だが拡張UiStateでの再検証、独立ファイル）。
    @Test
    fun tDep2_1_extendedUiState_stillDisplaysEtaEventAndBufferLabels() {
        composeTestRule.setContent {
            DepartureScreen(
                uiState = DepartureUiState(
                    estimatedArrival = fixedNow.plus(52, ChronoUnit.MINUTES),
                    eventStart = fixedNow.plus(60, ChronoUnit.MINUTES),
                    arrivalBuffer = Duration.ofMinutes(8),
                    isStartNavigationEnabled = false,
                    transportMode = TransportMode.TRANSIT,
                    isEtaStale = false,
                    etaFailureReason = null,
                    permissionState = LocationPermissionState.GRANTED,
                    manualTravelMinutes = null
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.departure_estimated_arrival_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_event_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_buffer_label)).assertIsDisplayed()
    }

    // T-DEP2-2: エッジケース - stale ETA時に「精度が低下している」注記が表示される（§95.6）。
    // DepartureScreen本文は現時点でisEtaStaleを一切参照しないため、Redになるのが正しい。
    @Test
    fun tDep2_2_staleEta_showsAccuracyReducedNotice() {
        composeTestRule.setContent {
            DepartureScreen(
                uiState = DepartureUiState(
                    estimatedArrival = fixedNow.plus(52, ChronoUnit.MINUTES),
                    eventStart = fixedNow.plus(60, ChronoUnit.MINUTES),
                    arrivalBuffer = Duration.ofMinutes(8),
                    isEtaStale = true
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.departure_eta_stale_notice)).assertIsDisplayed()
    }

    // T-DEP2-3: 異常系 - 手動Travel Time入力 → ETAが再表示される（対象: TravelTimeInput）。
    // TravelTimeInput自体はState Hoisting規約によりETA表示を持たない（TravelTimeInput.ktの
    // KDoc参照）ため、本ケースのスコープは「入力操作でonMinutesChangeが正しい値で呼ばれる
    // こと」に限定する（ETAの再表示自体はDepartureViewModel/DepartureScreen側の責務であり
    // P3-C5で結線される）。TravelTimeInputは現時点で空実装のため、testTag
    // "travel_time_input_field" のノードが見つからずRedになるのが正しい。
    @Test
    fun tDep2_3_manualMinutesEntered_notifiesOnMinutesChangeWithParsedValue() {
        var lastChangedMinutes: Int? = -1
        composeTestRule.setContent {
            TravelTimeInput(
                minutes = null,
                onMinutesChange = { lastChangedMinutes = it }
            )
        }

        composeTestRule.onNodeWithTag(TRAVEL_TIME_INPUT_TEST_TAG).performTextInput("25")

        assertEquals(25, lastChangedMinutes)
    }

    // T-DEP2-4: 正常系 - TransportMode 4択が表示され、選択でラムダが1回呼ばれる
    // （対象: TransportModeSelector）。TransportModeSelectorは現時点で空実装のため、
    // 4値それぞれのtestTagノードが見つからずRedになるのが正しい。
    @Test
    fun tDep2_4_allFourTransportModes_displayedAndSelectionInvokesCallbackOnce() {
        var selectionCallCount = 0
        var lastSelected: TransportMode? = null
        composeTestRule.setContent {
            TransportModeSelector(
                selectedMode = TransportMode.TRANSIT,
                onModeSelected = {
                    selectionCallCount++
                    lastSelected = it
                }
            )
        }

        TransportMode.entries.forEach { mode ->
            composeTestRule.onNodeWithTag(transportModeSelectorTestTag(mode)).assertIsDisplayed()
        }
        composeTestRule.onNodeWithTag(transportModeSelectorTestTag(TransportMode.WALKING)).performClick()

        assertEquals(1, selectionCallCount)
        assertEquals(TransportMode.WALKING, lastSelected)
    }

    // P11-C1（T-P11S-5、TEAMS§2承認済み・Fable 5指示書が承認記録）: phase11-i18n-a11y.md§7.4の
    // 死蔵リソース処置によりtravel_time_manual_apply_buttonが削除されたため、本リストから除外した
    // （location_permission_denied_messageは配線対象のため存置。他12キーは無変更）。
    // T-DEP2-5: エッジケース - ja/en双方で新規文言が非空かつ相互に異なる（§7）。
    // StringResourceParityTest（全キー横断）とは別に、Phase 3で追加された文言キーへ
    // 絞ったピンポイント検証として用意する。RobolectricのRuntimeEnvironment.setQualifiers
    // でロケールを動的切替する（instrumented E2Eと異なりJVM/Robolectricでは1テスト内切替が
    // 可能）。
    @Test
    fun tDep2_5_phase3StringKeys_nonEmptyAndDistinctBetweenEnAndJa() {
        val phase3Keys = listOf(
            "location_permission_rationale_title",
            "location_permission_rationale_message",
            "location_permission_grant_button",
            "location_permission_denied_message",
            "location_open_settings_button",
            "location_permission_coarse_only_notice",
            "travel_time_manual_label",
            "transport_mode_walking",
            "transport_mode_driving",
            "transport_mode_transit",
            "transport_mode_cycling",
            "departure_eta_stale_notice",
            "departure_geocode_no_match_message"
        )

        val enContext = RuntimeEnvironment.getApplication()
        val enValues = phase3Keys.associateWith { key ->
            val resId = enContext.resources.getIdentifier(key, "string", enContext.packageName)
            assertTrue("string resource '$key' not found (en)", resId != 0)
            enContext.getString(resId)
        }

        RuntimeEnvironment.setQualifiers("+ja")
        val jaContext = RuntimeEnvironment.getApplication()
        val jaValues = phase3Keys.associateWith { key ->
            val resId = jaContext.resources.getIdentifier(key, "string", jaContext.packageName)
            assertTrue("string resource '$key' not found (ja)", resId != 0)
            jaContext.getString(resId)
        }

        for (key in phase3Keys) {
            val en = enValues.getValue(key)
            val ja = jaValues.getValue(key)
            assertTrue("en value for '$key' must not be empty", en.isNotEmpty())
            assertTrue("ja value for '$key' must not be empty", ja.isNotEmpty())
            assertNotEquals("en/ja values for '$key' must differ (real translation, not a copy)", en, ja)
        }
    }

    // T-DEP2-6: エッジケース - 遅刻警告が色のみでなくテキストでも伝達される（§63 color-only
    // 情報禁止）。拡張UiState（新規フィールドを含む）を渡した状態でも、既存のテキスト警告
    // （departure_buffer_negative_warning）が表示されることを確認する回帰ガード。
    @Test
    fun tDep2_6_negativeBufferWithExtendedUiState_conveysWarningAsTextNotColorOnly() {
        composeTestRule.setContent {
            DepartureScreen(
                uiState = DepartureUiState(
                    estimatedArrival = fixedNow.plus(65, ChronoUnit.MINUTES),
                    eventStart = fixedNow.plus(60, ChronoUnit.MINUTES),
                    arrivalBuffer = Duration.ofMinutes(-5),
                    transportMode = TransportMode.DRIVING,
                    permissionState = LocationPermissionState.GRANTED
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.departure_buffer_negative_warning))
            .assertIsDisplayed()
    }

    // T-PERM3-1: 正常系 - 未許可の初回表示でsystem dialogを自動起動しない（launcher呼び出し
    // 0回・§95.4）。実際のsystem dialog起動はActionStarterNavHostが保持する
    // ActivityResultLauncher（P3-C6で結線）の責務であり、DepartureScreenはlauncherを直接
    // 持たない（疎結合規約、計画書§7.3）ため「呼び出し0回」は構造的に自明であり
    // （検証対象は宣言的に自明なため本テストでは直接アサーションしない）、本ケースの
    // 実質的な検証対象はNOT_REQUESTED状態で事前説明カード（タップ待ち）が表示されることに
    // ある。DepartureScreenは現時点でpermissionStateを一切参照しないため、Redになるのが
    // 正しい。
    @Test
    fun tPerm3_1_notRequestedState_showsRationaleCardBeforeAnyPermissionRequest() {
        composeTestRule.setContent {
            DepartureScreen(uiState = DepartureUiState(permissionState = LocationPermissionState.NOT_REQUESTED))
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.location_permission_rationale_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.location_permission_rationale_message))
            .assertIsDisplayed()
    }

    // T-PERM3-2: 正常系 - 事前説明カードのボタンタップで権限要求ラムダが1回だけ呼ばれる。
    // P3-C5（Green）実装済み: 本番コンポーネント化されたLocationPermissionRationaleCard
    // （DepartureScreen.kt）を対象にする（ローカルstub DepartureLocationPermissionRationaleCardStub
    // から差し替え。testTagは実コンポーネントも同じ値"departure_location_permission_grant_button"
    // を使う）。
    @Test
    fun tPerm3_2_rationaleCardGrantButtonTap_invokesRequestPermissionLambdaExactlyOnce() {
        var requestCallCount = 0
        composeTestRule.setContent {
            LocationPermissionRationaleCard(
                onRequestLocationPermission = { requestCallCount++ }
            )
        }

        composeTestRule.onNodeWithTag(LOCATION_PERMISSION_CARD_GRANT_BUTTON_TEST_TAG).performClick()

        assertEquals(1, requestCallCount)
    }

    // T-PERM3-3: 異常系 - 拒否 → 手動Travel Time入力＋Settings導線の両方が表示される。
    @Test
    fun tPerm3_3_deniedState_showsManualTravelTimeLabelAndSettingsButton() {
        composeTestRule.setContent {
            DepartureScreen(uiState = DepartureUiState(permissionState = LocationPermissionState.DENIED))
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.travel_time_manual_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.location_open_settings_button))
            .assertIsDisplayed()
    }

    // T-P11S-3（Phase 11, F83, 計画書§7.4）: 正常系 - location_permission_denied_messageが
    // DENIED状態で実際に描画される（showManualFallbackブロック内、TravelTimeInput直前）。
    // 従来はDENIED状態の説明として定義済みだが描画箇所を持たない死蔵リソース（UnusedResources
    // 警告対象）だった。文言自体・位置はS-2裁定どおり据え置き（既存文言をそのまま使用）。
    @Test
    fun tP11s3_deniedState_rendersLocationPermissionDeniedMessage() {
        composeTestRule.setContent {
            DepartureScreen(uiState = DepartureUiState(permissionState = LocationPermissionState.DENIED))
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.location_permission_denied_message))
            .assertIsDisplayed()
    }

    // T-PERM3-4: エッジケース - 拒否後ON_RESUMEで許可へ変化 → 自動で再計算しETA表示へ復帰する
    // （§95.4「Settingsから再許可すると自動連携に復帰する」。Phase 2のT-PERM-5と同型
    // シナリオ）。DepartureViewModelには現時点でON_RESUME相当の再チェックAPIが存在せず、
    // ActionStarterNavHostのDeparture routeにもDisposableEffect/LifecycleEventObserverが
    // 未結線（ActionStarterNavHost.kt参照）のため、DepartureRoutingViewModelTestと同じ
    // Redフェーズ・ヘルパー規約（`TODO()`）をこのファイル内に独立して定義する。
    @Test
    fun tPerm3_4_deniedThenGrantedOnResume_autoRecalculatesAndRestoresEta() = runTest {
        val eventStart = Instant.parse("2026-08-10T10:00:00Z")
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "Studio call",
            notes = null,
            startDate = eventStart,
            locationName = "Shibuya Office",
            coordinates = null,
            sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
        )
        val plan = ExecutionPlan(
            event = event,
            steps = emptyList(),
            transitionStart = eventStart.minus(Duration.ofMinutes(30)),
            departureTime = eventStart.minus(Duration.ofMinutes(10)),
            estimatedArrival = eventStart.minus(Duration.ofMinutes(5)),
            arrivalBuffer = Duration.ofMinutes(5)
        )
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(plan) }

        val locationService = Perm34FakeLocationService(LocationResult.PermissionDenied)
        val geocodingService = object : GeocodingService {
            override suspend fun geocode(locationName: String, timeout: Duration): GeocodeResult =
                GeocodeResult.Success(Coordinate(lat = 35.0, lon = 139.0))
        }
        val routingService = object : RoutingService {
            override suspend fun estimateRoute(
                origin: Coordinate,
                destination: Coordinate,
                mode: TransportMode,
                departureDate: Instant
            ): RouteEstimate = RouteEstimate(duration = Duration.ofMinutes(20), mode = mode, computedAt = departureDate)
        }

        val viewModel: DepartureViewModel =
            perm34CreateDepartureViewModel(sharedPlanViewModel, locationService, geocodingService, routingService)
        assertEquals(LocationPermissionState.DENIED, viewModel.uiState.value.permissionState)
        // Fable 5承認2026-08-09: T-DEPVM-8の差分規約に統一・init自動呼出の考慮漏れ修正。
        // DepartureViewModel.initがconfirmedPlan購読開始時に自動で1回recalculateする
        // （callCountはこの時点で既に1）ため、絶対値ではなくonResume呼び出し前後の差分で
        // 「ON_RESUMEが再照会をちょうど1回追加する」ことを検証する
        // （DepartureRoutingViewModelTest.tDepVm8と同型の規約）。
        val callCountAfterInitialLoad = locationService.callCount

        locationService.result = LocationResult.Success(
            coordinate = Coordinate(lat = 35.6586, lon = 139.7454),
            accuracyMeters = 10f,
            fixedAt = eventStart.minus(Duration.ofMinutes(30))
        )
        viewModel.onResumeForTest()

        assertEquals(LocationPermissionState.GRANTED, viewModel.uiState.value.permissionState)
        assertEquals(
            "ON_RESUME re-check must query the (now-granted) LocationService again",
            callCountAfterInitialLoad + 1,
            locationService.callCount
        )
    }

    // T-PERM3-5: エッジケース - COARSEのみ許可（precise拒否）時の挙動が定義どおり（S-1裁定に
    // 依存）。P3-C5（Green）実装済み: 本番コンポーネント化されたCoarsePrecisionNotice
    // （DepartureScreen.kt、閾値100m超で表示）を対象にする（ローカルstub
    // DepartureCoarsePrecisionNoticeStubから差し替え）。
    @Test
    fun tPerm3_5_coarseAccuracyLocation_showsReducedAccuracyNotice() {
        composeTestRule.setContent {
            // 1500mはCOARSE相当の粗い精度の目安値（実装の表示閾値は100m超、DepartureScreen.kt参照）。
            CoarsePrecisionNotice(accuracyMeters = 1500f)
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.location_permission_coarse_only_notice))
            .assertIsDisplayed()
    }

    /**
     * T-PERM3-4専用の最小fake（[com.actionstarter.features.DepartureRoutingViewModelTest]の
     * fakeとは独立。本ファイル内でのみ使用する。[EventSelectionViewModelPermissionTest]の
     * `FakePermissionGate`等と同じ「テストファイルごとに閉じたfakeを持つ」既存規約に従う）。
     */
    private class Perm34FakeLocationService(var result: LocationResult) : LocationService {
        var callCount = 0
            private set

        override suspend fun currentLocation(timeout: Duration): LocationResult {
            callCount++
            return result
        }
    }

    /**
     * P3-C5実装済み: [DepartureRoutingViewModelTest.createDepartureViewModel]と同じく
     * [DepartureViewModel]の実コンストラクタ（計画書§9.7）へ委譲する（clock省略時は
     * 既定の`Clock.systemUTC()`）。
     */
    private fun perm34CreateDepartureViewModel(
        sharedPlanViewModel: SharedPlanViewModel,
        locationService: LocationService,
        geocodingService: GeocodingService,
        routingService: RoutingService
    ): DepartureViewModel = DepartureViewModel(
        sharedPlanViewModel = sharedPlanViewModel,
        locationService = locationService,
        geocodingService = geocodingService,
        routingService = routingService
    )

    /**
     * T-PERM3-4用ヘルパー。P3-C5で追加された[DepartureViewModel.onResume]
     * （ON_RESUME相当の再チェックAPI）へ委譲する。`ActionStarterNavHost`側の
     * `DisposableEffect`/`LifecycleEventObserver`結線自体はC6の対象（完了報告
     * 「NavHost結線の要否判定」参照）。
     */
    private fun DepartureViewModel.onResumeForTest() {
        onResume()
    }
}

private const val LOCATION_PERMISSION_CARD_GRANT_BUTTON_TEST_TAG = "departure_location_permission_grant_button"
