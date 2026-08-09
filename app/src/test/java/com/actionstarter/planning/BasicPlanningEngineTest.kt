package com.actionstarter.planning

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.PersonalExecutionProfile
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * T-BPE-1〜27・29（計画書§8.2、`docs/plans/phase4-basic-engine.md`）。
 *
 * [BasicPlanningEngine.createPlan]は本ファイル作成時点（P4-C2）で本体が`TODO("P4-C3で実装")`
 * のため、以下は全件`NotImplementedError`によりRedになるのが正しい（意図した失敗。
 * [com.actionstarter.mock.MockPlanFactoryTest]のKDocと同じ趣旨）。T-BPE-20〜23（異常系）は
 * `TODO()`自体が`NotImplementedError`を送出するため`assertThrows(IllegalArgumentException::
 * class.java)` / `assertThrows(Exception::class.java)`は「期待する例外型と異なる」ことにより
 * 失敗し、これもRedとして扱う。
 *
 * T-BPE-28（`com.actionstarter.ai`・`LocalLanguageModel`非参照の構造ガード）は
 * [PlanningLlmIsolationTest]（別ファイル）が担当する。
 *
 * ## 設計メモ
 * - [planningContext]ヘルパーの既定値は「transition/preparation/travelがすべて生成される」
 *   標準ケース（transition 10分・preparation 20分・travel 20分・arrivalBuffer 10分、
 *   `now`はeventStartの3時間前）とし、各テストは必要な次元のみを名前付き引数で上書きする。
 * - [expectedTransitionStart]・[expectedDepartureTime]・[expectedEstimatedArrival]は
 *   仕様§13式をそのまま素朴に再実装したものであり、`travelEstimate == null`のときは
 *   TravelTime項を0として扱う（F44。20分等の固定値で穴埋めしない）。
 * - T-BPE-13の「Arrival Buffer既定値」云々（計画書G-1）は`PlanningContext.arrivalBuffer`が
 *   常に呼び出し側必須供給の非null値であり、`BasicPlanningEngine`自身が内部でデフォルト化する
 *   対象ではない（既定値10分の適用箇所は`PlanReviewViewModel`等の呼び出し側、計画書§6.2）。
 *   そのため本ファイルのT-BPE-13はtransition/preparationの既定値適用のみを検証する。
 * - T-BPE-22の「profile内の所要時間（averageTransitionDuration等）」は、
 *   `BasicPlanningEngine`が実際に読む2フィールド（averageTransitionDuration・
 *   averagePreparationDuration、計画書§7.3テンプレート表）に限定して検証する。
 *   `averageResponseDelay`等、本Phaseで未使用のフィールドは対象外とする。
 */
class BasicPlanningEngineTest {

    private val baseZoneId: ZoneId = ZoneId.of("UTC")
    private val baseLocale: Locale = Locale.US
    private val baseNow: Instant = Instant.parse("2026-08-10T07:00:00Z")

    private fun sampleEvent(
        startDate: Instant = Instant.parse("2026-08-10T10:00:00Z"),
        locationName: String? = "Shibuya",
        coordinates: Coordinate? = Coordinate(lat = 35.6595, lon = 139.7005)
    ): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = startDate,
        locationName = locationName,
        coordinates = coordinates,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun sampleProfile(
        transition: Duration = Duration.ofMinutes(10),
        preparation: Duration = Duration.ofMinutes(20)
    ): PersonalExecutionProfile = PersonalExecutionProfile(
        eventCategory = "default",
        averageTransitionDuration = transition,
        averagePreparationDuration = preparation,
        averageResponseDelay = Duration.ofMinutes(2),
        averageDepartureDelay = Duration.ofMinutes(2),
        preferredArrivalBuffer = Duration.ofMinutes(10)
    )

