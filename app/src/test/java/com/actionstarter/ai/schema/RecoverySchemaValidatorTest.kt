package com.actionstarter.ai.schema

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-P9-8〜11（計画書`docs/plans/phase9-recovery-ai.md`§3.3・§7、ADR-0063想定）。
 * [RecoverySchemaValidator]のテスト。[RecoverySchemaValidator.validate]本体が`TODO()`のため、
 * 全件`NotImplementedError`によりRedになるのが正しい。
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

    // T-P9-10: 異常 - pairing検証: 返却semanticActionが入力集合の部分集合のみ(1件欠落)→Invalid
    @Test
    fun validate_missingOneExpectedSemanticAction_isInvalid() {
        val validator = RecoverySchemaValidator()
        val raw = rawJson("keep_all_steps" to "Continue getting ready and leave when done.")

        val result = validator.validate(
            raw,
            expectedSemanticActions = setOf("keep_all_steps", "change_transport_mode")
        )

        assertTrue(
            "期待集合の一部が返却集合に欠落している場合はInvalidであるべきです(T-P9-10)",
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
