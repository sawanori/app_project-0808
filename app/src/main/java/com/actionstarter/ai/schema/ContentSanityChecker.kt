package com.actionstarter.ai.schema

import com.actionstarter.ai.AIPlanResponse
import com.actionstarter.ai.AIRecoveryResponse
import com.actionstarter.ai.SanityRejectReason
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext

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
 * **責務（品質ハーネス§6②・裁定4＋Phase 9計画書§4.2 L2、7項目）**:
 * 1. **display_textの長さ上限の再確認**（60字、①の再確認。①をすり抜けたケースの保険）
 * 2. **禁止語／プレースホルダ検出**（例: `"TODO"`／`"example"`／`"lorem"`／`"???"`／
 *    `"<think"`等。品質ハーネスQH-10）
 * 3. **捏造検出**（§13／§15／§34）: `display_text`に数字・時刻（`:`や「時」「分」）・URL・
 *    `@`・住所らしい語を含んだら不合格（数値・時刻はKotlin専任のはずであり、LLMの
 *    `display_text`に現れること自体が§13/§15からの逸脱。品質ハーネスQH-4）
 * 4. **few-shotエコー検出（R1a、Phase 9計画書§4.2）**: `display_text`/`explanation`が
 *    模範例（few-shot）の**タイトル**そのものと一致・ほぼ一致した場合に不合格とする
 *    （[checkFewShotEcho]参照。modelStepsのdisplay_text相当のエコー検出＝R1bはPhase 9.5送り）
 * 5. **titleコピー検出（緩和版・Gemini G1 CRITICAL #3反映）**: (a) イベントtitleが6文字未満
 *    なら本検査を適用しない（過検出防止）。(b) それ以外は、正規化後の`display_text`との
 *    完全一致、または`display_text`の80%以上をtitleが占める場合にのみ不合格とする
 *    （「テニスの準備をする」のような自然な言い換え・部分包含は合格させる。品質ハーネス
 *    QH-5・QH-15）
 * 6. **locale整合**: `context.locale`がjaのとき`display_text`が日本語書記素を含む、
 *    enのときほぼLatin文字であることを確認（品質ハーネスQH-6。**Planのみ**——
 *    [RecoveryContext]は`locale`フィールドを持たないため[checkRecovery]は本検査を含まない、
 *    後述「Recoveryにlocale整合を適用しない理由」参照）
 * 7. **動詞相当表現の存在確認（R2(c)、Phase 9計画書§4.2）**: 純粋に説明的・非実行的な文言
 *    （例:「〜についての一般的な情報」）を排除する軽量ヒューリスティック（[containsVerbLikeExpression]
 *    参照）
 * 8. **重複action_type検出**（`uniqueItems`相当、Planのみ）: `ResponseFormat.json()`の
 *    LLGuidanceが`uniqueItems`をenforceしないため（品質ハーネス§1・§5「LLGuidanceが
 *    **enforceしない**: `uniqueItems`」）、[SchemaValidator]ではなく本コンポーネントがこの
 *    検出を担う（P7-C2完了記録が残した論点「重複action_typeの担当（①か②か）」への回答。
 *    品質ハーネスUQ-3・T-SCH-21相当）
 *
 * **決定的・PII非出力**（LLM-judgeを使わない。品質ハーネス§6末尾「②はKotlin決定的処理の
 * み...すべて観測可能・PII非出力・端末内完結」）。同一入力には常に同一判定を返す。
 *
 * **`LocalAiGateway`への配線**: [check]・[checkRecovery]とも
 * [com.actionstarter.ai.LocalAiGateway]の検証パイプラインへ配線済み（Plan: P7-C3〜、
 * Recovery: Phase 9コミット2）。[com.actionstarter.ai.LocalAiGateway]のKDoc「検証パイプライン」
 * 参照。
 *
 * **titleコピー検出の閾値**（Gemini G1 CRITICAL #3・反映3）: イベントtitleの正規化後の長さが
 * 6文字未満なら本検査自体を適用しない（QH-15a・b、過検出防止が占有率判定より優先される）。
 * 6文字以上の場合のみ、正規化後の完全一致、または`display_text`の80%以上をtitleが占める
 * 逐語コピーの場合に不合格とする（QH-5a・b）。「テニスの準備をする」のような自然な言い換え・
 * 部分包含は合格させる。[checkFewShotEcho]（R1a）も同一の正規化＋占有率ロジック（[isCopyOf]）を
 * few-shotタイトル集合の各要素に対して適用する共通実装である。
 *
 * **捏造検出**（QH-4a〜d・§13/§15/§34）: `display_text`に数字（全角含む）・URL（`http(s)://`・
 * `www.`）・`@`のいずれかを含めば不合格とする。時刻表記（"10:00"）・単位付き数字（"15分"）は
 * いずれも数字を含むため同一の数字検出規則で捕捉される。「住所らしい語」の一般的な辞書判定は
 * 実装しない（誤検出リスクが高く、テスト観点にも存在しないため。将来必要になれば別途設計）。
 *
 * **Recoveryにlocale整合を適用しない理由（Phase 9コミット2 Green、実装調査による発見・
 * Redステージからの訂正）**: Redステージの[checkRecovery] KDocは「locale整合はcheckと共通」と
 * 申し送っていたが、[RecoveryContext]が`locale`フィールドを持たない（計画書§5「非変更」）ため、
 * 実際に呼び出すには`Locale.getDefault()`をどこかで解決する必要がある。[checkRecovery]自身が
 * `Locale.getDefault()`を呼ぶ設計にすると、JVMテスト実行環境の既定localeに判定結果が依存し
 * テストが不安定化するリスクがある（本コミットの[com.actionstarter.ai.schema.
 * ContentSanityCheckerTest]のT-P9-14・T-P9-18のような日本語固定fixtureが、CI環境の既定localeが
 * 非日本語の場合に誤ってInvalidになりかねない）。加えて呼び出し側に`locale`引数を追加すると
 * 既存Redステージテスト（3引数で固定済み）を壊す。content-sanityのlocale整合は品質ゲートで
 * あり（実行不能にはならない）、[RecoveryPromptBuilder]・[com.actionstarter.ai.adapter.
 * LiteRtLmLocalLanguageModel]が既に`Locale.getDefault()`で解決している「実際に送信する言語」
 * ほど構造的に重要ではないため、本コミットでは[checkRecovery]にlocale整合を含めないことを
 * 意図的な決定として採用した（[RecoveryContext]がlocaleを持つようになれば再検討、計画書§9
 * 再検討トリガー相当）。
 *
 * **動詞相当表現の判定（R2(c)、両言語対応）**: [containsVerbLikeExpression]は`locale`引数を
 * 取らず、日本語の動詞語尾（godan/ichidan終止形・て/で形・「ください」）へのsuffix一致と、
 * 英語の動詞語彙リストへのcontains一致の両方をOR判定する。**単語の完全一致リストではなく
 * 語尾のsuffix一致にした理由**: 当初は日本語側も固定語彙リストのcontains判定を検討したが、
 * 実装時の実測で「作業を切り上げる」「保険証を持って行く」のような既存の正当な模範文言
 * （[com.actionstarter.ai.prompt.PlanPromptBuilder]・実在テストフィクスチャ双方）が任意の
 * 固定語彙リストに漏れなく収まらないことが判明した（日本語動詞の活用語幹は語彙が非常に
 * 多様なため）。語尾のsuffix一致（る/く/ぐ/す/つ/ぬ/ぶ/む/う/て/で/ください）へ切り替える
 * ことで、多様な動詞活用を実務的に広くカバーしつつ、T-P9-17の「〜についての一般的な情報」
 * のような体言（名詞）止めの説明文は引き続き正しく検出できる（末尾が漢字/名詞のため
 * いずれのsuffixにも一致しない）。localeを問わないOR判定にしたのは[checkRecovery]が
 * localeを持たないことに合わせた設計（[check]からも同一関数を共有するための統一）。
 */
