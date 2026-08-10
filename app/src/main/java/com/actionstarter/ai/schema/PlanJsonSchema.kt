package com.actionstarter.ai.schema

import org.json.JSONArray
import org.json.JSONObject

/**
 * F94実装（計画書§7.1・§8.4・§12.2・§14 P7-C1／P7契約確定）。`ResponseFormat.json(schema)`
 * （[com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]がP7-C5で呼び出す）へ渡すJSON
 * Schema文字列。
 *
 * **契約確定（Fable 5裁定1・2、ADR-0045・ADR-0046。P7-C2完了記録の差し戻し事項2を解決）**:
 * 品質ハーネス（`docs/plans/phase7-quality-harness.md`§2・§5、Semantic Contextualization）を
 * 統合し、LLMの出力面積を「分類（`event_type`／`action_type`）」と「予定固有の文脈化された
 * 行動文生成（`display_text`）」のみへ最小化した。**`estimated_minutes`／`priority`／
 * `skippable`／`type`（[com.actionstarter.domain.model.ExecutionStepType]相当）はLLM出力から
 * 完全に除去**し、Kotlin側の決定的計算（`action_type`からのマップ、実装はPhase 8
 * `LocalAIPlanningEngine`の責務。計画書§18申し送り参照）に一本化した（§13「数値計算は必ず
 * 通常コード」・§15「時刻演算をLLMに渡さない」）。
 *
 * **契約（確定）**:
 * ```text
 * event_type   : string, enum [PlanEventType.JSON_VALUES]（8値、Fable 5裁定2）
 * steps        : array, minItems 1, maxItems 8
 *   action_type : string, enum [PlanActionType.JSON_VALUES]（7値、Fable 5裁定2）
 *   display_text: string, minLength 1, maxLength 60（**enum非制約の自由文**。
 *                 Semantic Contextualizationの自由度を残す唯一のフィールド）
 * additionalProperties: false（全階層）
 * ```
 * 絶対時刻・ETA・座標に相当するプロパティは持たせない（§15、T-RF-4）。
 *
 * **P7-C3実装済み（Green）**: [TEXT]はFable 5裁定1・2（ADR-0045・ADR-0046）で確定した契約を
 * [PlanEventType.JSON_VALUES]／[PlanActionType.JSON_VALUES]から**単一情報源**として組み立てる
 * JSON Schema文字列リテラルである（enum語彙の変更が発生してもここを手で書き換える必要が
 * ない設計。T-RF-1参照）。`by lazy`で初回アクセス時に1度だけ構築し、以後は同一インスタンスを
 * 返す（T-RF-2「呼び出しごとに同一文字列を返す」の決定性を、再計算コストなしで満たす）。
 * `const val`にできないのは値がenumから動的に組み立てられるためだが、実質的に不変の定数として
 * 振る舞う。
 *
 * 構築には`org.json`（本番はAndroid SDK同梱、テストは`org.json:json`のpure Java実装。U-11）を
 * 用いる。§8.4の契約どおり`additionalProperties:false`を全階層に持たせ、絶対時刻・ETA・座標に
 * 相当するプロパティは一切含めない（T-RF-4）。
 */
object PlanJsonSchema {

    private const val PROPERTY_EVENT_TYPE = "event_type"
    private const val PROPERTY_STEPS = "steps"
    private const val PROPERTY_ACTION_TYPE = "action_type"
    private const val PROPERTY_DISPLAY_TEXT = "display_text"

    private const val STEPS_MIN_ITEMS = 1
    private const val STEPS_MAX_ITEMS = 8
    private const val DISPLAY_TEXT_MIN_LENGTH = 1
    private const val DISPLAY_TEXT_MAX_LENGTH = 60

    /**
     * 定数として振る舞う（T-RF-2「呼び出しごとに同一文字列を返す」）文字列プロパティ。
     * 初回アクセス時に[buildSchemaJson]で1度だけ構築し、以後同一インスタンスを返す。
     */
    val TEXT: String by lazy { buildSchemaJson() }

