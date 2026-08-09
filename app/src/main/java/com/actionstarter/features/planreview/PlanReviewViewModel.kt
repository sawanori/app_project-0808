package com.actionstarter.features.planreview

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.planning.PlanningEngine
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 仕様§26準拠（PlanReviewScreenのViewModel）。
 *
 * C5（統合サイクル）で[planningEngine]によるPlan生成ロジックを実装した。
 * [sharedPlanViewModel.selectedEvent]（activity-scoped、計画書§10.1）を購読し、
 * イベントが選択されるたびに[PlanningEngine.createPlan]（決定的計算、Mock実装は
 * LLM不使用・仕様§13/§15）を呼び出してPlanを生成する。
 *
 * [arrivalBuffer]はPhase 1のMock限定の既定値であり、仕様上の規定値ではない（Personal
 * Execution Profileに置き換わるのはPhase 10）。
 *
 * 「画面表示だけでは自動的にexecutionへ遷移しない」（T-PLAN-2、仕様§26）という制約は
 * 本ViewModelでは状態を更新するのみで満たされる：実際のNavigation呼び出しは
 * [confirmAndStart]を呼んだ`ActionStarterNavHost`側で行う（本ViewModelはNavController
 * を保持しない）。
 *
 * **P4-C8実装（仕様§13の式の完全実装、`docs/plans/phase4-basic-engine.md`P4-C8行）**:
 * F44（`travelEstimate == null`でもTRAVELステップなしでPlanが成立するフォールバック、
 * `planning/BasicPlanningEngine.kt`実装済み）自体は正しいが、それを取得しにいく主経路が
 * P4-C6完了時点まで一切配線されておらず、`travelEstimate`が常に`null`固定という統合漏れが
 * あった。本サイクルで[com.actionstarter.features.departure.DepartureViewModel]（P3-C5実装）と
 * 同型の「geocode→currentLocation→estimateRoute」パイプラインをPlan構築側にも配線する。
 *
 * [geocodingService]／[locationService]／[routingService]／[permissionGate]はいずれも既定値
 * `null`で追加した（[com.actionstarter.features.execution.ExecutionViewModel]のP5-C2b契約変更
 * ・ADR-0028と同型の後方互換パターン：新引数はすべてデフォルト値付きで追加し、既存の2引数
 * 構築（`AppContainerTest`等が使う可能性のある`PlanReviewViewModel(planningEngine,
 * sharedPlanViewModel)`という旧呼び出し形状）を壊さない。T-P4C8-5で回帰ロック）。
 *
 * **取得パイプライン（[fetchTravelEstimate]）**: [com.actionstarter.features.departure.
 * DepartureViewModel.recalculate]と同じ順序・同じ「異常は例外的ではなく多数派の正常系」という
 * 思想を踏襲する。
 * 1. `event.locationName`が空/nullなら何も呼ばず`null`（手がかりが無い。T-P4C8-4）。
 * 2. 4引数のいずれかが`null`（未配線環境。既存2引数構築など）なら何も呼ばず`null`。
 * 3. [permissionGate]が非nullかつ位置権限（FINE/COARSE）を両方とも持たないと分かっていれば、
 *    geocode／location／routingのいずれも呼ばず`null`（[DepartureViewModel]のP3-C8fixと同じ、
 *    無駄な非同期呼び出しを避ける事前チェック。T-P4C8-3）。[permissionGate]が`null`の場合は
 *    事前チェックをスキップする（[DepartureViewModel]の既定`AlwaysGrantedPermissionGate`と
 *    同じ思想：実権限の権威は[locationService]自身の応答とする）。
 * 4. geocode→currentLocation→estimateRouteの順で呼び、いずれかの段で非成功
 *    （[GeocodeResult.NoMatch]／[GeocodeResult.Failure]、[LocationResult.PermissionDenied]／
 *    [LocationResult.Failure]、[RoutingException]の任意のサブクラス）ならその時点で`null`へ
 *    フォールバックする。[RoutingException]はsealed基底型で一括catchするため、将来サブクラスが
 *    増減してもこの一括フォールバック自体は構造的に維持される（F44・エラー＆レスキュー
 *    マップ#1と同じ「捏造しない」原則。T-P4C8-2で回帰ロック）。
 *
 * **非同期化（[init]）**: イベント選択時、まず`travelEstimate=null`でPlanを即座に構築・表示し
 * （Phase 4までの挙動そのまま。ユーザーを待たせない）、その後[fetchTravelEstimate]を同じ
 * suspendチェーン内で実行し、成功したときのみPlanを再構築して差し替える
 * （[DepartureViewModel.recalculate]の`applyPlanBaseline`即時表示→suspendする再計算という
 * パターンと同型）。差し替えにより`StartOfTransition`は移動時間ぶん早まる方向にのみ変化する
 * （§13式のTravelTime項が0からtravelEstimateへ変わるため。T-P4C8-1）。差し替え直前に
 * `sharedPlanViewModel.selectedEvent`が依然として同一イベント（`event.id`一致）を指しているかを
 * 確認し、フェッチ中に別イベントが選択されていた場合は古い結果で新しいPlanを上書きしない
 * （stale-write防止の防御的チェック）。
 *
 * **UI文言**: 新規文言は追加しない。移動時間が結局取得できなかった場合の表示は既存のG-9共通
 * 文言のまま（`step_title_travel`のステップが生成されないだけで、他は不変）。
 *
 * @param geocodingService 目的地文字列のgeocoding（F23）。`null`の間はfetchを行わない。
 * @param locationService 現在地取得（F22）。`null`の間はfetchを行わない。
 * @param routingService 経路時間見積り（F24/F25）。`null`の間はfetchを行わない。
 * @param permissionGate 位置権限の事前チェック（無駄な非同期呼び出しの回避用）。`null`の間は
 *   事前チェックをスキップし[locationService]の応答を権威とする。
 */
