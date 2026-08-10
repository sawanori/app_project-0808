package com.actionstarter.ai

import android.os.StrictMode
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.typeOf

/**
 * Phase 8（計画書`docs/plans/phase8-ai-execution-wiring.md`§8）。[LocalAiPlanContextualizer]・
 * [overlay]のC2 Redテスト。T-P8-1・2・5〜12・14〜18・20・21・23・25（19件）を収容する
 * （T-P8-3/4/13/19/22/26はViewModelレベルのため`features.PlanReviewViewModelTest`、
 * T-P8-24は`ai.model.ModelStorageTest`）。
 *
 * **fakeの設計**: [AiGatewayTestFixtures]（同パッケージ）が確立した「[LocalLanguageModel]の
 * 境界のみfake化し、[LocalAiGateway]自体は実物をRobolectricで駆動する」既存規約
 * （[LocalAiGatewayTest]のKDoc「fake注入の設計」参照）を再利用する。
 *
 * **現状のRed原因（C2時点）**: [LocalAiPlanContextualizer.contextualize]・[overlay]がいずれも
 * `TODO()`のため、全件`NotImplementedError`によりRedになるのが正しい
 * （[com.actionstarter.planning.BasicPlanningEngineTest]と同じ確立された規約）。
 */
@RunWith(RobolectricTestRunner::class)
class LocalAiPlanContextualizerTest {

    // ------------------------------------------------------------------
    // domainフィクスチャ
    // ------------------------------------------------------------------

