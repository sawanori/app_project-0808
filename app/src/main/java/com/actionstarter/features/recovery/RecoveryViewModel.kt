package com.actionstarter.features.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.recovery.RecoveryEngine
import com.actionstarter.recovery.RecoveryPlanApplier
import com.actionstarter.services.location.LocationFailureReason
import com.actionstarter.services.location.LocationResult
import com.actionstarter.services.location.LocationService
import com.actionstarter.services.notification.NotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 仕様§31-34準拠（RecoveryScreenのViewModel）。
 *
 * C5（統合サイクル）で[recoveryEngine]によるRecovery候補生成ロジックを実装した。
 * [sharedPlanViewModel.confirmedPlan]（Execution画面到達時点で確定済みのはず。
 * T-NAV-4ガードにより`ActionStarterNavHost`側でnull時はexecutionへ到達させない）から
 * [RecoveryContext]を構築し、[RecoveryEngine.createRecoveryPlan]（決定的計算、Mock実装は
 * LLM不使用・仕様§13/§15）を呼び出す。
 *
 * 候補選択は自動適用されない（仕様§34、T-REC-5）: [RecoveryUiState.selectedOptionId]の
 * 「仮選択」状態自体はRecoveryScreen側（画面ローカルstate）が保持し、本ViewModelは
 * 候補一覧の供給のみを担当する。
 *
 * **P6-C3実装（計画書§6.2・§7.7・§9エラーマップ#7/#17、Fable 5裁定4）**: 既知バグ3件を修正した。
 * - 欠陥1: [buildRecoveryContext]の`currentTime`は[clock]（注入済み）由来へ変更した
 *   （`Instant.now()`直呼びを廃止。T-RECVM-1）。
 * - 欠陥1（現在地）: `currentLocation`は目的地座標（`plan.event.coordinates`）を誤って
 *   流用していたバグを修正し、[locationService]経由の実測現在地へ変更した
 *   （取得不能時（`PermissionDenied`／`Failure`）は`null`のままD案のみを落とす。
 *   §95.6「Recovery Modeは位置情報なしでも成立するようフォールバック」に整合。T-RECVM-2/3）。
 * - 欠陥5: `init`の`viewModelScope.launch`に`try/catch`を追加し、[recoveryEngine]の例外を
 *   握り潰さず[RecoveryUiState.routingFailureReason]へ表面化させる（`CancellationException`は
 *   構造化並行性の取り消しを妨げないよう再送出する。`CachingRoutingService.kt`の先例に倣う。
 *   T-RECVM-5）。`routingFailureReason`は本来D案専用の欄だが、engineが例外自体を送出した
 *   （＝候補が1件も出せない）場合の汎用エラー欄としてもこのフィールドを転用し、
 *   専用の[R.string.recovery_engine_error_message]（P6-C5統合ウィンドウでstrings.xmlへ
 *   ja/en追加）を表示する（P6-C4時点までは`R.string.recovery_no_options_message`を暫定転用
 *   していたが、P6-C5で専用キーへ差替済み）。
 *
 * [useThisPlan]（Fable 5裁定3）は「Use this plan」タップ起点でのみ呼ばれる契約とし
 * （§34ガード厳守。本クラス自身が自動的に呼び出す経路は作らない）、[recoveryPlanApplier]経由で
 * 適用した[ExecutionPlan]を[sharedPlanViewModel.confirmPlan]へ渡す。適用後は
 * [RecoveryUiState.isPlanApplied]を立て、以後の呼び出しをone-shotガードで無視する
 * （§9エラーマップ#10、無限ループ防止、T-RECVM-6/7/8）。
 *
 * **P6-C5統合ウィンドウ追加（Fable 5裁定・P5-C6申し送り③への回答、
 * `docs/plans/phase5-notification-execution.md`§10.8）**: [useThisPlan]適用時、
 * [notificationService]が非nullなら旧アラーム（`cancelAll`）を取り消し、適用後のPlanで
 * `schedule`を再実行する。P5-C6時点では「Recoveryが確定Planを更新しても通知は再スケジュール
 * されない」既知の未配線だったが、Recoveryが実際にPlanを更新できるようになったP6-C5で解消する。
 * [notificationService]の既定値は`null`（`RecoveryViewModelTest`等、通知結線を検証しない
 * 既存呼び出し元は無変更のまま成立する）。
 */
