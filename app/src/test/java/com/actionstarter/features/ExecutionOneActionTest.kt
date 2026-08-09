package com.actionstarter.features

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.features.execution.ExecutionViewModel
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.services.notification.DegradationReason
import com.actionstarter.services.notification.NotificationKind
import com.actionstarter.services.notification.NotificationPayload
import com.actionstarter.services.notification.NotificationService
import com.actionstarter.services.notification.NotifyResult
import com.actionstarter.services.notification.ScheduleResult
import com.actionstarter.services.permission.PermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-P5UI-1〜8（計画書§8.2「F58/F59/F51/F52 — Execution One Action・Snooze・劣化表示」
 * 全8件、`docs/plans/phase5-notification-execution.md`§6.1のファイル配置表どおり）。
 *
 * **P5-C2で見送られP5-C2bで作成した経緯**: 計画書§10.4（P5-C2完了記録）が
 * 「F58はR-3の契約変更（`ExecutionViewModel`への`sharedPlanViewModel`等の新引数追加）を
 * 前提とするが、契約変更手続き未完了のためscaffold未到達」として本8件を差し戻していた。
 * `DECISIONS.md`のADR-0028（TEAMS§5契約変更経路、R-3・S-7）に基づき、P5-C2bで
 * `ExecutionViewModel`／`ExecutionUiState`へ新引数・新フィールドが追加された
 * （いずれもデフォルト値付き。既存`ExecutionViewModelTest`／`ExecutionScreenTest`は無変更で
 * Green維持）ため、本サイクル（P5-C2b）でT-P5UI-1〜8を作成する。
 *
 * **Red化の設計方針**: `ExecutionViewModel`は本サイクルでは新引数を保持するのみで、
 * 多段階遷移・Snooze再登録・劣化表示ロジックは一切実装しない（現行プレースホルダ3ステップ
 * 挙動を維持）。したがって以下のテストは`TODO()`由来の`NotImplementedError`ではなく、
 * **「確定Planの実データを期待するが、実際にはプレースホルダの値が返る」という期待値差分に
 * よりRedになる**（P5-C3実装時にプレースホルダロジックが確定Plan駆動ロジックへ置き換わる
 * ことでGreenへ遷移する設計）。T-P5UI-8のみ例外で、既存契約（T-EXEC-7の画面回転保持）は
 * 新引数の追加によって影響を受けないため作成時点からGreenになる（`docs/plans/
 * phase5-notification-execution.md`§10.4がT-NOTIF-4/8/9・T-STORE-7について記録した
 * 「現状が誤っていることを示すことを目的としない回帰ガード」と同型の許容ケース）。
 */
@RunWith(AndroidJUnit4::class)
class ExecutionOneActionTest {

    private val fixedNow: Instant = Instant.parse("2026-08-10T07:00:00Z")

    // T-P5UI-1: 正常 - Doneで次ステップへ進み、画面には常に1ステップのみ存在する（ONE ACTION回帰）
    @Test
    fun tP5ui1_done_advancesToNextConfirmedPlanStep_stillOnlyOneStepPresent() {
        val plan = buildConfirmedPlan()
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = sharedPlanViewModelWith(plan)
        )

        // 初期状態は確定Planの最初のステップであるべき（現状はPhase 1プレースホルダのため
        // 期待値差分でRedになる）
        assertEquals(plan.steps[0].id, viewModel.uiState.value.currentStep?.id)

        viewModel.uiState.value.onDone?.invoke()

