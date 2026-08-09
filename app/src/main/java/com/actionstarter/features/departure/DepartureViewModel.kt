package com.actionstarter.features.departure

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.services.location.GeocodeResult
import com.actionstarter.services.location.GeocodingService
import com.actionstarter.services.location.LocationResult
import com.actionstarter.services.location.LocationService
import com.actionstarter.services.permission.PermissionGate
import com.actionstarter.services.routing.RoutingException
import com.actionstarter.services.routing.RoutingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration

/**
 * 仕様§29準拠（DepartureScreenのViewModel）。
 *
 * P4-C5（統合ウィンドウ）で実装した[sharedPlanViewModel.confirmedPlan]の購読と計画時点値
 * （eventStart／estimatedArrival／arrivalBuffer）マッピングは、[applyPlanBaseline]として
 * 本サイクル（P3-C5、ui-implementer）でも不変のまま維持している（T-P4DEP-1〜5回帰ガード）。
 *
 * P3-C5で追加したのは、位置情報・geocoding・Routes APIを用いた**フォアグラウンド単発の
 * リアルタイム再計算**である（計画書§9.7 T-DEPVM-1〜9・F26/F27/F28）。再計算パイプライン
 * （[recalculate]）は次の順序で行う（計画書のコンストラクタ注記どおり）：
 * 1. `plan.event.locationName`をgeocode（[geocodingService]）。`null`／空白または
 *    [GeocodeResult.NoMatch]は異常ではなく「手動導線状態」（[DepartureUiState.isDestinationUnresolved]）
 *    へ写像し、以降のcurrentLocation／estimateRouteを呼ばない（T-DEPVM-5）。
 * 2. 現在地を取得（[locationService]、権限ガードは[LocationService]自身の実装が担う。
 *    [permissionGate]はAppContainer実配線時にFINE/COARSEいずれも権限が無いことが判明している
 *    場合の無駄な呼び出しを避ける防御的事前チェックとしてのみ使う＝既定`granted`実装では
 *    常に[locationService]の応答を権威とする、T-DEPVM-1〜9で検証）。
 * 3. 座標が揃えば[routingService.estimateRoute]を呼び、成功時はETA
 *    （`now + duration`）・[DepartureUiState.isEtaStale]（`computedAt`が10分超過で古い、
 *    計画書§8）を更新し、失敗時は[RoutingException]の8サブクラスを`else`節なしの網羅`when`
 *    （[RoutingException.toEtaFailureReason]）で[EtaFailureReason]へ写像する（T-DEPVM-9、
 *    S-4裁定）。
 *
 * **Phase 4回帰ガード（T-P4DEP-1〜5を壊さないこと）**: 位置権限未許可・geocode未解決・
 * 経路取得失敗のいずれの場合も、[recalculateRoute]・[recalculate]は[applyPlanBaseline]が
 * 設定した`estimatedArrival`／`arrivalBuffer`（Basic Engineの計画時点値）を上書きしない
 * （成功時のみ上書きする設計）。したがってサービスが一切使えない環境（例:
 * `AppContainer`のP3-C6以前の一時ブリッジ）では、従来のPhase 4マッピングがそのまま
 * 成立する。
 */
