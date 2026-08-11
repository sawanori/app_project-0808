package com.actionstarter.ai.schema

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-P9-8〜11（計画書`docs/plans/phase9-recovery-ai.md`§3.3・§7、ADR-0063想定）。
 * [RecoverySchemaValidator]のテスト。当初（Phase 9コミット1 Red）は本体が`TODO()`のため
 * 全件`NotImplementedError`でRedだったが、コミット1 Greenで実装済み。
 *
 * **Phase 9.5 F-4追加（`docs/plans/phase9.5-performance-quality.md`§3.9・§14発見①、
 * 優先繰り上げ・Step 3 Red）**: T-P9-10の期待値を「完全一致必須」から「交差一致で緩和」へ更新し、
 * T-P9-38〜40を新設した。[RecoverySchemaValidator]本体はまだ旧仕様（完全一致）のままのため、
 * これらは`AssertionError`でRedになるのが正しい（Phase 9コミット2のT-P9-19〜23と同型の
 * behavioral-gap Red）。T-P9-8・9・11・フェンス/壊れたJSON系は無変更のままGreenを維持する。
 */
class RecoverySchemaValidatorTest {

    private fun rawJson(vararg options: Pair<String, String>): String {
        val optionsArray = JSONArray()
        options.forEach { (semanticAction, explanation) ->
            optionsArray.put(
                JSONObject()
                    .put("semantic_action", semanticAction)
                    .put("explanation", explanation)
            )
        }
        return JSONObject().put("options", optionsArray).toString()
    }

    // T-P9-8: 正常 - pairing検証: 返却semanticAction集合=入力options集合→Valid
    @Test
    fun validate_semanticActionSetMatchesExpectedSet_isValid() {
        val validator = RecoverySchemaValidator()
        val raw = rawJson(
            "keep_all_steps" to "Continue getting ready and leave when done.",
            "skip_optional_steps" to "Skip the optional steps and leave now."
        )

        val result = validator.validate(raw, expectedSemanticActions = setOf("keep_all_steps", "skip_optional_steps"))

        assertTrue(
            "返却集合が入力集合と完全一致する場合はValidであるべきです(T-P9-8)",
            result is RecoverySchemaValidationResult.Valid
        )
    }

    // T-P9-9: 異常 - pairing検証: 返却semanticActionに重複あり→Invalid
    @Test
    fun validate_duplicateSemanticActionInResponse_isInvalid() {
        val validator = RecoverySchemaValidator()
        val raw = rawJson(
            "keep_all_steps" to "Continue getting ready and leave when done.",
            "keep_all_steps" to "Duplicate entry for the same action."
        )

        val result = validator.validate(raw, expectedSemanticActions = setOf("keep_all_steps"))

        assertTrue(
            "返却集合に重複がある場合はInvalidであるべきです(T-P9-9)",
            result is RecoverySchemaValidationResult.Invalid
        )
    }

    // T-P9-10（F-4緩和により期待値更新、計画書§3.9・§14発見①・§9再検討トリガー発動）:
    // 正常 - pairing検証: 返却semanticActionが入力集合の部分集合(1件欠落)でも交差が1件以上あれば
    // Valid（交差分のみ採用、Best-Effort部分適用）。旧仕様（完全一致必須）はF-4により交差一致へ
    // 緩和されたため、期待値をInvalid→Valid（交差分1件のみ採用）へ更新した。現状の
    // RecoverySchemaValidator本体は旧仕様（完全一致）のままのため、本テストはAssertionErrorで
    // Redになるのが正しい（commit 2のT-P9-19〜23と同型のbehavioral-gap Red）。
    @Test
    fun validate_missingOneExpectedSemanticAction_intersectionNonEmpty_isValidWithIntersectionOnly() {
        val validator = RecoverySchemaValidator()
        val raw = rawJson("keep_all_steps" to "Continue getting ready and leave when done.")

        val result = validator.validate(
            raw,
            expectedSemanticActions = setOf("keep_all_steps", "change_transport_mode")
        )

        assertTrue(
            "expected集合の一部が欠落していても交差が1件以上あればValidであるべきです(T-P9-10、F-4緩和): $result",
            result is RecoverySchemaValidationResult.Valid
        )
        assertEquals(
            "交差分（keep_all_stepsの1件）のみが採用されるべきです(T-P9-10)",
            listOf("keep_all_steps"),
            (result as RecoverySchemaValidationResult.Valid).response.options.map { it.semanticAction }
        )
    }