    private fun buildSchemaJson(): String {
        val stepProperties = JSONObject()
            .put(
                PROPERTY_ACTION_TYPE,
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(PlanActionType.JSON_VALUES))
            )
            .put(
                PROPERTY_DISPLAY_TEXT,
                JSONObject()
                    .put("type", "string")
                    .put("minLength", DISPLAY_TEXT_MIN_LENGTH)
                    .put("maxLength", DISPLAY_TEXT_MAX_LENGTH)
            )

        val stepSchema = JSONObject()
            .put("type", "object")
            .put("properties", stepProperties)
            .put("required", JSONArray(listOf(PROPERTY_ACTION_TYPE, PROPERTY_DISPLAY_TEXT)))
            .put("additionalProperties", false)

        val topLevelProperties = JSONObject()
            .put(
                PROPERTY_EVENT_TYPE,
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(PlanEventType.JSON_VALUES))
            )
            .put(
                PROPERTY_STEPS,
                JSONObject()
                    .put("type", "array")
                    .put("minItems", STEPS_MIN_ITEMS)
                    .put("maxItems", STEPS_MAX_ITEMS)
                    .put("items", stepSchema)
            )

        return JSONObject()
            .put("type", "object")
            .put("properties", topLevelProperties)
            .put("required", JSONArray(listOf(PROPERTY_EVENT_TYPE, PROPERTY_STEPS)))
            .put("additionalProperties", false)
            .toString()
    }
}

/**
 * Fable 5裁定2（2026-08-10、ADR-0046）で確定した`event_type`（予定分類）のenum語彙。8値。
 *
 * LLMが生成する予定分類。[PlanJsonSchema.TEXT]の`event_type`フィールドのenum制約
 * （`ResponseFormat.json()`のLLGuidanceがdecode時にenforce、品質ハーネス§1）と、
 * [com.actionstarter.ai.schema.SchemaValidator]のmembership検証の単一情報源。
 *
 * **enum→Domain変換をしない設計**: [com.actionstarter.ai.AIPlanResponse.eventType]は
 * 検証後もこの enum のインスタンスではなく`String`のまま保持する（Fable 5裁定3。
 * `action_type`をStringのまま保持する既存前例——[com.actionstarter.domain.model.
 * RecoveryOption.semanticAction]・[com.actionstarter.domain.model.ExecutionStep.semanticId]と
 * 同型の設計——を踏襲）。
 *
 * **語彙の再検証条項（裁定2）**: 実機プローブ（P7-C8）で語彙の過不足を再検証する。
 */
enum class PlanEventType {
    BUSINESS_MEETING,
    MEDICAL,
    SOCIAL,
    MEAL,
    TRAVEL,
    ERRAND,
    PERSONAL,
    OTHER;

    companion object {
        /** [PlanJsonSchema.TEXT]の`event_type` enum配列に使うJSON表現（snake_case）。 */
        val JSON_VALUES: List<String> = entries.map { it.name.lowercase() }
    }
}

/**
 * Fable 5裁定2（2026-08-10、ADR-0046）で確定した`action_type`（行動種別）のenum語彙。7値。
 * Basic版のステップ意味（`semanticId`、[com.actionstarter.planning.BasicPlanningEngine]）に
 * 対応させてあり、Kotlin側（Phase 8 `LocalAIPlanningEngine`の責務）がこれで
 * `skippable`／`priority`／ステップ種別（[com.actionstarter.domain.model.ExecutionStepType]）を
 * 決定的にマップする（品質ハーネス§2「Kotlinはaction_type(enum)でskippable/priority/時刻を
 * 決定」）。
 *
 * `display_text`（自由文・enum非制約）とは異なり、`action_type`は生成段階でこの7値へ
 * LLGuidanceにより強制される（品質ハーネス§1・§5）。
 *
 * **enum→Domain変換をしない設計**: [PlanEventType]のKDoc参照。
 *
 * **語彙の再検証条項（裁定2）**: 実機プローブ（P7-C8）で語彙の過不足を再検証する。
 */
enum class PlanActionType {
    FINISH_CURRENT_TASK,
    PREPARE_ITEMS,
    GET_READY,
    GATHER_BELONGINGS,
    LEAVE,
    COMMUTE,
    ARRIVE;

    companion object {
        /** [PlanJsonSchema.TEXT]の`action_type` enum配列に使うJSON表現（snake_case）。 */
        val JSON_VALUES: List<String> = entries.map { it.name.lowercase() }
    }
}
