package com.actionstarter.recovery

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.services.routing.RoutingException
import com.actionstarter.services.routing.RoutingService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-BRE-1〜31（計画書§8.2、`docs/plans/phase6-recovery-basic.md`。T-BRE-32は構造ガードのため
 * 別ファイル[RecoveryLlmIsolationTest]が担当）。
 *
 * [BasicRecoveryEngine.createRecoveryPlan]は本ファイル作成時点（P6-C2）で本体が
 * `TODO("P6-C3で実装")`のため、以下は原則すべて`NotImplementedError`によりRedになるのが正しい
 * （意図した失敗。[com.actionstarter.mock.MockRecoveryFactoryTest]・
 * [com.actionstarter.planning.BasicPlanningEngineTest]と同じ趣旨）。T-BRE-27・T-BRE-30
 * （異常系）は`TODO()`自体が`NotImplementedError`を送出するため`assertThrows`が「期待する例外型と
 * 異なる」ことにより失敗し、これもRedとして扱う（[com.actionstarter.planning.BasicPlanningEngineTest]
 * のT-BPE-20/23と同じ扱い）。
 *
 * ## 既知の限定事項（P6-C2 test-writerによる差し戻し事項。完了報告参照）
 * - **T-BRE-17**: 計画書§7.5は「失敗理由を`RecoveryPlan`に載せてUIへ伝える」と規定するが、
 *   `domain/model/RecoveryPlan.kt`（P6-C1完了時点で未変更）には失敗理由を保持するフィールドが
 *   存在しない。本テストは「D案が生成されず他案はそのまま返る」部分のみをコンパイル可能な形で
 *   検証し、「失敗理由が`RecoveryPlan`に載る」部分は対象外とする（フィールド追加はsrc/main変更に
 *   あたりtest-writerの権限外）。
 *   **P6-C3追補（Fable 5裁定1、V-3解消）**: `RecoveryPlan.routingFailureReason: String?`を
 *   デフォルト値付きで追加した（`domain/model/RecoveryPlan.kt`）ため、本テストを
 *   「失敗理由が`RecoveryPlan`に載る」側も検証する完全な形へ拡張した。
 * - **T-BRE-24**: §7.3のsortKey第4要素`ruleOrdinal`は、A/D（省略件数0同士）はD案生成ガード
 *   `V' < V`（狭義不等号）によりETAが必ず異なるため、また B/C・B/D・C/Dは省略件数（|K|）が
 *   構造的に一致しないため、正規の入力からは「feasible・|K|・ETAが完全に一致する異なるルール同士」
 *   を作れない（分析済み）。本テストはA/D間でfeasible・|K|が一致しつつETAで順序が決まる
 *   近似ケースを検証する（ETA差を作るため`ruleOrdinal`自体の分岐は実際には踏まない）。
 */
class BasicRecoveryEngineTest {

    private val baseCurrentTime: Instant = Instant.parse("2026-08-10T09:00:00Z")
    private val baseEventStart: Instant = Instant.parse("2026-08-10T10:00:00Z")
    private val originCoordinate = Coordinate(lat = 35.6586, lon = 139.7454)
    private val destinationCoordinate = Coordinate(lat = 35.6595, lon = 139.7005)

    private val knownSemanticActions = setOf(
        "keep_all_steps",
        "skip_optional_steps",
        "skip_optional_and_important_steps",
        "change_transport_mode"
    )