class DepartureViewModel(
    private val sharedPlanViewModel: SharedPlanViewModel,
    private val locationService: LocationService,
    private val geocodingService: GeocodingService,
    private val routingService: RoutingService,
    private val permissionGate: PermissionGate = AlwaysGrantedPermissionGate,
    private val clock: Clock = Clock.systemUTC()
) : ViewModel() {

    private val _uiState = MutableStateFlow(DepartureUiState())
    val uiState: StateFlow<DepartureUiState> = _uiState.asStateFlow()

    /**
     * 直近に確定された[ExecutionPlan]。[onTransportModeSelected]・[onResume]は
     * `confirmedPlan`のFlow購読とは独立して呼ばれるため、再計算に使うplanをキャッシュする
     * （T-DEPVM-8・T-PERM3-4）。
     */
    private var latestPlan: ExecutionPlan? = null

    init {
        viewModelScope.launch {
            sharedPlanViewModel.confirmedPlan.collect { plan ->
                latestPlan = plan
                if (plan != null) {
                    applyPlanBaseline(plan)
                    recalculate(plan, _uiState.value.transportMode)
                }
            }
        }
    }

    /**
     * P4-C5実装（計画書§7.5、F48、T-P4DEP-1〜5）。マッピング規則:
     * - [DepartureUiState.eventStart] = `plan.event.startDate`
     * - [DepartureUiState.estimatedArrival] = TRAVELステップが存在する場合のみ
     *   `plan.estimatedArrival`、存在しない場合は`null`（G-3）
     * - [DepartureUiState.arrivalBuffer] = 「実現余裕」（`eventStart − estimatedArrival`、G-5）。
     *   `plan.arrivalBuffer`（「希望余裕」）はそのまま転記しない。`estimatedArrival`が`null`の
     *   場合は`arrivalBuffer`も`null`（減算を実行しない。§9エラーマップ#15）
     */
    private fun applyPlanBaseline(plan: ExecutionPlan) {
        val hasTravelStep = plan.steps.any { it.type == ExecutionStepType.TRAVEL }
        val baselineEstimatedArrival = if (hasTravelStep) plan.estimatedArrival else null
        val baselineArrivalBuffer = baselineEstimatedArrival?.let { Duration.between(it, plan.event.startDate) }
        _uiState.value = _uiState.value.copy(
            eventStart = plan.event.startDate,
            estimatedArrival = baselineEstimatedArrival,
            arrivalBuffer = baselineArrivalBuffer,
            isStartNavigationEnabled = false
        )
    }

    /**
     * F26（計画書§9.7 T-DEPVM-8）: transportMode変更を1回だけ反映し、その変更を反映した
     * 再計算を1回だけ走らせる（多重発火しない）。
     */
    fun onTransportModeSelected(mode: TransportMode) {
        _uiState.value = _uiState.value.copy(transportMode = mode)
        val plan = latestPlan ?: return
        viewModelScope.launch {
            recalculate(plan, mode)
        }
    }

    /**
     * ON_RESUME相当の再チェック（計画書§9.7 T-PERM3-4、§95.4「Settingsから再許可すると
     * 自動連携に復帰する」）。`ActionStarterNavHost`側の`DisposableEffect`＋
     * `LifecycleEventObserver`（Phase 2のEventSelection route結線と同型、C6で結線）から
     * 呼ばれる想定の公開API。直近の確定Planに対して現在の`transportMode`で再計算する。
     */
    fun onResume() {
        val plan = latestPlan ?: return
        viewModelScope.launch {
            recalculate(plan, _uiState.value.transportMode)
        }
    }

    /** F28（S-2裁定）: 手動Travel Time入力値の反映のみを行う（[TravelTimeInput]が呼ぶ）。 */
    fun onManualTravelMinutesChanged(minutes: Int?) {
        _uiState.value = _uiState.value.copy(manualTravelMinutes = minutes)
    }

    /**
     * 再計算パイプライン本体（クラスKDoc参照）。`plan.event.locationName`のgeocodeから開始する。
     */
    private suspend fun recalculate(plan: ExecutionPlan, mode: TransportMode) {
        val locationName = plan.event.locationName
        if (locationName.isNullOrBlank()) {
            markDestinationUnresolved()
            return
        }

        when (val geocodeResult = geocodingService.geocode(locationName)) {
            is GeocodeResult.Success -> {
                _uiState.value = _uiState.value.copy(isDestinationUnresolved = false)
                recalculateRoute(destination = geocodeResult.coordinate, mode = mode)
            }

            is GeocodeResult.NoMatch -> {
                // 異常ではない（計画書§5.3）: 会議室名・URL等はretryせず手動導線へ誘導する。
                markDestinationUnresolved()
            }

            is GeocodeResult.Failure -> {
                // NoMatchと異なりgeocoderそのものが失敗した状態。RoutingExceptionの文脈では
                // ないためetaFailureReasonは変更せず、手動導線としてのみ表出させる
                // （偽ETAを生成しない。§9エラーマップ#12〜14）。
                markDestinationUnresolved()
            }
        }
    }

    private fun markDestinationUnresolved() {
        _uiState.value = _uiState.value.copy(
            isDestinationUnresolved = true,
            etaFailureReason = null,
            isEtaStale = false
        )
    }

    private suspend fun recalculateRoute(destination: Coordinate, mode: TransportMode) {
        val hasAnyLocationPermission = permissionGate.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            permissionGate.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasAnyLocationPermission) {
            _uiState.value = _uiState.value.copy(
                permissionState = LocationPermissionState.DENIED,
                etaFailureReason = null,
                isEtaStale = false
            )
            return
        }

        when (val locationResult = locationService.currentLocation()) {
            is LocationResult.PermissionDenied -> {
                _uiState.value = _uiState.value.copy(
                    permissionState = LocationPermissionState.DENIED,
                    etaFailureReason = null,
                    isEtaStale = false
                )
            }

            is LocationResult.Failure -> {
                // 権限自体はある（fix取得の失敗）。偽ETAを生成せず、計画時点値のフォールバックを
                // 維持する（§9エラーマップ#6〜9）。
                _uiState.value = _uiState.value.copy(
                    permissionState = LocationPermissionState.GRANTED,
                    etaFailureReason = null,
                    isEtaStale = false
                )
            }

            is LocationResult.Success -> {
                _uiState.value = _uiState.value.copy(
                    permissionState = LocationPermissionState.GRANTED,
                    locationAccuracyMeters = locationResult.accuracyMeters
                )
                estimateAndApplyRoute(origin = locationResult.coordinate, destination = destination, mode = mode)
            }
        }
    }

    private suspend fun estimateAndApplyRoute(origin: Coordinate, destination: Coordinate, mode: TransportMode) {
        val now = clock.instant()
        try {
            val estimate = routingService.estimateRoute(
                origin = origin,
                destination = destination,
                mode = mode,
                departureDate = now
            )
            val estimatedArrival = now.plus(estimate.duration)
            val eventStart = _uiState.value.eventStart
            _uiState.value = _uiState.value.copy(
                estimatedArrival = estimatedArrival,
                arrivalBuffer = eventStart?.let { Duration.between(estimatedArrival, it) },
                etaFailureReason = null,
                isEtaStale = Duration.between(estimate.computedAt, now) >= STALE_THRESHOLD
            )
        } catch (routingException: RoutingException) {
            // S-4裁定: RoutingServiceのシグネチャは変えず、失敗はsealな例外で表現する。
            // ここでは偽ETAを返さない（estimatedArrival/arrivalBufferをnullへ倒す）。
            _uiState.value = _uiState.value.copy(
                estimatedArrival = null,
                arrivalBuffer = null,
                etaFailureReason = routingException.toEtaFailureReason(),
                isEtaStale = false
            )
        }
    }

    /**
     * T-DEPVM-9: [RoutingException]の8サブクラス全てを`else`節なしの網羅`when`で
     * [EtaFailureReason]へ写像する（サイレント障害防止、S-4裁定）。式本体のため、
     * サブクラスの追加・削除があればコンパイルエラーで検出できる。
     */
    private fun RoutingException.toEtaFailureReason(): EtaFailureReason = when (this) {
        is RoutingException.NotConfigured -> EtaFailureReason.NOT_CONFIGURED
        is RoutingException.Offline -> EtaFailureReason.OFFLINE
        is RoutingException.Timeout -> EtaFailureReason.TIMEOUT
        is RoutingException.Unauthorized -> EtaFailureReason.UNAUTHORIZED
        is RoutingException.QuotaExceeded -> EtaFailureReason.QUOTA_EXCEEDED
        is RoutingException.ServerError -> EtaFailureReason.SERVER_ERROR
        is RoutingException.NoRoute -> EtaFailureReason.NO_ROUTE
        is RoutingException.MalformedResponse -> EtaFailureReason.MALFORMED_RESPONSE
    }

    private companion object {
        /** キャッシュ由来ETAの陳腐化閾値（計画書§8「10分」、T-DEPVM-4）。 */
        val STALE_THRESHOLD: Duration = Duration.ofMinutes(10)
    }
}

/**
 * [DepartureViewModel]の[PermissionGate]既定値（引数省略時）。[PermissionGate]は
 * `fun interface`ではないためSAM変換できず、オブジェクト式で提供する。常に許可扱いとし、
 * 実際の権限判定は[LocationService]自身の実装（[com.actionstarter.services.location.FusedLocationService]）
 * に委ねる（クラスKDoc参照）。
 */
private object AlwaysGrantedPermissionGate : PermissionGate {
    override fun isGranted(permission: String): Boolean = true
}
