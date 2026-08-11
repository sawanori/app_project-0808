package com.actionstarter.ai.prompt

import java.util.Locale

/**
 * Phase 9.5新設（計画書`docs/plans/phase9.5-performance-quality.md`§3.2 F-1、ADR-0064想定、
 * **Plan限定スコープ**、敵対的レビュー採用A-3・A-4）。イベントタイトルから
 * [PlanPromptBuilder.FewShotSeed.eventType]と同じ4値（[CATEGORY_SOCIAL]・[CATEGORY_MEDICAL]・
 * [CATEGORY_TRAVEL]・[CATEGORY_BUSINESS_MEETING]）＋不明時フォールバック（[CATEGORY_UNKNOWN]）を
 * 推定する状態レス純関数。[PlanPromptBuilder.buildFewShot]が推定カテゴリに一致する模範例のみへ
 * 絞り込むために使う（計画書§3.2「buildFewShotを拡張し、推定カテゴリに一致する模範例のみを選ぶ」）。
 *
 * **入力はタイトル文字列のみ（PII非送信・§10不変）**: 固定キーワード辞書のみで判定し、
 * ネットワークを一切使用しない（計画書§3.2）。
 *
 * **ロケール別辞書（敵対的レビューA-4、両レビュー一致）**: [PlanPromptBuilder.buildFewShot]
 * （`locale`引数）が既にja/en例文プールを言語分離している既存規約と整合させ、[classify]は
 * [Locale]を受け取りja辞書（[JAPANESE_KEYWORDS]）／en辞書（[ENGLISH_KEYWORDS]）を切り替える。
 *
 * **優先順位は決定的（T-P95-6）**: 複数カテゴリのキーワードを同時に含むタイトルに対しては、
 * [CATEGORY_PRIORITY_ORDER]の先頭から順に最初に一致したカテゴリを採用する（同一入力に対して
 * 常に同一出力、品質ハーネスQH-11「同一入力には常に同一の判定」の精神を踏襲）。
 *
 * **Step 4（Green）で実装済み**: [classify]はキーワード辞書（[JAPANESE_KEYWORDS]・
 * [ENGLISH_KEYWORDS]）・優先順位（[CATEGORY_PRIORITY_ORDER]）を参照するのみで完結する
 * （scaffold段階の設計どおり）。
 *
 * **本番配線はF-1 A/B負判定によりロールバック済み・Phase 12実験材料（計画書§3.2 F-1c、
 * 2026-08-12）**: 本クラス自体・[PlanPromptBuilder.buildFewShot]のカテゴリ絞り込みロジックは
 * 削除しないが、[com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel.
 * buildConversationConfig]からは呼ばれない（`eventTitle`引数を渡さないため常に全件混合経路）。
 * F-1（コミット09f4d99）・F-1b（各カテゴリ2件への増強、コミット12a14d6）のいずれも実機A/Bで
 * 有意な品質退行（`TITLE_COPY`→`MIN_QUALITY`）を示し、計測駆動フェーズの撤退基準（計画書§9・
 * §0）が発動したため本番配線を撤回した——結論は「カテゴリ条件選択は0.6B×現行プロンプト予算
 * では品質下限を割る」（計画書§3.2「結論」参照）。本クラス・増強済みseedプール・関連テストは
 * 将来（Phase 12、より大きいモデル・より大きいプロンプト予算での再評価）の実験材料として
 * コード上に残す。
 */
class EventCategoryClassifier {

    /**
     * [title]を[locale]別キーワード辞書（[JAPANESE_KEYWORDS]／[ENGLISH_KEYWORDS]）と突き合わせ、
     * 一致したカテゴリを返す。どのキーワードにも一致しない場合は[CATEGORY_UNKNOWN]を返す
     * （クラッシュしない、T-P95-5）。複数カテゴリが同時に一致する場合は[CATEGORY_PRIORITY_ORDER]
     * の順で決定的に解決する（T-P95-6）。大文字小文字は無視して比較する（`ignoreCase = true`、
     * 英語タイトルの語頭大文字化〔"Business trip to Osaka"〕が小文字辞書エントリ
     * 〔"business trip"〕と一致するために必要、T-P95-4実測で確認）。
     */
    fun classify(title: String, locale: Locale): String {
        val keywords = keywordsFor(locale)
        return CATEGORY_PRIORITY_ORDER.firstOrNull { category ->
            keywords[category].orEmpty().any { keyword -> title.contains(keyword, ignoreCase = true) }
        } ?: CATEGORY_UNKNOWN
    }

