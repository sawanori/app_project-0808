package com.actionstarter.domain

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-DM-6（計画書§11.2 F1）。[ExecutionPlan.steps]が昇順で保持される不変条件を検証する。
 *
 * 対象の`init`検証は契約scaffold（C2）時点では未実装（[ExecutionPlan]のKDoc参照）。
 * 解釈注記：計画書はT-DM-6を「正常系」（例外を伴わない）に分類しているため、本テストは
 * 「構築時の入力順が乱れていても、`.steps`はscheduledStart昇順で返る」という正規化保証
 * として解釈した（仕様§25のユーザーフロー例が08:40→08:50→09:00→09:10→09:52と時系列
 * 昇順でステップを列挙している点とも整合する）。`init`で入力順そのものを拒否する方式
 * （異常系）を採用する場合は本テストの書き換えが必要になる可能性があり、その旨を
 * 完了報告に明記する。
 *
 * T-DM-9はこのファイルに実装していない。理由は完了報告を参照（`ExecutionPlan`に
 * `skippedStepIds`フィールド／コンストラクタ引数が存在せず、コンパイル可能なテストを
 * 作成できないため。KDocは当該フィールドの存在を前提に記述しているが、実際のデータ
 * クラス定義には含まれていない）。
 */
class ExecutionPlanTest {

    private val event = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = Instant.parse("2026-08-10T10:00:00Z"),
        locationName = "Shibuya",
        coordinates = null,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun step(semanticId: String, scheduledStart: Instant) = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = semanticId,
        type = ExecutionStepType.PREPARATION,
        title = semanticId,
        estimatedDuration = Duration.ofMinutes(10),
        priority = StepPriority.IMPORTANT,
        skippable = false,
        scheduledStart = scheduledStart,
        completedAt = null
    )

    // T-DM-6: ExecutionPlan.stepsが昇順で保持される
    @Test
    fun steps_areHeldInAscendingScheduledStartOrder() {
        val stepLate = step("leave", Instant.parse("2026-08-10T09:10:00Z"))
        val stepEarly = step("stop_working", Instant.parse("2026-08-10T08:40:00Z"))
        val stepMid = step("get_dressed", Instant.parse("2026-08-10T08:50:00Z"))

        // わざと昇順でない入力順（late, early, mid）で構築する
        val plan = ExecutionPlan(
            event = event,
            steps = listOf(stepLate, stepEarly, stepMid),
            transitionStart = Instant.parse("2026-08-10T08:40:00Z"),
            departureTime = Instant.parse("2026-08-10T09:10:00Z"),
            estimatedArrival = Instant.parse("2026-08-10T09:52:00Z"),
            arrivalBuffer = Duration.ofMinutes(8)
        )

        val expectedOrder = listOf(stepEarly.semanticId, stepMid.semanticId, stepLate.semanticId)
        assertEquals(expectedOrder, plan.steps.map { it.semanticId })
    }
}
