package com.actionstarter.ai.schema

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-P9-5（計画書`docs/plans/phase9-recovery-ai.md`§3.3・§7、ADR-0063想定）。[RecoveryJsonSchema]の
 * テスト。[RecoveryJsonSchema.TEXT]の`buildSchemaJson`本体が`TODO()`のため、`.TEXT`への
 * 初回アクセス（`by lazy`の評価）で全件`NotImplementedError`によりRedになるのが正しい。
 */
class RecoveryJsonSchemaTest {

    // T-P9-5: 正常 - RecoveryJsonSchema.TEXTがRecoveryActionType4値・options 1〜3件・
    // additionalProperties:false全階層を含む
    @Test
    fun text_declaresRecoveryActionTypeEnum_optionsCardinality_andAdditionalPropertiesFalseEverywhere() {
        val root = JSONObject(RecoveryJsonSchema.TEXT)

        assertEquals("object", root.getString("type"))
        assertFalse(
            "ルート階層もadditionalProperties:falseであるべきです",
            root.getBoolean("additionalProperties")
        )

        val optionsSchema = root.getJSONObject("properties").getJSONObject("options")
        assertEquals("array", optionsSchema.getString("type"))
        assertEquals(1, optionsSchema.getInt("minItems"))
        assertEquals(3, optionsSchema.getInt("maxItems"))

        val itemSchema = optionsSchema.getJSONObject("items")
        assertFalse(
            "options[]要素もadditionalProperties:falseであるべきです",
            itemSchema.getBoolean("additionalProperties")
        )

        val semanticActionEnum = itemSchema.getJSONObject("properties")
            .getJSONObject("semantic_action")
            .getJSONArray("enum")
        val actualValues = (0 until semanticActionEnum.length()).map { semanticActionEnum.getString(it) }.toSet()
        assertEquals(RecoveryActionType.JSON_VALUES.toSet(), actualValues)
        assertEquals(4, actualValues.size)

        assertTrue(
            "explanationプロパティが定義されているべきです",
            itemSchema.getJSONObject("properties").has("explanation")
        )
    }

    // T-P9-5補足: RecoveryActionType.JSON_VALUESが4値・BasicRecoveryEngineのsemanticActionと
    // 同じsnake_case語彙であることの回帰ガード
    @Test
    fun recoveryActionType_jsonValues_matchesBasicRecoveryEngineSemanticActionVocabulary() {
        val expected = setOf(
            "keep_all_steps",
            "skip_optional_steps",
            "skip_optional_and_important_steps",
            "change_transport_mode"
        )

        assertEquals(expected, RecoveryActionType.JSON_VALUES.toSet())
    }
}
