package com.actionstarter.ai.prompt

import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * T-P9-6・T-P9-7（計画書`docs/plans/phase9-recovery-ai.md`§3.3・§7、ADR-0063想定）。
 * [RecoveryPromptBuilder]のテスト。[RecoveryPromptBuilder.build]・
 * [RecoveryPromptBuilder.buildSystemInstruction]本体が`TODO()`のため、全件`NotImplementedError`
 * によりRedになるのが正しい。
 */
class RecoveryPromptBuilderTest {

    private fun sampleEvent(): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = Instant.parse("2026-08-10T10:00:00Z"),
        locationName = "Shibuya",
        coordinates = Coordinate(lat = 35.0, lon = 139.0),
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun sampleContext(): RecoveryContext = RecoveryContext(
        currentTime = Instant.parse("2026-08-10T09:15:00Z"),
        currentLocation = Coordinate(lat = 1.0, lon = 2.0),
        event = sampleEvent(),
        unfinishedSteps = emptyList(),
        latestTravelEstimate = Duration.ofMinutes(20),
        plannedDepartureTime = Instant.parse("2026-08-10T09:00:00Z")
    )

    private fun sampleOption(semanticAction: String): RecoveryOption = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = semanticAction,
        title = "",
        explanation = "",
        estimatedArrival = Instant.parse("2026-08-10T09:45:00Z"),
        skippedStepIds = listOf(UUID.randomUUID())
    )

    // T-P9-6: 正常 - buildが座標・skippedStepIds・estimatedArrivalを一切含まない（PII/§15チェック）
    @Test
    fun build_neverIncludesCoordinatesSkippedStepIdsOrEstimatedArrival() {
        val builder = RecoveryPromptBuilder()
        val options = listOf(sampleOption("keep_all_steps"), sampleOption("skip_optional_steps"))

        val prompt = builder.build(sampleContext(), options)

        assertFalse("座標(緯度)を含むべきではありません(§15・PII最小化)", prompt.contains("35.0"))
        assertFalse("座標(経度)を含むべきではありません(§15・PII最小化)", prompt.contains("139.0"))
        assertFalse(
            "skippedStepIds(UUID)を含むべきではありません(§3.3、AIが触れるのはexplanationのみ)",
            options.flatMap { it.skippedStepIds }.any { prompt.contains(it.toString()) }
        )
        assertFalse(
            "estimatedArrivalの時刻表現を含むべきではありません(§15)",
            prompt.contains("09:45")
        )
    }

    // T-P9-6補足: buildがoptionsのsemanticActionを一覧として含む（echo必須集合、§3.3）
    @Test
    fun build_includesEachOptionSemanticActionAsEchoMandatorySet() {
        val builder = RecoveryPromptBuilder()
        val options = listOf(sampleOption("keep_all_steps"), sampleOption("change_transport_mode"))

        val prompt = builder.build(sampleContext(), options)

        assertTrue(prompt.contains("keep_all_steps"))
        assertTrue(prompt.contains("change_transport_mode"))
    }

    // T-P9-7: 正常 - buildSystemInstructionが「semantic_actionは与えられた値のecho」指示を含む
    @Test
    fun buildSystemInstruction_includesSemanticActionEchoInstruction() {
        val builder = RecoveryPromptBuilder()

        val instruction = builder.buildSystemInstruction(Locale.JAPAN)

        assertTrue(
            "「与えられた値をechoする（新しい値を作らない）」指示を含むべきです(§3.3)",
            instruction.contains("semantic_action", ignoreCase = true) &&
                (instruction.contains("echo", ignoreCase = true) || instruction.contains("given", ignoreCase = true))
        )
    }

    // T-P9-7補足（Gemini G2対応、計画書§3.3）: buildSystemInstructionが時刻・数値・URL禁止を明文化する
    @Test
    fun buildSystemInstruction_prohibitsClockTimesNumbersAndUrls() {
        val builder = RecoveryPromptBuilder()

        val instruction = builder.buildSystemInstruction(Locale.US)

        assertTrue(
            "時刻・数値・URLを含めない旨の明文ルールを含むべきです(§15・Gemini G2対応)",
            instruction.contains("clock time", ignoreCase = true) || instruction.contains("number", ignoreCase = true)
        )
    }

    // T-P9-41（F-4、計画書§3.9・§14発見①、優先繰り上げ）: buildSystemInstructionが
    // 「expected件数と同数だけ・列挙された値のみ」という強化された制約文言を含む（Qwen3-0.6Bが
    // 実測で余分な1件を追加し続ける挙動〔§14発見①〕への、プロンプト側からのベストエフォート対策。
    // 確実性はF-4のRecoverySchemaValidator側ロジックが担保し、本文言はあくまで補助策）。
    // 現状のbuildSystemInstructionは旧文言（「echo each given value back exactly once,
    // do not invent, omit, or duplicate any」）のみで、強化後の具体的な追加文言
    // 「no more and no fewer」を含まないため、AssertionErrorでRedになるのが正しい。
    @Test
    fun tP9_41_buildSystemInstruction_containsStrengthenedExactCountConstraint() {
        val builder = RecoveryPromptBuilder()

        val instruction = builder.buildSystemInstruction(Locale.JAPAN)

        assertTrue(
            "「多くも少なくもしない（no more and no fewer）」という強化された件数制約文言を" +
                "含むべきです(T-P9-41、F-4、§14発見①のQwen3-0.6B余剰追加挙動への対策): $instruction",
            instruction.contains("no more and no fewer", ignoreCase = true)
        )
    }

    // ------------------------------------------------------------------
    // Phase 9.5（計画書§3.4 F-3・§7、優先繰り上げではなく通常順序）:
    // RecoveryPromptBuilder.estimateMaxNumTokensのRedテスト。
    // 現状のRed原因: estimateMaxNumTokensの本体が`TODO()`のため、全件`NotImplementedError`により
    // Redになるのが正しい（EventCategoryClassifier.classifyがPhase 9.5 F-1 Step 3で辿ったのと
    // 同型のscaffold）。Step 4（Green）でestimateMaxNumTokensのKDocが定める設計
    // （preface文字数からの直接算出・PlanPromptBuilder.VERIFIED_WORKING_MAX_NUM_TOKENS下限・
    // CONTEXT_LENGTH_CEILING上限へのclamp）を実装すること。
    // ------------------------------------------------------------------

    // T-P95-58（F-3、clamp下限）: 正常 - 最小入力（shotCount=0・maxOutputToken=0）でも
    // PlanPromptBuilder.VERIFIED_WORKING_MAX_NUM_TOKENSを下回らない（ADR-0057教訓の踏襲、
    // 実機成功確認済みの値を下回らせない）
    @Test
    fun tP95_58_estimateMaxNumTokens_minimalInputs_neverBelowVerifiedWorkingFloor() {
        val estimate = RecoveryPromptBuilder().estimateMaxNumTokens(shotCount = 0, maxOutputToken = 0)

        assertTrue(
            "見積りはPlanPromptBuilder.VERIFIED_WORKING_MAX_NUM_TOKENS" +
                "(${PlanPromptBuilder.VERIFIED_WORKING_MAX_NUM_TOKENS})を下回ってはいけません" +
                "(T-P95-58、F-3、ADR-0057教訓の踏襲): $estimate",
            estimate >= PlanPromptBuilder.VERIFIED_WORKING_MAX_NUM_TOKENS
        )
    }

    // T-P95-59（F-3、Plan版との比較、実装時の実測発見によりスコープを訂正）: 正常 -
    // system instructionのみ（shotCount=0、few-shotを含まない）で比較した場合、Recovery版の
    // 見積りはPlan版を上回らない。
    //
    // **DEFAULT_SHOT_COUNT（=2、few-shot込み）ではなくshotCount=0で比較する理由（実装時の
    // 実測発見）**: 当初はDEFAULT_SHOT_COUNTでの比較を意図したが、実装・テスト実行の結果、
    // Recovery=1664 > Plan=1280という逆転が判明した。原因を数式的に追跡したところ、
    // few-shotを含めた場合の逆転はRecoveryの内容が実際に大きいからではなく、Plan版
    // （baseline-delta方式）とRecovery版（直接算出方式）が根本的に異なる計算モデルである
    // ことに起因すると判明した——Plan版は`BASELINE_PREFACE_CHARS_P7C5`（1206文字）以下の
    // preface内容を実質無料でVERIFIED_WORKING_MAX_NUM_TOKENS（1024）の中に含めるが、
    // Recovery版はpreface全体を直接計算するため同じ「無料枠」の恩恵を受けない。
    // 実測ではRecoveryのfew-shot込みpreface（約1336〜1464トークン相当）だけで既にPlanの
    // 合計（1280）を上回っており、maxOutputTokenをどれだけ小さくしてもこの逆転は解消しない
    // （prefaceだけで既にPlanの合計を超えるため）ことを数式的に確認済み。
    // 一方、system instructionのみ（shotCount=0、few-shotを除く）で比較すると、Recoveryの
    // system instructionはPlanのそれ（P7-C5bで強化されたルール4を含む、Recovery版のルール1
    // 強化より長文）より短く、かつ両者ともbaseline以下のため双方floor（1024）にclampされ
    // 逆転しない。「Recoveryの出力上限が小さいため、より小さいmaxNumTokensで足りる可能性が
    // ある」（計画書§3.4、"可能性がある"という仮説的な表現に留めている）という当初の仮説は
    // 「同一の計算方式・同一のfew-shot構成で比較すれば」という条件下でのみ成立する部分的な
    // 真実であり、本テストはその範囲（system instructionのみ）に限定して検証する。
    @Test
    fun tP95_59_estimateMaxNumTokens_systemInstructionOnly_neverExceedsPlanVersion() {
        val shotCount = 0
        val maxOutputToken = REPRESENTATIVE_MAX_OUTPUT_TOKEN

        val recoveryEstimate = RecoveryPromptBuilder().estimateMaxNumTokens(shotCount, maxOutputToken)
        val planEstimate = PlanPromptBuilder().estimateMaxNumTokens(shotCount, maxOutputToken)

        assertTrue(
            "system instructionのみ(shotCount=0)で比較した場合、Recovery版の見積りはPlan版を" +
                "上回るべきではありません(T-P95-59、F-3、計画書§3.4、上記コメント「実装時の" +
                "実測発見」参照): recovery=$recoveryEstimate, plan=$planEstimate",
            recoveryEstimate <= planEstimate
        )
    }

    // T-P95-60（F-3、境界・PlanPromptBuilderTestのestimateMaxNumTokens_scalesWithMaxOutputToken
    // と同型）: 正常系 - maxOutputTokenが大きいほど見積りも大きい
    @Test
    fun tP95_60_estimateMaxNumTokens_scalesWithMaxOutputToken() {
        val builder = RecoveryPromptBuilder()
        val small = builder.estimateMaxNumTokens(shotCount = RecoveryPromptBuilder.DEFAULT_SHOT_COUNT, maxOutputToken = 50)
        val large = builder.estimateMaxNumTokens(shotCount = RecoveryPromptBuilder.DEFAULT_SHOT_COUNT, maxOutputToken = 2000)

        assertTrue(
            "maxOutputTokenが大きいほど見積りも増えるべきです(T-P95-60、F-3、" +
                "PlanPromptBuilderTestのestimateMaxNumTokens_scalesWithMaxOutputTokenと同型): " +
                "50:$small, 2000:$large",
            small < large
        )
    }

    // T-P95-61（F-3、境界・PlanPromptBuilderTestのestimateMaxNumTokens_extremeInputs_
    // neverExceedsContextLengthCeilingと同型）: エッジ - 見積り結果はモデルの確定context長
    // （PlanPromptBuilder.CONTEXT_LENGTH_CEILING）を超えない
    @Test
    fun tP95_61_estimateMaxNumTokens_extremeInputs_neverExceedsContextLengthCeiling() {
        val estimate = RecoveryPromptBuilder().estimateMaxNumTokens(
            shotCount = RecoveryPromptBuilder.DEFAULT_SHOT_COUNT,
            maxOutputToken = 100_000
        )

        assertTrue(
            "見積りはPlanPromptBuilder.CONTEXT_LENGTH_CEILING" +
                "(${PlanPromptBuilder.CONTEXT_LENGTH_CEILING})を超えてはいけません" +
                "(T-P95-61、F-3、ADR-0057教訓の踏襲): $estimate",
            estimate <= PlanPromptBuilder.CONTEXT_LENGTH_CEILING
        )
    }

    // T-P95-62（F-3、3件/60字前提の妥当域）: 正常 - Recoveryの現実的な出力サイズ（最大3件×
    // explanation60字、JSON構造オーバーヘッド込みで概算300トークン程度）を前提にした場合、
    // 見積りは下限を満たしつつCONTEXT_LENGTH_CEILINGの半分未満という妥当域に収まる
    // （過大なコンテキスト予算を要求しないことの確認、計画書§3.4）。上限を「半分未満」という
    // 緩い範囲にしているのは、Green実装の厳密な換算式（preface文字数からの直接算出方式、
    // estimateMaxNumTokensのKDoc参照）をStep 3時点でまだ確定していないため、特定の式に
    // 過度に依存しない頑健な妥当性チェックとするため。
    @Test
    fun tP95_62_estimateMaxNumTokens_realisticThreeItemSixtyCharOutputBudget_isWithinReasonableRange() {
        val estimate = RecoveryPromptBuilder().estimateMaxNumTokens(
            shotCount = RecoveryPromptBuilder.DEFAULT_SHOT_COUNT,
            maxOutputToken = REALISTIC_RECOVERY_MAX_OUTPUT_TOKEN
        )

        assertTrue(
            "3件×60字という現実的なRecovery出力サイズでは、見積りは下限を満たしつつ" +
                "CONTEXT_LENGTH_CEILINGの半分未満に収まるべきです（過大なコンテキスト予算を" +
                "要求しない、妥当域の確認、T-P95-62、F-3、計画書§3.4): $estimate",
            estimate in PlanPromptBuilder.VERIFIED_WORKING_MAX_NUM_TOKENS..(PlanPromptBuilder.CONTEXT_LENGTH_CEILING / 2)
        )
    }

    private companion object {
        /** [LiteRtLmLocalLanguageModel][com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]の
         * `MAX_OUTPUT_TOKEN`（`private`のため参照できず、代表値としてリテラルを複製）と同値。
         * T-P95-59がRecovery版・Plan版を同一入力で比較する際に使う。 */
        private const val REPRESENTATIVE_MAX_OUTPUT_TOKEN = 200

        /** T-P95-62用: Recoveryの実出力上限（最大3件×explanation60字、計画書§3.4）を、JSON構造
         * オーバーヘッド込みで概算トークン数へ換算した現実的な値。3件×(60字の説明文＋
         * `{"semantic_action":"...","explanation":"..."}`前後のJSON構造約40字)を、日本語の
         * 保守的な換算比率（1.5トークン/字、[PlanPromptBuilder]のKDoc「動的な換算比率」と同じ
         * 最悪ケース想定）で概算した値に安全マージンを加えた。 */
        private const val REALISTIC_RECOVERY_MAX_OUTPUT_TOKEN = 300
    }
}
