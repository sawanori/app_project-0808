package com.actionstarter.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-C2c（Fable 5裁定9、2026-08-10、ADR-0050）。[SamplingPolicy]の契約テスト
 * （品質ハーネス`docs/plans/phase7-quality-harness.md`§4「サンプリング設計」・§11 QH-9相当）。
 *
 * **born-green（本タスクの制約により意図的にGreenとして追加）**: [SamplingPolicy]は
 * enum定数として全プロパティが確定済み（`TODO()`を含まない）であるため、本テストは
 * [AiFallbackReasonTest]・[AiMetricsTest]・[com.actionstarter.ai.model.ModelCatalogTest]と
 * 同じ「契約が既に確定している型の回帰ガード」パターンに従い、意図的にGreenとして追加する
 * （Red化を狙うテストではない。P7-C2完了記録の「born-green」区分を踏襲）。
 *
 * QH-9原文（品質ハーネス§11）: 「`SamplingPolicy`既定が§4（primary `topK=1,temp=0.0`／retry
 * `topK=5,temp=0.15,seed≠`）。`topK>0`制約を破らない」。
 */
class SamplingPolicyTest {

    // 正常系 - Primaryはgreedy近似（topK=1・temperature=0.0）で、簡潔化制約を付加しない
    // （品質ハーネス§4「1回目（既定）」・QH-9）
    @Test
    fun primary_isGreedyApproximationAndDoesNotAppendConcisenessConstraint() {
        assertEquals("Primaryのtopは1（greedy近似）であるべきです", 1, SamplingPolicy.Primary.topK)
        assertEquals(
            "Primaryのtemperatureは0.0であるべきです",
            0.0,
            SamplingPolicy.Primary.temperature,
            0.0
        )
        assertFalse(
            "Primary（1回目）は簡潔化制約を付加しないべきです" +
                "(品質ハーネス§6「静的制約の追加（2回目のみ）」)",
            SamplingPolicy.Primary.appendConcisenessConstraint
        )
    }

    // 正常系 - Retryは微小摂動（topK=5・temperature 0.1〜0.2）で、簡潔化制約を付加する
    // （品質ハーネス§4「2回目（再試行）」・§6「静的制約の追加（2回目のみ）」・QH-9）
    @Test
    fun retry_isSmallPerturbationWithinRecommendedRangeAndAppendsConcisenessConstraint() {
        assertEquals("Retryのtopは5であるべきです", 5, SamplingPolicy.Retry.topK)
        assertTrue(
            "Retryのtemperatureは0.1〜0.2の範囲であるべきです(品質ハーネス§4): " +
                "${SamplingPolicy.Retry.temperature}",
            SamplingPolicy.Retry.temperature in 0.1..0.2
        )
        assertTrue(
            "Retry（2回目）は簡潔化制約を付加するべきです(品質ハーネス§6「静的制約の追加（2回目のみ）」)",
            SamplingPolicy.Retry.appendConcisenessConstraint
        )
    }

    // 正常系 - 全ポリシーがtopK>0制約を満たす（LiteRT-LM SamplerConfigのrequire(topK>0)、
    // topK=0の純粋greedy指定は不可。品質ハーネス§1・§4・QH-9）
    @Test
    fun allPolicies_satisfyTopKGreaterThanZeroConstraint() {
        SamplingPolicy.entries.forEach { policy ->
            assertTrue(
                "topKは0より大きくなければなりません(LiteRT-LMのSamplerConfigはrequire(topK>0)、" +
                    "topK=0の純粋greedy指定は不可): ${policy.name}のtopK=${policy.topK}",
                policy.topK > 0
            )
        }
    }

    // 異常系（回帰ロック） - PrimaryとRetryは異なるサンプリング条件を持つ（同一条件でのretryは
    // 同一失敗を再現するだけで無効、という旧基盤計画S-2欠陥の再発防止。品質ハーネス§0・
    // ADR-0049「retry契約の是正」）
    @Test
    fun primaryAndRetry_useDifferentSamplingConditions_soRetryCannotReproduceIdenticalFailure() {
        assertTrue(
            "PrimaryとRetryのtopKが同一だと決定的retryが同一失敗を再現しかねません(S-2欠陥の再発防止): " +
                "両方とも${SamplingPolicy.Primary.topK}",
            SamplingPolicy.Primary.topK != SamplingPolicy.Retry.topK
        )
        assertTrue(
            "PrimaryとRetryのtemperatureが同一だと決定的retryが同一失敗を再現しかねません" +
                "(S-2欠陥の再発防止): 両方とも${SamplingPolicy.Primary.temperature}",
            SamplingPolicy.Primary.temperature != SamplingPolicy.Retry.temperature
        )
    }
}
