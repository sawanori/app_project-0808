package com.actionstarter.features.execution

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.services.notification.DegradationReason
import com.actionstarter.services.notification.NotificationService
import com.actionstarter.services.notification.ScheduleResult
import com.actionstarter.services.permission.PermissionGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.util.UUID

/**
 * 仕様§27-28準拠（ExecutionScreenのViewModel）。
 *
 * [savedStateHandle]は、画面回転後も`currentStepIndex`が保持されること（T-EXEC-7）、
 * プロセス再生成後に状態が復元されること（T-EXEC-8）、復元不能な場合はeventSelectionへ
 * 遷移しSnackbarで通知すること（T-EXEC-9、エラー＆レスキューマップ#8）を満たすため
 * コンストラクタ注入の形で保持する。SavedStateHandleキー規約は`"currentStepIndex"`
 * （[KEY_CURRENT_STEP_INDEX]）に固定する（C3テストと共有する規約）。
 *
 * **プレースホルダ経路（P5-C7でKDoc是正。旧文面はP5-C6で置き換わった設計を指していた）**:
 * 本ViewModelは[sharedPlanViewModel]が`null`、またはその[SharedPlanViewModel.confirmedPlan]
 * が`null`のときのみ、実行中のPlanを使わずプレースホルダの[ExecutionStep]で
 * [ExecutionUiState.currentStep]を構成する（`title`は空文字とし、Screen側で
 * `resolveStepTitle`（`features/common/StepTitle.kt`）経由の`step_title_fallback`へ
 * フォールバック表示する。ハードコードUI文字列を持ち込まないため。P11-C1（T-P11S-7）で
 * 旧プレースホルダ専用文字列リソース（`resolveStepTitle`導入以降、Phase 4時点で構造的に
 * 置換済みだった死蔵リソース。`docs/plans/phase4-basic-engine.md`のP4-C6完了記録参照）の
 * 削除に伴いKDoc参照を是正した）。本番の[com.actionstarter.navigation.ActionStarterNavHost]は
 * **P5-C6統合ウィンドウ以降、execution routeで本ViewModelを`vmFactory`
 * （[com.actionstarter.di.AppContainer.createViewModelFactory]）経由で取得し、実引数の
 * [sharedPlanViewModel]を渡す**（`ActionStarterNavHost`のKDoc「ExecutionViewModelの本番結線」
 * 参照。旧文面が記していた「本ViewModelを経由せず直接ExecutionUiStateを構築する」設計（旧
 * M5-14）はP5-C6で置き換え済み）。加えてexecution routeは[SharedPlanViewModel.confirmedPlan]
 * が`null`の間はT-NAV-4ガードでeventSelectionへ戻すため、本番でこのcomposableへ到達する
 * 時点では確定Planが常に存在する。したがってプレースホルダ経路は**本番では到達しない**
 * （`sharedPlanViewModel`が非nullかつ`confirmedPlan`も非nullの状態でのみ本番構築される
 * ため）が、[sharedPlanViewModel]の既定値が`null`であること自体はP5-C2b（ADR-0028）が
 * 既存テスト（[ExecutionViewModelTest]／[com.actionstarter.features.ExecutionScreenTest]、
 * いずれも`SavedStateHandle`のみで構築）を壊さないために維持している契約であり、
 * プレースホルダ挙動はこれら単体テスト（T-EXEC-3/6/7/8/9）で引き続き検証されるため存置する。
 *
 * **P5-C2b契約scaffold（Fable 5裁定・ADR-0028・R-3）**: F58（Execution One Actionの
 * 多段階前進）の本番結線に向け、[sharedPlanViewModel]・[notificationService]・
 * [permissionGate]の3引数を追加した。TEAMS§5の契約変更経路（変更提案→影響分析→
 * Fable 5承認→ADR記録→両側テスト更新）に基づき、`DECISIONS.md`のADR-0028として記録済み。
 * 既存テスト（[ExecutionViewModelTest]／[com.actionstarter.features.ExecutionScreenTest]、
 * いずれも`SavedStateHandle`のみで構築）を壊さないため、**新引数はすべてデフォルト値`null`
 * 付きで追加し、[sharedPlanViewModel]が`null`（またはその[SharedPlanViewModel.confirmedPlan]
 * が`null`）のときは現行プレースホルダ挙動（3ステップ固定）を維持する**。
 * [com.actionstarter.navigation.ActionStarterNavHost]の実配線（`ExecutionViewModel`経由への
 * 切り替え）と`NavigationFlowTest`（T-NAV-1／T-NAV-3）の期待値更新は、統合ウィンドウ
 * （P5-C6）で同時に行う（ADR-0028参照）。
 *
 * **P5-C3実装（F58/F59本番結線、T-P5UI-1〜8）**: [sharedPlanViewModel]の
 * [SharedPlanViewModel.confirmedPlan]が非nullの場合、[currentStepIndex]は本ViewModelが
 * 保持する「working copy」の[ExecutionPlan.steps]を指すようになる（プレースホルダの
 * 3ステップではなく確定Planの実ステップ列。T-P5UI-1）。Doneは`currentStepIndex`を1つ進め、
 * ステップ数を使い切ると`currentStep=null`（snackbarMessageResIdもnull）でdepartureへの
 * 直行を通知する（T-EXEC-4契約の維持、T-P5UI-2）。「5 min later」は当該ステップの
 * `scheduledStart`を+5分し（[POSTPONE_DURATION]、S-6単一情報源）、[notificationService]へ
 * 再登録を委譲する（F59、T-P5UI-3）が、その結果イベント開始時刻を超える場合は§34
 * （ステップ省略はユーザー確認必須）に基づき**シフトそのものを行わない**（T-P5UI-5。
 * ダイアログ等の確認UIはExecutionUiStateの新フィールドを要し本サイクルの8ケースの
 * 範囲外のため、確認プロンプトの表示自体は据え置き、最小限「勝手に進めない」動作のみを
 * 実装する）。
 *
 * **`isExactAlarmDegraded`の算出タイミング（P5-C3fix・Fable 5裁定2026-08-09、
 * `docs/plans/phase5-notification-execution.md`§10.7で構造的に解消済み）**: P5-C3時点では
 * T-P5UI-4（「postponeタップごとにNotificationService.scheduleがちょうど1回だけ呼ばれる」、
 * `scheduleCallCount`の**厳密な**等値アサーション`==2`）とT-P5UI-7（`onPostpone`等を一切呼ばず
 * **コンストラクタ直後**に`isExactAlarmDegraded=true`を要求）が、`isExactAlarmDegraded`を
 * [NotificationService.schedule]の戻り値のみから得る設計のもとでは両立不能だった
 * （`init`で`schedule()`を呼べばT-P5UI-4が壊れ、呼ばなければT-P5UI-7が満たせない）。
 * Fable 5裁定により[NotificationService]へ読み取り専用の能力照会契約
 * [NotificationService.isExactAlarmAvailable]（`schedule()`を伴わない、現在の能力状態のみを
 * 返す照会）を追加し構造的に解消した: [notificationService]が非nullのとき`init`で
 * [NotificationService.isExactAlarmAvailable]のみを1回照会し[initialExactAlarmDegraded]
 * として保持する（`schedule()`は呼ばない——T-P5UI-4の`scheduleCallCount`厳密等値を維持する）。
 * postpone成功時は従来どおり[lastScheduleResult]（`schedule()`の戻り値）で上書きする。
 * これにより`init`時点で`schedule()`の呼び出し回数を1件も増やさずに、コンストラクタ直後の
 * 劣化表示（T-P5UI-7）も同時に満たす。
 *
 * @param sharedPlanViewModel activity-scoped共有ViewModel（計画書§10.1）。確定Planの
 *   ステップ列を参照するための注入経路。
 * @param notificationService Snooze時のアラーム再登録・劣化表示の判定に委譲する先（F59）。
 * @param permissionGate POST_NOTIFICATIONS等の許可状態照会（T-P5UI-6）。
 */
class ExecutionViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val sharedPlanViewModel: SharedPlanViewModel? = null,
    private val notificationService: NotificationService? = null,
    private val permissionGate: PermissionGate? = null
) : ViewModel() {

    /**
     * F58本番結線: [sharedPlanViewModel]が確定Planを持っていれば、それを本ViewModelの
     * "working copy"として保持する。「5 min later」による`scheduledStart`変更はこの
     * ローカルコピーのみを更新し、[sharedPlanViewModel]自体へは書き戻さない
     * （他コンシューマへの副作用を持ち込まない）。`null`（[sharedPlanViewModel]自体が
     * `null`、または確定Planがまだない）の場合は既存プレースホルダ挙動を維持する。
     */
    private var workingPlan: ExecutionPlan? = sharedPlanViewModel?.confirmedPlan?.value

    /**
     * P5-C3fix（Fable 5裁定2026-08-09、§10.7）: [notificationService]が非nullのときのみ
     * construction時に一度だけ[NotificationService.isExactAlarmAvailable]（現在の能力状態、
     * 読み取り専用）を照会する。[NotificationService.schedule]は呼ばない
     * （T-P5UI-4の`scheduleCallCount`厳密等値アサーションを壊さないため）。
     * [notificationService]が`null`のときは劣化なし相当（`false`）とする。
     */
    private val initialExactAlarmDegraded: Boolean =
        notificationService?.isExactAlarmAvailable()?.not() ?: false

    /** T-P5UI-3/4/5: postpone成功時にのみ更新される。`null`の間（＝まだpostponeが一度も
     * 成功していない間）は[initialExactAlarmDegraded]（construction時の能力照会結果）を使う。 */
    private var lastScheduleResult: ScheduleResult? = null

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<ExecutionUiState> = _uiState.asStateFlow()

    /**
     * P11-C1 scaffold（F80、§7.1「PermissionGateの2値契約とONResume再評価」・T-P11N-7/8）:
     * `currentStep`／`currentStepIndex`／`onDone`／`onPostpone`は変更せず、劣化フラグのうち
     * [permissionGate]／[notificationService]から都度再照会可能な2種
     * （[ExecutionUiState.isNotificationPermissionDenied]・[ExecutionUiState.isExactAlarmDegraded]）
     * のみを再計算する。[ExecutionUiState.isForegroundServiceDegraded]は本ViewModel内に
     * 独立した照会元を持たない既存のdead field（grep実測: 生成箇所0件、既定`false`のまま）で
     * あるため、再評価対象に含めず現在値のまま維持する（`copy()`の対象外フィールドは
     * 自動的に不変。P11計画書§7.1参照）。呼び出し元（`ActionStarterNavHost`のExecution route）
     * はSettingsから戻ったON_RESUME（`DisposableEffect`＋`LifecycleEventObserver`、
     * EventSelection/Departure routeと同型）でこれを呼ぶ（F80）。
     *
     * P11-C1時点ではpermissionGate/notificationServiceへの委譲のみで新規ロジックを持たない
     * ため単体では既に正しく動作する（T-P11N-7はborn-green想定）が、本番での呼び出し元
     * （`ActionStarterNavHost`のExecution route ON_RESUME）はP11-C3まで存在せず本番未到達
     * のまま。P11-C3でNavHost側のON_RESUME結線を追加し、実際にユーザー操作から到達可能にする。
     */
    fun refreshDegradationState() {
        _uiState.value = _uiState.value.copy(
            isNotificationPermissionDenied = isNotificationPermissionDenied(),
            isExactAlarmDegraded = isExactAlarmDegraded()
        )
    }

    private fun restoredIndex(): Int = savedStateHandle.get<Int>(KEY_CURRENT_STEP_INDEX) ?: 0

    private fun buildInitialState(): ExecutionUiState {
        val plan = workingPlan
        return if (plan != null) stateForConfirmedPlan(restoredIndex(), plan) else stateForIndex(restoredIndex())
    }

    /**
     * [index]から[ExecutionUiState]を構成する（プレースホルダ経路。[workingPlan]が
     * `null`のときのみ使う）。
     * - `index < 0` … 復元不能（破損）状態。eventSelectionへ遷移しSnackbar通知する
     *   契約を表すため[ExecutionUiState.snackbarMessageResId]を設定する（T-EXEC-9）。
     * - `index >= PLACEHOLDER_STEP_COUNT` … プレースホルダのステップを使い切った正常終了。
     *   Screen側は`currentStep == null && snackbarMessageResId == null`をもって
     *   departureへの直行と解釈する（T-EXEC-4／T-EXEC-5と同一の契約）。
     * - それ以外 … 有効なステップindex。[ExecutionUiState.onDone]／[ExecutionUiState.onPostpone]
     *   を本インスタンスのメソッドへ束縛して公開する。
     */
    private fun stateForIndex(index: Int): ExecutionUiState = when {
        index < 0 -> ExecutionUiState(
            currentStep = null,
            currentStepIndex = index,
            snackbarMessageResId = R.string.execution_restored_snackbar_message
        )

        index >= PLACEHOLDER_STEP_COUNT -> ExecutionUiState(
            currentStep = null,
            currentStepIndex = index,
            snackbarMessageResId = null
        )

        else -> ExecutionUiState(
            currentStep = placeholderStep(index),
            currentStepIndex = index,
            onDone = ::handleDone,
            onPostpone = ::handlePostpone,
            isNotificationPermissionDenied = isNotificationPermissionDenied(),
            isExactAlarmDegraded = isExactAlarmDegraded()
        )
    }

    private fun handleDone() {
        val next = _uiState.value.currentStepIndex + 1
        savedStateHandle[KEY_CURRENT_STEP_INDEX] = next
        _uiState.value = stateForIndex(next)
    }

    private fun handlePostpone() {
        val current = _uiState.value.currentStep ?: return
        val postponed = current.copy(scheduledStart = current.scheduledStart?.plus(POSTPONE_DURATION))
        _uiState.value = _uiState.value.copy(currentStep = postponed)
    }

    private fun placeholderStep(index: Int): ExecutionStep = ExecutionStep(
        id = PLACEHOLDER_STEP_IDS[index],
        semanticId = "execution_placeholder_step_$index",
        type = ExecutionStepType.PREPARATION,
        title = "",
        estimatedDuration = Duration.ZERO,
        priority = StepPriority.OPTIONAL,
        skippable = true,
        scheduledStart = null,
        completedAt = null
    )

    /**
     * [index]から[ExecutionUiState]を構成する（F58確定Plan経路、T-P5UI-1〜8）。
     * `index >= plan.steps.size`でステップを使い切ったときはT-EXEC-4と同一の契約
     * （`currentStep=null`かつ`snackbarMessageResId=null`）でdepartureへの直行を表す
     * （T-P5UI-2）。本メソッド自体は[notificationService]を呼ばない
     * （クラスKDoc「`isExactAlarmDegraded`の算出タイミング」参照。呼び出し側
     * （[buildInitialState]／[handleConfirmedPlanDone]／[handleConfirmedPlanPostpone]）が
     * 必要なタイミングでのみ[lastScheduleResult]を更新する）。
     */
    private fun stateForConfirmedPlan(index: Int, plan: ExecutionPlan): ExecutionUiState = when {
        index < 0 -> ExecutionUiState(
            currentStep = null,
            currentStepIndex = index,
            snackbarMessageResId = R.string.execution_restored_snackbar_message
        )

        index >= plan.steps.size -> ExecutionUiState(
            currentStep = null,
            currentStepIndex = index,
            snackbarMessageResId = null,
            isNotificationPermissionDenied = isNotificationPermissionDenied(),
            isExactAlarmDegraded = isExactAlarmDegraded()
        )

        else -> ExecutionUiState(
            currentStep = plan.steps[index],
            currentStepIndex = index,
            onDone = ::handleConfirmedPlanDone,
            onPostpone = ::handleConfirmedPlanPostpone,
            isNotificationPermissionDenied = isNotificationPermissionDenied(),
            isExactAlarmDegraded = isExactAlarmDegraded()
        )
    }

    /** T-P5UI-1: Doneで次ステップへ進む。T-P5UI-2: 最終ステップのDoneはdepartureへの
     * 直行契約（currentStep=null）を発火させる。 */
    private fun handleConfirmedPlanDone() {
        val plan = workingPlan ?: return
        val next = _uiState.value.currentStepIndex + 1
        savedStateHandle[KEY_CURRENT_STEP_INDEX] = next
        _uiState.value = stateForConfirmedPlan(next, plan)
    }

    /**
     * F59: 現在ステップの`scheduledStart`を[POSTPONE_DURATION]だけ後ろへずらし、
     * [notificationService]へアラーム再登録を委譲する（T-P5UI-3、`scheduleCallCount`が
     * タップごとにちょうど1回増えることをT-P5UI-4が固定する）。
     *
     * §34（ステップ省略はユーザー確認必須）: ずらした結果がイベント開始時刻以降になる
     * 場合、シフトそのものを行わない（T-P5UI-5。自動でスケジュールを決めない）。
     */
    private fun handleConfirmedPlanPostpone() {
        val plan = workingPlan ?: return
        val index = _uiState.value.currentStepIndex
        val current = plan.steps.getOrNull(index) ?: return
        val currentStart = current.scheduledStart ?: return
        val postponedStart = currentStart.plus(POSTPONE_DURATION)

        if (!postponedStart.isBefore(plan.event.startDate)) {
            // Postponing would land at-or-past the event start; §34 forbids silently
            // advancing/skipping, so this tap is a no-op until a confirmation flow exists.
            return
        }

        val updatedSteps = plan.steps.mapIndexed { stepIndex, step ->
            if (stepIndex == index) step.copy(scheduledStart = postponedStart) else step
        }
        val updatedPlan = plan.copy(steps = updatedSteps)
        workingPlan = updatedPlan

        lastScheduleResult = notificationService?.schedule(updatedPlan)

        _uiState.value = stateForConfirmedPlan(index, updatedPlan)
    }

    private fun isNotificationPermissionDenied(): Boolean =
        permissionGate?.isGranted(POST_NOTIFICATIONS_PERMISSION) == false

    /**
     * P5-C3fix（§10.7）: [lastScheduleResult]が一度でも設定されていれば（＝postponeが
     * 少なくとも1回成功していれば）それを優先する（直近の`schedule()`結果の方が
     * construction時点の能力照会より新しい）。まだ設定されていなければ
     * [initialExactAlarmDegraded]（construction時の[NotificationService.isExactAlarmAvailable]
     * 照会結果）を使う。
     */
    private fun isExactAlarmDegraded(): Boolean {
        val result = lastScheduleResult
        return if (result != null) {
            result is ScheduleResult.Degraded && result.reason == DegradationReason.EXACT_ALARM_NOT_PERMITTED
        } else {
            initialExactAlarmDegraded
        }
    }

    private companion object {
        const val KEY_CURRENT_STEP_INDEX = "currentStepIndex"
        const val PLACEHOLDER_STEP_COUNT = 3
        val POSTPONE_DURATION: Duration = Duration.ofMinutes(5)
        val PLACEHOLDER_STEP_IDS: List<UUID> = List(PLACEHOLDER_STEP_COUNT) { index ->
            UUID.nameUUIDFromBytes("execution-placeholder-step-$index".toByteArray())
        }
        val POST_NOTIFICATIONS_PERMISSION: String = android.Manifest.permission.POST_NOTIFICATIONS
    }
}
