package com.actionstarter.ai.schema

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-C2／P7契約確定（計画書§12.2・T-SCH-1〜22・Fable 5裁定1〜4・ADR-0045〜ADR-0047）。
 * [SchemaValidator]（F95）の失敗テスト（Red）。
 *
 * **E1（純JVM）で実行する（U-11裁定）**: 本ファイルは`@RunWith(RobolectricTestRunner::class)`を
 * 使わない。`org.json`はAndroid SDK同梱版（純JVM実行時は`Stub!`例外）ではなく、
 * `app/build.gradle.kts`へP7-C2で追加した`testImplementation(libs.org.json)`
 * （pure Java実装、`org.json:json:20250517`）から解決される。
 *
 * **現状のRed原因**: [SchemaValidator.validate]の本体は`TODO()`のため、以下は全件
 * `NotImplementedError`によりRedになるのが正しい（`docs/plans/phase6-recovery-basic.md`以前から
 * 続く本プロジェクトの確立された規約。[com.actionstarter.planning.BasicPlanningEngineTest]の
 * KDoc参照）。
 *
 * **P7契約確定によるスキーマ縮小（Fable 5裁定1、2026-08-10、ADR-0045）**: `estimated_minutes`／
 * `priority`／`skippable`／`type`をLLM出力契約から完全に除去し、`event_type`＋
 * `steps[action_type, display_text]`のみの最小スキーマへ縮小した（Semantic
 * Contextualization、品質ハーネス§2/§5）。[stepJson]／[planJson]フィクスチャヘルパーを
 * この縮小後の形へ更新した。
 *
 * **enum語彙の確定（Fable 5裁定2、2026-08-10、ADR-0046）**: P7-C2完了記録が残した差し戻し
 * 事項2（「`event_type`／`action_type`の確定enum語彙が正仕様書§21に存在せず本サイクルでは
 * 確定できない」）が解決された。`event_type`は[PlanEventType]（8値）、`action_type`は
 * [PlanActionType]（7値）へ確定した。
 *
 * **本サイクルでのケース調整一覧（7件削除・3件更新・16件無変更＋T-RF側2件更新・2件無変更）**:
 * - **削除（フィールド消滅・対応する検証対象が存在しなくなったため）**: T-SCH-4
 *   （priorityがenum外→不合格。priorityフィールド自体が消滅）、T-SCH-9〜12
 *   （estimated_minutesの範囲/型検証4件。フィールド自体が消滅）、T-SCH-20（priority=required
 *   かつskippable=trueの矛盾検証。両フィールドとも消滅）。
 * - **削除（責務移管・Fable 5裁定4、ADR-0047）**: T-SCH-21（同一action_typeの重複ステップ検出）。
 *   `ResponseFormat.json()`のLLGuidanceが`uniqueItems`をenforceしないため元々第2層検証が
 *   必要だったが、裁定4によりこの検出は[SchemaValidator]ではなく新設
 *   [ContentSanityChecker]（②内容sanity）の責務と確定した。本サイクルでは
 *   `ContentSanityChecker`向けの新規Redテストは作成しない（「P7-C2の66テストの整合調整」の
 *   範囲外——新規コンポーネントへの新規テスト作成であり、既存テストの整合調整ではないため。
 *   P7-C3以降の別サイクルへ申し送る）。
 * - **更新（意図を保持しつつ、消滅したフィールドから存続フィールドへ検証対象を差し替え）**:
 *   T-SCH-2（旧: 全`ExecutionStepType`/`StepPriority`enum値が検証を通過する網羅test →
 *   新: 全[PlanEventType]×[PlanActionType]の組み合わせが検証を通過する網羅test。「enum網羅
 *   検証」という意図は保持）。T-SCH-5（旧: `type`がenum外→不合格 → 新: `action_type`が
 *   enum外→不合格。「フィールドレベルのenum拒否」という意図は保持。event_type側の対応は
 *   既存T-SCH-3が担う）。T-SCH-14（旧: 必須フィールド`skippable`欠落→不合格 → 新: 必須
 *   フィールド`action_type`欠落→不合格。「required欠落の拒否」という意図は保持）。
 * - **無変更（フィールド構成に依存しないロジック、または存続フィールドのみを扱うため
 *   影響なし）**: T-SCH-1（内容はフィールド削減に合わせ更新、ケースの主旨=公式例が検証通過
 *   しAIPlanResponseへ写像される、は不変）、T-SCH-3・6・7・8・13・15・16・17・18・19・22
 *   （13件、assertionロジック自体は無変更。[stepJson]/[planJson]ヘルパー更新の影響のみ受ける）。
 * - **T-RF側**: T-RF-1（type/priority enum配列の検証→event_type/action_type enum配列の
 *   検証、requiredセットを縮小後の2フィールドへ更新）・T-RF-3（期待フィールド集合を
 *   2フィールドへ更新）は更新。T-RF-2・T-RF-4は無変更。
 *
 * いずれの調整も「検証内容の意図を保持しassertionを弱めない」方針（本タスク制約）に基づく——
 * 削除ケースは対応する検証対象自体がスキーマから消滅した（またはSchemaValidatorの責務外へ
 * 移管された）ことによるものであり、弱体化ではない。
 *
 * **確定した論点（P7-C1差し戻し#1・P7-C2完了記録の確定論点の再確認）**: `action_type`／
 * `event_type`は検証通過後も[ExecutionStepType]・[StepPriority]のようなDomain enum
 * インスタンスへは変換されず`String`のまま保持される（Fable 5裁定3、[SchemaValidator]の
 * KDoc「責務確定」参照）。Domain enumへの変換・数値/優先度/省略可否の決定的マップは
 * Phase 8 `LocalAIPlanningEngine`の責務であり、本クラスの後段（計画書§18申し送り）。
 */