    // T-P9-38（F-4、計画書§3.9・§14発見①の実例接地）: 正常 - pairing検証: 返却semanticActionが
    // expected集合を超過（今回の実測ケース: expected 2件に対しQwenが3件目を追加）していても、
    // 交差2件がValidとして採用され、交差分（2件）のexplanationのみが採用される（余分な3件目は
    // 破棄）。現状は完全一致必須のためAssertionErrorでRedになるのが正しい。
    @Test
    fun tP9_38_actualExceedsExpectedWithExtraOption_intersectionIsValid_extraDiscarded() {
        val validator = RecoverySchemaValidator()
        val raw = rawJson(
            "keep_all_steps" to "Continue getting ready and leave when done.",
            "skip_optional_steps" to "Skip the optional steps and leave now.",
            "skip_optional_and_important_steps" to "Skip everything non-essential and leave immediately."
        )

        val result = validator.validate(
            raw,
            expectedSemanticActions = setOf("keep_all_steps", "skip_optional_steps")
        )

        assertTrue(
            "expected集合を超過していても交差2件がValidであるべきです(T-P9-38、§14発見①実例接地): $result",
            result is RecoverySchemaValidationResult.Valid
        )
        assertEquals(
            "交差分（2件）のみが採用され、余分な3件目は破棄されるべきです(T-P9-38)",
            setOf("keep_all_steps", "skip_optional_steps"),
            (result as RecoverySchemaValidationResult.Valid).response.options.map { it.semanticAction }.toSet()
        )
    }

    // T-P9-39（F-4）: 異常 - pairing検証: 返却semanticActionがexpected集合と完全に無関係（交差0件）
    // ならInvalidを維持する。
    @Test
    fun tP9_39_noIntersectionWithExpectedSet_isInvalid() {
        val validator = RecoverySchemaValidator()
        val raw = rawJson("change_transport_mode" to "Switch how you are getting there.")

        val result = validator.validate(
            raw,
            expectedSemanticActions = setOf("keep_all_steps", "skip_optional_steps")
        )

        assertTrue(
            "交差が0件ならInvalidを維持するべきです(T-P9-39、F-4): $result",
            result is RecoverySchemaValidationResult.Invalid
        )
    }

    // T-P9-40（F-4）: 異常 - 交差内（expected集合と一致する側）に重複がある場合、余分なオプションが
    // 混在していてもInvalidを維持する（既存の重複検出はexpected集合の内外を問わないブランケット
    // 判定のまま緩和しない）。
    @Test
    fun tP9_40_duplicateWithinIntersectionAlongsideExtraOption_isInvalid() {
        val validator = RecoverySchemaValidator()
        val raw = rawJson(
            "keep_all_steps" to "Continue getting ready and leave when done.",
            "keep_all_steps" to "Duplicate entry for the same action.",
            "change_transport_mode" to "Switch how you are getting there."
        )

        val result = validator.validate(
            raw,
            expectedSemanticActions = setOf("keep_all_steps", "skip_optional_steps")
        )

        assertTrue(
            "交差内に重複があれば余分なオプションが混在していてもInvalidを維持するべきです(T-P9-40、F-4): $result",
            result is RecoverySchemaValidationResult.Invalid
        )
    }

    // T-P9-11: 異常 - pairing検証: 返却semanticActionに未知の値→Invalid(enum制約と二重防御)
    @Test
    fun validate_unknownSemanticActionValue_isInvalid() {
        val validator = RecoverySchemaValidator()
        val raw = rawJson("totally_unknown_action" to "This action was never offered.")

        val result = validator.validate(raw, expectedSemanticActions = setOf("totally_unknown_action"))

        assertTrue(
            "RecoveryActionType.JSON_VALUESに存在しない値はInvalidであるべきです(T-P9-11、enum制約との二重防御)",
            result is RecoverySchemaValidationResult.Invalid
        )
    }

    // T-P9-11補足: 壊れたJSON・空文字列は例外を投げずInvalidへ写像する(SchemaValidatorと同型の契約)
    @Test
    fun validate_malformedJson_doesNotThrow_returnsInvalid() {
        val validator = RecoverySchemaValidator()

        val result = validator.validate("not valid json {{{", expectedSemanticActions = setOf("keep_all_steps"))

        assertTrue(result is RecoverySchemaValidationResult.Invalid)
    }

    // T-P9-11補足（Gemini G9対応・計画書§3.4 A-8訂正）: コードフェンス付きJSONは黙って剥がさず
    // 不合格にする(SchemaValidatorTest.tSch19と同型の非寛容パース方針)
    @Test
    fun validate_markdownFencedJson_isInvalidNotSilentlyStripped() {
        val validator = RecoverySchemaValidator()
        val fenced = "```json\n${rawJson("keep_all_steps" to "Continue getting ready.")}\n```"

        val result = validator.validate(fenced, expectedSemanticActions = setOf("keep_all_steps"))

        assertTrue(
            "```json フェンス付きは黙って剥がさず不合格にするべきです(§3.4 A-8訂正)",
            result is RecoverySchemaValidationResult.Invalid
        )
    }
}
