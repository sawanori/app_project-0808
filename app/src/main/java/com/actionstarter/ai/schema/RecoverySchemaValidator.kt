package com.actionstarter.ai.schema

import com.actionstarter.ai.AIRecoveryOptionResponse
import com.actionstarter.ai.AIRecoveryResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 9実装（計画書`docs/plans/phase9-recovery-ai.md`§3.3、ADR-0063想定）。decode時の文法制約
 * （[RecoveryJsonSchema]）を信用せず独立に行うKotlin側の第1層検証（信頼境界、§20）。
 * [SchemaValidator]と対になる、Recovery経路専用の検証器（[SchemaValidator]のKDoc
 * 「Phase 9でgenerateRecoveryを実装する際に対となる検証メソッドを追加する」への対応）。
 *
 * **pairing検証（[SchemaValidator]にはない、Recovery固有の追加責務）**: 形式検証（enum・件数・
 * 長さ・additionalProperties）に加え、返却された`semantic_action`の集合が[expectedSemanticActions]
 * （`BasicRecoveryEngine`が既に決定した候補集合、呼び出し側が渡す）と完全一致（順不同・重複禁止）
 * しなければ`Invalid`とする。pairing不一致は「入力と出力の対応が壊れている」という形式契約違反
 * であり、[ContentSanityChecker]（②内容sanity）の責務ではなく本クラス（①形式）の責務とする
 * （計画書§3.3、重複action_type検出をContentSanityCheckerへ寄せたPlanの先例〔ADR-0047〕とは
 * 異なる判断であることに注意）。
 *
 * **JSONクレンジング方針（計画書§3.4・§13 A-8訂正）**: [SchemaValidator]と同じ非寛容パース方針
 * （コードフェンス等の前置き文が混入していれば剥がさず`Invalid`とする、
 * `SchemaValidatorTest.tSch19_markdownFencedJson_isInvalidNotSilentlyStripped`と同型の回帰）を
 * 独立に採用する。
 *
 * Step 4（Green）で[validate]を実装済み。
 */
class RecoverySchemaValidator {

    /**
     * [rawJson]（LLM生出力テキスト）を[expectedSemanticActions]（`BasicRecoveryEngine`が既に
     * 決定した候補の`semanticAction`集合）と突き合わせて検証する。例外は一切外へ投げない
     * （[SchemaValidator.validate]と同型の契約）。
     */
    fun validate(rawJson: String, expectedSemanticActions: Set<String>): RecoverySchemaValidationResult {
        return try {
            val root = JSONObject(rawJson)
            validateRoot(root, expectedSemanticActions)
        } catch (e: Exception) {
            // org.jsonのJSONException（壊れたJSON・空文字列・フェンス付き前置き文等）を含む、
            // パース段の全例外をここで握り潰さず不合格へ写像する（[SchemaValidator.validate]と
            // 同型の信頼境界）。
            RecoverySchemaValidationResult.Invalid(
                "rawJson is not syntactically valid JSON: ${e::class.simpleName}: ${e.message}"
            )
        }
    }

    private fun validateRoot(root: JSONObject, expectedSemanticActions: Set<String>): RecoverySchemaValidationResult {
        val unknownTopLevelFields = root.propertyNames() - TOP_LEVEL_FIELDS
        if (unknownTopLevelFields.isNotEmpty()) {
            return RecoverySchemaValidationResult.Invalid(
                "Unknown top-level field(s) not permitted by additionalProperties=false: " +
                    unknownTopLevelFields.joinToString()
            )
        }

        if (!root.has(FIELD_OPTIONS) || root.isNull(FIELD_OPTIONS)) {
            return RecoverySchemaValidationResult.Invalid("Missing required field: $FIELD_OPTIONS")
        }
        val optionsArray = root.opt(FIELD_OPTIONS) as? JSONArray
            ?: return RecoverySchemaValidationResult.Invalid("Field $FIELD_OPTIONS must be a JSON array")

        if (optionsArray.length() < OPTIONS_MIN_ITEMS || optionsArray.length() > OPTIONS_MAX_ITEMS) {
            return RecoverySchemaValidationResult.Invalid(
                "Field $FIELD_OPTIONS must contain between $OPTIONS_MIN_ITEMS and $OPTIONS_MAX_ITEMS items " +
                    "(actual: ${optionsArray.length()})"
            )
        }

        val options = ArrayList<AIRecoveryOptionResponse>(optionsArray.length())
        for (index in 0 until optionsArray.length()) {
            val optionObject = optionsArray.opt(index) as? JSONObject
                ?: return RecoverySchemaValidationResult.Invalid("$FIELD_OPTIONS[$index] must be a JSON object")
            when (val optionResult = validateOption(optionObject, index)) {
                is OptionValidation.Invalid -> return RecoverySchemaValidationResult.Invalid(optionResult.reason)
                is OptionValidation.Valid -> options.add(optionResult.option)
            }
        }

        // pairing検証（計画書§3.3、SchemaValidatorにはないRecovery固有の責務）: 返却された
        // semantic_actionの集合が、Kotlin側が既に決定したexpectedSemanticActionsと完全一致
        // （順不同・重複禁止）しなければInvalidとする。
        val returnedActions = options.map { it.semanticAction }
        if (returnedActions.toSet().size != returnedActions.size) {
            return RecoverySchemaValidationResult.Invalid("Duplicate semantic_action detected across options: $returnedActions")
        }
        if (returnedActions.toSet() != expectedSemanticActions) {
            return RecoverySchemaValidationResult.Invalid(
                "Returned semantic_action set does not match the expected set " +
                    "(expected=$expectedSemanticActions, actual=${returnedActions.toSet()})"
            )
        }

        return RecoverySchemaValidationResult.Valid(AIRecoveryResponse(options = options))
    }