    private fun planningContext(
        event: ExecutionEvent = sampleEvent(),
        now: Instant = baseNow,
        zoneId: ZoneId = baseZoneId,
        locale: Locale = baseLocale,
        transportMode: TransportMode = TransportMode.WALKING,
        travelEstimate: Duration? = Duration.ofMinutes(20),
        arrivalBuffer: Duration = Duration.ofMinutes(10),
        profile: PersonalExecutionProfile? = sampleProfile()
    ): PlanningContext = PlanningContext(
        event = event,
        now = now,
        zoneId = zoneId,
        locale = locale,
        transportMode = transportMode,
        travelEstimate = travelEstimate,
        arrivalBuffer = arrivalBuffer,
        profile = profile
    )

    private fun resolvedTransitionDuration(profile: PersonalExecutionProfile?): Duration =
        profile?.averageTransitionDuration ?: BasicPlanningDefaults.TRANSITION

    private fun resolvedPreparationDuration(profile: PersonalExecutionProfile?): Duration =
        profile?.averagePreparationDuration ?: BasicPlanningDefaults.PREPARATION

    private fun effectiveTravel(travelEstimate: Duration?): Duration = travelEstimate ?: Duration.ZERO

    private fun expectedTransitionStart(ctx: PlanningContext): Instant = ctx.event.startDate
        .minus(ctx.arrivalBuffer)
        .minus(effectiveTravel(ctx.travelEstimate))
        .minus(resolvedPreparationDuration(ctx.profile))
        .minus(resolvedTransitionDuration(ctx.profile))

    private fun expectedDepartureTime(ctx: PlanningContext): Instant = ctx.event.startDate
        .minus(ctx.arrivalBuffer)
        .minus(effectiveTravel(ctx.travelEstimate))

    private fun expectedEstimatedArrival(ctx: PlanningContext): Instant =
        expectedDepartureTime(ctx).plus(effectiveTravel(ctx.travelEstimate))

    private fun expectedSemanticId(type: ExecutionStepType): String = when (type) {
        ExecutionStepType.TRANSITION -> "transition"
        ExecutionStepType.PREPARATION -> "preparation"
        ExecutionStepType.DEPARTURE -> "departure"
        ExecutionStepType.TRAVEL -> "travel"
    }

    private fun expectedStepId(event: ExecutionEvent, semanticId: String): UUID =
        UUID.nameUUIDFromBytes("${event.id}:$semanticId".toByteArray())

    // T-BPE-1: 正常系 - transitionStartが仕様§13式と一致する（旧T-MOCK-10）
    @Test
    fun tBpe1_transitionStart_matchesSection13Formula() = runTest {
        val ctx = planningContext(
            travelEstimate = Duration.ofMinutes(52),
            arrivalBuffer = Duration.ofMinutes(8),
            profile = sampleProfile()
        )

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertEquals(expectedTransitionStart(ctx), plan.transitionStart)
    }

    // T-BPE-2: 正常系 - departureTime = eventStart - arrivalBuffer - travelEstimate
    @Test
    fun tBpe2_departureTime_equalsEventStartMinusArrivalBufferMinusTravelEstimate() = runTest {
        val ctx = planningContext(travelEstimate = Duration.ofMinutes(30), arrivalBuffer = Duration.ofMinutes(15))

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertEquals(expectedDepartureTime(ctx), plan.departureTime)
    }

    // T-BPE-3: 正常系 - estimatedArrival = departureTime + travelEstimate
    @Test
    fun tBpe3_estimatedArrival_equalsDepartureTimePlusTravelEstimate() = runTest {
        val ctx = planningContext(travelEstimate = Duration.ofMinutes(30), arrivalBuffer = Duration.ofMinutes(15))

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertEquals(plan.departureTime.plus(Duration.ofMinutes(30)), plan.estimatedArrival)
    }

    // T-BPE-4: 正常系 - arrivalBufferは入力値がExecutionPlan.arrivalBufferへそのまま反映される
    @Test
    fun tBpe4_arrivalBuffer_reflectsContextInputValueDirectly() = runTest {
        val ctx = planningContext(arrivalBuffer = Duration.ofMinutes(17))

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertEquals(Duration.ofMinutes(17), plan.arrivalBuffer)
    }

