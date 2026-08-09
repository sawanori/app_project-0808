package com.actionstarter.features.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.recovery.RecoveryEngine
import com.actionstarter.services.location.LocationFailureReason
import com.actionstarter.services.location.LocationResult
import com.actionstarter.services.location.LocationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant

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
 * **P6-C1 scaffold注記（計画書§6.2・§10）**: [locationService]／[clock]は本サイクルで
 * コンストラクタへ追加した注入物であり、[AppContainer][com.actionstarter.di.AppContainer]の
 * 既存呼び出し（`RecoveryViewModel(recoveryEngine, sharedPlanViewModel)`、2引数のみ）が
 * 無変更でコンパイルを維持できるよう、いずれもデフォルト値を持たせている。**この時点では
 * [buildRecoveryContext]のロジックは変更していない**（`Instant.now()`／
 * `plan.event.coordinates`を直接使う現行実装のまま、既知欠陥1・欠陥5は温存）。
 * [locationService]を`currentLocation`取得へ、[clock]を`currentTime`取得へ実際に結線する
 * ロジック（欠陥1修正・T-RECVM-1/2/3対応）はP6-C4（ui-implementer）で実装する。
 */
class RecoveryViewModel(
    private val recoveryEngine: RecoveryEngine,
    private val sharedPlanViewModel: SharedPlanViewModel,
    private val locationService: LocationService = UnavailableLocationService,
    private val clock: Clock = Clock.systemUTC()
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val plan = sharedPlanViewModel.confirmedPlan.value ?: return@launch
            val recoveryPlan = recoveryEngine.createRecoveryPlan(buildRecoveryContext(plan))
            _uiState.value = RecoveryUiState(options = recoveryPlan.options, selectedOptionId = null)
        }
    }

    private fun buildRecoveryContext(plan: ExecutionPlan): RecoveryContext {
        val remainingTravel = Duration.between(plan.departureTime, plan.estimatedArrival).let {
            if (it.isNegative) Duration.ZERO else it
        }
        return RecoveryContext(
            currentTime = Instant.now(),
            currentLocation = plan.event.coordinates,
            event = plan.event,
            unfinishedSteps = plan.steps,
            latestTravelEstimate = remainingTravel,
            plannedDepartureTime = plan.departureTime
        )
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