class ContentSanityChecker {

    /**
     * [response]（①形式検証を通過済み）を[context]（`title`／`locale`）と突き合わせて
     * 内容sanityを判定する。同一入力には常に同一の判定を返す（QH-11、決定的処理）。
     *
     * @param knownFewShotTitles R1a（[checkFewShotEcho]）が参照するfew-shotタイトル集合
     *   （Phase 9コミット2で追加、既定は空集合＝R1a事実上無効）。既定値を空集合にしたのは
     *   既存呼び出し元（[com.actionstarter.ai.schema.ContentSanityCheckerTest]の既存
     *   QH-*テスト群）が3引数目を渡さないまま無変更でコンパイル・動作し続けるための後方互換
     *   （計画書§4.2「Plan経路への最小侵襲統合」）。実運用では
     *   [com.actionstarter.ai.LocalAiGateway]が[com.actionstarter.ai.prompt.PlanPromptBuilder.
     *   fewShotEventTitles]・[com.actionstarter.ai.prompt.RecoveryPromptBuilder.
     *   fewShotEventTitles]の和集合を明示的に渡す（Plan/Recovery両方のfew-shotタイトルに対する
     *   エコーを検出する。両経路が同一モデルエンジンを共有する以上、混線はどちら向きにも
     *   起こり得るため）。
     */
    fun check(
        response: AIPlanResponse,
        context: PlanningContext,
        knownFewShotTitles: Set<String> = emptySet()
    ): ContentSanityResult {
        val title = context.event.title

        response.steps.forEach { step ->
            val displayText = step.displayText

            if (displayText.length < DISPLAY_TEXT_MIN_LENGTH || displayText.length > DISPLAY_TEXT_MAX_LENGTH) {
                return ContentSanityResult.Invalid(
                    "display_text length ${displayText.length} is outside the allowed range " +
                        "($DISPLAY_TEXT_MIN_LENGTH..$DISPLAY_TEXT_MAX_LENGTH) for action_type=${step.actionType}",
                    SanityRejectReason.LENGTH_OUT_OF_RANGE
                )
            }

            val bannedWord = BANNED_WORDS.firstOrNull { displayText.contains(it, ignoreCase = true) }
            if (bannedWord != null) {
                return ContentSanityResult.Invalid(
                    "display_text contains a banned/placeholder word ('$bannedWord') for " +
                        "action_type=${step.actionType}",
                    SanityRejectReason.BANNED_WORD
                )
            }

            if (containsFabricatedContent(displayText)) {
                return ContentSanityResult.Invalid(
                    "display_text appears to fabricate a number, clock time, URL, or email address " +
                        "that Kotlin (not the LLM) is responsible for computing (action_type=${step.actionType})",
                    SanityRejectReason.FABRICATED_CONTENT
                )
            }

            if (checkFewShotEcho(displayText, knownFewShotTitles)) {
                return ContentSanityResult.Invalid(
                    "display_text echoes a known few-shot example title (R1a, action_type=${step.actionType})",
                    SanityRejectReason.FEW_SHOT_ECHO
                )
            }

            if (isTitleCopy(title = title, displayText = displayText)) {
                return ContentSanityResult.Invalid(
                    "display_text is a verbatim (or near-verbatim, >= " +
                        "${(TITLE_COPY_OCCUPANCY_THRESHOLD * 100).toInt()}% overlap) copy of the event " +
                        "title (action_type=${step.actionType})",
                    SanityRejectReason.TITLE_COPY
                )
            }

            if (!isLocaleConsistent(displayText = displayText, locale = context.locale)) {
                return ContentSanityResult.Invalid(
                    "display_text language does not match locale=${context.locale} " +
                        "(action_type=${step.actionType})",
                    SanityRejectReason.LOCALE_MISMATCH
                )
            }

            if (!containsVerbLikeExpression(displayText)) {
                return ContentSanityResult.Invalid(
                    "display_text contains no verb-like actionable expression (R2(c), action_type=${step.actionType})",
                    SanityRejectReason.MIN_QUALITY
                )
            }
        }

        val actionTypes = response.steps.map { it.actionType }
        if (actionTypes.toSet().size != actionTypes.size) {
            return ContentSanityResult.Invalid(
                "Duplicate action_type detected across steps: $actionTypes",
                SanityRejectReason.DUPLICATE_ACTION_TYPE
            )
        }

        return ContentSanityResult.Valid
    }