class SchemaValidatorTest {

    // ------------------------------------------------------------------
    // フィクスチャヘルパー（Fable 5裁定1確定の縮小スキーマに基づく：
    // event_type + steps[action_type, display_text]のみ）
    // ------------------------------------------------------------------

    /**
     * `JSONObject.keySet()`はAndroid SDK同梱org.json（compile classpath上でtestImplementation
     * org.json:20250517と共存する）とpure Java版とで解決が不安定なため使わず、両者に共通して
     * 存在する`keys(): Iterator<*>`だけを使ってキー集合を取得する（T-RF-1・T-RF-3・T-RF-4）。
     */
    private fun JSONObject.propertyNames(): Set<String> {
        val names = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            names.add(iterator.next().toString())
        }
        return names
    }

    private fun stepJson(
        actionType: String = "prepare_items",
        displayText: String = "Prepare documents for the meeting"
    ): JSONObject = JSONObject().apply {
        put("action_type", actionType)
        put("display_text", displayText)
    }

    private fun planJson(
        eventType: String = "business_meeting",
        steps: JSONArray = JSONArray().put(stepJson())
    ): JSONObject = JSONObject().apply {
        put("event_type", eventType)
        put("steps", steps)
    }

    // ------------------------------------------------------------------
    // T-SCH-1〜2: 正常系
    // ------------------------------------------------------------------

    // T-SCH-1: 正常系 - 仕様§20公式JSON例をFable 5裁定1後の縮小スキーマへ適合させた形が検証を
    // 通過しAIPlanResponseへ写像される（更新: 旧type/estimated_minutes/priority/skippableの
    // assertionを削除し、存続フィールドのみを検証）
    @Test
    fun tSch1_officialExampleAdaptedToReducedSchema_validatesAndMapsToAiPlanResponse() {
        val json = planJson(
            eventType = "business_meeting",
            steps = JSONArray().put(
                stepJson(actionType = "prepare_items", displayText = "Prepare documents for the meeting")
            )
        )

        val result = SchemaValidator().validate(json.toString())

        assertTrue(
            "仕様§20公式JSON例（縮小スキーマへ適合）が検証を通過しませんでした: $result",
            result is SchemaValidationResult.Valid
        )
        val response = (result as SchemaValidationResult.Valid).response
        assertEquals("business_meeting", response.eventType)
        assertEquals(1, response.steps.size)
        val step = response.steps.single()
        assertEquals("prepare_items", step.actionType)
        assertEquals("Prepare documents for the meeting", step.displayText)
    }

    // T-SCH-2（更新・Fable 5裁定2でenum語彙確定）: 正常系 - 確定済みのevent_type
    // （PlanEventType、8値）×action_type（PlanActionType、7値）の全組み合わせが検証を通過する
    // （旧: ExecutionStepType×StepPriorityの全組み合わせを検証していたenum網羅テストを、
    // 消滅したtype/priorityから存続するevent_type/action_typeへ検証対象を差し替えた。
    // 「enum網羅検証」という意図は保持）
    @Test
    fun tSch2_allConfirmedEventTypeAndActionTypeValues_validate() {
        PlanEventType.JSON_VALUES.forEach { eventType ->
            PlanActionType.JSON_VALUES.forEach { actionType ->
                val json = planJson(
                    eventType = eventType,
                    steps = JSONArray().put(stepJson(actionType = actionType))
                )

                val result = SchemaValidator().validate(json.toString())

                assertTrue(
                    "event_type=$eventType/action_type=${actionType}が検証を通過しませんでした: $result",
                    result is SchemaValidationResult.Valid
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // T-SCH-3・5: 異常系（enum外）
    // ------------------------------------------------------------------

    // T-SCH-3: 異常系 - event_typeがenum外 → 不合格。理由に該当フィールド名が含まれる
    // （無変更。Fable 5裁定2でevent_type語彙が確定した後もこの負例検証の設計は不変）
    @Test
    fun tSch3_eventTypeOutsideEnum_isInvalidAndReasonMentionsField() {
        val json = planJson(eventType = "totally_unknown_event_category_xyz")

        val result = SchemaValidator().validate(json.toString())

        assertTrue(
            "event_type='totally_unknown_event_category_xyz'は不合格になるべきです: $result",
            result is SchemaValidationResult.Invalid
        )
        val reason = (result as SchemaValidationResult.Invalid).reason
        assertTrue(
            "不合格理由に'event_type'が含まれていません: $reason",
            reason.contains("event_type")
        )
    }

    // T-SCH-5（更新・旧: typeがenum外→不合格）: 異常系 - action_typeがenum外 → 不合格
    // （typeフィールドがFable 5裁定1で消滅したため、「フィールドレベルのenum拒否」という意図を
    // 保持したまま、スキーマに残る唯一のstep内enumフィールドaction_typeへ検証対象を差し替えた）
    @Test
    fun tSch5_actionTypeOutsideEnum_isInvalid() {
        val json = planJson(steps = JSONArray().put(stepJson(actionType = "totally_unknown_action_xyz")))

        val result = SchemaValidator().validate(json.toString())

        assertTrue(result is SchemaValidationResult.Invalid)
        assertTrue(
            "不合格理由に'action_type'が含まれていません: ${(result as SchemaValidationResult.Invalid).reason}",
            result.reason.contains("action_type")
        )
    }

    // ------------------------------------------------------------------
    // T-SCH-6〜8: steps件数（minItems/maxItems）（無変更）
    // ------------------------------------------------------------------

    // T-SCH-6: 異常系 - stepsが空配列（minItems違反）→ 不合格
    @Test
    fun tSch6_emptySteps_isInvalid() {
        val json = planJson(steps = JSONArray())

        val result = SchemaValidator().validate(json.toString())

        assertTrue(result is SchemaValidationResult.Invalid)
    }

    // T-SCH-7: エッジ - stepsが9件（maxItems=8超過）→ 不合格
    @Test
    fun tSch7_nineSteps_exceedsMaxItemsAndIsInvalid() {
        val steps = JSONArray()
        repeat(9) { index -> steps.put(stepJson(actionType = "prepare_items")) }
        val json = planJson(steps = steps)

        val result = SchemaValidator().validate(json.toString())

        assertTrue("steps 9件はmaxItems=8超過のため不合格になるべきです", result is SchemaValidationResult.Invalid)
    }

    // T-SCH-8: エッジ - stepsがちょうど8件 → 合格（境界値）
    @Test
    fun tSch8_eightSteps_boundaryIsValid() {
        val steps = JSONArray()
        repeat(8) { index -> steps.put(stepJson(actionType = "prepare_items")) }
        val json = planJson(steps = steps)

        val result = SchemaValidator().validate(json.toString())

        assertTrue("steps 8件は境界値として合格するべきです: $result", result is SchemaValidationResult.Valid)
        assertEquals(8, (result as SchemaValidationResult.Valid).response.steps.size)
    }

    // ------------------------------------------------------------------
    // T-SCH-13〜15: additionalProperties / required / minLength
    // ------------------------------------------------------------------

    // T-SCH-13: 異常系 - 未知フィールドが存在（additionalProperties:false・全階層）→ 不合格
    // （無変更。stepJson/planJsonヘルパー更新の影響のみ受ける）
    @Test
    fun tSch13_unknownField_isInvalidAtTopLevelAndStepLevel() {
        val topLevelJson = planJson()
        topLevelJson.put("unexpected_top_level_field", "value")
        assertTrue(
            "トップレベルの未知フィールドはadditionalProperties:falseにより不合格になるべきです",
            SchemaValidator().validate(topLevelJson.toString()) is SchemaValidationResult.Invalid
        )

        val stepWithUnknownField = stepJson()
        stepWithUnknownField.put("unexpected_step_field", "value")
        val stepLevelJson = planJson(steps = JSONArray().put(stepWithUnknownField))
        assertTrue(
            "step内の未知フィールドはadditionalProperties:falseにより不合格になるべきです",
            SchemaValidator().validate(stepLevelJson.toString()) is SchemaValidationResult.Invalid
        )
    }

    // T-SCH-14（更新・旧: 必須フィールドskippable欠落→不合格）: 異常系 - 必須フィールド
    // action_type欠落 → 不合格（skippableフィールドがFable 5裁定1で消滅したため、「required
    // 欠落の拒否」という意図を保持したまま、スキーマに残るstep必須フィールドaction_typeへ
    // 検証対象を差し替えた）
    @Test
    fun tSch14_missingRequiredField_actionType_isInvalid() {
        val step = stepJson()
        step.remove("action_type")
        val json = planJson(steps = JSONArray().put(step))

        val result = SchemaValidator().validate(json.toString())

        assertTrue(result is SchemaValidationResult.Invalid)
    }

    // T-SCH-15: 異常系 - display_textが空文字 → 不合格（minLength=1、無変更）
    @Test
    fun tSch15_emptyDisplayText_isInvalid() {
        val json = planJson(steps = JSONArray().put(stepJson(displayText = "")))

        assertTrue(SchemaValidator().validate(json.toString()) is SchemaValidationResult.Invalid)
    }

    // T-SCH-16: エッジ - display_textが61文字→不合格 / 60文字→合格（無変更）
    @Test
    fun tSch16_displayTextBoundary_sixtyValid_sixtyOneInvalid() {
        val sixty = "a".repeat(60)
        val sixtyOne = "a".repeat(61)

        val validResult = SchemaValidator()
            .validate(planJson(steps = JSONArray().put(stepJson(displayText = sixty))).toString())
        assertTrue("display_text 60文字は合格するべきです: $validResult", validResult is SchemaValidationResult.Valid)

        val invalidResult = SchemaValidator()
            .validate(planJson(steps = JSONArray().put(stepJson(displayText = sixtyOne))).toString())
        assertTrue("display_text 61文字は不合格になるべきです", invalidResult is SchemaValidationResult.Invalid)
    }

    // ------------------------------------------------------------------
    // T-SCH-17〜19: 構文異常・前置き文（無変更）
    // ------------------------------------------------------------------

    // T-SCH-17: 異常系 - JSONとして壊れている（閉じ括弧なし）→ 例外を投げず不合格を返す
    @Test
    fun tSch17_malformedJson_missingClosingBrace_doesNotThrowAndIsInvalid() {
        val malformed = "{\"event_type\": \"business_meeting\", \"steps\": ["

        val result = SchemaValidator().validate(malformed)

        assertTrue("壊れたJSONは例外を投げず不合格を返すべきです", result is SchemaValidationResult.Invalid)
    }

    // T-SCH-18: 異常系 - 空文字列入力 → 不合格
    @Test
    fun tSch18_emptyStringInput_isInvalid() {
        assertTrue(SchemaValidator().validate("") is SchemaValidationResult.Invalid)
    }

    // T-SCH-19: 異常系 - JSON前後にモデルの前置き文（```json フェンス等）→ 不合格
    // （文法制約下では起きないはずの事象。黙って剥がさず検出する）
    @Test
    fun tSch19_markdownFencedJson_isInvalidNotSilentlyStripped() {
        val fenced = "```json\n${planJson()}\n```"

        val result = SchemaValidator().validate(fenced)

        assertTrue(
            "```json フェンス付きは黙って剥がさず不合格にするべきです",
            result is SchemaValidationResult.Invalid
        )
    }

    // ------------------------------------------------------------------
    // T-SCH-22: DoS耐性（無変更）
    // ------------------------------------------------------------------

    // T-SCH-22: 異常系 - 深いネスト・巨大配列（DoS的入力）で例外を投げず一定時間内に不合格を返す
    @Test(timeout = 5_000)
    fun tSch22_hugeStepsArray_doesNotThrowAndReturnsInvalidWithinTimeLimit() {
        val steps = JSONArray()
        repeat(50_000) { index -> steps.put(stepJson(actionType = "action_$index")) }
        val json = planJson(steps = steps)

        val result = SchemaValidator().validate(json.toString())

        assertTrue(
            "巨大配列入力は例外を投げず（maxItems=8超過等により）不合格を返すべきです",
            result is SchemaValidationResult.Invalid
        )
    }

    // ------------------------------------------------------------------
    // T-RF-1〜4: PlanJsonSchema.TEXT自体の検証
    // ------------------------------------------------------------------

    // T-RF-1（更新・Fable 5裁定1・2）: 正常系 - PlanJsonSchema.TEXTがJSONとして構文的に妥当で、
    // 確定済みenum値（event_type=PlanEventType、action_type=PlanActionType）・
    // additionalProperties:false・requiredを含む。旧type/priority enum配列の検証を
    // event_type/action_type enum配列の検証へ差し替え、requiredセットを縮小後の2フィールド
    // （action_type, display_text）へ更新した。
    @Test
    fun tRf1_text_isSyntacticallyValidJsonAndContainsConfirmedEnumsAndConstraints() {
        val schema = JSONObject(PlanJsonSchema.TEXT)

        assertEquals("object", schema.getString("type"))
        assertFalse(
            "トップレベルはadditionalProperties:falseであるべきです",
            schema.getBoolean("additionalProperties")
        )

        val topLevelProperties = schema.getJSONObject("properties")
        val eventTypeEnum = topLevelProperties.getJSONObject("event_type").getJSONArray("enum")
            .let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        assertEquals(
            "event_typeのenum配列はPlanEventType（Fable 5裁定2、8値）と一致するべきです",
            PlanEventType.JSON_VALUES.toSet(),
            eventTypeEnum
        )

        val stepSchema = topLevelProperties.getJSONObject("steps").getJSONObject("items")
        assertFalse(
            "step階層もadditionalProperties:falseであるべきです",
            stepSchema.getBoolean("additionalProperties")
        )

        val actionTypeEnum = stepSchema.getJSONObject("properties").getJSONObject("action_type").getJSONArray("enum")
            .let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        assertEquals(
            "action_typeのenum配列はPlanActionType（Fable 5裁定2、7値）と一致するべきです",
            PlanActionType.JSON_VALUES.toSet(),
            actionTypeEnum
        )

        val stepRequired = stepSchema.getJSONArray("required")
            .let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        assertEquals(
            "requiredはFable 5裁定1後の縮小スキーマ（action_type, display_textのみ）と" +
                "一致するべきです",
            setOf("action_type", "display_text"),
            stepRequired
        )
    }

    // T-RF-2: 正常系 - PlanJsonSchema.TEXTが定数であり、呼び出しごとに同一文字列を返す（決定性、無変更）
    @Test
    fun tRf2_text_returnsSameStringOnRepeatedAccess() {
        val first = PlanJsonSchema.TEXT
        val second = PlanJsonSchema.TEXT

        assertEquals(first, second)
    }

    // T-RF-3（更新・Fable 5裁定1）: 正常系 - AIPlanStepResponseの全フィールドがスキーマの
    // propertiesに1:1で存在する（スキーマとKotlinデータクラスの乖離を防ぐ回帰ロック）。
    // 期待フィールド集合を縮小後の2フィールド（action_type, display_text）へ更新した。
    @Test
    fun tRf3_schemaStepProperties_matchAiPlanStepResponseFieldsOneToOne() {
        val schema = JSONObject(PlanJsonSchema.TEXT)
        val stepProperties = schema.getJSONObject("properties")
            .getJSONObject("steps")
            .getJSONObject("items")
            .getJSONObject("properties")

        val expectedFields = setOf("action_type", "display_text")
        assertEquals(expectedFields, stepProperties.propertyNames())
    }

    // T-RF-4: エッジ - スキーマに絶対時刻・ETA・座標に相当するプロパティが存在しない（§15の
    // 機械検証、無変更。フィールド集合が縮小しても検査ロジック自体は変わらない）
    @Test
    fun tRf4_schema_hasNoAbsoluteTimeOrEtaOrCoordinateProperties() {
        val schema = JSONObject(PlanJsonSchema.TEXT)
        val topLevelProperties = schema.getJSONObject("properties").propertyNames()
        val stepProperties = schema.getJSONObject("properties")
            .getJSONObject("steps")
            .getJSONObject("items")
            .getJSONObject("properties")
            .propertyNames()

        val forbiddenSubstrings =
            listOf("arrival", "eta", "latitude", "longitude", "coordinate", "timestamp", "epoch", "instant")
        (topLevelProperties + stepProperties).forEach { name ->
            forbiddenSubstrings.forEach { forbidden ->
                assertFalse(
                    "プロパティ名'$name'に絶対時刻/ETA/座標を示唆する'$forbidden'が含まれています(§15)",
                    name.lowercase().contains(forbidden)
                )
            }
        }
    }
}
