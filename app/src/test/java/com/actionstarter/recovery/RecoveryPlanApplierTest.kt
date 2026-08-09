package com.actionstarter.recovery

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * T-APPLY-1〜7（計画書§8.4、`docs/plans/phase6-recovery-basic.md`）。
 *
 * ## P6-C3追補: 再計算式の確定（Fable 5裁定2、承認済み期待値更新）
 * P6-C2 test-writerが暫定採用した解釈（`estimatedArrival`＝新departureTime＋元Planの移動時間）は、
 * D案（`change_transport_mode`）選択時に元Planの（変更前の遅い）移動時間をそのまま使い、
 * D案が実現するはずの短縮された移動時間を無視してしまう構造的な誤りを含むことが判明した
 * （`RecoveryPlanApplier`は`RoutingService`を持たず新たな移動時間を独自に問い合わせられないため、
 * 元Planの移動時間を流用するとD案の効果が消える）。[RecoveryOption.estimatedArrival]は
 * `BasicRecoveryEngine`が構成ごとに権威的に計算済みの値（D案ならRoutingServiceの代替見積りを
 * 反映済み）であり、UIにも同じ値が表示される（§32）ため、これを採用する側が正しいと判断した
 * （`RecoveryPlanApplier.kt`のクラスKDoc「再計算式の確定」参照）。
 *
 * これに伴い、T-APPLY-2／T-APPLY-4の期待値を以下のとおり承認済み変更として更新した
 * （`option()`ヘルパーに`estimatedArrival`引数を追加し、両ケースで明示的な値を渡す）:
 * - `estimatedArrival`（新） = `option.estimatedArrival`（そのまま転記。option側が権威的な値を
 *   保持するため再計算しない）
 * - `departureTime`（新） = `clock.instant()` + Σ(除去後に残るTRANSITION/PREPARATIONの
 *   `estimatedDuration`、`completedAt != null`のものは除く) — §7.2のR_all定義と同型
 *   （この部分はP6-C2の解釈のまま変更していない。option側に対応する権威的な値が存在しないため）。
 */
class RecoveryPlanApplierTest {