    private fun step(
        semanticId: String,
        type: ExecutionStepType,
        scheduledStart: Instant,
        title: String = "",
        duration: Duration = Duration.ofMinutes(5),
        priority: StepPriority = StepPriority.IMPORTANT,
        skippable: Boolean = false
    ): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = semanticId,
        type = type,
        title = title,
        estimatedDuration = duration,
        priority = priority,
        skippable = skippable,
        scheduledStart = scheduledStart,
        completedAt = null
    )

    private fun sampleEvent(title: String = "Dentist checkup"): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = title,
        notes = null,
        startDate = Instant.parse("2026-08-10T10:00:00Z"),
        locationName = "Shibuya Clinic",
        coordinates = null,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun basePlan(event: ExecutionEvent = sampleEvent(), steps: List<ExecutionStep>): ExecutionPlan = ExecutionPlan(
        event = event,
        steps = steps,
        transitionStart = Instant.parse("2026-08-10T09:00:00Z"),
        departureTime = Instant.parse("2026-08-10T09:40:00Z"),
        estimatedArrival = Instant.parse("2026-08-10T09:55:00Z"),
        arrivalBuffer = Duration.ofMinutes(10)
    )

    /** transition/preparation/departure/travel各1件の基本形（§7.3の全4 ExecutionStepTypeを網羅）。 */
    private fun canonicalFourStepPlan(event: ExecutionEvent = sampleEvent()): ExecutionPlan {
        val t0 = Instant.parse("2026-08-10T09:00:00Z")
        return basePlan(
            event = event,
            steps = listOf(
                step("transition", ExecutionStepType.TRANSITION, scheduledStart = t0),
                step("preparation", ExecutionStepType.PREPARATION, scheduledStart = t0.plusSeconds(600)),
                step(
                    "departure",
                    ExecutionStepType.DEPARTURE,
                    duration = Duration.ZERO,
                    priority = StepPriority.REQUIRED,
                    scheduledStart = t0.plusSeconds(2400)
                ),
                step(
                    "travel",
                    ExecutionStepType.TRAVEL,
                    priority = StepPriority.REQUIRED,
                    scheduledStart = t0.plusSeconds(2400)
                )
            )
        )
    }

    // ContentSanityChecker.isLocaleConsistent（§15逸脱の二重防御）はcontext.localeとdisplay_textの
    // 書記素を突き合わせる。本ファイルのgateway経由テスト（T-P8-1・20・21）はいずれも日本語の
    // display_textフィクスチャを使うため、localeもLocale.JAPANに揃える（Locale.USのままだと
    // 正しくSCHEMA_INVALIDへ判定される＝gateway側の実装は正しい。フィクスチャ側を合わせる）。
    private fun samplePlanningContext(event: ExecutionEvent): PlanningContext = PlanningContext(
        event = event,
        now = Instant.parse("2026-08-10T07:00:00Z"),
        zoneId = ZoneId.of("UTC"),
        locale = Locale.JAPAN,
        transportMode = TransportMode.WALKING,
        travelEstimate = Duration.ofMinutes(20),
        arrivalBuffer = Duration.ofMinutes(10),
        profile = null
    )

    // ------------------------------------------------------------------
    // 正常系
    // ------------------------------------------------------------------

    // T-P8-1: AI ON+DL済、gateway Success → 各stepのtitleが対応display_textへ差し替わる
    @Test
    fun tP8_1_aiOnAndInstalled_gatewaySuccess_eachStepTitleReplacedByDisplayText() = runTest {
        val event = sampleEvent()
        val base = canonicalFourStepPlan(event)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(
                AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(
                    AiGatewayTestFixtures.multiStepPlanJson(
                        "medical",
                        listOf(
                            "finish_current_task" to "作業を切り上げる",
                            "prepare_items" to "保険証を持って行く",
                            "leave" to "家を出る",
                            "commute" to "バスに乗る"
                        )
                    )
                )
            )
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_1")
        val contextualizer = LocalAiPlanContextualizer(gateway)

        val result = contextualizer.contextualize(base, samplePlanningContext(event))

        assertTrue("gateway Successならばresultは Applied のはずです: $result", result is ContextualizationResult.Applied)
        val plan = (result as ContextualizationResult.Applied).plan
        assertEquals("作業を切り上げる", plan.steps.single { it.type == ExecutionStepType.TRANSITION }.title)
        assertEquals("保険証を持って行く", plan.steps.single { it.type == ExecutionStepType.PREPARATION }.title)
        assertEquals("家を出る", plan.steps.single { it.type == ExecutionStepType.DEPARTURE }.title)
        assertEquals("バスに乗る", plan.steps.single { it.type == ExecutionStepType.TRAVEL }.title)
    }

    // T-P8-2: overlayマッピング（base側は各type1件の基本形）— 純粋関数を直接検証
    @Test
    fun tP8_2_overlay_eachCanonicalTypeReceivesCorrectActionTypeText() {
        val base = canonicalFourStepPlan()
        val ai = AIPlanResponse(
            eventType = "business_meeting",
            steps = listOf(
                AIPlanStepResponse("finish_current_task", "A"),
                AIPlanStepResponse("prepare_items", "B"),
                AIPlanStepResponse("leave", "C"),
                AIPlanStepResponse("commute", "D")
            )
        )

        val result = overlay(base, ai)

        assertEquals("A", result.steps.single { it.type == ExecutionStepType.TRANSITION }.title)
        assertEquals("B", result.steps.single { it.type == ExecutionStepType.PREPARATION }.title)
        assertEquals("C", result.steps.single { it.type == ExecutionStepType.DEPARTURE }.title)
        assertEquals("D", result.steps.single { it.type == ExecutionStepType.TRAVEL }.title)
    }

    // T-P8-5: locale（ja→日本語文言 / en→英語文言。overlayはgatewayが保証した文言をそのまま透過する）
    @Test
    fun tP8_5_localeSpecificDisplayTextPassesThroughUnchanged() {
        val base = canonicalFourStepPlan()
        val ja = overlay(
            base,
            AIPlanResponse("medical", listOf(AIPlanStepResponse("prepare_items", "保険証を持って行く")))
        )
        val en = overlay(
            base,
            AIPlanResponse("medical", listOf(AIPlanStepResponse("prepare_items", "Bring your insurance card")))
        )

        assertEquals("保険証を持って行く", ja.steps.single { it.type == ExecutionStepType.PREPARATION }.title)
        assertEquals("Bring your insurance card", en.steps.single { it.type == ExecutionStepType.PREPARATION }.title)
    }

    // ------------------------------------------------------------------
    // 異常系（5系統フォールバック、§5.2）
    // ------------------------------------------------------------------

    // T-P8-6: AI OFF → plan不変・AI_DISABLED・モデル未呼び出し
    @Test
    fun tP8_6_aiDisabled_planUnchanged_aiDisabledReason_modelNeverInvoked() = runTest {
        val event = sampleEvent()
        val base = canonicalFourStepPlan(event)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "X")))
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_6", aiEnabled = false)
        val contextualizer = LocalAiPlanContextualizer(gateway)

        val result = contextualizer.contextualize(base, samplePlanningContext(event))

        assertTrue(result is ContextualizationResult.Unchanged)
        assertEquals(AiFallbackReason.AI_DISABLED, (result as ContextualizationResult.Unchanged).reason)
        assertEquals(base, result.plan)
        assertEquals(0, model.callCount)
    }

    // T-P8-7: 未DL → MODEL_NOT_INSTALLED・plan不変
    @Test
    fun tP8_7_modelNotInstalled_planUnchanged_modelNotInstalledReason() = runTest {
        val event = sampleEvent()
        val base = canonicalFourStepPlan(event)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "X")))
        )
        val gateway = AiGatewayTestFixtures.readyGateway(
            model,
            prefsFileName = "p8_7",
            modelStorage = AiGatewayTestFixtures.notInstalledModelStorage()
        )
        val contextualizer = LocalAiPlanContextualizer(gateway)

        val result = contextualizer.contextualize(base, samplePlanningContext(event))

        assertTrue(result is ContextualizationResult.Unchanged)
        assertEquals(AiFallbackReason.MODEL_NOT_INSTALLED, (result as ContextualizationResult.Unchanged).reason)
        assertEquals(base, result.plan)
    }

    // T-P8-8: 生成失敗（retry後もSCHEMA_INVALID）→ plan不変・2回呼ばれる（1回目Primary+2回目Retry）
    @Test
    fun tP8_8_schemaInvalidAfterRetry_planUnchanged_schemaInvalidReason() = runTest {
        val event = sampleEvent()
        val base = canonicalFourStepPlan(event)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.schemaInvalidNineStepResponse()))
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_8")
        val contextualizer = LocalAiPlanContextualizer(gateway)

        val result = contextualizer.contextualize(base, samplePlanningContext(event))

        assertTrue(result is ContextualizationResult.Unchanged)
        assertEquals(AiFallbackReason.SCHEMA_INVALID, (result as ContextualizationResult.Unchanged).reason)
        assertEquals(base, result.plan)
        assertEquals("Primary+Retryの2回呼ばれるはずです", 2, model.callCount)
    }

    // T-P8-9: TIMEOUT → plan不変・リトライなし（1回のみ呼ばれる）
    @Test
    fun tP8_9_timeout_planUnchanged_timeoutReason_noRetry() = runTest {
        val event = sampleEvent()
        val base = canonicalFourStepPlan(event)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "X"))),
            delayMillisPerCall = 25_000L
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_9")
        val contextualizer = LocalAiPlanContextualizer(gateway)

        val result = contextualizer.contextualize(base, samplePlanningContext(event))

        assertTrue(result is ContextualizationResult.Unchanged)
        assertEquals(AiFallbackReason.TIMEOUT, (result as ContextualizationResult.Unchanged).reason)
        assertEquals(base, result.plan)
        assertEquals("TIMEOUTはretry対象外のため1回のみ呼ばれるはずです", 1, model.callCount)
    }

    // T-P8-10: OOM_PREVENTED（能動ガード・モデル未呼び出し）／OUT_OF_MEMORY（二次防御）
    @Test
    fun tP8_10_outOfMemoryPreventedAndOutOfMemory_bothFallBackWithoutCrash() = runTest {
        val eventA = sampleEvent()
        val baseA = canonicalFourStepPlan(eventA)
        val modelA = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "X")))
        )
        val gatewayA = AiGatewayTestFixtures.readyGateway(
            modelA,
            prefsFileName = "p8_10a",
            deviceCapability = AiGatewayTestFixtures.lowMemoryDeviceCapability()
        )
        val resultA = LocalAiPlanContextualizer(gatewayA).contextualize(baseA, samplePlanningContext(eventA))
        assertTrue(resultA is ContextualizationResult.Unchanged)
        assertEquals(AiFallbackReason.OUT_OF_MEMORY_PREVENTED, (resultA as ContextualizationResult.Unchanged).reason)
        assertEquals("OOM事前ガード発動時はモデル呼び出しが0回のはずです", 0, modelA.callCount)

        val eventB = sampleEvent()
        val baseB = canonicalFourStepPlan(eventB)
        val modelB = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.ThrowError(OutOfMemoryError("fake native alloc failure")))
        )
        val gatewayB = AiGatewayTestFixtures.readyGateway(modelB, prefsFileName = "p8_10b")
        val resultB = LocalAiPlanContextualizer(gatewayB).contextualize(baseB, samplePlanningContext(eventB))
        assertTrue(resultB is ContextualizationResult.Unchanged)
        assertEquals(AiFallbackReason.OUT_OF_MEMORY, (resultB as ContextualizationResult.Unchanged).reason)
    }

    // T-P8-11: MODEL_CORRUPTED（ロード前再検証・サイズ不一致）→ plan不変
    @Test
    fun tP8_11_modelCorrupted_planUnchanged_modelCorruptedReason() = runTest {
        val event = sampleEvent()
        val base = canonicalFourStepPlan(event)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "X")))
        )
        val gateway = AiGatewayTestFixtures.readyGateway(
            model,
            prefsFileName = "p8_11",
            modelStorage = AiGatewayTestFixtures.corruptedModelStorage()
        )
        val contextualizer = LocalAiPlanContextualizer(gateway)

        val result = contextualizer.contextualize(base, samplePlanningContext(event))

        assertTrue(result is ContextualizationResult.Unchanged)
        assertEquals(AiFallbackReason.MODEL_CORRUPTED, (result as ContextualizationResult.Unchanged).reason)
        assertEquals(base, result.plan)
    }

    // T-P8-12: UNSUPPORTED_DEVICE（RAM不足）／UNSUPPORTED_ABI（arm64-v8a非搭載）
    @Test
    fun tP8_12_unsupportedDeviceAndUnsupportedAbi_bothFallBackWithoutCrash() = runTest {
        val eventA = sampleEvent()
        val baseA = canonicalFourStepPlan(eventA)
        val modelA = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "X")))
        )
        val gatewayA = AiGatewayTestFixtures.readyGateway(
            modelA,
            prefsFileName = "p8_12a",
            deviceCapability = AiGatewayTestFixtures.unsupportedRamDeviceCapability()
        )
        val resultA = LocalAiPlanContextualizer(gatewayA).contextualize(baseA, samplePlanningContext(eventA))
        assertTrue(resultA is ContextualizationResult.Unchanged)
        assertEquals(AiFallbackReason.UNSUPPORTED_DEVICE, (resultA as ContextualizationResult.Unchanged).reason)
        assertEquals(0, modelA.callCount)

        val eventB = sampleEvent()
        val baseB = canonicalFourStepPlan(eventB)
        val modelB = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "X")))
        )
        val gatewayB = AiGatewayTestFixtures.readyGateway(
            modelB,
            prefsFileName = "p8_12b",
            deviceCapability = AiGatewayTestFixtures.unsupportedAbiDeviceCapability()
        )
        val resultB = LocalAiPlanContextualizer(gatewayB).contextualize(baseB, samplePlanningContext(eventB))
        assertTrue(resultB is ContextualizationResult.Unchanged)
        assertEquals(AiFallbackReason.UNSUPPORTED_ABI, (resultB as ContextualizationResult.Unchanged).reason)
        assertEquals(0, modelB.callCount)
    }

    // ------------------------------------------------------------------
    // エッジ
    // ------------------------------------------------------------------

    // T-P8-14: §13不変条件（最重要）— title以外の全step/planフィールドが不変。
    // 同一ExecutionStepType（PREPARATION）のstepがbase内に複数存在するfixtureを含む。
    @Test
    fun tP8_14_overlay_onlyTitleChanges_allOtherFieldsUnchanged_includingDuplicateTypeFixture() {
        val event = sampleEvent()
        val t0 = Instant.parse("2026-08-10T09:00:00Z")
        val base = basePlan(
            event = event,
            steps = listOf(
                step("transition", ExecutionStepType.TRANSITION, scheduledStart = t0),
                step("preparation-1", ExecutionStepType.PREPARATION, scheduledStart = t0.plusSeconds(600)),
                step("preparation-2", ExecutionStepType.PREPARATION, scheduledStart = t0.plusSeconds(900)),
                step(
                    "departure",
                    ExecutionStepType.DEPARTURE,
                    duration = Duration.ZERO,
                    priority = StepPriority.REQUIRED,
                    scheduledStart = t0.plusSeconds(2400)
                ),
                step(
                    "travel",
                    ExecutionStepType.TRAVEL,
                    priority = StepPriority.REQUIRED,
                    scheduledStart = t0.plusSeconds(2400)
                )
            )
        )
        val ai = AIPlanResponse(
            eventType = "medical",
            steps = listOf(
                AIPlanStepResponse("finish_current_task", "T"),
                AIPlanStepResponse("prepare_items", "P1"),
                AIPlanStepResponse("get_ready", "P2"),
                AIPlanStepResponse("leave", "D"),
                AIPlanStepResponse("commute", "TR")
            )
        )

        val result = overlay(base, ai)

        assertEquals(base.event, result.event)
        assertEquals(base.transitionStart, result.transitionStart)
        assertEquals(base.departureTime, result.departureTime)
        assertEquals(base.estimatedArrival, result.estimatedArrival)
        assertEquals(base.arrivalBuffer, result.arrivalBuffer)
        assertEquals(base.steps.size, result.steps.size)
        assertEquals(base.steps.map { it.id }, result.steps.map { it.id })

        base.steps.zip(result.steps).forEach { (expected, actual) ->
            assertEquals(expected.id, actual.id)
            assertEquals(expected.semanticId, actual.semanticId)
            assertEquals(expected.type, actual.type)
            assertEquals(expected.estimatedDuration, actual.estimatedDuration)
            assertEquals(expected.priority, actual.priority)
            assertEquals(expected.skippable, actual.skippable)
            assertEquals(expected.scheduledStart, actual.scheduledStart)
            assertEquals(expected.completedAt, actual.completedAt)
        }
    }

    // T-P8-15: AIが同一typeへ複数action_typeを返す（base側は該当type1件）
    // → queueByTypeに複数積まれるが消費は1回のみ＝AI出力順先頭のみ採用、残りは捨てられる
    @Test
    fun tP8_15_multipleActionTypesMapToSameType_baseHasOneStep_firstAiOrderTextWins() {
        val base = canonicalFourStepPlan()
        val ai = AIPlanResponse(
            eventType = "medical",
            steps = listOf(
                AIPlanStepResponse("prepare_items", "First"),
                AIPlanStepResponse("get_ready", "Second"),
                AIPlanStepResponse("gather_belongings", "Third")
            )
        )

        val result = overlay(base, ai)

        assertEquals("First", result.steps.single { it.type == ExecutionStepType.PREPARATION }.title)
        assertEquals(base.steps.size, result.steps.size)
    }

    // T-P8-16: AIがARRIVEを返す → 対応step無しで捨てる。step数不変
    @Test
    fun tP8_16_aiReturnsArrive_hasNoMatchingStep_discardedWithoutAffectingOthers() {
        val base = canonicalFourStepPlan()
        val ai = AIPlanResponse(
            eventType = "medical",
            steps = listOf(
                AIPlanStepResponse("arrive", "到着"),
                AIPlanStepResponse("prepare_items", "保険証")
            )
        )

        val result = overlay(base, ai)

        assertEquals(base.steps.size, result.steps.size)
        assertEquals("保険証", result.steps.single { it.type == ExecutionStepType.PREPARATION }.title)
    }

    // T-P8-17: Basic側でtransition/travel非生成＋AIが該当action_type → マッチ先無しで捨て、他stepは正常overlay
    @Test
    fun tP8_17_baseOmitsTransitionAndTravel_matchingAiTextDiscarded_otherStepsOverlayNormally() {
        val t0 = Instant.parse("2026-08-10T09:00:00Z")
        val base = basePlan(
            steps = listOf(
                step("preparation", ExecutionStepType.PREPARATION, scheduledStart = t0),
                step(
                    "departure",
                    ExecutionStepType.DEPARTURE,
                    duration = Duration.ZERO,
                    priority = StepPriority.REQUIRED,
                    scheduledStart = t0.plusSeconds(600)
                )
            )
        )
        val ai = AIPlanResponse(
            eventType = "medical",
            steps = listOf(
                AIPlanStepResponse("finish_current_task", "捨てられる"),
                AIPlanStepResponse("prepare_items", "保険証"),
                AIPlanStepResponse("leave", "家を出る"),
                AIPlanStepResponse("commute", "捨てられる2")
            )
        )

        val result = overlay(base, ai)

        assertEquals(2, result.steps.size)
        assertEquals("保険証", result.steps.single { it.type == ExecutionStepType.PREPARATION }.title)
        assertEquals("家を出る", result.steps.single { it.type == ExecutionStepType.DEPARTURE }.title)
    }

    // T-P8-18: AIが一部typeのみcarve（例: departure文言なし）→ そのstepはtitle=""のまま
    @Test
    fun tP8_18_aiOmitsSomeTypes_thatStepTitleStaysBlank_perStepDegradation() {
        val base = canonicalFourStepPlan()
        val ai = AIPlanResponse(
            eventType = "medical",
            steps = listOf(AIPlanStepResponse("prepare_items", "保険証"))
        )

        val result = overlay(base, ai)

        assertEquals("保険証", result.steps.single { it.type == ExecutionStepType.PREPARATION }.title)
        assertEquals("", result.steps.single { it.type == ExecutionStepType.DEPARTURE }.title)
        assertEquals("", result.steps.single { it.type == ExecutionStepType.TRANSITION }.title)
        assertEquals("", result.steps.single { it.type == ExecutionStepType.TRAVEL }.title)
    }

    // T-P8-20: PII非送信 — contextualize 1回をStrictMode.detectNetwork().penaltyDeath()下で通過（§10 L2相当）
    @Test
    fun tP8_20_contextualizeUnderStrictModeNetworkPenaltyDeath_completesWithoutNetworkAccess() = runTest {
        val event = sampleEvent()
        val base = canonicalFourStepPlan(event)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "保険証")))
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_20")
        val contextualizer = LocalAiPlanContextualizer(gateway)

        val originalPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
        try {
            val result = contextualizer.contextualize(base, samplePlanningContext(event))
            assertTrue(
                "無通信のためStrictMode network penaltyDeathを回避してAppliedに到達するはずです(§10 L2相当): $result",
                result is ContextualizationResult.Applied
            )
        } finally {
            StrictMode.setThreadPolicy(originalPolicy)
        }
    }

    // T-P8-21: メトリクス非PII — AiMetrics/ログにイベントtitle/場所/座標が現れない（T-AIMET-1再利用＋overlay経路確認）
    @Test
    fun tP8_21_metricsFromOverlayPath_haveNoStringTypedFieldsAndCannotCarryEventPii() = runTest {
        val event = sampleEvent(title = "Alice's Confidential Meeting")
        val base = canonicalFourStepPlan(event)
        val model = AiGatewayTestFixtures.FakeLocalLanguageModel(
            listOf(AiGatewayTestFixtures.FakeLocalLanguageModel.Outcome.Respond(AiGatewayTestFixtures.singleStepPlanJson("prepare_items", "保険証")))
        )
        val gateway = AiGatewayTestFixtures.readyGateway(model, prefsFileName = "p8_21")
        val contextualizer = LocalAiPlanContextualizer(gateway)

        val result = contextualizer.contextualize(base, samplePlanningContext(event))

        assertTrue(result is ContextualizationResult.Applied)
        val stringType: KType = typeOf<String>()
        val stringTypedFields = AiMetrics::class.memberProperties.filter { it.returnType == stringType }
        assertTrue(
            "AiMetricsにString型フィールドが存在します(自由文・PII混入のリスク、T-AIMET-1再利用): " +
                stringTypedFields.map { it.name },
            stringTypedFields.isEmpty()
        )
    }

    // T-P8-23: overlay冪等性 — overlay(overlay(base,ai),ai) == overlay(base,ai)
    @Test
    fun tP8_23_overlayIsIdempotent_reapplyingSameResponseYieldsSameResult() {
        val base = canonicalFourStepPlan()
        val ai = AIPlanResponse(
            eventType = "medical",
            steps = listOf(
                AIPlanStepResponse("finish_current_task", "A"),
                AIPlanStepResponse("prepare_items", "B"),
                AIPlanStepResponse("leave", "C"),
                AIPlanStepResponse("commute", "D")
            )
        )

        val once = overlay(base, ai)
        val twice = overlay(once, ai)

        assertEquals(once, twice)
    }

    // T-P8-25: 同一ExecutionStepType（PREPARATION）のbase stepが複数存在 → 各stepが異なるAI文言へ1対1対応する
    @Test
    fun tP8_25_duplicateStepTypeInBase_eachStepReceivesDistinctAiTextOneToOne() {
        val t0 = Instant.parse("2026-08-10T09:00:00Z")
        val prep1 = step("preparation-1", ExecutionStepType.PREPARATION, scheduledStart = t0)
        val prep2 = step("preparation-2", ExecutionStepType.PREPARATION, scheduledStart = t0.plusSeconds(300))
        val base = basePlan(steps = listOf(prep1, prep2))

        val aiTwo = AIPlanResponse(
            eventType = "medical",
            steps = listOf(AIPlanStepResponse("prepare_items", "A"), AIPlanStepResponse("get_ready", "B"))
        )
        val resultTwo = overlay(base, aiTwo)
        assertEquals("A", resultTwo.steps[0].title)
        assertEquals("B", resultTwo.steps[1].title)
        assertNotEquals(
            "2件のbase stepが同一文言を重複して受け取ってはいけません(T-P8-25)",
            resultTwo.steps[0].title,
            resultTwo.steps[1].title
        )

        val aiOne = AIPlanResponse(
            eventType = "medical",
            steps = listOf(AIPlanStepResponse("prepare_items", "OnlyOne"))
        )
        val resultOne = overlay(base, aiOne)
        assertEquals("OnlyOne", resultOne.steps[0].title)
        assertEquals(
            "AI文言が1件のみのとき2件目のbase stepはtitle=\"\"のままのはずです(T-P8-25)",
            "",
            resultOne.steps[1].title
        )
    }
}