class PlanReviewViewModel(
    private val planningEngine: PlanningEngine,
    private val sharedPlanViewModel: SharedPlanViewModel,
    private val geocodingService: GeocodingService? = null,
    private val locationService: LocationService? = null,
    private val routingService: RoutingService? = null,
    private val permissionGate: PermissionGate? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanReviewUiState())
    val uiState: StateFlow<PlanReviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sharedPlanViewModel.selectedEvent.collect { event ->
                if (event != null) {
                    applyPlan(event, travelEstimate = null)

                    val travelEstimate = fetchTravelEstimate(event)
                    if (travelEstimate != null && sharedPlanViewModel.selectedEvent.value?.id == event.id) {
                        applyPlan(event, travelEstimate = travelEstimate)
                    }
                }
            }
        }
    }

    /**
     * 「Start」タップ相当の操作（T-PLAN-3）。生成済みのPlanを
     * [SharedPlanViewModel.confirmPlan]で確定する。Planが未生成（`null`）の場合は
     * 何もしない（画面側のStartボタンはPlan生成後にのみ有効な文脈で呼ばれる想定）。
     */
    fun confirmAndStart() {
        val plan = _uiState.value.plan ?: return
        sharedPlanViewModel.confirmPlan(plan)
    }

    private suspend fun applyPlan(event: ExecutionEvent, travelEstimate: Duration?) {
        val plan = planningEngine.createPlan(buildPlanningContext(event, travelEstimate))
        val isBehindSchedule = plan.transitionStart.isBefore(Instant.now())
        _uiState.value = PlanReviewUiState(
            plan = plan,
            isBehindSchedule = isBehindSchedule,
            isEditEnabled = false
        )
    }

    /** クラスKDoc「取得パイプライン」参照。 */
    private suspend fun fetchTravelEstimate(event: ExecutionEvent): Duration? {
        val locationName = event.locationName
        if (locationName.isNullOrBlank()) return null

        val geocoding = geocodingService ?: return null
        val location = locationService ?: return null
        val routing = routingService ?: return null
        if (!hasLocationPermission()) return null

        val destination = when (val geocodeResult = geocoding.geocode(locationName)) {
            is GeocodeResult.Success -> geocodeResult.coordinate
            is GeocodeResult.NoMatch, is GeocodeResult.Failure -> return null
        }

        val origin = when (val locationResult = location.currentLocation()) {
            is LocationResult.Success -> locationResult.coordinate
            is LocationResult.PermissionDenied, is LocationResult.Failure -> return null
        }

        return try {
            routing.estimateRoute(
                origin = origin,
                destination = destination,
                mode = DEFAULT_TRANSPORT_MODE,
                departureDate = Instant.now()
            ).duration
        } catch (routingException: RoutingException) {
            null
        }
    }

    /** [DepartureViewModel]のP3-C8fix同型の同期事前チェック（クラスKDoc参照）。 */
    private fun hasLocationPermission(): Boolean {
        val gate = permissionGate ?: return true
        return gate.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            gate.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun buildPlanningContext(event: ExecutionEvent, travelEstimate: Duration?): PlanningContext = PlanningContext(
        event = event,
        now = Instant.now(),
        zoneId = ZoneId.systemDefault(),
        locale = Locale.getDefault(),
        transportMode = DEFAULT_TRANSPORT_MODE,
        travelEstimate = travelEstimate,
        arrivalBuffer = DEFAULT_ARRIVAL_BUFFER,
        profile = null
    )

    private companion object {
        val DEFAULT_ARRIVAL_BUFFER: Duration = Duration.ofMinutes(10)

        /**
         * P4-C8設計裁定: [com.actionstarter.features.departure.DepartureUiState]の既定
         * `transportMode`（TRANSIT）と同一値を、Plan構築時のgeocode→route見積りにも用いる
         * （設定画面での調整はPersonal Execution Profileと同じく将来Phase）。
         */
        val DEFAULT_TRANSPORT_MODE: TransportMode = TransportMode.TRANSIT
    }
}
