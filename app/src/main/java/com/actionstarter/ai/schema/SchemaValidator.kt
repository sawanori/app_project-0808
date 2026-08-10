package com.actionstarter.ai.schema

import com.actionstarter.ai.AIPlanResponse
import com.actionstarter.ai.AIPlanStepResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * F95実装（計画書§7.1・§8.4・§12.1・§12.2・§14 P7-C1／P7契約確定）。decode時の文法制約
 * （[PlanJsonSchema]）を信用せず独立に行うKotlin側の第1層検証（信頼境界、§20）。
 *
 * **責務確定（Fable 5裁定3、ADR-0047。P7-C2完了記録の差し戻し事項1・3を解決）**: 本クラスは
 * **形式検証＋パースまで**に専念する（`SchemaValidationResult.Valid(response: AIPlanResponse)`。
 * raw Stringを別途保持しない——[ContentSanityChecker]の入力形が`AIPlanResponse`＋
 * `PlanningContext`であり、raw Stringを再利用する消費者が存在しないため）。
 * `action_type`／`event_type`のenum→Domain enumインスタンスへの変換は**行わない**——
 * [AIPlanResponse]／[com.actionstarter.ai.AIPlanStepResponse]は検証後も`String`のまま
 * 保持する設計を維持する（[AIPlanResponse]のKDoc「信頼境界」参照）。§21の意味論的な
 * Domain変換（`action_type`から[com.actionstarter.domain.model.ExecutionStepType]／
 * [com.actionstarter.domain.model.StepPriority]／`skippable`／所要分を決定的に導出する処理）は
 * 本クラスの後段（Phase 8 `LocalAIPlanningEngine`の責務。計画書§18申し送り）に属し、
 * ここでは行わない（P7-C2完了記録が残した「type/priority→Domain enum変換」の帰属論点への
 * 回答。旧`type`／`priority`フィールド自体もFable 5裁定1によりスキーマから除去済み）。
 *
 * **パイプライン上の位置（Fable 5裁定3）**: `LLM生JSON(String)` → **本クラス（①形式）** →
 * [ContentSanityChecker]（②内容） → [com.actionstarter.ai.LocalAiGateway]が
 * `AIPlanResponse`を保持。
 *
 * **重複action_type検出は本クラスの責務ではない（Fable 5裁定4）**: `ResponseFormat.json()`の
 * LLGuidanceが`uniqueItems`をenforceしないため第2層での検出が必要だが、この検出は
 * [ContentSanityChecker]（②内容sanity）へ寄せる設計とした（品質ハーネスUQ-3）。本クラスは
 * 形式（enum・件数・長さ・`additionalProperties`）のみを見る。
 *
 * **本番はAndroid同梱`org.json`、テストのみpure Java版（U-11・Gemini G1 CRITICAL #5）**:
 * 本番実装（P7-C3）はAndroid SDK同梱の`org.json`をそのまま使う（新規依存を増やさない）。
 * ただし同梱版は純JVM実行時にはスタブで`Stub!`例外を送出するため、Robolectric非経由のE1
 * テストで実クラスとして動かすには`testImplementation("org.json:json:...")`（pure Java実装）が
 * 必要（U-11。P7-C2で`app/build.gradle.kts`へ追加済み）。
 *
 * **Phase 7のスコープはPlanning経路のみ（§2.2）**: [com.actionstarter.ai.AIRecoveryResponse]
 * （`skippedStepIds`のUUID変換を要する）の検証はPhase 7の対象外。Phase 9で`generateRecovery`を
 * 実装する際に対となる検証メソッドを追加する（§18申し送り5）。
 *
 * **P7-C3実装済み（Green）**: `org.json`（本番はAndroid SDK同梱、テストは`org.json:json`の
 * pure Java実装。U-11）でパースし、[PlanEventType.JSON_VALUES]／[PlanActionType.JSON_VALUES]を
 * 単一情報源としてmembership検証する（T-SCH-1〜3・5〜8・13〜19・22。契約確定によるP7-C2テスト
 * 整合調整の詳細は`SchemaValidatorTest`のクラスKDoc参照）。**例外を一切外へ投げない**
 * （T-SCH-17「壊れたJSON」・T-SCH-18「空文字列」・T-SCH-19「前置き文」・T-SCH-22
 * 「DoS的巨大入力」のいずれも、`org.json`の例外を`try/catch`で捕捉し不合格として返す）。
 * `additionalProperties:false`はキー集合の完全一致比較（許可リスト外のキーが1つでもあれば
 * 不合格）で担保する。`JSONObject.keySet()`はAndroid同梱実装とpure Java実装とで解決が不安定な
 * ため使わず、両者に共通する`keys(): Iterator<*>`のみでキー集合を取得する
 * （`SchemaValidatorTest.propertyNames()`と同じ方式）。
 */