class RecoveryViewModel(
    private val recoveryEngine: RecoveryEngine,
    private val sharedPlanViewModel: SharedPlanViewModel,
    private val locationService: LocationService = UnavailableLocationService,
    private val clock: Clock = Clock.systemUTC(),
    private val notificationService: NotificationService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState.asStateFlow()

    private val recoveryPlanApplier = RecoveryPlanApplier(clock)

    init {
        viewModelScope.launch {
            val plan = sharedPlanViewModel.confirmedPlan.value ?: return@launch
            try {
                val recoveryPlan = recoveryEngine.createRecoveryPlan(buildRecoveryContext(plan))
                _uiState.value = RecoveryUiState(options = recoveryPlan.options, selectedOptionId = null)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (engineFailure: Exception) {
                // 握り潰さない（欠陥5修正）：engine例外をUiStateへ表面化させる。P6-C5で
                // 専用のR.string.recovery_engine_error_messageへ差替済み（クラスKDoc参照）。
                // options空のまま既定の「案なし」導線へ合流させ、固まったまま何も表示されない
                // 状態を防ぐ。
                _uiState.value = RecoveryUiState(routingFailureReason = R.string.recovery_engine_error_message)
            }
        }
    }

    /**
     * 「Use this plan」相当の操作（計画書§7.7・§9エラーマップ#10/#14、Fable 5裁定3）。
     * §34ガード厳守: 本関数はユーザーの明示タップ起点でのみ呼ばれる契約とし、自動適用経路は
     * 作らない（呼び出し元は`RecoveryScreen`の「Use this plan」ボタンのみ）。
     *
     * - [optionId]が`null`（候補未選択）の場合は何もしない（T-RECVM-7）。
     * - 既に適用済み（[RecoveryUiState.isPlanApplied]）の場合も何もしない
     *   （one-shotガード、§9エラーマップ#10、T-RECVM-8）。
     * - [optionId]が現在の候補一覧に存在しない場合も何もしない（古い／無関係なidの適用防止）。
     * - [sharedPlanViewModel.confirmedPlan]が`null`の場合も何もしない（T-NAV-4ガードにより
     *   通常到達しないが、防御的に扱う）。
     */
    fun useThisPlan(optionId: UUID?) {
        if (optionId == null) return
        if (_uiState.value.isPlanApplied) return
        val plan = sharedPlanViewModel.confirmedPlan.value ?: return
        val option = _uiState.value.options.firstOrNull { it.id == optionId } ?: return

        val updatedPlan = recoveryPlanApplier.apply(plan, option)

        // P6-C5統合ウィンドウ（クラスKDoc「useThisPlan→通知再スケジュール」参照）: 適用で
        // ExecutionPlanが更新されたら、旧アラーム（transitionStart/departureTime基準の
        // 予約）を取り消し、更新後Planで再スケジュールする。event.idは適用前後で不変
        // （RecoveryPlanApplier.apply、「eventは変更しない」）なので、cancelAll/scheduleとも
        // 同一planIdを対象とする。notificationServiceが未注入（既定null）のテスト等では何もしない。
        notificationService?.let { service ->
            service.cancelAll(plan.event.id.toString())
            service.schedule(updatedPlan)
        }

        sharedPlanViewModel.confirmPlan(updatedPlan)
        _uiState.value = _uiState.value.copy(isPlanApplied = true)
    }

    private suspend fun buildRecoveryContext(plan: ExecutionPlan): RecoveryContext {
        val remainingTravel = Duration.between(plan.departureTime, plan.estimatedArrival).let {
            if (it.isNegative) Duration.ZERO else it
        }
        return RecoveryContext(
            currentTime = clock.instant(),
            currentLocation = resolveCurrentLocation(),
            event = plan.event,
            unfinishedSteps = plan.steps,
            latestTravelEstimate = remainingTravel,
            plannedDepartureTime = plan.departureTime
        )
    }

    /**
     * 現在地取得（欠陥1修正）。[LocationService]の結果を網羅的に[Coordinate]?へ写像する
     * （`else`節なしのexhaustive `when`＝サイレント障害防止、`DepartureViewModel`の先例）。
     * 取得不能（権限拒否／失敗）はいずれも`null`として扱い、D案のみを落として他案は成立させる
     * （§95.6、T-RECVM-3）。
     */
    private suspend fun resolveCurrentLocation(): Coordinate? =
        when (val result = locationService.currentLocation()) {
            is LocationResult.Success -> result.coordinate
            is LocationResult.PermissionDenied -> null
            is LocationResult.Failure -> null
        }
}

/**
 * [RecoveryViewModel]の[LocationService]既定値（P6-C1 scaffold、引数省略時のみ使用）。
 * `DepartureViewModel.kt`の`AlwaysGrantedPermissionGate`と同型の「呼び出し側が明示的に
 * 実サービスを注入しなかった場合の安全側フォールバック」であり、常に
 * [LocationFailureReason.UNAVAILABLE]を返す（位置情報を捏造しない）。P6-C4で
 * [locationService]を実際に結線する際も、[AppContainer][com.actionstarter.di.AppContainer]は
 * 引き続き実装（`FusedLocationService`）を明示注入する想定であり、本オブジェクトは
 * テスト・移行期の安全弁としてのみ使う。
 */
private object UnavailableLocationService : LocationService {
    override suspend fun currentLocation(timeout: Duration): LocationResult =
        LocationResult.Failure(reason = LocationFailureReason.UNAVAILABLE, cause = null)
}
