@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.actionstarter.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.ai.AiGatewayTestFixtures
import com.actionstarter.ai.LocalAiGateway
import com.actionstarter.ai.LocalAiRecoveryContextualizer
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.RecoveryPlan
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.features.recovery.RecoveryUiState
import com.actionstarter.features.recovery.RecoveryViewModel
import com.actionstarter.navigation.SharedPlanViewModel
import com.actionstarter.recovery.RecoveryEngine
import com.actionstarter.services.location.LocationFailureReason
import com.actionstarter.services.location.LocationResult
import com.actionstarter.services.location.LocationService
import com.actionstarter.services.notification.NotificationKind
import com.actionstarter.services.notification.NotificationPayload
import com.actionstarter.services.notification.NotificationService
import com.actionstarter.services.notification.NotifyResult
import com.actionstarter.services.notification.ScheduleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * T-RECVM-1〜5（計画書§8.5、`docs/plans/phase6-recovery-basic.md`）。
 *
 * [RecoveryViewModel]は`BasicRecoveryEngine`と異なりP6-C1時点で`TODO()`本体ではなく、
 * Phase 1由来の実装（`Instant.now()`/`plan.event.coordinates`を直接使う）が現役のまま
 * 残っている。そのため本ファイルのテストは[com.actionstarter.recovery.RecoveryEngine]の
 * フェイク実装を注入して`BasicRecoveryEngine`を経由せずに検証し、`NotImplementedError`
 * ではなく**現行の実装済みロジックに対するassertion failure**としてRedになる
 * （計画書§0欠陥1・欠陥5の回帰ロック。意図した失敗）。
 *
 * ## P6-C3追補: T-RECVM-6/7/8（Fable 5裁定3、C2完了報告の差し戻し事項への回答）
 * P6-C2完了報告は、`RecoveryViewModel`に「選択候補を適用する」ためのpublicメソッド
 * （`RecoveryScreen.onUseThisPlan: (UUID?) -> Unit`ラムダの受け皿）が存在しないため
 * T-RECVM-6/7/8をC2対象外としていた。P6-C3で`fun useThisPlan(optionId: UUID?)`を
 * `RecoveryViewModel`へscaffold追加した（本体`TODO()`）ため、本追補で3件を追加する。
 * scaffold時点では`useThisPlan`本体が`TODO()`のため、以下3件は`NotImplementedError`により
 * Redになるのが正しい（意図した失敗。実装はP6-C3実装フェーズで行う）。
 *
 * ## T-RECVM-5に関する限定事項
 * [RecoveryUiState]には汎用のエラー欄が存在しない（`routingFailureReason: Int?`はD案の
 * 経路取得失敗専用、`isPlanApplied: Boolean`は適用状態専用）。そのため「engineが例外を
 * 送出してもUiStateが黙って既定状態のまま留まらない」ことを、既定値`RecoveryUiState()`との
 * 不一致という最小限のcompilable な形で検証する。C4でエラー欄が追加され次第、具体的な値の
 * 検証へ強化する必要がある（差し戻し事項）。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleEvent(coordinates: Coordinate?): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = Instant.parse("2026-08-10T10:00:00Z"),
        locationName = "Shibuya",
        coordinates = coordinates,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun samplePlan(
        eventCoordinates: Coordinate? = Coordinate(lat = 35.0, lon = 139.0)
    ): ExecutionPlan = ExecutionPlan(
        event = sampleEvent(eventCoordinates),
        steps = emptyList(),
        transitionStart = Instant.parse("2026-08-10T08:30:00Z"),
        departureTime = Instant.parse("2026-08-10T09:00:00Z"),
        estimatedArrival = Instant.parse("2026-08-10T09:30:00Z"),
        arrivalBuffer = Duration.ofMinutes(15)
    )

    private fun sampleOption(): RecoveryOption = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = "keep_all_steps",
        title = "",
        explanation = "",
        estimatedArrival = Instant.parse("2026-08-10T09:30:00Z"),
        skippedStepIds = emptyList()
    )

    private val alwaysSuccessLocationService = object : LocationService {
        override suspend fun currentLocation(timeout: Duration): LocationResult =
            LocationResult.Success(coordinate = Coordinate(lat = 1.0, lon = 2.0), accuracyMeters = 5f, fixedAt = Instant.now())
    }

    /** [RecoveryContext]を捕捉し、固定の[RecoveryPlan]を返すfake。 */
    private class CapturingRecoveryEngine(
        private val result: RecoveryPlan = RecoveryPlan(options = emptyList())
    ) : RecoveryEngine {
        var capturedContext: RecoveryContext? = null
            private set
        var callCount = 0
            private set

        override suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan {
            capturedContext = context
            callCount++
            return result
        }
    }

    private class ThrowingRecoveryEngine(private val exception: Throwable) : RecoveryEngine {
        override suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan = throw exception
    }

    /**
     * [NotificationService]のrecording fake（T-RECVM-9）。`ExecutionOneActionTest.kt`の
     * `SpyNotificationService`と同型のcallCount方式に加え、引数（`cancelAll`のplanId／
     * `schedule`のplan）と呼び出し順序（[callOrder]）まで記録し、
     * 「cancelAll→scheduleが更新後Planに対しそれぞれ1回ずつ、この順で呼ばれる」ことを
     * 観測可能にする。
     */
    private class RecordingNotificationService : NotificationService {
        var cancelAllCallCount = 0
            private set
        var scheduleCallCount = 0
            private set
        var cancelledPlanId: String? = null
            private set
        var scheduledPlan: ExecutionPlan? = null
            private set
        val callOrder = mutableListOf<String>()

        override fun schedule(plan: ExecutionPlan): ScheduleResult {
            scheduleCallCount++
            scheduledPlan = plan
            callOrder += "schedule"
            return ScheduleResult.Exact(plan.steps.size)
        }

        override fun cancelAll(planId: String) {
            cancelAllCallCount++
            cancelledPlanId = planId
            callOrder += "cancelAll"
        }

        override fun notifyNow(kind: NotificationKind, payload: NotificationPayload): NotifyResult = NotifyResult.Shown
        override fun restoreFromStore(): ScheduleResult = ScheduleResult.Exact(0)
        override fun isExactAlarmAvailable(): Boolean = true
    }

    // T-RECVM-1: 正常系 - confirmedPlanからRecoveryContextを構築し、currentTimeが注入Clock由来である
    @Test
    fun tRecVm1_currentTime_derivedFromInjectedClock() = runTest(testDispatcher) {
        val fixedInstant = Instant.parse("2026-08-10T09:15:00Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(samplePlan()) }
        val engine = CapturingRecoveryEngine()

        RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = clock
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(fixedInstant, engine.capturedContext?.currentTime)
    }

    // T-RECVM-2: 異常系 - currentLocationがLocationService由来である
    // （event.coordinatesを現在地に使わない＝現行バグの回帰ロック）
    @Test
    fun tRecVm2_currentLocation_derivedFromLocationServiceNotEventCoordinates() = runTest(testDispatcher) {
        val locationFromService = Coordinate(lat = 1.0, lon = 2.0)
        val eventCoordinates = Coordinate(lat = 35.0, lon = 139.0) // 現行バグはこちらを誤って使う
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(samplePlan(eventCoordinates = eventCoordinates)) }
        val engine = CapturingRecoveryEngine()
        val locationService = object : LocationService {
            override suspend fun currentLocation(timeout: Duration): LocationResult =
                LocationResult.Success(coordinate = locationFromService, accuracyMeters = 5f, fixedAt = Instant.now())
        }

        RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = locationService,
            clock = Clock.systemUTC()
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(locationFromService, engine.capturedContext?.currentLocation)
    }

    // T-RECVM-3: エッジケース - 位置取得がPermissionDenied/nullでもoptionsは生成される（D案のみ落ちる）
    @Test
    fun tRecVm3_locationPermissionDenied_stillProducesOptionsWithNullCurrentLocation() = runTest(testDispatcher) {
        val sharedPlanViewModel = SharedPlanViewModel().apply {
            confirmPlan(samplePlan(eventCoordinates = Coordinate(lat = 35.0, lon = 139.0)))
        }
        val engine = CapturingRecoveryEngine(result = RecoveryPlan(options = listOf(sampleOption())))
        val deniedLocationService = object : LocationService {
            override suspend fun currentLocation(timeout: Duration): LocationResult = LocationResult.PermissionDenied
        }

        val viewModel = RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = deniedLocationService,
            clock = Clock.systemUTC()
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(engine.capturedContext?.currentLocation)
        assertTrue(viewModel.uiState.value.options.isNotEmpty())
    }

    // T-RECVM-4: エッジケース - confirmedPlan == null → クラッシュせずoptions空＋案内状態になる
    @Test
    fun tRecVm4_noConfirmedPlan_doesNotCrashAndKeepsOptionsEmpty() = runTest(testDispatcher) {
        val sharedPlanViewModel = SharedPlanViewModel() // confirmedPlan未設定のまま
        val engine = CapturingRecoveryEngine()

        val viewModel = RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC()
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, engine.callCount)
        assertTrue(viewModel.uiState.value.options.isEmpty())
    }

    // T-RECVM-5: 異常系 - engineが例外を送出 → UiStateにエラー理由が現れ、握り潰されない
    @Test
    fun tRecVm5_engineThrows_doesNotSilentlySwallowFailure() = runTest(testDispatcher) {
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(samplePlan()) }
        val engine = ThrowingRecoveryEngine(RuntimeException("simulated engine failure"))

        val viewModel = RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC()
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // 汎用エラー欄が存在しないため、既定状態のまま留まっていない（=握り潰されていない）ことを
        // 最小限の形で固定する（ファイル冒頭KDoc「T-RECVM-5に関する限定事項」参照）。
        assertNotEquals(RecoveryUiState(), viewModel.uiState.value)
    }

    // T-RECVM-6: 正常系 - 「Use this plan」でRecoveryPlanApplierを経由しSharedPlanViewModel
    // .confirmPlanが呼ばれ、適用後のPlanが反映される（Fable 5裁定3）。
    // SharedPlanViewModelはfinalクラスでありspyできないため、呼び出し「回数」ではなく
    // confirmedPlan.valueが適用結果（option.estimatedArrivalへの転記・時刻再計算）へ実際に
    // 更新されることを観測可能な形で検証する。useThisPlan自体は早期returnガードのみで
    // 構成される直線的な処理のため、1回の呼び出しでconfirmPlanは高々1回しか呼ばれ得ない。
    @Test
    fun tRecVm6_useThisPlan_appliesSelectedOptionAndConfirmsUpdatedPlan() = runTest(testDispatcher) {
        val originalPlan = samplePlan()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(originalPlan) }
        val option = sampleOption() // skippedStepIds=空、estimatedArrival=2026-08-10T09:30:00Z
        val engine = CapturingRecoveryEngine(result = RecoveryPlan(options = listOf(option)))
        val fixedClock = Clock.fixed(Instant.parse("2026-08-10T09:12:00Z"), ZoneOffset.UTC)

        val viewModel = RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = fixedClock
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.useThisPlan(option.id)

        val confirmedAfterApply = sharedPlanViewModel.confirmedPlan.value
        assertNotEquals(originalPlan, confirmedAfterApply)
        // estimatedArrivalはoption.estimatedArrivalがそのまま転記される（裁定2の設計と整合）。
        assertEquals(option.estimatedArrival, confirmedAfterApply?.estimatedArrival)
        // departureTimeは注入Clock起点で再計算される（steps=空のためremainingPreparation=0）。
        assertEquals(Instant.parse("2026-08-10T09:12:00Z"), confirmedAfterApply?.departureTime)
    }

    // T-RECVM-7: 異常系 - 候補未選択（optionId=null）のまま確定操作 → 何も適用されず
    // confirmPlanが呼ばれない（§34）
    @Test
    fun tRecVm7_useThisPlanWithNullOptionId_doesNotApplyAnyPlan() = runTest(testDispatcher) {
        val originalPlan = samplePlan()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(originalPlan) }
        val option = sampleOption()
        val engine = CapturingRecoveryEngine(result = RecoveryPlan(options = listOf(option)))

        val viewModel = RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC()
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.useThisPlan(null)

        assertEquals(originalPlan, sharedPlanViewModel.confirmedPlan.value)
        assertFalse(viewModel.uiState.value.isPlanApplied)
    }

    // T-RECVM-8: 正常系 - 適用後は再突入抑止フラグ(isPlanApplied)が立つ（無限ループ防止、
    // §9エラーマップ#10）。同一フラグにより2回目のuseThisPlan呼び出しは何も適用しない
    // （one-shotガード）。
    @Test
    fun tRecVm8_afterApplyingPlan_setsIsPlanAppliedFlagAndGuardsAgainstReapplication() = runTest(testDispatcher) {
        val originalPlan = samplePlan()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(originalPlan) }
        val option = sampleOption()
        val engine = CapturingRecoveryEngine(result = RecoveryPlan(options = listOf(option)))

        val viewModel = RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC()
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.useThisPlan(option.id)
        assertTrue(viewModel.uiState.value.isPlanApplied)
        val planAfterFirstApply = sharedPlanViewModel.confirmedPlan.value

        viewModel.useThisPlan(option.id) // 2回目（one-shotガードにより無視されるべき）

        assertEquals(planAfterFirstApply, sharedPlanViewModel.confirmedPlan.value)
    }

    // T-RECVM-9（P5-C8補完・仕様§95接地・Fable 5承認2026-08-09）: 「Use this plan」適用で
    // notificationServiceが非nullなら、旧アラームのcancelAll(旧planのevent.id文字列)→
    // schedule(適用後の更新済みPlan)がこの順でそれぞれちょうど1回だけ呼ばれる。
    // `RecoveryViewModel.useThisPlan`本体（クラスKDoc「P6-C5統合ウィンドウ追加」参照）は
    // `docs/plans/phase6-recovery-basic.md`§10 P6-C5行の完了記録項目⑤（Fable 5裁定・
    // P5-C6申し送り③への回答）で既に実装済みのため、本テストは作成時点からGreenになる
    // 可能性が高い（「現状が誤っていることを示す」ことを目的としない回帰ガード。
    // T-P5UI-8・T-NOTIF-4/8/9・T-STORE-7と同型の許容ケース）。
    @Test
    fun tRecVm9_useThisPlan_cancelsOldAlarmsThenReschedulesUpdatedPlan_eachExactlyOnce() = runTest(testDispatcher) {
        val originalPlan = samplePlan()
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(originalPlan) }
        val option = sampleOption()
        val engine = CapturingRecoveryEngine(result = RecoveryPlan(options = listOf(option)))
        val notificationService = RecordingNotificationService()

        val viewModel = RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC(),
            notificationService = notificationService
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.useThisPlan(option.id)

        assertEquals(1, notificationService.cancelAllCallCount)
        assertEquals(1, notificationService.scheduleCallCount)
        assertEquals(originalPlan.event.id.toString(), notificationService.cancelledPlanId)
        assertEquals(sharedPlanViewModel.confirmedPlan.value, notificationService.scheduledPlan)
        assertEquals(listOf("cancelAll", "schedule"), notificationService.callOrder)
    }

    // ------------------------------------------------------------------
    // Phase 9（計画書`docs/plans/phase9-recovery-ai.md`§3.1・§7、オーケストレーター指摘A9、
    // ADR-0063想定）: stale-write防御（世代トークン）。[RecoveryViewModel.refresh]のAI差し替え
    // 分岐が`TODO()`のため、`NotImplementedError`によりRedになるのが正しい。
    // ------------------------------------------------------------------

    /** [refresh]の呼び出しごとに異なる[RecoveryPlan]を返すfake（1回目→A案のみ、2回目→D案のみ）。 */
    private class SequencedRecoveryEngine(private val results: List<RecoveryPlan>) : RecoveryEngine {
        var callCount = 0
            private set

        override suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan {
            val result = results.getOrElse(callCount) { results.last() }
            callCount += 1
            return result
        }
    }

    // T-P9-31: エッジ・ゲート - refresh()を2回呼び出し、1回目のrefresh()が起動したAI応答
    // （遅延応答）が2回目のrefresh()完了後に到着しても、2回目のbaseへ古いexplanationが
    // 適用されない（世代トークン不一致により無視される）
    @Test
    fun tP9_31_staleAiResponseFromFirstRefresh_doesNotOverwriteSecondRefreshsBasicPlan() = runTest(testDispatcher) {
        val optionA = sampleOption() // 1回目のrefresh()の候補（semanticAction=keep_all_steps）
        val optionD = RecoveryOption(
            id = UUID.randomUUID(),
            semanticAction = "change_transport_mode",
            title = "",
            explanation = "",
            estimatedArrival = Instant.parse("2026-08-10T09:20:00Z"),
            skippedStepIds = emptyList()
        )
        val engine = SequencedRecoveryEngine(
            results = listOf(
                RecoveryPlan(options = listOf(optionA)),
                RecoveryPlan(options = listOf(optionD))
            )
        )
        val sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(samplePlan()) }

        // 1回目のAI応答だけを遅延させ、2回目のrefresh()のBasic計算が先に完了する状況を作る
        // （AiGatewayTestFixtures.FakeLocalLanguageModel.delayMillisPerCallはgeneratePlan専用の
        // ため、generateRecovery用の遅延はrecoveryOutcomes経路の呼び出し内で明示的に模す）。
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.recoveryOptionsJson("keep_all_steps" to "Finish getting ready and leave when done.")
                )
            ),
            delayMillisPerCall = 5_000L
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = AiGatewayTestFixtures.installedModelStorage(),
            modelVerifier = ModelVerifierImpl(),
            deviceCapability = AiGatewayTestFixtures.supportedDeviceCapability(),
            preferences = AiGatewayTestFixtures.preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP9_31")
        )
        val contextualizer = LocalAiRecoveryContextualizer(gateway)

        val viewModel = RecoveryViewModel(
            recoveryEngine = engine,
            sharedPlanViewModel = sharedPlanViewModel,
            locationService = alwaysSuccessLocationService,
            clock = Clock.systemUTC(),
            aiRecoveryContextualizer = contextualizer
        )
        testDispatcher.scheduler.advanceUntilIdle() // 1回目のrefresh()（init由来）のBasic部分のみ進む

        viewModel.refresh() // 2回目（世代を進める）
        testDispatcher.scheduler.advanceUntilIdle() // 2回目のBasic部分＋1回目の遅延AI応答の両方が進む

        assertEquals(
            "2回目のrefresh()完了後は2回目のbase(D案)がuiStateに反映されているべきです(T-P9-31)",
            listOf(optionD.semanticAction),
            viewModel.uiState.value.options.map { it.semanticAction }
        )
        assertTrue(
            "1回目のrefresh()由来の遅延AI応答が2回目のbaseへ誤って適用されていないべきです" +
                "(explanationが空文字のまま=stale応答が無視された、T-P9-31)",
            viewModel.uiState.value.options.all { it.explanation.isEmpty() }
        )
    }
}
