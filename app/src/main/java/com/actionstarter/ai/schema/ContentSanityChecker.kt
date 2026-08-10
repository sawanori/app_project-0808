package com.actionstarter.ai.schema

import com.actionstarter.ai.AIPlanResponse
import com.actionstarter.domain.model.PlanningContext

/**
 * F95隣接・新設（Fable 5裁定4、2026-08-10、ADR-0047）。品質ハーネス
 * （`docs/plans/phase7-quality-harness.md`§6②・§10・§11 QH-4〜7・QH-10〜11・QH-15）の
 * 「②内容sanity検証」をscaffold化したコンポーネント。
 *
 * **パイプライン上の位置（Fable 5裁定3、ADR-0047）**:
 * ```text
 * LLM生JSON(String) → SchemaValidator.validate(rawJson)[①形式] → ContentSanityChecker[②内容]
 *   → パース済み AIPlanResponse を LocalAiGateway が保持
 * ```
 * [SchemaValidator]（①形式検証・enum/件数/長さ/additionalProperties）とは責務を分離した
 * 独立コンポーネント（品質ハーネスUQ-3で採用確定）。①を通過した
 * [com.actionstarter.ai.schema.SchemaValidationResult.Valid.response]を入力に取り、①が
 * 検出できない・検出すべきでない「内容」レベルの不備を判定する。
 *
 * **責務（品質ハーネス§6②・裁定4、5項目）**:
 * 1. **display_textの長さ上限の再確認**（60字、①の再確認。①をすり抜けたケースの保険）
 * 2. **禁止語／プレースホルダ検出**（例: `"TODO"`／`"example"`／`"lorem"`／`"???"`／
 *    `"<think"`等。品質ハーネスQH-10）
 * 3. **捏造検出**（§13／§15／§34）: `display_text`に数字・時刻（`:`や「時」「分」）・URL・
 *    `@`・住所らしい語を含んだら不合格（数値・時刻はKotlin専任のはずであり、LLMの
 *    `display_text`に現れること自体が§13/§15からの逸脱。品質ハーネスQH-4）
 * 4. **titleコピー検出（緩和版・Gemini G1 CRITICAL #3反映）**: (a) イベントtitleが6文字未満
 *    なら本検査を適用しない（過検出防止）。(b) それ以外は、正規化後の`display_text`との
 *    完全一致、または`display_text`の80%以上をtitleが占める場合にのみ不合格とする
 *    （「テニスの準備をする」のような自然な言い換え・部分包含は合格させる。品質ハーネス
 *    QH-5・QH-15）
 * 5. **locale整合**: `context.locale`がjaのとき`display_text`が日本語書記素を含む、
 *    enのときほぼLatin文字であることを確認（品質ハーネスQH-6）
 * 6. **重複action_type検出**（`uniqueItems`相当）: `ResponseFormat.json()`のLLGuidanceが
 *    `uniqueItems`をenforceしないため（品質ハーネス§1・§5「LLGuidanceが**enforceしない**:
 *    `uniqueItems`」）、[SchemaValidator]ではなく本コンポーネントがこの検出を担う
 *    （P7-C2完了記録が残した論点「重複action_typeの担当（①か②か）」への回答。
 *    品質ハーネスUQ-3・T-SCH-21相当）
 *
 * **決定的・PII非出力**（LLM-judgeを使わない。品質ハーネス§6末尾「②はKotlin決定的処理の
 * み...すべて観測可能・PII非出力・端末内完結」）。同一入力には常に同一判定を返す。
 *
 * **`LocalAiGateway`への配線は本サイクルでは行わない**: [com.actionstarter.ai.LocalAiGateway]
 * のコンストラクタへ本クラスを追加するとAppContainerの配線変更が本タスクの制約
 * （「裁定5のinterface化に必要な最小変更のみ可」）を超えるため、本サイクルでは見送る。
 * P7-C5（Green: adapter/gateway）で[com.actionstarter.ai.LocalAiGateway]のパイプライン
 * 実装と同時に注入すること（[com.actionstarter.ai.LocalAiGateway]のKDoc参照）。
 *
 * **Redテスト（P7-C2c、2026-08-10、test-writer）**: P7契約確定サイクル（§14.4）時点では
 * 「本クラスは契約確定パス（scaffold＋TODO本体）の対象であり、対応するRedテストの作成は
 * 本タスクの範囲外」として申し送られていたが、P7-C2c（品質ハーネス由来の新設部品への
 * Red補完サイクル）で[com.actionstarter.ai.schema.ContentSanityCheckerTest]
 * （品質ハーネスQH-4〜7・QH-10〜11・QH-15相当）を新設した。本体が`TODO()`のため全件
 * `NotImplementedError`によりRed。P7-C3でGreen化すること。
 *
 * **P7-C3実装済み（Green）**: 決定的・PII非出力（LLM-judgeを使わない。品質ハーネス§6末尾）。
 * 各stepを順に検査し、最初に見つかった不合格を理由付きで返す（QH-4a〜d・QH-10・QH-5・QH-15・
 * QH-6a〜b）。全step通過後にstep横断の重複`action_type`検出（QH-7）を行う。
 *
 * **titleコピー検出の閾値**（Gemini G1 CRITICAL #3・反映3）: イベントtitleの正規化後の長さが
 * 6文字未満なら本検査自体を適用しない（QH-15a・b、過検出防止が占有率判定より優先される）。
 * 6文字以上の場合のみ、正規化後の完全一致、または`display_text`の80%以上をtitleが占める
 * 逐語コピーの場合に不合格とする（QH-5a・b）。「テニスの準備をする」のような自然な言い換え・
 * 部分包含は合格させる。
 *
 * **捏造検出**（QH-4a〜d・§13/§15/§34）: `display_text`に数字（全角含む）・URL（`http(s)://`・
 * `www.`）・`@`のいずれかを含めば不合格とする。時刻表記（"10:00"）・単位付き数字（"15分"）は
 * いずれも数字を含むため同一の数字検出規則で捕捉される。「住所らしい語」の一般的な辞書判定は
 * 実装しない（誤検出リスクが高く、テスト観点にも存在しないため。将来必要になれば別途設計）。
 */