    /**
     * Phase 9実装（計画書`docs/plans/phase9-recovery-ai.md`§4.2、コミット2、ADR-0063想定）。
     * [response]（[RecoverySchemaValidator]の①形式＋pairing検証を通過済み）の各`explanation`を
     * [context]・[knownFewShotTitles]（R1a判定用、[com.actionstarter.ai.prompt.
     * RecoveryPromptBuilder.fewShotEventTitles]・[com.actionstarter.ai.prompt.PlanPromptBuilder.
     * fewShotEventTitles]の和集合を[com.actionstarter.ai.LocalAiGateway]が供給）と突き合わせて
     * 内容sanityを判定する。[check]と同様、最初に見つかった不合格をその場で返す（決定的処理、
     * QH-11と同型）。
     *
     * **適用ルール（R1a→R2(a)→R2(b)→捏造検出→R2(c)の順で検査、クラスKDoc「Recoveryにlocale
     * 整合を適用しない理由」参照——[check]と異なりlocale整合検査を含まない）**:
     * - R1a: [checkFewShotEcho]で`explanation`が[knownFewShotTitles]のいずれかと一致すれば不合格
     * - R2(a): [EXPLANATION_MIN_LENGTH]未満、または[DISPLAY_TEXT_MAX_LENGTH]超過（①の再確認、
     *   [check]の長さ再確認と同型）なら不合格
     * - 禁止語／プレースホルダ検出（[check]と共通ロジック）
     * - 捏造検出（[containsFabricatedContent]、[check]と共通——§15はRecoveryにもそのまま適用
     *   されるため）
     * - R2(b): イベントタイトル単独との完全一致／80%以上占有（既存[isTitleCopy]をそのまま流用）
     *   なら不合格
     * - R2(c): [containsVerbLikeExpression]が`false`（動詞相当の表現を一切含まない）なら不合格
     */
    fun checkRecovery(
        response: AIRecoveryResponse,
        context: RecoveryContext,
        knownFewShotTitles: Set<String>
    ): ContentSanityResult {
        val title = context.event.title

        response.options.forEach { option ->
            val explanation = option.explanation

            if (checkFewShotEcho(explanation, knownFewShotTitles)) {
                return ContentSanityResult.Invalid(
                    "explanation echoes a known few-shot example title (R1a, semantic_action=${option.semanticAction})",
                    SanityRejectReason.FEW_SHOT_ECHO
                )
            }

            if (explanation.length < EXPLANATION_MIN_LENGTH) {
                return ContentSanityResult.Invalid(
                    "explanation length ${explanation.length} is below the minimum quality threshold " +
                        "($EXPLANATION_MIN_LENGTH, R2(a), semantic_action=${option.semanticAction})",
                    SanityRejectReason.LENGTH_OUT_OF_RANGE
                )
            }
            if (explanation.length > DISPLAY_TEXT_MAX_LENGTH) {
                return ContentSanityResult.Invalid(
                    "explanation length ${explanation.length} exceeds $DISPLAY_TEXT_MAX_LENGTH " +
                        "(①の再確認, semantic_action=${option.semanticAction})",
                    SanityRejectReason.LENGTH_OUT_OF_RANGE
                )
            }

            val bannedWord = BANNED_WORDS.firstOrNull { explanation.contains(it, ignoreCase = true) }
            if (bannedWord != null) {
                return ContentSanityResult.Invalid(
                    "explanation contains a banned/placeholder word ('$bannedWord') for " +
                        "semantic_action=${option.semanticAction}",
                    SanityRejectReason.BANNED_WORD
                )
            }

            if (containsFabricatedContent(explanation)) {
                return ContentSanityResult.Invalid(
                    "explanation appears to fabricate a number, clock time, URL, or email address " +
                        "that Kotlin (not the LLM) is responsible for computing (semantic_action=${option.semanticAction})",
                    SanityRejectReason.FABRICATED_CONTENT
                )
            }

            if (isTitleCopy(title = title, displayText = explanation)) {
                return ContentSanityResult.Invalid(
                    "explanation is a verbatim (or near-verbatim) copy of the event title " +
                        "(R2(b), semantic_action=${option.semanticAction})",
                    SanityRejectReason.TITLE_COPY
                )
            }

            if (!containsVerbLikeExpression(explanation)) {
                return ContentSanityResult.Invalid(
                    "explanation contains no verb-like actionable expression (R2(c), semantic_action=${option.semanticAction})",
                    SanityRejectReason.MIN_QUALITY
                )
            }
        }

        return ContentSanityResult.Valid
    }

