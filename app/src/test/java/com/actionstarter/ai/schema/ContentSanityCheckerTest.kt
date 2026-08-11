package com.actionstarter.ai.schema

import com.actionstarter.ai.AIPlanResponse
import com.actionstarter.ai.AIPlanStepResponse
import com.actionstarter.ai.AIRecoveryOptionResponse
import com.actionstarter.ai.AIRecoveryResponse
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.PersonalExecutionProfile
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * P7-C2c（品質ハーネス`docs/plans/phase7-quality-harness.md`§6②・§10・§11 QH-4〜7・
 * QH-10〜11・QH-15相当）。[ContentSanityChecker]（F95隣接、ADR-0047新設）の失敗テスト（Red）。
 *
 * **契約確定サイクル（P7-C2完了記録・`docs/plans/phase7-local-llm-foundation.md`§14.4申し送り3）
 * が残した空白を埋める**: `ContentSanityChecker`はADR-0047でscaffold新設されたが、対応する
 * Redテストは「P7-C2の66テストの整合調整」の範囲外として作成が見送られていた。本ファイルは
 * P7-C2c（品質ハーネス由来の新設部品へのRed補完サイクル）としてQH-4〜7・QH-10〜11・QH-15
 * 相当のRedテストを新規作成する。
 *
 * **現状のRed原因**: [ContentSanityChecker.check]の本体は`TODO()`のため、以下は全件
 * `NotImplementedError`によりRedになるのが正しい（`SchemaValidatorTest`・
 * `PlanPromptBuilderTest`と同じ、本プロジェクトで確立された規約）。
 *
 * **E1（純JVM、Android Framework非依存）**: [ContentSanityChecker]は`AIPlanResponse`
 * （パース済みKotlinオブジェクト）と[PlanningContext]のみを扱い、JSON文字列やAndroid APIに
 * 依存しないため`@RunWith(RobolectricTestRunner::class)`を要しない。
 *
 * **各検査が独立して発火することの確認方針**: 各テストのfixtureは、検証対象の条件（捏造・
 * 禁止語・titleコピー・locale不整合・重複action_type・長さ超過）のうち**検査対象の1つだけ**を
 * 満たし、他の検査には抵触しないよう構成する（複数条件が同時に成立すると、どの検査が実際に
 * 不合格を発生させたのか切り分けられなくなるため）。
 */
class ContentSanityCheckerTest {

    // ------------------------------------------------------------------
    // フィクスチャヘルパー
    // ------------------------------------------------------------------

    private fun sampleEvent(title: String): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = title,
        notes = null,
        startDate = Instant.parse("2026-08-10T10:00:00Z"),
        locationName = "Shibuya Office",
        coordinates = Coordinate(lat = 35.6595, lon = 139.7005),
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun planningContext(title: String, locale: Locale = Locale.JAPAN): PlanningContext = PlanningContext(
        event = sampleEvent(title = title),
        now = Instant.parse("2026-08-10T07:00:00Z"),
        zoneId = ZoneId.of("UTC"),
        locale = locale,
        transportMode = TransportMode.WALKING,
        travelEstimate = Duration.ofMinutes(20),
        arrivalBuffer = Duration.ofMinutes(10),
        profile = null as PersonalExecutionProfile?
    )

    private fun singleStepResponse(
        eventType: String = "business_meeting",
        actionType: String = "prepare_items",
        displayText: String
    ): AIPlanResponse = AIPlanResponse(
        eventType = eventType,
        steps = listOf(AIPlanStepResponse(actionType = actionType, displayText = displayText))
    )

    // ------------------------------------------------------------------
    // Phase 9（計画書§4.2、コミット2）フィクスチャヘルパー
    // ------------------------------------------------------------------

    private fun recoveryContext(title: String): RecoveryContext = RecoveryContext(
        currentTime = Instant.parse("2026-08-10T09:15:00Z"),
        currentLocation = null,
        event = sampleEvent(title = title),
        unfinishedSteps = emptyList(),
        latestTravelEstimate = Duration.ofMinutes(20),
        plannedDepartureTime = Instant.parse("2026-08-10T09:00:00Z")
    )

    private fun singleOptionRecoveryResponse(semanticAction: String = "keep_all_steps", explanation: String): AIRecoveryResponse =
        AIRecoveryResponse(options = listOf(AIRecoveryOptionResponse(semanticAction = semanticAction, explanation = explanation)))