    // T-BPE-5: 正常系 - 生成されたstepsの並び順が§48 enum順(TRANSITION→PREPARATION→DEPARTURE→
    // TRAVEL)と一致する（G-6。M4-8の逆順バグ修正の回帰ロック）
    @Test
    fun tBpe5_stepsOrder_matchesSection48EnumOrder() = runTest {
        val ctx = planningContext() // 既定でtransition/preparation/travelが全て正の値で揃う

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertEquals(
            listOf(
                ExecutionStepType.TRANSITION,
                ExecutionStepType.PREPARATION,
                ExecutionStepType.DEPARTURE,
                ExecutionStepType.TRAVEL
            ),
            plan.steps.map { it.type }
        )
    }

    // T-BPE-6: 正常系 - preparationステップのscheduledStart == transitionStart + transition所要時間
    @Test
    fun tBpe6_preparationStep_scheduledStartEqualsTransitionStartPlusTransitionDuration() = runTest {
        val ctx = planningContext(
            profile = sampleProfile(transition = Duration.ofMinutes(9), preparation = Duration.ofMinutes(21))
        )

        val plan = BasicPlanningEngine().createPlan(ctx)
        val preparationStep = plan.steps.single { it.type == ExecutionStepType.PREPARATION }

        assertEquals(plan.transitionStart.plus(Duration.ofMinutes(9)), preparationStep.scheduledStart)
    }

    // T-BPE-7: 正常系 - 各ExecutionStep.semanticIdがテンプレート種別ごとに固定である
    @Test
    fun tBpe7_semanticId_isFixedPerStepTypeForAllGeneratedSteps() = runTest {
        val ctx = planningContext()

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertTrue(plan.steps.isNotEmpty())
        plan.steps.forEach { step ->
            assertEquals(expectedSemanticId(step.type), step.semanticId)
        }
    }

    // T-BPE-8: 正常系 - 各ExecutionStep.titleが空文字である（G-4。localization解決はUI層の責務）
    @Test
    fun tBpe8_title_isEmptyStringForAllGeneratedSteps() = runTest {
        val ctx = planningContext()

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertTrue(plan.steps.isNotEmpty())
        assertTrue(plan.steps.all { it.title == "" })
    }

    // T-BPE-9: エッジケース - travelEstimate == null → TRAVELステップを生成せず、固定値で穴埋めしない
    @Test
    fun tBpe9_nullTravelEstimate_generatesNoTravelStep() = runTest {
        val ctx = planningContext(travelEstimate = null)

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertTrue(plan.steps.none { it.type == ExecutionStepType.TRAVEL })
    }

    // T-BPE-10: エッジケース - travelEstimate == Duration.ZERO → TRAVELステップを生成しない
    @Test
    fun tBpe10_zeroTravelEstimate_generatesNoTravelStep() = runTest {
        val ctx = planningContext(travelEstimate = Duration.ZERO)

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertTrue(plan.steps.none { it.type == ExecutionStepType.TRAVEL })
    }

    // T-BPE-11: エッジケース - 場所情報なし(locationName == null)でもtravelEstimateが非nullなら
    // TRAVEL生成判定に影響しない（BasicPlanningEngineはlocationName/coordinatesを参照しない
    // 設計の回帰ロック。旧T-MOCK-4とは逆の結論になる点に注意：§7.4の設計変更により
    // hasLocation判定自体が廃止された）
    @Test
    fun tBpe11_eventWithoutLocation_travelStepGenerationUnaffectedByLocationFields() = runTest {
        val eventWithoutLocation = sampleEvent(locationName = null, coordinates = null)
        val ctx = planningContext(event = eventWithoutLocation, travelEstimate = Duration.ofMinutes(15))

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertTrue(plan.steps.any { it.type == ExecutionStepType.TRAVEL })
    }

