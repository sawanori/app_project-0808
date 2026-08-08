package com.actionstarter.domain

import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-DM-11（計画書§11.2 F1）。[ExecutionStep.estimatedDuration]の負値拒否を、構築時・
 * copy()実行時の両方について検証する（ADR-0010）。契約scaffold（C2）時点では`init`検証が
 * ないため、現状は例外が送出されず`assertThrows`が失敗する形でRedになる（意図した失敗）。
 */
class ExecutionStepTest {

    private fun validStep(estimatedDuration: Duration = Duration.ofMinutes(10)) = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = "get_dressed",
        type = ExecutionStepType.PREPARATION,
        title = "Get dressed",
        estimatedDuration = estimatedDuration,
        priority = StepPriority.IMPORTANT,
        skippable = false,
        scheduledStart = Instant.parse("2026-08-10T08:50:00Z"),
        completedAt = null
    )

    // T-DM-11: 構築時にestimatedDurationが負の値だと例外
    @Test
    fun construct_negativeEstimatedDuration_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            validStep(estimatedDuration = Duration.ofMinutes(-1))
        }
    }

    // T-DM-11（ADR-0010: copy()時にも検証）: copy()実行時にもestimatedDurationの負値検証が働く
    @Test
    fun copy_negativeEstimatedDuration_throwsIllegalArgumentException() {
        val step = validStep()

        assertThrows(IllegalArgumentException::class.java) {
            step.copy(estimatedDuration = Duration.ofMinutes(-5))
        }
    }
}