        // Done後も常に1ステップのみが提示される（ONE ACTION、§28）。次は確定Planの2番目のステップ
        assertEquals(plan.steps[1].id, viewModel.uiState.value.currentStep?.id)
        assertEquals(1, viewModel.uiState.value.currentStepIndex)
    }

    // T-P5UI-2: 正常 - 最終ステップのDoneでdepartureへ遷移する（既存T-EXEC-4契約を壊さない）
    // T-EXEC-4契約: currentStep == null（かつsnackbarMessageResId == null）をもって
    // Screen側がdepartureへの直行と解釈する。
    @Test
    fun tP5ui2_doneOnFinalConfirmedPlanStep_signalsDepartureTransition_perTExec4Contract() {
        val plan = buildConfirmedPlan() // 2ステップ
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = sharedPlanViewModelWith(plan)
        )

        viewModel.uiState.value.onDone?.invoke() // step0 -> step1（最終ステップ）
        viewModel.uiState.value.onDone?.invoke() // step1（最終）のDone -> departureへ

        assertNull(
            "final step's Done should signal departure via currentStep=null (T-EXEC-4 contract)",
            viewModel.uiState.value.currentStep
        )
    }

    // T-P5UI-3: 正常 - 「5 min later」で当該ステップのscheduledStartが+5分され、
    // アラームもNotificationServiceへの委譲経由で+5分で再登録される（F59）
    @Test
    fun tP5ui3_postpone_shiftsScheduledStartByFiveMinutes_andReregistersAlarmViaNotificationService() {
        val plan = buildConfirmedPlan()
        val notificationService = SpyNotificationService()
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = sharedPlanViewModelWith(plan),
            notificationService = notificationService
        )
        val originalScheduledStart = plan.steps[0].scheduledStart

        viewModel.uiState.value.onPostpone?.invoke()

        assertEquals(
            originalScheduledStart?.plus(Duration.ofMinutes(5)),
            viewModel.uiState.value.currentStep?.scheduledStart
        )
        assertTrue(
            "expected NotificationService.schedule to be delegated to for alarm re-registration",
            notificationService.scheduleCallCount >= 1
        )
    }

    // T-P5UI-4: 回帰ガード - 「5 min later」で通知が新規に増えない（同一idで置換）。
    // ViewModelレベルでは、postponeタップごとにNotificationService.scheduleが
    // ちょうど1回だけ呼ばれる（累積加算されない）ことで表現する。実際の同一通知id置換は
    // AlarmScheduler/NotificationService側の責務でありT-ALARM-4／T-NOTIF-7が固定する。
    @Test
    fun tP5ui4_postponeTwice_callsNotificationServiceScheduleExactlyOncePerTap_notAccumulating() {
        val plan = buildConfirmedPlan()
        val notificationService = SpyNotificationService()
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = sharedPlanViewModelWith(plan),
            notificationService = notificationService
        )

        viewModel.uiState.value.onPostpone?.invoke()
        viewModel.uiState.value.onPostpone?.invoke()

        assertEquals(
            "each postpone tap should trigger exactly one re-schedule call (replace, not accumulate)",
            2,
            notificationService.scheduleCallCount
        )
    }

    // T-P5UI-5: エッジ - Snoozeでイベント開始時刻を越える場合、ステップを自動省略せず
    // ユーザーに確認を促す（AIも自動処理もステップ省略を勝手に決めない、§34）
    @Test
    fun tP5ui5_postponePastEventStart_doesNotSilentlyAdvance_requiresUserConfirmation() {
        // イベント開始は3分後。ステップのscheduledStartはfixedNowなので、+5分すると
        // イベント開始時刻を超える。
        val eventStart = fixedNow.plus(Duration.ofMinutes(3))
        val plan = buildConfirmedPlan(eventStart = eventStart)
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = sharedPlanViewModelWith(plan)
        )
        // プレースホルダ既定値との偶然の一致（scheduledStart=null同士）を避けるため、
        // まず実データが使われていることをidで確認する（この時点で既に期待値差分によりRed）。
        assertEquals(plan.steps[0].id, viewModel.uiState.value.currentStep?.id)
        val originalScheduledStart = viewModel.uiState.value.currentStep?.scheduledStart

        viewModel.uiState.value.onPostpone?.invoke()

        assertEquals(
            "postpone must not silently push the step past the event start time without user confirmation",
            originalScheduledStart,
            viewModel.uiState.value.currentStep?.scheduledStart
        )
    }

    // T-P5UI-6: 異常 - POST_NOTIFICATIONS未許可時、Execution画面のNOWカードのみで状態が伝わり
    // 設定導線が出る（isNotificationPermissionDeniedフラグで表現、エラー&レスキューマップ#3）
    @Test
    fun tP5ui6_postNotificationsDenied_setsNotificationPermissionDeniedFlag() {
        val plan = buildConfirmedPlan()
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = sharedPlanViewModelWith(plan),
            permissionGate = FixedPermissionGate(granted = emptySet())
        )

        assertEquals(plan.steps[0].id, viewModel.uiState.value.currentStep?.id)
        assertTrue(
            "expected isNotificationPermissionDenied=true when POST_NOTIFICATIONS is denied",
            viewModel.uiState.value.isNotificationPermissionDenied
        )
    }

    // T-P5UI-7: 異常 - SCHEDULE_EXACT_ALARM未許可時、精度低下の明示表示＋
    // ACTION_REQUEST_SCHEDULE_EXACT_ALARM導線が出る（isExactAlarmDegradedフラグで表現、
    // S-4・エラー&レスキューマップ#1・ADR-0026）
    //
    // P5-C3fix更新（Fable 5裁定2026-08-09、§10.7）: isExactAlarmDegradedの算出元をNotification
    // Service.isExactAlarmAvailable()（schedule()を伴わない現在の能力照会）へ変更した。fakeの
    // 能力フラグexactAlarmAvailable=falseで「exact alarm不可」を表現し、コンストラクタ直後に
    // isExactAlarmDegraded==trueを検証する。あわせてscheduleCallCountが0のままであることも
    // 検証し、T-P5UI-4（postponeタップごとにscheduleがちょうど1回）との両立を証明する。
    @Test
    fun tP5ui7_exactAlarmNotPermitted_setsExactAlarmDegradedFlag() {
        val plan = buildConfirmedPlan()
        val notificationService = DegradedExactAlarmNotificationService(exactAlarmAvailable = false)
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = sharedPlanViewModelWith(plan),
            notificationService = notificationService
        )

        assertEquals(plan.steps[0].id, viewModel.uiState.value.currentStep?.id)
        assertTrue(
            "expected isExactAlarmDegraded=true when NotificationService reports exact alarm unavailable",
            viewModel.uiState.value.isExactAlarmDegraded
        )
        assertEquals(
            "isExactAlarmDegraded must come from the isExactAlarmAvailable() capability query, not " +
                "schedule() -- schedule() must stay uncalled at construction time (coexists with " +
                "T-P5UI-4's strict per-tap scheduleCallCount)",
            0,
            notificationService.scheduleCallCount
        )
    }

    // T-P5UI-8（回帰ガード的性質・作成時点からGreenを許容。計画書§10.4のT-NOTIF-4/8/9・
    // T-STORE-7と同型の理由） - エッジ: 画面回転でcurrentStepIndexが保持される
    // （既存T-EXEC-7契約の維持）。新規3引数（sharedPlanViewModel/notificationService/
    // permissionGate）を実引数で与えても、SavedStateHandle経由のcurrentStepIndex復元
    // ロジック自体は本サイクルで変更していないため、本テストは作成時点からGreenになる
    // （「現状が誤っていることを示す」ことを目的としない回帰ガード。将来の実装変更で
    // この契約が破られた場合に検知することが目的）。
    @Test
    fun tP5ui8_rotationWithNewConstructorArgsSupplied_preservesCurrentStepIndex_perTExec7() {
        val plan = buildConfirmedPlan()
        val restoredHandle = SavedStateHandle(mapOf(KEY_CURRENT_STEP_INDEX to 1))

        val viewModel = ExecutionViewModel(
            savedStateHandle = restoredHandle,
            sharedPlanViewModel = sharedPlanViewModelWith(plan),
            notificationService = SpyNotificationService(),
            permissionGate = FixedPermissionGate(granted = setOf(POST_NOTIFICATIONS_PERMISSION))
        )

        assertEquals(1, viewModel.uiState.value.currentStepIndex)
    }

    // ---- 共有フィクスチャ ---------------------------------------------------------------

    private fun sharedPlanViewModelWith(plan: ExecutionPlan): SharedPlanViewModel =
        SharedPlanViewModel().apply { confirmPlan(plan) }

    /**
     * [com.actionstarter.services.notification.AlarmSchedulingTest]の`buildPlan`と同型の
     * 最小フィクスチャ。2ステップ固定（[eventStart]は既定でstep双方より十分先）。
     */
    private fun buildConfirmedPlan(
        transitionStart: Instant = fixedNow.plus(Duration.ofMinutes(10)),
        eventStart: Instant = fixedNow.plus(Duration.ofHours(1))
    ): ExecutionPlan {
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "Team sync",
            notes = null,
            startDate = eventStart,
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "cal-1", displayName = "Work")
        )
        val steps = listOf(
            ExecutionStep(
                id = UUID.nameUUIDFromBytes("${event.id}:prep".toByteArray()),
                semanticId = "prep",
                type = ExecutionStepType.PREPARATION,
                title = "",
                estimatedDuration = Duration.ofMinutes(10),
                priority = StepPriority.IMPORTANT,
                skippable = true,
                scheduledStart = fixedNow,
                completedAt = null
            ),
            ExecutionStep(
                id = UUID.nameUUIDFromBytes("${event.id}:transition".toByteArray()),
                semanticId = "transition",
                type = ExecutionStepType.TRANSITION,
                title = "",
                estimatedDuration = Duration.ofMinutes(5),
                priority = StepPriority.REQUIRED,
                skippable = false,
                scheduledStart = transitionStart,
                completedAt = null
            )
        )
        return ExecutionPlan(
            event = event,
            steps = steps,
            transitionStart = transitionStart,
            departureTime = eventStart.minus(Duration.ofMinutes(20)),
            estimatedArrival = eventStart.minus(Duration.ofMinutes(5)),
            arrivalBuffer = Duration.ofMinutes(10)
        )
    }

    /**
     * [NotificationService]のspy fake。`schedule`の呼び出し回数を記録する（T-P5UI-3/4）。
     * [isExactAlarmAvailable]はP5-C3fix（§10.7）で追加された契約メソッド。本fakeはexact alarm
     * 能力について通常時（許可あり）を表すため既定`true`を返す——T-P5UI-3/4/8で本fakeを使う
     * ケースは劣化表示を検証対象にしないため、無関係な副作用を持ち込まないための既定値。
     */
    private class SpyNotificationService : NotificationService {
        var scheduleCallCount = 0
            private set

        override fun schedule(plan: ExecutionPlan): ScheduleResult {
            scheduleCallCount++
            return ScheduleResult.Exact(plan.steps.size)
        }

        override fun cancelAll(planId: String) = Unit
        override fun notifyNow(kind: NotificationKind, payload: NotificationPayload): NotifyResult = NotifyResult.Shown
        override fun restoreFromStore(): ScheduleResult = ScheduleResult.Exact(0)
        override fun isExactAlarmAvailable(): Boolean = true
    }

    /**
     * 能力ベースの劣化を表す[NotificationService]フェイク（T-P5UI-7）。P5-C3fix（Fable 5裁定
     * 2026-08-09、§10.7）により、[isExactAlarmDegraded]の算出元は`schedule()`の戻り値ではなく
     * [isExactAlarmAvailable]（現在の能力状態の読み取り専用照会）へ変更されたため、本fakeは
     * [exactAlarmAvailable]で能力状態を表現する。[schedule]が呼ばれた場合の戻り値は後方互換の
     * ため引き続きDegradedを返すが、[scheduleCallCount]で呼び出し回数を記録し、
     * construction直後は未呼び出し（0のまま）であることをテスト側で検証できるようにする
     * （T-P5UI-4の`scheduleCallCount`厳密等値との両立の証拠）。
     */
    private class DegradedExactAlarmNotificationService(
        private val exactAlarmAvailable: Boolean = false
    ) : NotificationService {
        var scheduleCallCount = 0
            private set

        override fun schedule(plan: ExecutionPlan): ScheduleResult {
            scheduleCallCount++
            return ScheduleResult.Degraded(plan.steps.size, DegradationReason.EXACT_ALARM_NOT_PERMITTED)
        }

        override fun cancelAll(planId: String) = Unit
        override fun notifyNow(kind: NotificationKind, payload: NotificationPayload): NotifyResult = NotifyResult.Shown
        override fun restoreFromStore(): ScheduleResult = ScheduleResult.Exact(0)
        override fun isExactAlarmAvailable(): Boolean = exactAlarmAvailable
    }

    /** 指定した権限文字列集合のみ許可する[PermissionGate]フェイク（T-P5UI-6/8）。 */
    private class FixedPermissionGate(private val granted: Set<String>) : PermissionGate {
        override fun isGranted(permission: String): Boolean = permission in granted
    }

    private companion object {
        const val KEY_CURRENT_STEP_INDEX = "currentStepIndex"
        const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