class ContentSanityChecker {

    /**
     * [response]（①形式検証を通過済み）を[context]（`title`／`locale`）と突き合わせて
     * 内容sanityを判定する。同一入力には常に同一の判定を返す（QH-11、決定的処理）。
     */
    fun check(response: AIPlanResponse, context: PlanningContext): ContentSanityResult {
        val title = context.event.title

        response.steps.forEach { step ->
            val displayText = step.displayText

            if (displayText.length < DISPLAY_TEXT_MIN_LENGTH || displayText.length > DISPLAY_TEXT_MAX_LENGTH) {
                return ContentSanityResult.Invalid(
                    "display_text length ${displayText.length} is outside the allowed range " +
                        "($DISPLAY_TEXT_MIN_LENGTH..$DISPLAY_TEXT_MAX_LENGTH) for action_type=${step.actionType}"
                )
            }

            val bannedWord = BANNED_WORDS.firstOrNull { displayText.contains(it, ignoreCase = true) }
            if (bannedWord != null) {
                return ContentSanityResult.Invalid(
                    "display_text contains a banned/placeholder word ('$bannedWord') for " +
                        "action_type=${step.actionType}"
                )
            }

            if (containsFabricatedContent(displayText)) {
                return ContentSanityResult.Invalid(
                    "display_text appears to fabricate a number, clock time, URL, or email address " +
                        "that Kotlin (not the LLM) is responsible for computing (action_type=${step.actionType})"
                )
            }

            if (isTitleCopy(title = title, displayText = displayText)) {
                return ContentSanityResult.Invalid(
                    "display_text is a verbatim (or near-verbatim, >= " +
                        "${(TITLE_COPY_OCCUPANCY_THRESHOLD * 100).toInt()}% overlap) copy of the event " +
                        "title (action_type=${step.actionType})"
                )
            }

            if (!isLocaleConsistent(displayText = displayText, locale = context.locale)) {
                return ContentSanityResult.Invalid(
                    "display_text language does not match locale=${context.locale} " +
                        "(action_type=${step.actionType})"
                )
            }
        }

        val actionTypes = response.steps.map { it.actionType }
        if (actionTypes.toSet().size != actionTypes.size) {
            return ContentSanityResult.Invalid("Duplicate action_type detected across steps: $actionTypes")
        }

        return ContentSanityResult.Valid
    }

    private fun containsFabricatedContent(displayText: String): Boolean =
        DIGIT_PATTERN.containsMatchIn(displayText) ||
            URL_PATTERN.containsMatchIn(displayText) ||
            displayText.contains("@")

    private fun isTitleCopy(title: String, displayText: String): Boolean {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.length < MIN_TITLE_LENGTH_FOR_COPY_CHECK) return false

        val normalizedDisplayText = normalize(displayText)
        if (normalizedTitle == normalizedDisplayText) return true

        if (normalizedDisplayText.isEmpty() || !normalizedDisplayText.contains(normalizedTitle)) return false
        val occupancyRatio = normalizedTitle.length.toDouble() / normalizedDisplayText.length.toDouble()
        return occupancyRatio >= TITLE_COPY_OCCUPANCY_THRESHOLD
    }

    /** 空白除去＋小文字化（Latin文字圏の大小無視・日本語には影響しない）で正規化する。 */
    private fun normalize(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()

    private fun isLocaleConsistent(displayText: String, locale: java.util.Locale): Boolean {
        val containsJapanese = JAPANESE_SCRIPT_PATTERN.containsMatchIn(displayText)
        return if (locale.language == "ja") containsJapanese else !containsJapanese
    }

    companion object {
        private const val DISPLAY_TEXT_MIN_LENGTH = 1
        private const val DISPLAY_TEXT_MAX_LENGTH = 60
        private const val MIN_TITLE_LENGTH_FOR_COPY_CHECK = 6
        private const val TITLE_COPY_OCCUPANCY_THRESHOLD = 0.8

        private val BANNED_WORDS = listOf("TODO", "example", "<think", "lorem", "???")

        /** 半角(0-9)・全角(０-９)いずれの数字も検出する。 */
        private val DIGIT_PATTERN = Regex("[0-9０-９]")
        private val URL_PATTERN = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)

        /** ひらがな・カタカナ・常用漢字域（`PlanPromptBuilderTest`と同一の判定範囲）。 */
        private val JAPANESE_SCRIPT_PATTERN = Regex("[぀-ヿ一-鿿]")
    }
}

/** [ContentSanityChecker.check]の戻り値。[SchemaValidationResult]と対になる形にしてある。 */
sealed interface ContentSanityResult {
    data object Valid : ContentSanityResult

    /** [reason]は不合格の理由（該当ステップ・検出種別を特定できる情報を含む）。 */
    data class Invalid(val reason: String) : ContentSanityResult
}