    // T-BPE-12: エッジケース - travelEstimate == nullのときDuration.ofMinutes(20)等の固定値が
    // 一切使われない（20分捏造の削除を直接検証する回帰ロック）
    @Test
    fun tBpe12_nullTravelEstimate_departureAndArrivalNeverReflectTwentyMinuteFabrication() = runTest {
        val ctx = planningContext(travelEstimate = null, arrivalBuffer = Duration.ofMinutes(10))

        val plan = BasicPlanningEngine().createPlan(ctx)

        // TravelTime項を0として算出する（§7.4）
        assertEquals(ctx.event.startDate.minus(ctx.arrivalBuffer), plan.departureTime)
        assertEquals(plan.departureTime, plan.estimatedArrival)
        // 旧MockPlanFactoryの20分捏造が紛れ込んでいないことを直接否定する
        assertNotEquals(
            ctx.event.startDate.minus(ctx.arrivalBuffer).minus(Duration.ofMinutes(20)),
            plan.departureTime
        )
    }

    // T-BPE-13: 正常系 - profile == nullのときBasicPlanningDefaultsの既定値
    // (transition 5分/preparation 15分)が適用される
    @Test
    fun tBpe13_nullProfile_appliesBasicPlanningDefaultsForTransitionAndPreparation() = runTest {
        val ctx = planningContext(profile = null, travelEstimate = Duration.ofMinutes(10), arrivalBuffer = Duration.ofMinutes(12))

        val plan = BasicPlanningEngine().createPlan(ctx)
        val transitionStep = plan.steps.single { it.type == ExecutionStepType.TRANSITION }
        val preparationStep = plan.steps.single { it.type == ExecutionStepType.PREPARATION }

        assertEquals(BasicPlanningDefaults.TRANSITION, transitionStep.estimatedDuration)
        assertEquals(BasicPlanningDefaults.PREPARATION, preparationStep.estimatedDuration)
        assertEquals(expectedTransitionStart(ctx), plan.transitionStart)
    }

    // T-BPE-14: 正常系 - profileが非nullのときprofile.averageTransitionDuration/
    // averagePreparationDurationが既定値より優先される
    @Test
    fun tBpe14_nonNullProfile_overridesBasicPlanningDefaults() = runTest {
        val profile = sampleProfile(transition = Duration.ofMinutes(3), preparation = Duration.ofMinutes(27))
        val ctx = planningContext(profile = profile, travelEstimate = Duration.ofMinutes(10), arrivalBuffer = Duration.ofMinutes(12))

        val plan = BasicPlanningEngine().createPlan(ctx)
        val transitionStep = plan.steps.single { it.type == ExecutionStepType.TRANSITION }
        val preparationStep = plan.steps.single { it.type == ExecutionStepType.PREPARATION }

        assertEquals(Duration.ofMinutes(3), transitionStep.estimatedDuration)
        assertEquals(Duration.ofMinutes(27), preparationStep.estimatedDuration)
        assertNotEquals(BasicPlanningDefaults.TRANSITION, transitionStep.estimatedDuration)
        assertNotEquals(BasicPlanningDefaults.PREPARATION, preparationStep.estimatedDuration)
    }

    // T-BPE-15: エッジケース - transition所要時間が0分に確定する場合
    // (profile.averageTransitionDuration == ZERO) → transitionステップが生成されない
    @Test
    fun tBpe15_zeroTransitionDurationFromProfile_generatesNoTransitionStep() = runTest {
        val profile = sampleProfile(transition = Duration.ZERO, preparation = Duration.ofMinutes(15))
        val ctx = planningContext(profile = profile, travelEstimate = Duration.ofMinutes(10))

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertTrue(plan.steps.none { it.type == ExecutionStepType.TRANSITION })
        assertTrue(plan.steps.any { it.type == ExecutionStepType.PREPARATION })
    }