    private sealed interface OptionValidation {
        data class Valid(val option: AIRecoveryOptionResponse) : OptionValidation
        data class Invalid(val reason: String) : OptionValidation
    }

    private fun validateOption(option: JSONObject, index: Int): OptionValidation {
        val unknownOptionFields = option.propertyNames() - OPTION_FIELDS
        if (unknownOptionFields.isNotEmpty()) {
            return OptionValidation.Invalid(
                "Unknown field(s) in $FIELD_OPTIONS[$index] not permitted by additionalProperties=false: " +
                    unknownOptionFields.joinToString()
            )
        }

        val semanticAction = readRequiredString(option, FIELD_SEMANTIC_ACTION)
            ?: return OptionValidation.Invalid(
                "Missing or non-string required field: $FIELD_SEMANTIC_ACTION ($FIELD_OPTIONS[$index])"
            )
        if (semanticAction !in RecoveryActionType.JSON_VALUES) {
            return OptionValidation.Invalid(
                "Field $FIELD_SEMANTIC_ACTION has a value outside the confirmed enum (RecoveryActionType): " +
                    "$semanticAction ($FIELD_OPTIONS[$index])"
            )
        }

        val explanation = readRequiredString(option, FIELD_EXPLANATION)
            ?: return OptionValidation.Invalid(
                "Missing or non-string required field: $FIELD_EXPLANATION ($FIELD_OPTIONS[$index])"
            )
        if (explanation.length < EXPLANATION_MIN_LENGTH || explanation.length > EXPLANATION_MAX_LENGTH) {
            return OptionValidation.Invalid(
                "Field $FIELD_EXPLANATION length must be between $EXPLANATION_MIN_LENGTH and " +
                    "$EXPLANATION_MAX_LENGTH (actual: ${explanation.length}, $FIELD_OPTIONS[$index])"
            )
        }

        return OptionValidation.Valid(AIRecoveryOptionResponse(semanticAction = semanticAction, explanation = explanation))
    }

    /** [key]が存在し、かつ非null・String型のときのみ値を返す（[SchemaValidator]と同型）。 */
    private fun readRequiredString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.opt(key) as? String
    }

    /**
     * `JSONObject.keySet()`はAndroid同梱org.json（本番）とpure Java実装（テスト）とで解決が
     * 不安定なため使わず、両者に共通する`keys(): Iterator<*>`のみでキー集合を取得する
     * （[SchemaValidator.propertyNames]と同型）。
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
        private const val FIELD_OPTIONS = "options"
        private const val FIELD_SEMANTIC_ACTION = "semantic_action"
        private const val FIELD_EXPLANATION = "explanation"

        private val TOP_LEVEL_FIELDS = setOf(FIELD_OPTIONS)
        private val OPTION_FIELDS = setOf(FIELD_SEMANTIC_ACTION, FIELD_EXPLANATION)

        private const val OPTIONS_MIN_ITEMS = 1
        private const val OPTIONS_MAX_ITEMS = 3
        private const val EXPLANATION_MIN_LENGTH = 1
        private const val EXPLANATION_MAX_LENGTH = 60
    }
}

/** [RecoverySchemaValidator.validate]の戻り値。[SchemaValidationResult]と対になる形にしてある。 */
sealed interface RecoverySchemaValidationResult {
    data class Valid(val response: AIRecoveryResponse) : RecoverySchemaValidationResult

    /** [reason]は不合格の理由（形式違反かpairing不一致かを識別できる情報を含む）。 */
    data class Invalid(val reason: String) : RecoverySchemaValidationResult
}