    companion object {
        /** [PlanPromptBuilder.FewShotSeed.eventType]の4値と同じ文字列（値のみ揃える。本クラスと
         * `PlanPromptBuilder`の間にコンパイル時依存は作らない）。 */
        const val CATEGORY_SOCIAL: String = "social"
        const val CATEGORY_MEDICAL: String = "medical"
        const val CATEGORY_TRAVEL: String = "travel"
        const val CATEGORY_BUSINESS_MEETING: String = "business_meeting"

        /**
         * 不明時フォールバック値（計画書§3.2「4値＋不明時フォールバック」）。
         * [PlanPromptBuilder.FewShotSeed.eventType]の4値のいずれとも一致しない専用sentinel値。
         * [PlanPromptBuilder.buildFewShot]側はこの値を見て「未知カテゴリ→全件混合フォールバック」
         * （計画書§3.2「不一致0件時は現行の全件混合へフォールバックする」）を判別する想定
         * （few-shot側の実配線はGreenで行う）。
         */
        const val CATEGORY_UNKNOWN: String = "unknown"

        /**
         * T-P95-6の決定的優先順位。複数カテゴリのキーワードが同時に一致した場合、[classify]の
         * Green実装はこのリストの先頭から順に最初に一致したカテゴリを採用する。
         */
        val CATEGORY_PRIORITY_ORDER: List<String> = listOf(
            CATEGORY_SOCIAL,
            CATEGORY_MEDICAL,
            CATEGORY_TRAVEL,
            CATEGORY_BUSINESS_MEETING
        )

        /**
         * ja辞書（[PlanPromptBuilder]のJAPANESE_FEW_SHOT_SEEDSと同じ4テーマ〔結婚式／歯科検診／
         * 出張／打ち合わせ〕を代表するキーワード集合）。大文字小文字・空白の正規化方針は
         * Green実装側の関心事とし、本辞書は素の代表キーワードのみを保持する。
         */
        private val JAPANESE_KEYWORDS: Map<String, List<String>> = mapOf(
            CATEGORY_SOCIAL to listOf("結婚式", "パーティー", "飲み会", "同窓会"),
            CATEGORY_MEDICAL to listOf("検診", "病院", "歯科", "クリニック", "診察"),
            CATEGORY_TRAVEL to listOf("出張", "旅行", "空港", "新幹線"),
            CATEGORY_BUSINESS_MEETING to listOf("打ち合わせ", "会議", "商談", "ミーティング")
        )

        /** en辞書（[JAPANESE_KEYWORDS]と同じ4テーマ、大文字小文字の扱いはGreen実装側の関心事）。 */
        private val ENGLISH_KEYWORDS: Map<String, List<String>> = mapOf(
            CATEGORY_SOCIAL to listOf("wedding", "party", "reunion"),
            CATEGORY_MEDICAL to listOf("checkup", "clinic", "doctor", "dental", "hospital"),
            CATEGORY_TRAVEL to listOf("business trip", "travel", "flight", "airport"),
            CATEGORY_BUSINESS_MEETING to listOf("meeting", "negotiation", "conference")
        )

        /**
         * [locale]に応じたキーワード辞書（[classify]のGreen実装が参照する想定のアクセサ、
         * T-P95-1〜6の根拠データ）。`internal`公開はテスト（同一Gradleモジュール内）が辞書内容を
         * 直接検証できるようにするため。
         */
        internal fun keywordsFor(locale: Locale): Map<String, List<String>> =
            if (locale.language == Locale.JAPANESE.language) JAPANESE_KEYWORDS else ENGLISH_KEYWORDS
    }
}