    /**
     * R1a（計画書§4.2、few-shotエコー検出——模範例**タイトル**のみが対象。modelStepsの
     * display_text相当のエコー検出＝R1bはPhase 9.5送り、計画書§4.2「R1分割」参照）。[text]が
     * [knownSeedStrings]のいずれかと完全一致、または80%以上を占める場合`true`
     * （[isTitleCopy]と同一の正規化＋占有率ロジック[isCopyOf]を、[knownSeedStrings]の各要素に
     * 対してOR判定する）。
     *
     * `internal`（[com.actionstarter.ai.LocalAiPlanContextualizerTest]の`overlay`と同型の
     * 「テストから直接呼べるようinternalにする」規約）。
     */
    internal fun checkFewShotEcho(text: String, knownSeedStrings: Set<String>): Boolean =
        knownSeedStrings.any { seed -> isCopyOf(reference = seed, text = text, minReferenceLength = 1) }

    private fun containsFabricatedContent(displayText: String): Boolean =
        DIGIT_PATTERN.containsMatchIn(displayText) ||
            URL_PATTERN.containsMatchIn(displayText) ||
            displayText.contains("@")

    /**
     * [text]が[reference]の逐語コピーかどうかを判定する共通ロジック（[isTitleCopy]・
     * [checkFewShotEcho]が共有、Phase 9コミット2でisTitleCopyから抽出）。[reference]の正規化後の
     * 長さが[minReferenceLength]未満なら常に`false`（過検出防止）。それ以外は正規化後の完全一致、
     * または[text]の[TITLE_COPY_OCCUPANCY_THRESHOLD]以上を[reference]が占める場合に`true`。
     *
     * @param minReferenceLength [isTitleCopy]は[MIN_TITLE_LENGTH_FOR_COPY_CHECK]（6）を渡す——
     *   イベントtitleは外部/ユーザー入力であり「テニス」のような短い一般名詞の自然な言い換えを
     *   誤検出しないための緩和（QH-15）。[checkFewShotEcho]は`1`を渡す（実質免除なし）——
     *   few-shotタイトルはシステムが管理する既知の固定集合（「歯科検診」4文字・「出張」2文字を
     *   含む）であり、[isTitleCopy]と同じ免除閾値を適用すると6文字未満の模範例タイトルへの
     *   エコーを一切検出できなくなってしまう（実装時の実測で発見。T-P9-12/13は偶然R2(a)最小長
     *   検査が同じ入力を別の理由で不合格にしていたためテスト自体は当初から合格していたが、
     *   [com.actionstarter.ai.LocalAiGatewayTest]のT-P9-22（`lastSanityRejectReason ==
     *   SanityRejectReason.FEW_SHOT_ECHO`を明示的に要求）で顕在化した）。
     */
    private fun isCopyOf(reference: String, text: String, minReferenceLength: Int): Boolean {
        val normalizedReference = normalize(reference)
        if (normalizedReference.length < minReferenceLength) return false

        val normalizedText = normalize(text)
        if (normalizedReference == normalizedText) return true

        if (normalizedText.isEmpty() || !normalizedText.contains(normalizedReference)) return false
        val occupancyRatio = normalizedReference.length.toDouble() / normalizedText.length.toDouble()
        return occupancyRatio >= TITLE_COPY_OCCUPANCY_THRESHOLD
    }

