package com.actionstarter.ai.schema

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 9実装（計画書`docs/plans/phase9-recovery-ai.md`§3.3、ADR-0063想定）。`ResponseFormat.
 * json(schema)`（[com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]が呼び出す）へ渡す
 * JSON Schema文字列。[com.actionstarter.ai.schema.PlanJsonSchema]と同型のenum単一情報源パターン。
 *
 * **契約（計画書§3.3）**:
 * ```text
 * options       : array, minItems 1, maxItems 3（§32上限）
 *   semantic_action : string, enum [RecoveryActionType.JSON_VALUES]（4値）
 *   explanation     : string, minLength 1, maxLength 60
 * additionalProperties: false（全階層）
 * ```
 * `skipped_step_ids`・`estimated_arrival`に相当するプロパティは持たせない（§15、計画書§3.3
 * 「AIが触れるのはexplanationのみ」）。
 *
 * Step 4（Green）で[buildSchemaJson]を実装済み。
 */
object RecoveryJsonSchema {

    /**
     * 定数として振る舞う（[PlanJsonSchema.TEXT]と同型の「呼び出しごとに同一文字列を返す」契約）。
     * 初回アクセス時に[buildSchemaJson]で1度だけ構築する。
     */
    val TEXT: String by lazy { buildSchemaJson() }

    private const val PROPERTY_OPTIONS = "options"
    private const val PROPERTY_SEMANTIC_ACTION = "semantic_action"
    private const val PROPERTY_EXPLANATION = "explanation"

    private const val OPTIONS_MIN_ITEMS = 1
    private const val OPTIONS_MAX_ITEMS = 3
    private const val EXPLANATION_MIN_LENGTH = 1
    private const val EXPLANATION_MAX_LENGTH = 60

    /** Step 4（Green）実装済み。[PlanJsonSchema.buildSchemaJson]と同型の組み立て方式。 */
    private fun buildSchemaJson(): String {
        val optionProperties = JSONObject()
            .put(
                PROPERTY_SEMANTIC_ACTION,
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(RecoveryActionType.JSON_VALUES))
            )
            .put(
                PROPERTY_EXPLANATION,
                JSONObject()
                    .put("type", "string")
                    .put("minLength", EXPLANATION_MIN_LENGTH)
                    .put("maxLength", EXPLANATION_MAX_LENGTH)
            )

        val optionSchema = JSONObject()
            .put("type", "object")
            .put("properties", optionProperties)
            .put("required", JSONArray(listOf(PROPERTY_SEMANTIC_ACTION, PROPERTY_EXPLANATION)))
            .put("additionalProperties", false)

        val topLevelProperties = JSONObject()
            .put(
                PROPERTY_OPTIONS,
                JSONObject()
                    .put("type", "array")
                    .put("minItems", OPTIONS_MIN_ITEMS)
                    .put("maxItems", OPTIONS_MAX_ITEMS)
                    .put("items", optionSchema)
            )

        return JSONObject()
            .put("type", "object")
            .put("properties", topLevelProperties)
            .put("required", JSONArray(listOf(PROPERTY_OPTIONS)))
            .put("additionalProperties", false)
            .toString()
    }
}

/**
 * Phase 9新設（計画書§3.3、ADR-0063想定）。`semantic_action`のenum語彙。
 * [com.actionstarter.recovery.BasicRecoveryEngine]の`SEMANTIC_*`定数（private）と1:1対応する
 * 4値。既存`PlanEventType`/`PlanActionType`と同型の「enum→Domain変換をしない設計」
 * （検証後も`String`のまま保持、[com.actionstarter.ai.AIRecoveryOptionResponse.semanticAction]
 * 参照）。
 */
enum class RecoveryActionType {
    KEEP_ALL_STEPS,
    SKIP_OPTIONAL_STEPS,
    SKIP_OPTIONAL_AND_IMPORTANT_STEPS,
    CHANGE_TRANSPORT_MODE;

    companion object {
        /** [RecoveryJsonSchema.TEXT]の`semantic_action` enum配列に使うJSON表現（snake_case）。 */
        val JSON_VALUES: List<String> = entries.map { it.name.lowercase() }
    }
}