class SchemaValidator {

    /** [rawJson]（LLM生出力テキスト）を検証する。例外は一切外へ投げない（T-SCH-17・18・19・22）。 */
    fun validate(rawJson: String): SchemaValidationResult {
        return try {
            val root = JSONObject(rawJson)
            validateRoot(root)
        } catch (e: Exception) {
            // org.jsonのJSONException（壊れたJSON・空文字列・フェンス付き前置き文等、
            // T-SCH-17・18・19）を含む、パース段の全例外をここで握り潰さず不合格へ写像する
            // （信頼境界。§20「Schema validation必須」）。
            SchemaValidationResult.Invalid(
                "rawJson is not syntactically valid JSON: ${e::class.simpleName}: ${e.message}"
            )
        }
    }

    private fun validateRoot(root: JSONObject): SchemaValidationResult {
        val unknownTopLevelFields = root.propertyNames() - TOP_LEVEL_FIELDS
        if (unknownTopLevelFields.isNotEmpty()) {
            return SchemaValidationResult.Invalid(
                "Unknown top-level field(s) not permitted by additionalProperties=false: " +
                    unknownTopLevelFields.joinToString()
            )
        }

        val eventType = readRequiredString(root, FIELD_EVENT_TYPE)
            ?: return SchemaValidationResult.Invalid("Missing or non-string required field: $FIELD_EVENT_TYPE")
        if (eventType !in PlanEventType.JSON_VALUES) {
            return SchemaValidationResult.Invalid(
                "Field $FIELD_EVENT_TYPE has a value outside the confirmed enum (PlanEventType): $eventType"
            )
        }

        if (!root.has(FIELD_STEPS) || root.isNull(FIELD_STEPS)) {
            return SchemaValidationResult.Invalid("Missing required field: $FIELD_STEPS")
        }
        val stepsArray = root.opt(FIELD_STEPS) as? JSONArray
            ?: return SchemaValidationResult.Invalid("Field $FIELD_STEPS must be a JSON array")

        // maxItems超過は個々のstepを一切検証せず即座に弾く（T-SCH-22のDoS耐性。50,000件配列でも
        // ここで打ち切るため要素走査コストが発生しない）。
        if (stepsArray.length() < STEPS_MIN_ITEMS || stepsArray.length() > STEPS_MAX_ITEMS) {
            return SchemaValidationResult.Invalid(
                "Field $FIELD_STEPS must contain between $STEPS_MIN_ITEMS and $STEPS_MAX_ITEMS items " +
                    "(actual: ${stepsArray.length()})"
            )
        }

        val steps = ArrayList<AIPlanStepResponse>(stepsArray.length())
        for (index in 0 until stepsArray.length()) {
            val stepObject = stepsArray.opt(index) as? JSONObject
                ?: return SchemaValidationResult.Invalid("$FIELD_STEPS[$index] must be a JSON object")
            when (val stepResult = validateStep(stepObject, index)) {
                is StepValidation.Invalid -> return SchemaValidationResult.Invalid(stepResult.reason)
                is StepValidation.Valid -> steps.add(stepResult.step)
            }
        }

        return SchemaValidationResult.Valid(AIPlanResponse(eventType = eventType, steps = steps))
    }