    // T-BPE-16: エッジケース - preparation所要時間が0分に確定する場合
    // (profile.averagePreparationDuration == ZERO) → preparationステップが生成されない
    @Test
    fun tBpe16_zeroPreparationDurationFromProfile_generatesNoPreparationStep() = runTest {
        val profile = sampleProfile(transition = Duration.ofMinutes(5), preparation = Duration.ZERO)
        val ctx = planningContext(profile = profile, travelEstimate = Duration.ofMinutes(10))

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertTrue(plan.steps.none { it.type == ExecutionStepType.PREPARATION })
        assertTrue(plan.steps.any { it.type == ExecutionStepType.TRANSITION })
    }

    // T-BPE-17: エッジケース - transition・preparation・travelが全て0分/null条件に該当する場合、
    // 生成されるstepsはDEPARTURE1件のみになる
    @Test
    fun tBpe17_transitionPreparationAndTravelAllZeroOrNull_onlyDepartureStepRemains() = runTest {
        val profile = sampleProfile(transition = Duration.ZERO, preparation = Duration.ZERO)
        val ctx = planningContext(profile = profile, travelEstimate = null)

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertEquals(1, plan.steps.size)
        assertEquals(ExecutionStepType.DEPARTURE, plan.steps.single().type)
    }

    // T-BPE-18: 正常系 - transitionStart < now（逼迫）でもPlan生成は継続し、
    // ステップを自動省略しない（旧T-MOCK-7）
    @Test
    fun tBpe18_transitionStartBeforeNow_keepsAllStepsAndTransitionStartIsBeforeNow() = runTest {
        val fixedEvent = sampleEvent(startDate = Instant.parse("2026-08-10T10:00:00Z"))
        val travelEstimate = Duration.ofMinutes(20)
        val arrivalBuffer = Duration.ofMinutes(10)
        val profile = sampleProfile()
        val expectedTransitionStartInstant = fixedEvent.startDate
            .minus(arrivalBuffer)
            .minus(travelEstimate)
            .minus(profile.averagePreparationDuration)
            .minus(profile.averageTransitionDuration)

        fun ctxAt(now: Instant) = planningContext(
            event = fixedEvent,
            now = now,
            travelEstimate = travelEstimate,
            arrivalBuffer = arrivalBuffer,
            profile = profile
        )

        val roomyContext = ctxAt(now = expectedTransitionStartInstant.minus(Duration.ofHours(1)))
        val behindContext = ctxAt(now = expectedTransitionStartInstant.plus(Duration.ofHours(1)))

        val roomyPlan = BasicPlanningEngine().createPlan(roomyContext)
        val behindPlan = BasicPlanningEngine().createPlan(behindContext)

        assertFalse(roomyPlan.transitionStart.isBefore(roomyContext.now))
        assertTrue(behindPlan.transitionStart.isBefore(behindContext.now))
        assertEquals(roomyPlan.steps.size, behindPlan.steps.size)
    }

    // T-BPE-19: エッジケース - イベントのstartDateが既に過去でも、例外を送出せずPlanを生成する
    @Test
    fun tBpe19_eventStartDateAlreadyPast_doesNotThrowAndGeneratesPlan() = runTest {
        val pastEventStart = Instant.parse("2026-08-01T09:00:00Z")
        val ctx = planningContext(
            event = sampleEvent(startDate = pastEventStart),
            now = pastEventStart.plus(Duration.ofDays(1))
        )

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertEquals(expectedTransitionStart(ctx), plan.transitionStart)
        assertTrue(plan.transitionStart.isBefore(ctx.now))
    }

