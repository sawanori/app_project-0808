package com.actionstarter.domain

import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.RecoveryPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * T-DM-7・T-DM-8（計画書§11.2 F1）。[RecoveryPlan.options]の上限3件不変条件
 * （仕様§32、エラー＆レスキューマップ#12: 4件以上を黙って切り捨てず即時失敗させる）を
 * 検証する。契約scaffold（C2）時点では`init`検証がないため、T-DM-7は現状
 * 例外が送出されず`assertThrows`が失敗する形でRedになる（意図した失敗。ADR-0010）。
 */
class RecoveryPlanTest {

    private fun option(label: String) = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = label,
        title = label,
        explanation = label,
        estimatedArrival = null,
        skippedStepIds = emptyList()
    )

    // T-DM-7: RecoveryPlanに4件目を渡すと例外（黙って切り捨てない）
    @Test
    fun construct_fourOptions_throwsIllegalArgumentException() {
        val fourOptions = listOf(option("a"), option("b"), option("c"), option("d"))

        assertThrows(IllegalArgumentException::class.java) {
            RecoveryPlan(options = fourOptions)
        }
    }

    // T-DM-7（ADR-0010追補）: copy()実行時にも上限検証が働く
    @Test
    fun copy_fourOptions_throwsIllegalArgumentException() {
        val plan = RecoveryPlan(options = listOf(option("a"), option("b"), option("c")))
        val fourOptions = listOf(option("a"), option("b"), option("c"), option("d"))

        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(options = fourOptions)
        }
    }

    // T-DM-8: RecoveryPlanが0件でも生成は成功し、UI側で「案なし」表示に写像される
    @Test
    fun construct_zeroOptions_succeeds() {
        val plan = RecoveryPlan(options = emptyList())

        assertEquals(0, plan.options.size)
    }
}