    private fun sampleEvent(
        startDate: Instant = baseEventStart,
        coordinates: Coordinate? = destinationCoordinate
    ): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = startDate,
        locationName = "Shibuya",
        coordinates = coordinates,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun step(
        semanticId: String,
        type: ExecutionStepType = ExecutionStepType.PREPARATION,
        duration: Duration = Duration.ofMinutes(10),
        priority: StepPriority = StepPriority.OPTIONAL,
        skippable: Boolean = true,
        completedAt: Instant? = null
    ): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = semanticId,
        type = type,
        title = "",
        estimatedDuration = duration,
        priority = priority,
        skippable = skippable,
        scheduledStart = baseCurrentTime,
        completedAt = completedAt
    )

    private fun context(
        currentTime: Instant = baseCurrentTime,
        currentLocation: Coordinate? = originCoordinate,
        event: ExecutionEvent = sampleEvent(),
        unfinishedSteps: List<ExecutionStep> = emptyList(),
        latestTravelEstimate: Duration = Duration.ofMinutes(20),
        plannedDepartureTime: Instant = baseEventStart.minus(Duration.ofMinutes(20))
    ): RecoveryContext = RecoveryContext(
        currentTime = currentTime,
        currentLocation = currentLocation,
        event = event,
        unfinishedSteps = unfinishedSteps,
        latestTravelEstimate = latestTravelEstimate,
        plannedDepartureTime = plannedDepartureTime
    )

    /** D案（移動手段変更）ガードが構造的に届かないはずのテストで使う、呼ばれたら即失敗するfake。 */
    private object NeverCalledRoutingService : RoutingService {
        override suspend fun estimateRoute(
            origin: Coordinate,
            destination: Coordinate,
            mode: TransportMode,
            departureDate: Instant
        ): RouteEstimate = error(
            "RoutingService must not be called in this scenario (BasicRecoveryEngineTest fixture guard)"
        )
    }

    /** 呼び出し回数を記録するfake（T-BRE-13/14/18/19/20/21/22/23/24用）。 */
    private class RecordingRoutingService(
        private val onEstimateRoute: (Coordinate, Coordinate, TransportMode, Instant) -> RouteEstimate
    ) : RoutingService {
        var callCount = 0
            private set

        override suspend fun estimateRoute(
            origin: Coordinate,
            destination: Coordinate,
            mode: TransportMode,
            departureDate: Instant
        ): RouteEstimate {
            callCount++
            return onEstimateRoute(origin, destination, mode, departureDate)
        }
    }

    private class ThrowingRoutingService(private val exception: RoutingException) : RoutingService {
        var callCount = 0
            private set

        override suspend fun estimateRoute(
            origin: Coordinate,
            destination: Coordinate,
            mode: TransportMode,
            departureDate: Instant
        ): RouteEstimate {
            callCount++
            throw exception
        }
    }

    private fun fixedEstimate(duration: Duration, mode: TransportMode): RouteEstimate =
        RouteEstimate(duration = duration, mode = mode, computedAt = baseCurrentTime)

    // T-BRE-1: 正常系 - A案（keep_all_steps）が常に生成される
    @Test
    fun tBre1_alwaysGeneratesKeepAllStepsOption() = runTest {
        val ctx = context() // 既定で feasible(A) = true（オンスケジュール）
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.any { it.semanticAction == "keep_all_steps" })
    }

    // T-BRE-2: 正常系 - ETA(A) = currentTime + R_all + latestTravelEstimate と一致する
    @Test
    fun tBre2_etaOfKeepAllSteps_matchesCurrentTimePlusRAllPlusTravelEstimate() = runTest {
        val transitionStep = step("transition", type = ExecutionStepType.TRANSITION, duration = Duration.ofMinutes(5))
        val preparationStep = step("preparation", type = ExecutionStepType.PREPARATION, duration = Duration.ofMinutes(10))
        val ctx = context(
            event = sampleEvent(startDate = Instant.parse("2026-08-10T09:35:00Z")),
            unfinishedSteps = listOf(transitionStep, preparationStep),
            latestTravelEstimate = Duration.ofMinutes(20)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        val aOption = plan.options.single { it.semanticAction == "keep_all_steps" }
        assertEquals(Instant.parse("2026-08-10T09:35:00Z"), aOption.estimatedArrival)
    }

    // T-BRE-3: 正常系 - R_allはTRANSITION/PREPARATIONのみの合計（DEPARTURE/TRAVELを含まない）
    @Test
    fun tBre3_rAll_excludesDepartureAndTravelStepDurations() = runTest {
        val transitionStep = step("transition", type = ExecutionStepType.TRANSITION, duration = Duration.ofMinutes(5))
        val preparationStep = step("preparation", type = ExecutionStepType.PREPARATION, duration = Duration.ofMinutes(10))
        val departureStep = step("departure", type = ExecutionStepType.DEPARTURE, duration = Duration.ofMinutes(3))
        val travelStep = step("travel", type = ExecutionStepType.TRAVEL, duration = Duration.ofMinutes(99))
        val ctx = context(
            event = sampleEvent(startDate = Instant.parse("2026-08-10T09:35:00Z")),
            unfinishedSteps = listOf(transitionStep, preparationStep, departureStep, travelStep),
            latestTravelEstimate = Duration.ofMinutes(20)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        val aOption = plan.options.single { it.semanticAction == "keep_all_steps" }
        // DEPARTURE(3分)・TRAVEL(99分)を含めば09:35から大きくずれるはずだが、含めないため09:35のまま
        assertEquals(Instant.parse("2026-08-10T09:35:00Z"), aOption.estimatedArrival)
    }

    // T-BRE-4: エッジケース - completedAt != null のステップはR_allから除外される
    @Test
    fun tBre4_completedSteps_excludedFromRAll() = runTest {
        val remainingStep = step("remaining", duration = Duration.ofMinutes(10), completedAt = null)
        val doneStep = step("done", duration = Duration.ofMinutes(8), completedAt = baseCurrentTime)
        val ctx = context(
            event = sampleEvent(startDate = Instant.parse("2026-08-10T09:30:00Z")),
            unfinishedSteps = listOf(remainingStep, doneStep),
            latestTravelEstimate = Duration.ofMinutes(20)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        val aOption = plan.options.single { it.semanticAction == "keep_all_steps" }
        assertEquals(Instant.parse("2026-08-10T09:30:00Z"), aOption.estimatedArrival)
    }

    // T-BRE-5: 正常系 - 逼迫度0: ETA(A) <= eventStart のときoptionsはA案1件のみ
    @Test
    fun tBre5_feasibleKeepAllSteps_producesOnlyOneOption() = runTest {
        val skippableOptional = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(10))
        val ctx = context(
            event = sampleEvent(startDate = Instant.parse("2026-08-10T09:35:00Z")),
            unfinishedSteps = listOf(skippableOptional),
            latestTravelEstimate = Duration.ofMinutes(10)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        assertEquals(1, plan.options.size)
        assertEquals("keep_all_steps", plan.options.single().semanticAction)
    }

    // T-BRE-6: 正常系 - 逼迫度小: ETA(A) > E かつ skippable OPTIONAL あり → B案生成
    // Fable 5承認済みfixture修正（2026-08-09）: D案ガード（§7.3）が独立成立しfakeが誤爆していたため、
    // T-BRE-9〜12と同型のcurrentLocation=nullでD案をスコープ外へ。アサーション無変更
    @Test
    fun tBre6_infeasibleKeepAllStepsWithSkippableOptional_generatesSkipOptionalOption() = runTest {
        val skippableOptional = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(20))
        val ctx = context(
            currentLocation = null,
            unfinishedSteps = listOf(skippableOptional),
            latestTravelEstimate = Duration.ofMinutes(50) // ETA(A)=09:00+20+50=10:10 > E(10:00)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        val bOption = plan.options.singleOrNull { it.semanticAction == "skip_optional_steps" }
        assertTrue("B案(skip_optional_steps)が生成されていません: ${plan.options}", bOption != null)
        assertEquals(Instant.parse("2026-08-10T09:50:00Z"), bOption!!.estimatedArrival)
    }

    // T-BRE-7: エッジケース - skippable OPTIONALが0件ならB案を生成しない
    // Fable 5承認済みfixture修正（2026-08-09）: D案ガード（§7.3）が独立成立しfakeが誤爆していたため、
    // T-BRE-9〜12と同型のcurrentLocation=nullでD案をスコープ外へ。アサーション無変更
    @Test
    fun tBre7_noSkippableOptionalSteps_doesNotGenerateSkipOptionalOption() = runTest {
        val nonSkippableImportant = step(
            "important_fixed",
            priority = StepPriority.IMPORTANT,
            skippable = false,
            duration = Duration.ofMinutes(40)
        )
        val ctx = context(
            currentLocation = null,
            unfinishedSteps = listOf(nonSkippableImportant),
            latestTravelEstimate = Duration.ofMinutes(30) // ETA(A)=09:00+40+30=10:10 > E(10:00)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.none { it.semanticAction == "skip_optional_steps" })
    }

    // T-BRE-8: エッジケース - skippable == false のOPTIONALは省略集合に入らない
    // Fable 5承認済みfixture修正（2026-08-09）: D案ガード（§7.3）が独立成立しfakeが誤爆していたため、
    // T-BRE-9〜12と同型のcurrentLocation=nullでD案をスコープ外へ。アサーション無変更
    @Test
    fun tBre8_nonSkippableOptionalStep_neverIncludedInSkipSet() = runTest {
        val nonSkippableOptional = step(
            "optional_fixed",
            priority = StepPriority.OPTIONAL,
            skippable = false,
            duration = Duration.ofMinutes(15)
        )
        val skippableOptional = step(
            "optional_free",
            priority = StepPriority.OPTIONAL,
            skippable = true,
            duration = Duration.ofMinutes(5)
        )
        val ctx = context(
            currentLocation = null,
            unfinishedSteps = listOf(nonSkippableOptional, skippableOptional),
            latestTravelEstimate = Duration.ofMinutes(50) // ETA(A)=09:00+20+50=10:10 > E(10:00)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        val bOption = plan.options.single { it.semanticAction == "skip_optional_steps" }
        assertEquals(listOf(skippableOptional.id), bOption.skippedStepIds)
        assertFalse(nonSkippableOptional.id in bOption.skippedStepIds)
    }

    // T-BRE-9: 正常系 - 逼迫度大: ETA(B) > E のときC案（OPTIONAL+IMPORTANT省略）を生成
    @Test
    fun tBre9_infeasibleAfterSkippingOptionalOnly_generatesSkipOptionalAndImportantOption() = runTest {
        val optionalStep = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(5))
        val importantStep = step("important", priority = StepPriority.IMPORTANT, skippable = true, duration = Duration.ofMinutes(20))
        val requiredStep = step("required", priority = StepPriority.REQUIRED, skippable = false, duration = Duration.ofMinutes(5))
        val ctx = context(
            currentLocation = null, // D案を本テストの関心事から外す
            unfinishedSteps = listOf(optionalStep, importantStep, requiredStep),
            latestTravelEstimate = Duration.ofMinutes(40)
            // R_all=30分。ETA(A)=09:00+30+40=10:10(infeasible)。ETA(B)=09:00+25+40=10:05(infeasible)。
            // ETA(C)=09:00+5+40=09:45(feasible)。
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        val cOption = plan.options.singleOrNull { it.semanticAction == "skip_optional_and_important_steps" }
        assertTrue("C案が生成されていません: ${plan.options}", cOption != null)
        assertEquals(setOf(optionalStep.id, importantStep.id), cOption!!.skippedStepIds.toSet())
    }

    // T-BRE-10: エッジケース - ETA(B) <= E（B案で成立）ならC案を生成しない（過剰省略の禁止・§33）
    @Test
    fun tBre10_feasibleAfterSkippingOptionalOnly_doesNotGenerateSkipOptionalAndImportantOption() = runTest {
        val optionalStep = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(25))
        val requiredStep = step("required", priority = StepPriority.REQUIRED, skippable = false, duration = Duration.ofMinutes(10))
        val ctx = context(
            currentLocation = null,
            unfinishedSteps = listOf(optionalStep, requiredStep),
            latestTravelEstimate = Duration.ofMinutes(30)
            // R_all=35分。ETA(A)=09:00+35+30=10:05(infeasible)。ETA(B)=09:00+10+30=09:40(feasible)。
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.any { it.semanticAction == "skip_optional_steps" })
        assertTrue(plan.options.none { it.semanticAction == "skip_optional_and_important_steps" })
    }

    // T-BRE-11: 異常系 - REQUIREDステップのidがいずれのskippedStepIdsにも含まれない（旧T-DM-9移設）
    @Test
    fun tBre11_requiredStepId_neverAppearsInAnyOptionsSkippedStepIds() = runTest {
        val requiredStep = step("required", priority = StepPriority.REQUIRED, skippable = false, duration = Duration.ofMinutes(10))
        val optionalStep = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(15))
        val importantStep = step("important", priority = StepPriority.IMPORTANT, skippable = true, duration = Duration.ofMinutes(20))
        val ctx = context(
            currentLocation = null,
            unfinishedSteps = listOf(requiredStep, optionalStep, importantStep),
            latestTravelEstimate = Duration.ofMinutes(40)
            // R_all=45分。ETA(A)=10:25(infeasible)。ETA(B)=10:10(infeasible)。ETA(C)=09:50(feasible)。
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        val allSkippedIds = plan.options.flatMap { it.skippedStepIds }
        assertTrue(
            "REQUIREDステップ(${requiredStep.id})がskippedStepIdsに含まれています: $allSkippedIds",
            requiredStep.id !in allSkippedIds
        )
    }

    // T-BRE-12: 異常系 - skippable == false かつ IMPORTANTのidもskippedStepIdsに含まれない
    @Test
    fun tBre12_nonSkippableImportantStepId_neverAppearsInAnyOptionsSkippedStepIds() = runTest {
        val nonSkippableImportant = step(
            "important_fixed",
            priority = StepPriority.IMPORTANT,
            skippable = false,
            duration = Duration.ofMinutes(20)
        )
        val optionalStep = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(10))
        val requiredStep = step("required", priority = StepPriority.REQUIRED, skippable = false, duration = Duration.ofMinutes(5))
        val ctx = context(
            currentLocation = null,
            unfinishedSteps = listOf(nonSkippableImportant, optionalStep, requiredStep),
            latestTravelEstimate = Duration.ofMinutes(40)
            // R_all=35分。ETA(A)=10:15(infeasible)。ETA(B)=10:05(infeasible、dur(B)=10のためCは
            // dur(C)>dur(B)を満たせず生成されない想定だが、本テストの関心はid非混入のみ）。
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        val allSkippedIds = plan.options.flatMap { it.skippedStepIds }
        assertTrue(
            "skippable=falseのIMPORTANTステップ(${nonSkippableImportant.id})がskippedStepIdsに" +
                "含まれています: $allSkippedIds",
            nonSkippableImportant.id !in allSkippedIds
        )
    }

    // T-BRE-13: 正常系 - fake RoutingServiceが現行より短い所要を返すときD案が生成される
    @Test
    fun tBre13_shorterAlternativeRoute_generatesChangeTransportModeOption() = runTest {
        val ctx = context(
            latestTravelEstimate = Duration.ofMinutes(70) // ETA(A)=09:00+0+70=10:10(infeasible)
        )
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(40), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        val plan = engine.createRecoveryPlan(ctx)

        val dOption = plan.options.singleOrNull { it.semanticAction == "change_transport_mode" }
        assertTrue("D案が生成されていません: ${plan.options}", dOption != null)
        assertEquals(Instant.parse("2026-08-10T09:40:00Z"), dOption!!.estimatedArrival)
    }

    // T-BRE-14: エッジケース - fakeが同等または長い所要を返すときD案を生成しない
    @Test
    fun tBre14_equalOrLongerAlternativeRoute_doesNotGenerateChangeTransportModeOption() = runTest {
        val ctxBase = context(latestTravelEstimate = Duration.ofMinutes(70))

        val equalFake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(70), mode) }
        val equalPlan = BasicRecoveryEngine(routingService = equalFake).createRecoveryPlan(ctxBase)
        assertTrue(equalPlan.options.none { it.semanticAction == "change_transport_mode" })

        val longerFake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(90), mode) }
        val longerPlan = BasicRecoveryEngine(routingService = longerFake).createRecoveryPlan(ctxBase)
        assertTrue(longerPlan.options.none { it.semanticAction == "change_transport_mode" })
    }

    // T-BRE-15: エッジケース - currentLocation == null のときD案のみ落ち、A/B/Cは生成される
    @Test
    fun tBre15_nullCurrentLocation_dropsOnlyChangeTransportModeOption() = runTest {
        val optionalStep = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(20))
        val ctx = context(
            currentLocation = null,
            unfinishedSteps = listOf(optionalStep),
            latestTravelEstimate = Duration.ofMinutes(50) // ETA(A)=10:10(infeasible), ETA(B)=09:50(feasible)
        )
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(10), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.any { it.semanticAction == "keep_all_steps" })
        assertTrue(plan.options.any { it.semanticAction == "skip_optional_steps" })
        assertTrue(plan.options.none { it.semanticAction == "change_transport_mode" })
        assertEquals(0, fake.callCount)
    }

    // T-BRE-16: エッジケース - event.coordinates == null のときD案を生成しない（座標を捏造しない）
    @Test
    fun tBre16_nullEventCoordinates_doesNotGenerateChangeTransportModeOption() = runTest {
        val ctx = context(
            event = sampleEvent(coordinates = null),
            latestTravelEstimate = Duration.ofMinutes(70) // ETA(A)=10:10(infeasible)
        )
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(10), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.none { it.semanticAction == "change_transport_mode" })
        assertEquals(0, fake.callCount)
    }

    // T-BRE-17: 異常系 - RoutingServiceがRoutingExceptionを送出 → D案なしで他案を返し、
    // 失敗理由がRecoveryPlanに載る（握り潰さない。P6-C3でRecoveryPlan.routingFailureReasonを
    // 追加したため完全な形へ拡張。ファイル冒頭KDoc「既知の限定事項」P6-C3追補参照）
    @Test
    fun tBre17_routingExceptionFromDelegate_returnsOtherOptionsWithoutChangeTransportModeOptionAndRecordsFailureReason() = runTest {
        val optionalStep = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(20))
        val ctx = context(
            unfinishedSteps = listOf(optionalStep),
            latestTravelEstimate = Duration.ofMinutes(50) // ETA(A)=10:10(infeasible), ETA(B)=09:50(feasible)
        )
        val exception = RoutingException.Offline(cause = null)
        val throwingFake = ThrowingRoutingService(exception)
        val engine = BasicRecoveryEngine(routingService = throwingFake)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.none { it.semanticAction == "change_transport_mode" })
        assertTrue(plan.options.any { it.semanticAction == "keep_all_steps" })
        assertTrue(plan.options.any { it.semanticAction == "skip_optional_steps" })
        // 失敗理由がRecoveryPlanに載る（握り潰さない。計画書§7.5・§9エラーマップ#4、Fable 5裁定1）。
        // RoutingException.messageは§58/§60によりサニタイズ済み（座標・APIキー・イベント情報を
        // 含まない）と保証されているため、そのまま転記される契約とする。
        assertEquals(exception.message, plan.routingFailureReason)
    }

    // T-BRE-18: 正常系 - createRecoveryPlan 1回あたりRoutingService呼び出しは最大1回
    @Test
    fun tBre18_atMostOneRoutingServiceCallPerCreateRecoveryPlan() = runTest {
        val ctx = context(latestTravelEstimate = Duration.ofMinutes(70)) // ETA(A)=10:10(infeasible)
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(10), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        engine.createRecoveryPlan(ctx)

        assertTrue("RoutingService呼び出し回数が1回を超えています: ${fake.callCount}", fake.callCount <= 1)
    }

    // T-BRE-19: 正常系 - A案が成立している場合、RoutingServiceを1回も呼ばない（§95.2コスト方針）
    @Test
    fun tBre19_feasibleKeepAllSteps_neverCallsRoutingService() = runTest {
        val ctx = context() // 既定でfeasible(A)=true
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(10), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        engine.createRecoveryPlan(ctx)

        assertEquals(0, fake.callCount)
    }

    private fun allFourRulesContext(): RecoveryContext {
        val optionalStep = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(5))
        val importantStep = step("important", priority = StepPriority.IMPORTANT, skippable = true, duration = Duration.ofMinutes(20))
        val requiredStep = step("required", priority = StepPriority.REQUIRED, skippable = false, duration = Duration.ofMinutes(5))
        return context(
            unfinishedSteps = listOf(optionalStep, importantStep, requiredStep),
            latestTravelEstimate = Duration.ofMinutes(40)
            // R_all=30分。ETA(A)=10:10(infeasible)。ETA(B)=10:05(infeasible)。ETA(C)=09:45(feasible)。
            // ETA(D、V'=25分)=09:55(feasible)。A/B/C/Dすべてのガードが成立する。
        )
    }

    // T-BRE-20: 正常系 - 4案すべての生成条件が揃っても options.size <= 3（§32）
    @Test
    fun tBre20_allFourRuleGuardsSatisfied_optionsCountNeverExceedsThree() = runTest {
        val ctx = allFourRulesContext()
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(25), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue("options件数が3を超えています: ${plan.options.size}", plan.options.size <= 3)
    }

    // T-BRE-21: 正常系 - 4案生成条件下でもA案が必ずoptionsに含まれる（§34）
    @Test
    fun tBre21_allFourRuleGuardsSatisfied_keepAllStepsOptionAlwaysIncluded() = runTest {
        val ctx = allFourRulesContext()
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(25), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.any { it.semanticAction == "keep_all_steps" })
    }

    private fun feasibleChangeTransportAndSkipOptionalContext(): RecoveryContext {
        val optionalStep = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(20))
        return context(
            unfinishedSteps = listOf(optionalStep),
            latestTravelEstimate = Duration.ofMinutes(45)
            // R_all=20分。ETA(A)=10:05(infeasible)。ETA(B)=09:45(feasible)。ETA(D、V'=35分)=09:55(feasible)。
        )
    }

    // T-BRE-22: 正常系 - 並び順: feasibleな案が!feasibleな案より前
    @Test
    fun tBre22_feasibleOptions_sortBeforeInfeasibleOptions() = runTest {
        val ctx = feasibleChangeTransportAndSkipOptionalContext()
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(35), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        val plan = engine.createRecoveryPlan(ctx)
        val order = plan.options.map { it.semanticAction }

        val aIndex = order.indexOf("keep_all_steps")
        assertTrue("A案がoptionsに存在しません: $order", aIndex >= 0)
        assertTrue(
            "feasibleなchange_transport_modeがinfeasibleなkeep_all_stepsより後ろにあります: $order",
            order.indexOf("change_transport_mode").let { it in 0 until aIndex }
        )
        assertTrue(
            "feasibleなskip_optional_stepsがinfeasibleなkeep_all_stepsより後ろにあります: $order",
            order.indexOf("skip_optional_steps").let { it in 0 until aIndex }
        )
    }

    // T-BRE-23: 正常系 - 並び順: feasible同士は省略件数の少ない案が前（D案がB案より前に来る）
    @Test
    fun tBre23_amongFeasibleOptions_fewerSkippedStepsSortsFirst() = runTest {
        val ctx = feasibleChangeTransportAndSkipOptionalContext()
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(35), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        val plan = engine.createRecoveryPlan(ctx)
        val order = plan.options.map { it.semanticAction }

        val dIndex = order.indexOf("change_transport_mode")
        val bIndex = order.indexOf("skip_optional_steps")
        assertTrue("D案またはB案がoptionsに存在しません: $order", dIndex >= 0 && bIndex >= 0)
        assertTrue("D案(省略0件)がB案(省略1件)より前に来ていません: $order", dIndex < bIndex)
    }

    // T-BRE-24: エッジケース - ETA・省略件数が完全同値でもruleOrdinal（A<B<C<D）で順序が一意
    // （ファイル冒頭KDoc「既知の限定事項」参照: 正規の入力ではruleOrdinal自体が最終決定要因になる
    // 場面は作れないため、feasible・|K|が一致しETAで順序が決まる近似ケースで検証する）
    @Test
    fun tBre24_sameFeasibilityAndSkipCount_keepAllStepsAndChangeTransportModeOrderIsDeterministic() = runTest {
        val ctx = context(latestTravelEstimate = Duration.ofMinutes(70)) // ETA(A)=10:10(infeasible), |K|=0
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(69), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        val plan = engine.createRecoveryPlan(ctx)
        val order = plan.options.map { it.semanticAction }

        // D(ETA=10:09,infeasible,|K|=0) と A(ETA=10:10,infeasible,|K|=0) は同一feasibleバケット・
        // 同一|K|であり、ETAで一意に順序が決まる（Dが先）。
        assertEquals(listOf("change_transport_mode", "keep_all_steps"), order)
    }

    // T-BRE-25: 正常系 - 同一RecoveryContextを2回渡すとRecoveryOption.idを含め完全同一の結果
    // （決定的id。idシード="${event.id}:${semanticAction}:${skippedStepIds.sorted().joinToString(",")}"、
    // estimatedArrivalは非含有）
    @Test
    fun tBre25_sameContextTwice_producesIdenticalResultsIncludingDeterministicIds() = runTest {
        val optionalStep = step("optional", priority = StepPriority.OPTIONAL, skippable = true, duration = Duration.ofMinutes(5))
        val importantStep = step("important", priority = StepPriority.IMPORTANT, skippable = true, duration = Duration.ofMinutes(20))
        val requiredStep = step("required", priority = StepPriority.REQUIRED, skippable = false, duration = Duration.ofMinutes(5))
        val ctx = context(
            currentLocation = null,
            unfinishedSteps = listOf(optionalStep, importantStep, requiredStep),
            latestTravelEstimate = Duration.ofMinutes(40)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val planFirst = engine.createRecoveryPlan(ctx)
        val planSecond = engine.createRecoveryPlan(ctx)

        assertEquals(planFirst.options, planSecond.options)
        assertEquals(planFirst.options.map { it.id }, planSecond.options.map { it.id })

        // idシードの規則自体を直接検証する（A案: skippedStepIds=空）
        val expectedAId = UUID.nameUUIDFromBytes("${ctx.event.id}:keep_all_steps:".toByteArray())
        val aOption = planFirst.options.single { it.semanticAction == "keep_all_steps" }
        assertEquals(expectedAId, aOption.id)
    }

    // T-BRE-26: 正常系 - 全RecoveryOptionのtitle/explanationが空文字、semanticActionが既定4キーのいずれか
    @Test
    fun tBre26_allOptions_haveEmptyTitleAndExplanationAndKnownSemanticAction() = runTest {
        val ctx = allFourRulesContext()
        val fake = RecordingRoutingService { _, _, mode, _ -> fixedEstimate(Duration.ofMinutes(25), mode) }
        val engine = BasicRecoveryEngine(routingService = fake)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.isNotEmpty())
        assertTrue(plan.options.all { it.title == "" })
        assertTrue(plan.options.all { it.explanation == "" })
        assertTrue(plan.options.all { it.semanticAction in knownSemanticActions })
    }

    // T-BRE-27: 異常系 - latestTravelEstimateが負 → IllegalArgumentException
    @Test
    fun tBre27_negativeLatestTravelEstimate_throwsIllegalArgumentException() {
        val ctx = context(latestTravelEstimate = Duration.ofMinutes(-1))
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.createRecoveryPlan(ctx) }
        }
    }

    // T-BRE-28: エッジケース - currentTime > event.startDate（開始済み）でも例外を送出せず生成する
    // Fable 5承認済みfixture修正（2026-08-09）: D案ガード（§7.3）が独立成立しfakeが誤爆していたため、
    // T-BRE-9〜12と同型のcurrentLocation=nullでD案をスコープ外へ。アサーション無変更
    @Test
    fun tBre28_eventAlreadyStarted_doesNotThrowAndGeneratesPlan() = runTest {
        val pastEventStart = Instant.parse("2026-08-10T08:00:00Z")
        val ctx = context(
            currentLocation = null,
            event = sampleEvent(startDate = pastEventStart),
            currentTime = pastEventStart.plus(Duration.ofMinutes(30))
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.isNotEmpty())
    }

    // T-BRE-29: エッジケース - DST切替を跨ぐeventStartでもInstant基準演算が1時間ずれない
    @Test
    fun tBre29_eventStartCrossingDstTransition_instantArithmeticDoesNotShiftByOneHour() = runTest {
        // 2026年米国DST開始は2026-03-08（America/New_York）。RecoveryContextにゾーン情報は
        // 存在しないため、Instant演算がゾーン非依存であることの回帰ロックとして機能する。
        val eventStart = Instant.parse("2026-03-08T10:00:00Z")
        val currentTime = Instant.parse("2026-03-08T09:00:00Z")
        val ctx = context(
            currentTime = currentTime,
            event = sampleEvent(startDate = eventStart),
            latestTravelEstimate = Duration.ofMinutes(20)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        val aOption = plan.options.single { it.semanticAction == "keep_all_steps" }
        assertEquals(currentTime.plus(Duration.ofMinutes(20)), aOption.estimatedArrival)
    }

    // T-BRE-30: 異常系 - Instant.MIN等でオーバーフローする入力 → 例外が握り潰されず伝播
    @Test
    fun tBre30_extremeInstantOverflow_propagatesExceptionWithoutSwallowing() {
        val ctx = context(
            currentTime = Instant.MAX.minus(Duration.ofSeconds(1)),
            latestTravelEstimate = Duration.ofDays(365_000)
        )
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        assertThrows(Exception::class.java) {
            runBlocking { engine.createRecoveryPlan(ctx) }
        }
    }

    // T-BRE-31: エッジケース - unfinishedStepsが空でもA案1件を生成（optionsが0件にならない）
    @Test
    fun tBre31_emptyUnfinishedSteps_stillGeneratesKeepAllStepsOption() = runTest {
        val ctx = context(unfinishedSteps = emptyList())
        val engine = BasicRecoveryEngine(routingService = NeverCalledRoutingService)

        val plan = engine.createRecoveryPlan(ctx)

        assertTrue(plan.options.isNotEmpty())
        assertTrue(plan.options.any { it.semanticAction == "keep_all_steps" })
    }
}