    // T-BPE-20: 異常系 - travelEstimateが負の値 → IllegalArgumentExceptionを送出する
    @Test
    fun tBpe20_negativeTravelEstimate_throwsIllegalArgumentException() {
        val ctx = planningContext(travelEstimate = Duration.ofMinutes(-1))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { BasicPlanningEngine().createPlan(ctx) }
        }
    }

    // T-BPE-21: 異常系 - arrivalBufferが負の値 → IllegalArgumentExceptionを送出する
    @Test
    fun tBpe21_negativeArrivalBuffer_throwsIllegalArgumentException() {
        val ctx = planningContext(arrivalBuffer = Duration.ofMinutes(-1))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { BasicPlanningEngine().createPlan(ctx) }
        }
    }

    // T-BPE-22: 異常系 - profile内の所要時間(averageTransitionDuration/averagePreparationDuration)
    // が負の値 → IllegalArgumentExceptionを送出する（BasicPlanningEngineが実際に読む2フィールドを検証）
    @Test
    fun tBpe22_negativeProfileDurations_throwsIllegalArgumentException() {
        val negativeTransitionCtx = planningContext(
            profile = sampleProfile(transition = Duration.ofMinutes(-1), preparation = Duration.ofMinutes(15))
        )
        val negativePreparationCtx = planningContext(
            profile = sampleProfile(transition = Duration.ofMinutes(5), preparation = Duration.ofMinutes(-1))
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { BasicPlanningEngine().createPlan(negativeTransitionCtx) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { BasicPlanningEngine().createPlan(negativePreparationCtx) }
        }
    }

    // T-BPE-23: 異常系 - Duration/Instant演算がオーバーフローする極端な入力 → 例外が握り潰されず
    // 呼び出し元へ伝播する（例外の型はArithmeticException/DateTimeExceptionいずれも許容。
    // §12未確認事項のため上位型Exceptionで受ける）
    @Test
    fun tBpe23_extremeInstantOverflow_propagatesExceptionWithoutSwallowing() {
        val ctx = planningContext(
            event = sampleEvent(startDate = Instant.MIN),
            travelEstimate = Duration.ofMinutes(20),
            arrivalBuffer = Duration.ofMinutes(10),
            profile = sampleProfile()
        )

        assertThrows(Exception::class.java) {
            runBlocking { BasicPlanningEngine().createPlan(ctx) }
        }
    }

    // T-BPE-24: エッジケース - DST切替を跨ぐeventStartでもInstant基準の演算が1時間ずれない
    @Test
    fun tBpe24_eventStartCrossingDstTransition_instantArithmeticDoesNotShiftByOneHour() = runTest {
        // 2026-08-09時点の実測: 米国の2026年DST開始は2026-03-08（2:00 AM local → 3:00 AM
        // local、America/New_Yorkゾーン）。eventStartをこの日の10:00 UTCに置き、
        // 5時間分（buffer 1h + travel 1h + preparation 2h + transition 1h）を差し引くと、
        // 遷移境界（07:00 UTC）を跨いでtransitionStartが算出される。
        val eventStart = Instant.parse("2026-03-08T10:00:00Z")
        val dstProfile = sampleProfile(transition = Duration.ofHours(1), preparation = Duration.ofHours(2))
        val ctx = planningContext(
            event = sampleEvent(startDate = eventStart),
            zoneId = ZoneId.of("America/New_York"),
            travelEstimate = Duration.ofHours(1),
            arrivalBuffer = Duration.ofHours(1),
            profile = dstProfile
        )

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertEquals(expectedTransitionStart(ctx), plan.transitionStart)
        assertEquals(expectedDepartureTime(ctx), plan.departureTime)
        assertEquals(expectedEstimatedArrival(ctx), plan.estimatedArrival)
    }

    // T-BPE-25: エッジケース - PlanningContext.zoneId/localeを変えても算出されるInstant値が
    // 変化しない（時刻演算はゾーン・ロケール非依存）
    @Test
    fun tBpe25_differentZoneIdAndLocale_producesIdenticalInstantResults() = runTest {
        val event = sampleEvent()
        val ctxUtcUs = planningContext(event = event, zoneId = ZoneId.of("UTC"), locale = Locale.US)
        val ctxTokyoJa = planningContext(event = event, zoneId = ZoneId.of("Asia/Tokyo"), locale = Locale.JAPAN)

        val planA = BasicPlanningEngine().createPlan(ctxUtcUs)
        val planB = BasicPlanningEngine().createPlan(ctxTokyoJa)

        assertEquals(planA.transitionStart, planB.transitionStart)
        assertEquals(planA.departureTime, planB.departureTime)
        assertEquals(planA.estimatedArrival, planB.estimatedArrival)
    }

    // T-BPE-26: 正常系 - ExecutionStep.idがUUID.nameUUIDFromBytes("${event.id}:$semanticId")相当の
    // 決定的値で生成され、同一入力での再planningでも同一idになる
    @Test
    fun tBpe26_stepId_isDeterministicFromEventIdAndSemanticId_stableAcrossReplanning() = runTest {
        val event = sampleEvent()
        val ctx = planningContext(event = event)
        val engine = BasicPlanningEngine()

        val planFirst = engine.createPlan(ctx)
        val planSecond = engine.createPlan(ctx)

        assertTrue(planFirst.steps.isNotEmpty())
        planFirst.steps.forEach { step ->
            assertEquals(expectedStepId(event, step.semanticId), step.id)
        }
        assertEquals(planFirst.steps.map { it.id }, planSecond.steps.map { it.id })
    }

    // T-BPE-27: 正常系 - 生成された全ステップのscheduledStartが非null
    @Test
    fun tBpe27_allGeneratedSteps_haveNonNullScheduledStart() = runTest {
        val ctx = planningContext()

        val plan = BasicPlanningEngine().createPlan(ctx)

        assertTrue(plan.steps.isNotEmpty())
        assertTrue(plan.steps.all { it.scheduledStart != null })
    }

    // T-BPE-29: 正常系 - transitionStart/departureTime/estimatedArrival/各ステップscheduledStart
    // が相互に矛盾しない（内部整合性の最終確認）
    @Test
    fun tBpe29_planFields_areMutuallyConsistent() = runTest {
        val profile = sampleProfile(transition = Duration.ofMinutes(8), preparation = Duration.ofMinutes(18))
        val ctx = planningContext(
            travelEstimate = Duration.ofMinutes(37),
            arrivalBuffer = Duration.ofMinutes(12),
            profile = profile
        )
        val plan = BasicPlanningEngine().createPlan(ctx)
        val transitionDuration = resolvedTransitionDuration(ctx.profile)
        val preparationDuration = resolvedPreparationDuration(ctx.profile)

        // transitionStart -> departureTime -> estimatedArrival の順序整合性
        assertFalse(plan.departureTime.isBefore(plan.transitionStart))
        assertFalse(plan.estimatedArrival.isBefore(plan.departureTime))

        // departureTime = transitionStart + transitionDuration + preparationDuration
        assertEquals(plan.departureTime, plan.transitionStart.plus(transitionDuration).plus(preparationDuration))
        // estimatedArrival = departureTime + travelEstimate
        assertEquals(plan.estimatedArrival, plan.departureTime.plus(effectiveTravel(ctx.travelEstimate)))

        // 各ステップのscheduledStartがPlan全体のフィールドと矛盾しない
        val transitionStep = plan.steps.single { it.type == ExecutionStepType.TRANSITION }
        val preparationStep = plan.steps.single { it.type == ExecutionStepType.PREPARATION }
        val departureStep = plan.steps.single { it.type == ExecutionStepType.DEPARTURE }
        val travelStep = plan.steps.single { it.type == ExecutionStepType.TRAVEL }

        assertEquals(plan.transitionStart, transitionStep.scheduledStart)
        assertEquals(plan.transitionStart.plus(transitionDuration), preparationStep.scheduledStart)
        assertEquals(plan.departureTime, departureStep.scheduledStart)
        assertEquals(plan.departureTime, travelStep.scheduledStart)
    }
}