    private fun isTitleCopy(title: String, displayText: String): Boolean =
        isCopyOf(reference = title, text = displayText, minReferenceLength = MIN_TITLE_LENGTH_FOR_COPY_CHECK)

    /** 空白除去＋小文字化（Latin文字圏の大小無視・日本語には影響しない）で正規化する。 */
    private fun normalize(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()

    private fun isLocaleConsistent(displayText: String, locale: java.util.Locale): Boolean {
        val containsJapanese = JAPANESE_SCRIPT_PATTERN.containsMatchIn(displayText)
        return if (locale.language == "ja") containsJapanese else !containsJapanese
    }

    /**
     * R2(c)（計画書§4.2、Plan/Recovery共通・敵対的レビューA-2拡充版）。[text]に動詞相当の
     * 表現が含まれるかを軽量ヒューリスティックで判定する。日本語は語尾のsuffix一致、英語は
     * 動詞語彙リストへのcontains一致（いずれも`.startsWith`は使わない——敵対的レビューA-2
     * 「先頭一致ではなく部分一致にする」）。両方の判定をORで行い、`locale`引数は取らない
     * （クラスKDoc「動詞相当表現の判定」参照）。
     */
    private fun containsVerbLikeExpression(text: String): Boolean {
        val trimmed = text.trim().trimEnd('。', '.', '！', '!', '、', ',')
        if (trimmed.isEmpty()) return false

        val hasJapaneseVerbEnding = JAPANESE_VERB_ENDING_SUFFIXES.any { trimmed.endsWith(it) }
        if (hasJapaneseVerbEnding) return true

        val lower = trimmed.lowercase()
        return ENGLISH_VERB_WORDS.any { lower.contains(it) }
    }

    companion object {
        private const val DISPLAY_TEXT_MIN_LENGTH = 1
        private const val DISPLAY_TEXT_MAX_LENGTH = 60
        private const val MIN_TITLE_LENGTH_FOR_COPY_CHECK = 6
        private const val TITLE_COPY_OCCUPANCY_THRESHOLD = 0.8

        /**
         * R2(a)（計画書§4.2、Recoveryの`explanation`専用の最小品質長）。
         * [MIN_TITLE_LENGTH_FOR_COPY_CHECK]と同値（6）だが意味的には別概念（片方は「titleコピー
         * 検査を適用するかどうかの閾値」、もう片方は「explanation自体の最小品質長」）のため、
         * 独立した定数として持つ。
         */
        private const val EXPLANATION_MIN_LENGTH = 6

        private val BANNED_WORDS = listOf("TODO", "example", "<think", "lorem", "???")

        /** 半角(0-9)・全角(０-９)いずれの数字も検出する。 */
        private val DIGIT_PATTERN = Regex("[0-9０-９]")
        private val URL_PATTERN = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)

        /** ひらがな・カタカナ・常用漢字域（`PlanPromptBuilderTest`と同一の判定範囲）。 */
        private val JAPANESE_SCRIPT_PATTERN = Regex("[぀-ヿ一-鿿]")

        /**
         * R2(c)語彙（日本語、敵対的レビューA-2拡充版）。godan/ichidan動詞の終止形語尾・
         * て/で形・「ください」への部分一致（`endsWith`、クラスKDoc「動詞相当表現の判定」の
         * 実測に基づく設計根拠参照）。
         */
        private val JAPANESE_VERB_ENDING_SUFFIXES = listOf(
            "る", "く", "ぐ", "す", "つ", "ぬ", "ぶ", "む", "う", "て", "で", "ください"
        )

        /** R2(c)語彙（英語、敵対的レビューA-2拡充版）。`contains`（`startsWith`不使用）。 */
        private val ENGLISH_VERB_WORDS = listOf(
            "bring", "check", "confirm", "prepare", "take", "leave", "head", "pack",
            "call", "get", "finish", "skip", "keep", "switch", "change", "hurry"
        )
    }
}

/** [ContentSanityChecker.check]・[ContentSanityChecker.checkRecovery]の戻り値。
 * [SchemaValidationResult]と対になる形にしてある。 */
sealed interface ContentSanityResult {
    data object Valid : ContentSanityResult

    /**
     * @param reason 不合格の理由（該当ステップ/optionと検出種別を特定できる自由文、ログ・
     *   デバッグ用途）。
     * @param rejectReason 不合格理由のPII非出力enum分類（Phase 9新設、計画書§4.5 L5）。
     *   [com.actionstarter.ai.LocalAiGateway]が[com.actionstarter.ai.AiMetrics.
     *   sanityRejectCount]／[com.actionstarter.ai.AiMetrics.lastSanityRejectReason]の実測値
     *   構築に使う。
     */
    data class Invalid(val reason: String, val rejectReason: SanityRejectReason) : ContentSanityResult
}