    private val fixedNow: Instant = Instant.parse("2026-08-10T09:05:00Z")
    private val testClock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val event: ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = Instant.parse("2026-08-10T10:00:00Z"),
        locationName = "Shibuya",
        coordinates = Coordinate(lat = 35.6595, lon = 139.7005),
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun step(
        type: ExecutionStepType,
        duration: Duration,
        priority: StepPriority,
        skippable: Boolean,
        scheduledStart: Instant
    ): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = type.name.lowercase(),
        type = type,
        title = "",
        estimatedDuration = duration,
        priority = priority,
        skippable = skippable,
        scheduledStart = scheduledStart,
        completedAt = null
    )

    private fun option(
        skippedStepIds: List<UUID> = emptyList(),
        estimatedArrival: Instant? = null
    ): RecoveryOption = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = if (skippedStepIds.isEmpty()) "keep_all_steps" else "skip_optional_steps",
        title = "",
        explanation = "",
        estimatedArrival = estimatedArrival,
        skippedStepIds = skippedStepIds
    )

    // 標準フィクスチャ: transition(5分)・prep1(OPTIONAL,skippable,10分)・prep2(IMPORTANT,skippable,8分)・
    // departure(REQUIRED)・travel(REQUIRED,25分)。元Planのdeparture=08:50・arrival=09:15
    // （移動時間25分）・arrivalBuffer=15分。
    private val transitionStep = step(ExecutionStepType.TRANSITION, Duration.ofMinutes(5), StepPriority.REQUIRED, false, Instant.parse("2026-08-10T08:37:00Z"))
    private val prepStep1 = step(ExecutionStepType.PREPARATION, Duration.ofMinutes(10), StepPriority.OPTIONAL, true, Instant.parse("2026-08-10T08:42:00Z"))
    private val prepStep2 = step(ExecutionStepType.PREPARATION, Duration.ofMinutes(8), StepPriority.IMPORTANT, true, Instant.parse("2026-08-10T08:42:00Z"))
    private val departureStep = step(ExecutionStepType.DEPARTURE, Duration.ZERO, StepPriority.REQUIRED, false, Instant.parse("2026-08-10T08:50:00Z"))
    private val travelStep = step(ExecutionStepType.TRAVEL, Duration.ofMinutes(25), StepPriority.REQUIRED, false, Instant.parse("2026-08-10T08:50:00Z"))

    private fun standardPlan(): ExecutionPlan = ExecutionPlan(
        event = event,
        steps = listOf(transitionStep, prepStep1, prepStep2, departureStep, travelStep),
        transitionStart = Instant.parse("2026-08-10T08:37:00Z"),
        departureTime = Instant.parse("2026-08-10T08:50:00Z"),
        estimatedArrival = Instant.parse("2026-08-10T09:15:00Z"), // departureTime + 25分
        arrivalBuffer = Duration.ofMinutes(15)
    )

    // T-APPLY-1: 正常系 - 選択案のskippedStepIdsに対応するステップがExecutionPlan.stepsから除去される
    @Test
    fun tApply1_removesStepsMatchingSkippedStepIds() {
        val plan = standardPlan()
        val applier = RecoveryPlanApplier(clock = testClock)

        val applied = applier.apply(plan, option(skippedStepIds = listOf(prepStep1.id)))

        assertTrue(prepStep1.id !in applied.steps.map { it.id })
        assertEquals(
            setOf(transitionStep.id, prepStep2.id, departureStep.id, travelStep.id),
            applied.steps.map { it.id }.toSet()
        )
    }

    // T-APPLY-2: 正常系 - departureTimeは除去後の残準備で再計算され、estimatedArrivalは
    // option.estimatedArrivalがそのまま転記される（Fable 5裁定2、承認済み期待値更新。
    // ファイル冒頭KDoc「再計算式の確定」参照）
    @Test
    fun tApply2_recalculatesDepartureTimeFromRemainingStepsAndTranscribesOptionEstimatedArrival() {
        val plan = standardPlan()
        val applier = RecoveryPlanApplier(clock = testClock)
        // D案相当の代替移動時間を反映した権威的なETA（元Planの移動時間25分とは異なる値にして、
        // 「転記」であって「元Planの移動時間からの再計算」ではないことを積極的に検証する）。
        val optionEstimatedArrival = Instant.parse("2026-08-10T09:41:00Z")

        val applied = applier.apply(
            plan,
            option(skippedStepIds = listOf(prepStep1.id), estimatedArrival = optionEstimatedArrival)
        )

        // 残り: transition(5分) + prep2(8分) = 13分。newDepartureTime = 09:05 + 13分 = 09:18。
        assertEquals(Instant.parse("2026-08-10T09:18:00Z"), applied.departureTime)
        assertEquals(optionEstimatedArrival, applied.estimatedArrival)
    }

    // T-APPLY-3: 異常系 - skippedStepIdsにREQUIREDのidが含まれる → IllegalArgumentException
    // （信頼境界の二重防御・§33）
    @Test
    fun tApply3_skippedStepIdsContainingRequiredStep_throwsIllegalArgumentException() {
        val plan = standardPlan()
        val applier = RecoveryPlanApplier(clock = testClock)

        assertThrows(IllegalArgumentException::class.java) {
            applier.apply(plan, option(skippedStepIds = listOf(departureStep.id)))
        }
    }

    // T-APPLY-4: エッジケース - skippedStepIdsが空（A案）→ stepsは不変、departureTimeは
    // 現在時刻基準で再計算、estimatedArrivalはoption.estimatedArrivalがそのまま転記される
    // （Fable 5裁定2、承認済み期待値更新）
    @Test
    fun tApply4_emptySkippedStepIds_keepsAllStepsRecalculatesDepartureTimeAndTranscribesEstimatedArrival() {
        val plan = standardPlan()
        val applier = RecoveryPlanApplier(clock = testClock)
        val optionEstimatedArrival = Instant.parse("2026-08-10T09:59:00Z")

        val applied = applier.apply(
            plan,
            option(skippedStepIds = emptyList(), estimatedArrival = optionEstimatedArrival)
        )

        assertEquals(plan.steps.map { it.id }.toSet(), applied.steps.map { it.id }.toSet())
        // 残り: transition(5分)+prep1(10分)+prep2(8分)=23分。newDepartureTime=09:05+23分=09:28。
        assertEquals(Instant.parse("2026-08-10T09:28:00Z"), applied.departureTime)
        assertEquals(optionEstimatedArrival, applied.estimatedArrival)
    }

    // T-APPLY-5: 異常系 - stepsに存在しないidが含まれる → 黙って無視せずIllegalArgumentException
    @Test
    fun tApply5_skippedStepIdsContainingUnknownId_throwsIllegalArgumentException() {
        val plan = standardPlan()
        val applier = RecoveryPlanApplier(clock = testClock)

        assertThrows(IllegalArgumentException::class.java) {
            applier.apply(plan, option(skippedStepIds = listOf(UUID.randomUUID())))
        }
    }

    // T-APPLY-6: 正常系 - arrivalBuffer（希望余裕）は変更されない
    @Test
    fun tApply6_arrivalBuffer_remainsUnchanged() {
        val plan = standardPlan()
        val applier = RecoveryPlanApplier(clock = testClock)

        val applied = applier.apply(plan, option(skippedStepIds = listOf(prepStep1.id)))

        assertEquals(plan.arrivalBuffer, applied.arrivalBuffer)
    }

    // T-APPLY-7: エッジケース - 全準備ステップ除去後もExecutionPlanが成立する
    // （stepsがDEPARTURE(+TRAVEL)のみでも例外なし）
    @Test
    fun tApply7_removingAllPreparationSteps_stillProducesValidExecutionPlan() {
        val onlyPrepStep = step(ExecutionStepType.PREPARATION, Duration.ofMinutes(10), StepPriority.OPTIONAL, true, Instant.parse("2026-08-10T08:50:00Z"))
        val plan = ExecutionPlan(
            event = event,
            steps = listOf(onlyPrepStep, departureStep, travelStep),
            transitionStart = Instant.parse("2026-08-10T08:50:00Z"),
            departureTime = Instant.parse("2026-08-10T08:50:00Z"),
            estimatedArrival = Instant.parse("2026-08-10T09:15:00Z"),
            arrivalBuffer = Duration.ofMinutes(15)
        )
        val applier = RecoveryPlanApplier(clock = testClock)

        val applied = applier.apply(plan, option(skippedStepIds = listOf(onlyPrepStep.id)))

        assertEquals(setOf(departureStep.id, travelStep.id), applied.steps.map { it.id }.toSet())
    }
}