    private sealed interface StepValidation {
        data class Valid(val step: AIPlanStepResponse) : StepValidation
        data class Invalid(val reason: String) : StepValidation
    }

    private fun validateStep(step: JSONObject, index: Int): StepValidation {
        val unknownStepFields = step.propertyNames() - STEP_FIELDS
        if (unknownStepFields.isNotEmpty()) {
            return StepValidation.Invalid(
                "Unknown field(s) in $FIELD_STEPS[$index] not permitted by additionalProperties=false: " +
                    unknownStepFields.joinToString()
            )
        }

        val actionType = readRequiredString(step, FIELD_ACTION_TYPE)
            ?: return StepValidation.Invalid(
                "Missing or non-string required field: $FIELD_ACTION_TYPE ($FIELD_STEPS[$index])"
            )
        if (actionType !in PlanActionType.JSON_VALUES) {
            return StepValidation.Invalid(
                "Field $FIELD_ACTION_TYPE has a value outside the confirmed enum (PlanActionType): " +
                    "$actionType ($FIELD_STEPS[$index])"
            )
        }

        val displayText = readRequiredString(step, FIELD_DISPLAY_TEXT)
            ?: return StepValidation.Invalid(
                "Missing or non-string required field: $FIELD_DISPLAY_TEXT ($FIELD_STEPS[$index])"
            )
        if (displayText.length < DISPLAY_TEXT_MIN_LENGTH || displayText.length > DISPLAY_TEXT_MAX_LENGTH) {
            return StepValidation.Invalid(
                "Field $FIELD_DISPLAY_TEXT length must be between $DISPLAY_TEXT_MIN_LENGTH and " +
                    "$DISPLAY_TEXT_MAX_LENGTH (actual: ${displayText.length}, $FIELD_STEPS[$index])"
            )
        }

        return StepValidation.Valid(AIPlanStepResponse(actionType = actionType, displayText = displayText))
    }

    /** [key]が存在し、かつ非null・String型のときのみ値を返す。それ以外は`null`。 */
    private fun readRequiredString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.opt(key) as? String
    }

    /**
     * `JSONObject.keySet()`はAndroid同梱org.json（本番）とpure Java実装（テスト、U-11）とで
     * 解決が不安定なため使わず、両者に共通する`keys(): Iterator<*>`のみでキー集合を取得する
     * （`SchemaValidatorTest.propertyNames()`と同じ方式）。
     */
    private fun JSONObject.propertyNames(): Set<String> {
        val names = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            names.add(iterator.next().toString())
        }
        return names
    }

    companion object {
        private const val FIELD_EVENT_TYPE = "event_type"
        private const val FIELD_STEPS = "steps"
        private const val FIELD_ACTION_TYPE = "action_type"
        private const val FIELD_DISPLAY_TEXT = "display_text"

        private val TOP_LEVEL_FIELDS = setOf(FIELD_EVENT_TYPE, FIELD_STEPS)
        private val STEP_FIELDS = setOf(FIELD_ACTION_TYPE, FIELD_DISPLAY_TEXT)

        private const val STEPS_MIN_ITEMS = 1
        private const val STEPS_MAX_ITEMS = 8
        private const val DISPLAY_TEXT_MIN_LENGTH = 1
        private const val DISPLAY_TEXT_MAX_LENGTH = 60
    }
}

/** [SchemaValidator.validate]の戻り値。 */
sealed interface SchemaValidationResult {
    data class Valid(val response: AIPlanResponse) : SchemaValidationResult

    /** [reason]は不合格の理由（該当フィールド名を含む、T-SCH-3・5・13〜19）。 */
    data class Invalid(val reason: String) : SchemaValidationResult
}