    /**
     * `PlanPromptBuilder`／`RecoveryPromptBuilder`の実在するja模範例タイトル（計画書§4.2 R1a
     * 検出対象）。実装時点ではbuilder側の`internal`アクセサ経由での取得を想定するが（[Recovery
     * PromptBuilder.fewShotEventTitles]は実装済み、`PlanPromptBuilder`側は未実装——計画書§6
     * への申し送り）、本テストは値そのものを既知の定数として直接使う（checkFewShotEchoの
     * シグネチャは`Set<String>`を受け取るだけであり、供給元の実装詳細に依存しない）。
     */
    private val knownJapaneseFewShotTitles = setOf(
        "結婚式", "歯科検診", "出張", "打ち合わせ", // PlanPromptBuilder.JAPANESE_FEW_SHOT_SEEDS
        "美容院の予約", "取引先との商談" // RecoveryPromptBuilder.JAPANESE_FEW_SHOT_SEEDS
    )

    // ------------------------------------------------------------------
    // QH-4: 捏造検出（数字・時刻・URL含有→不合格。§13/§15/§34）
    // ------------------------------------------------------------------

    // QH-4a: 異常系 - display_textに時刻表記（"10:00"）を含む → 不合格
    @Test
    fun qh4a_displayTextContainsClockTime_isInvalid() {
        val response = singleStepResponse(displayText = "10:00に出発する")
        val context = planningContext(title = "定例ミーティング")

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "時刻表記を含むdisplay_textは捏造検出で不合格になるべきです(QH-4): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // QH-4b: 異常系 - display_textに単位付き数字（"15分"）を含む → 不合格
    @Test
    fun qh4b_displayTextContainsNumberWithUnit_isInvalid() {
        val response = singleStepResponse(displayText = "15分前に到着する")
        val context = planningContext(title = "定例ミーティング")

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "所要分を含むdisplay_textは捏造検出で不合格になるべきです(QH-4・§13/§15): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // QH-4c: 異常系 - display_textに裸の数字（"3"）を含む → 不合格
    @Test
    fun qh4c_displayTextContainsBareDigit_isInvalid() {
        val response = singleStepResponse(displayText = "資料を3部用意する")
        val context = planningContext(title = "定例ミーティング")

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "数字を含むdisplay_textは捏造検出で不合格になるべきです(QH-4): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // QH-4d: 異常系 - display_textにURLを含む → 不合格
    @Test
    fun qh4d_displayTextContainsUrl_isInvalid() {
        val response = singleStepResponse(displayText = "https://shop-info.test/order で確認する")
        val context = planningContext(title = "定例ミーティング")

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "URLを含むdisplay_textは捏造検出で不合格になるべきです(QH-4・§34): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // QH-4e: 異常系 - display_textに"@"を含む（メールアドレス様の捏造）→ 不合格
    // （P7-C7・§34捏造検出の回帰ロック。containsFabricatedContentの"@"分岐が既存テストでは
    // 未検証だったため追加。数字もURLパターンも含まない入力で"@"分岐のみを単独検証する）
    @Test
    fun qh4e_displayTextContainsAtSign_isInvalid() {
        val response = singleStepResponse(displayText = "sales@company.jpへ連絡する")
        val context = planningContext(title = "定例ミーティング")

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "「@」を含むdisplay_textは捏造検出で不合格になるべきです(QH-4e・§34): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // ------------------------------------------------------------------
    // QH-10: 禁止語・プレースホルダ検出
    // ------------------------------------------------------------------

    // QH-10: 異常系 - 禁止語・プレースホルダ（"TODO"/"example"/"<think"/"lorem"/"???"）を
    // 含むdisplay_text → いずれも不合格
    @Test
    fun qh10_displayTextContainsBannedPlaceholderWord_isInvalid() {
        val context = planningContext(title = "定例ミーティング")
        val bannedWords = listOf("TODO", "example", "<think", "lorem", "???")

        bannedWords.forEach { bannedWord ->
            val response = singleStepResponse(displayText = "資料を準備する $bannedWord")

            val result = ContentSanityChecker().check(response, context)

            assertTrue(
                "禁止語/プレースホルダ'$bannedWord'を含むdisplay_textは不合格になるべきです(QH-10): $result",
                result is ContentSanityResult.Invalid
            )
        }
    }

    // ------------------------------------------------------------------
    // QH-11: 決定性（同一入力に同一判定。LLM非依存の決定的処理）
    // ------------------------------------------------------------------

    // QH-11: 正常系 - 同一のresponse/contextに対し2回連続で呼び出しても同一の判定を返す
    // （品質ハーネス§6末尾「②はKotlin決定的処理のみ」）
    @Test
    fun qh11_sameInputTwice_returnsEqualResult() {
        val response = singleStepResponse(displayText = "ご祝儀を準備する")
        val context = planningContext(title = "結婚式")
        val checker = ContentSanityChecker()

        val first = checker.check(response, context)
        val second = checker.check(response, context)

        assertEquals(
            "同一入力に対する判定は2回とも一致するべきです(QH-11・決定的処理)",
            first,
            second
        )
    }

    // ------------------------------------------------------------------
    // QH-5・QH-15: titleコピー検出（緩和版・Gemini G1 CRITICAL #3・反映3）
    // ------------------------------------------------------------------

    // QH-5a: 異常系 - title(6文字以上)とdisplay_textが正規化後に完全一致 → 不合格
    @Test
    fun qh5a_titleLengthAtLeastSix_exactMatchWithDisplayText_isInvalid() {
        val title = "健康診断の予約" // 7文字
        val response = singleStepResponse(displayText = title)
        val context = planningContext(title = title)

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "6文字以上のtitleとdisplay_textが完全一致する場合は不合格になるべきです(QH-5): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // QH-5b: 異常系 - title(6文字以上)がdisplay_textの80%以上を占める逐語コピー → 不合格
    @Test
    fun qh5b_titleLengthAtLeastSix_occupiesAtLeast80PercentOfDisplayText_isInvalid() {
        val title = "四半期定例会議" // 7文字
        val displayText = title + "へ" // 8文字中7文字がtitle = 87.5%（>= 80%）
        val response = singleStepResponse(displayText = displayText)
        val context = planningContext(title = title)

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "titleがdisplay_textの80%以上を占める場合は不合格になるべきです(QH-5・反映3): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // QH-15a: 正常系 - title(6文字未満)を含む自然な言い換えは合格（過検出防止の回帰ロック、
    // Gemini G1 CRITICAL #3・反映3。品質ハーネスが示す代表例そのもの）
    @Test
    fun qh15a_titleLengthUnderSix_naturalParaphraseContainingTitle_isValid() {
        val title = "テニス" // 3文字
        val response = singleStepResponse(displayText = "テニスの準備をする")
        val context = planningContext(title = title)

        val result = ContentSanityChecker().check(response, context)

        assertEquals(
            "6文字未満のtitleを含む自然な言い換えは合格するべきです(QH-15): $result",
            ContentSanityResult.Valid,
            result
        )
    }

    // QH-15b: エッジ - title(6文字未満)とdisplay_textが完全一致してもコピー検査自体が
    // 免除され合格する（(a)の免除は占有率判定より先に適用されることの回帰ロック）
    //
    // **fixture更新（Phase 9コミット2、Plan経路へのR2(c)適用に伴う波及）**: 従来のtitle="テニス"
    // （動詞を含まない裸の名詞）はR1a/R2(b)コピー検査の対象外であっても、独立したR2(c)（動詞相当
    // 表現の存在確認）には抵触しInvalidとなるため、本テストが検証したい「titleコピー検査自体の
    // 免除ロジック」とは無関係な理由でRedへ回帰していた。6文字未満・完全一致という検証対象の
    // 条件を保ったまま、動詞を含む同等の短い文言（"確認する"）へ最小更新した。
    @Test
    fun qh15b_titleLengthUnderSix_exactMatchIsStillExemptAndValid() {
        val title = "確認する" // 4文字
        val response = singleStepResponse(displayText = title)
        val context = planningContext(title = title)

        val result = ContentSanityChecker().check(response, context)

        assertEquals(
            "6文字未満のtitleは完全一致でもコピー検査の対象外として合格するべきです(QH-15・(a))",
            ContentSanityResult.Valid,
            result
        )
    }

    // ------------------------------------------------------------------
    // QH-6: locale整合
    // ------------------------------------------------------------------

    // QH-6a: 異常系 - locale=jaなのにdisplay_textが英語のみ → 不合格
    @Test
    fun qh6a_localeJapanese_displayTextEnglishOnly_isInvalid() {
        val response = singleStepResponse(displayText = "Prepare the documents for the meeting")
        val context = planningContext(title = "定例会議", locale = Locale.JAPAN)

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "locale=jaで英語のみのdisplay_textは不合格になるべきです(QH-6): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // QH-6b: 異常系 - locale=enなのにdisplay_textが日本語 → 不合格（locale整合の逆方向。
    // ContentSanityCheckerのKDoc「localeがenのときほぼLatin文字」の回帰ロック）
    @Test
    fun qh6b_localeEnglish_displayTextJapanese_isInvalid() {
        val response = singleStepResponse(displayText = "資料を準備する")
        val context = planningContext(title = "Quarterly Planning Meeting", locale = Locale.US)

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "locale=enで日本語のdisplay_textは不合格になるべきです(QH-6の逆方向): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // ------------------------------------------------------------------
    // QH-7: 重複action_type検出（uniqueItems相当。ADR-0047裁定4）
    // ------------------------------------------------------------------

    // QH-7: 異常系 - 同一action_typeを持つstepが複数存在 → 不合格（ResponseFormat.json()の
    // LLGuidanceがuniqueItemsを非enforceのため②が担う。ADR-0047裁定4）
    @Test
    fun qh7_duplicateActionTypeAcrossSteps_isInvalid() {
        val response = AIPlanResponse(
            eventType = "business_meeting",
            steps = listOf(
                AIPlanStepResponse(actionType = "prepare_items", displayText = "資料を準備する"),
                AIPlanStepResponse(actionType = "prepare_items", displayText = "パソコンを準備する")
            )
        )
        val context = planningContext(title = "定例会議")

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "同一action_typeを持つstepが複数あれば不合格になるべきです(QH-7・ADR-0047裁定4): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // ------------------------------------------------------------------
    // 長さ上限の再確認（①の再確認、品質ハーネス§6②冒頭）
    // ------------------------------------------------------------------

    // 異常系 - display_textが61文字（①をすり抜けた想定のケース）→ ②でも不合格
    @Test
    fun displayTextExceeds60Chars_isInvalidOnSecondCheckAsWell() {
        val response = singleStepResponse(displayText = "あ".repeat(61))
        val context = planningContext(title = "定例ミーティング")

        val result = ContentSanityChecker().check(response, context)

        assertTrue(
            "61文字のdisplay_textは②でも長さ上限超過として不合格になるべきです" +
                "(①の再確認、品質ハーネス§6②冒頭): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // ------------------------------------------------------------------
    // 正常系: 文脈化された行動文（Semantic Contextualization）は合格
    // ------------------------------------------------------------------

    // 正常系 - 品質ハーネス§3のSemantic Contextualization模範例（結婚式→ご祝儀準備）が
    // 全検査を通過し合格する
    @Test
    fun semanticContextualizationExample_allChecksPass_isValid() {
        val response = AIPlanResponse(
            eventType = "social",
            steps = listOf(
                AIPlanStepResponse(actionType = "finish_current_task", displayText = "今の作業を切り上げる"),
                AIPlanStepResponse(actionType = "prepare_items", displayText = "ご祝儀を準備する"),
                AIPlanStepResponse(actionType = "leave", displayText = "出発する")
            )
        )
        val context = planningContext(title = "結婚式")

        val result = ContentSanityChecker().check(response, context)

        assertEquals(
            "品質ハーネス§3のSemantic Contextualization模範例は全検査を通過し合格するべきです: $result",
            ContentSanityResult.Valid,
            result
        )
    }

    // ------------------------------------------------------------------
    // Phase 9（計画書`docs/plans/phase9-recovery-ai.md`§4.2、コミット2、ADR-0063想定）:
    // checkRecovery — R1a（few-shot模範例タイトルecho検出）・R2（最小品質ヒューリスティック）。
    // checkRecovery本体が`TODO()`のため、全件`NotImplementedError`によりRedになるのが正しい。
    // ------------------------------------------------------------------

    // T-P9-12: 異常・実例接地 - explanationが"歯科検診"（PlanPromptBuilderの実在few-shotタイトルと
    // 完全一致）→R1aでInvalid（Phase 8.5§12.5実例をそのままテストデータに使用）
    @Test
    fun tP9_12_explanationEchoesDentalCheckupFewShotTitle_isInvalid() {
        val response = singleOptionRecoveryResponse(explanation = "歯科検診")
        val context = recoveryContext(title = "映画館")

        val result = ContentSanityChecker().checkRecovery(response, context, knownJapaneseFewShotTitles)

        assertTrue(
            "few-shot模範例タイトル'歯科検診'と完全一致するexplanationはR1aでInvalidになるべきです" +
                "(T-P9-12、Phase 8.5§12.5実例): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // T-P9-13: 異常・実例接地（差し替え） - explanationが"出張"（3番目の模範例タイトルが裸で出る）
    // →R1aでInvalid（両レビュー指摘反映、旧T-P9-13のmodelSteps display_text一致ケースはR1b＝
    // Phase 9.5送りのため本フェーズの対象外）
    @Test
    fun tP9_13_explanationEchoesBusinessTripFewShotTitle_isInvalid() {
        val response = singleOptionRecoveryResponse(explanation = "出張")
        val context = recoveryContext(title = "友人とのランチ")

        val result = ContentSanityChecker().checkRecovery(response, context, knownJapaneseFewShotTitles)

        assertTrue(
            "few-shot模範例タイトル'出張'と完全一致するexplanationはR1aでInvalidになるべきです(T-P9-13): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // T-P9-14: 正常 - 本物の歯科検診の予定に対しexplanationが"保険証を確認する"→R1a・R2いずれにも
    // かからず合格する（両レビューが指摘した誤爆シナリオが、R1a限定化とR2(c)緩和の両方によって
    // 解消されていることの回帰確認）
    @Test
    fun tP9_14_genuineDentalCheckupEvent_insuranceCardExplanation_isValid() {
        val response = singleOptionRecoveryResponse(explanation = "保険証を確認する")
        val context = recoveryContext(title = "歯科検診")

        val result = ContentSanityChecker().checkRecovery(response, context, knownJapaneseFewShotTitles)

        assertEquals(
            "本物の歯科検診の予定に対する正当な言い換えは合格するべきです(T-P9-14): $result",
            ContentSanityResult.Valid,
            result
        )
    }

    // T-P9-15: 異常 - explanationが5文字以下→R2(a)でInvalid
    @Test
    fun tP9_15_explanationBelowMinimumLength_isInvalid() {
        val response = singleOptionRecoveryResponse(explanation = "了解")
        val context = recoveryContext(title = "映画館")

        val result = ContentSanityChecker().checkRecovery(response, context, knownJapaneseFewShotTitles)

        assertTrue(
            "5文字以下のexplanationはR2(a)最小長でInvalidになるべきです(T-P9-15): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // T-P9-16: 異常 - explanationがイベントタイトルの完全一致（既存isTitleCopyの適用確認）→R2(b)で
    // Invalid
    @Test
    fun tP9_16_explanationIsVerbatimEventTitleCopy_isInvalid() {
        val response = singleOptionRecoveryResponse(explanation = "取引先との商談")
        val context = recoveryContext(title = "取引先との商談")

        val result = ContentSanityChecker().checkRecovery(response, context, knownJapaneseFewShotTitles)

        assertTrue(
            "イベントタイトルの逐語コピーはR2(b)でInvalidになるべきです(T-P9-16): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // T-P9-17: 異常 - explanationに動詞相当の語尾/先頭語を一切含まない（例:「歯科検診について」）
    // →R2(c)でInvalid
    @Test
    fun tP9_17_explanationHasNoVerbLikeExpression_isInvalid() {
        val response = singleOptionRecoveryResponse(explanation = "映画館についての一般的な情報")
        val context = recoveryContext(title = "映画館")

        val result = ContentSanityChecker().checkRecovery(response, context, knownJapaneseFewShotTitles)

        assertTrue(
            "動詞相当の語尾/先頭語を含まないexplanationはR2(c)でInvalidになるべきです(T-P9-17): $result",
            result is ContentSanityResult.Invalid
        )
    }

    // T-P9-18: 正常 - 動詞相当を含む自然な文言（例:「保険証を確認する」）→R1a〜R2すべて通過
    @Test
    fun tP9_18_explanationWithVerbLikeExpression_allChecksPass_isValid() {
        val response = singleOptionRecoveryResponse(explanation = "そのまま準備を続けて出発しましょう")
        val context = recoveryContext(title = "映画館")

        val result = ContentSanityChecker().checkRecovery(response, context, knownJapaneseFewShotTitles)

        assertEquals(
            "動詞相当を含む自然な文言は全検査を通過し合格するべきです(T-P9-18): $result",
            ContentSanityResult.Valid,
            result
        )
    }
}
