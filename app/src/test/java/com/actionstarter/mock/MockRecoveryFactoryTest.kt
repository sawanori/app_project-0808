package com.actionstarter.mock

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-DM-9（ファクトリ層契約として再定義。Fable 5裁定 2026-08-08）。
 *
 * 当初計画書§11.2ではT-DM-9を「ExecutionPlan(§49)／StepPriority(§48)」を対象とする
 * Domain不変条件として分類していたが、`ExecutionPlan`には`skippedStepIds`フィールドが
 * 存在せず（仕様§49維持）コンパイル可能なテストを作成できなかった（本エージェントの
 * 先行報告で差し戻し済み）。Fable 5裁定により、この不変条件の検証責務はDomainモデルの
 * `init`ではなく[MockRecoveryFactory]（[com.actionstarter.recovery.RecoveryEngine]実装＝
 * ファクトリ層）に置くと確定した。
 *
 * 契約: 「[RecoveryContext]を与えられた[com.actionstarter.recovery.RecoveryEngine]実装は、
 * `unfinishedSteps`のうち[StepPriority.REQUIRED]のステップIDを
 * [com.actionstarter.domain.model.RecoveryOption.skippedStepIds]に含む
 * [com.actionstarter.domain.model.RecoveryOption]を返してはならない」（仕様§33：
 * 安全・必須物は勝手に省略しない）。
 *
 * T-REC-4（RecoveryScreen側で同観点をUIレベルで検証するテスト。Robolectric担当・
 * 別ファイル）とは別物であり、本テストはファクトリのロジック契約をpure JVMで検証する。
 *
 * [MockRecoveryFactory.createRecoveryPlan]は契約scaffold追補（C2b）時点で本文が
 * `TODO("C4で実装")`のため、本テストは現状`NotImplementedError`によりRedになる
 * （意図した失敗）。
 */
class MockRecoveryFactoryTest {

    private val event = ExecutionEvent(
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
        semanticId: String,
        priority: StepPriority,
        skippable: Boolean
    ) = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = semanticId,
        type = ExecutionStepType.PREPARATION,
        title = semanticId,
        estimatedDuration = Duration.ofMinutes(10),
        priority = priority,
        skippable = skippable,
        scheduledStart = Instant.parse("2026-08-10T09:00:00Z"),
        completedAt = null
    )

    // T-DM-9（ファクトリ層契約として再定義）
    @Test
    fun createRecoveryPlan_neverSkipsRequiredPriorityStep() = runTest {
        val requiredStep = step(
            semanticId = "prepare_required_documents",
            priority = StepPriority.REQUIRED,
            skippable = false
        )
        val importantStep = step(
            semanticId = "check_equipment",
            priority = StepPriority.IMPORTANT,
            skippable = true
        )
        val optionalStep = step(
            semanticId = "grab_umbrella",
            priority = StepPriority.OPTIONAL,
            skippable = true
        )

        val context = RecoveryContext(
            currentTime = Instant.parse("2026-08-10T09:05:00Z"),
            currentLocation = Coordinate(lat = 35.6586, lon = 139.7454),
            event = event,
            unfinishedSteps = listOf(requiredStep, importantStep, optionalStep),
            latestTravelEstimate = Duration.ofMinutes(30),
            plannedDepartureTime = Instant.parse("2026-08-10T09:10:00Z")
        )

        val plan = MockRecoveryFactory().createRecoveryPlan(context)

        val allSkippedStepIds = plan.options.flatMap { it.skippedStepIds }
        assertTrue(
            "REQUIREDステップ(${requiredStep.id})がいずれかのRecoveryOption.skippedStepIdsに" +
                "含まれています: $allSkippedStepIds",
            requiredStep.id !in allSkippedStepIds
        )
    }
}
