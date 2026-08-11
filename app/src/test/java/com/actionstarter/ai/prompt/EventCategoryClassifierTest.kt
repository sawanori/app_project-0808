package com.actionstarter.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * T-P95-1〜6（計画書`docs/plans/phase9.5-performance-quality.md`§3.2 F-1・§7、ADR-0064想定）。
 * [EventCategoryClassifier.classify]のRedテスト。
 *
 * **現状のRed原因**: [EventCategoryClassifier.classify]の本体が`TODO()`のため、全件
 * `NotImplementedError`によりRedになるのが正しい（`RecoverySchemaValidator`・
 * `RecoveryPromptBuilder`がPhase 9 Step 3で辿ったのと同型のscaffold）。Step 4（Green）で
 * [EventCategoryClassifier]のキーワード辞書（`JAPANESE_KEYWORDS`／`ENGLISH_KEYWORDS`）を参照する
 * 実装を行うこと。
 */
class EventCategoryClassifierTest {

    // T-P95-1: 正常 - ja: 医療関連キーワード（「検診」）を含むタイトル → medical判定
    @Test
    fun tP95_1_classify_japaneseMedicalKeyword_returnsMedical() {
        val result = EventCategoryClassifier().classify("歯科検診の予約", Locale.JAPAN)

        assertEquals(
            "「検診」を含む日本語タイトルはmedicalと判定されるべきです(T-P95-1)",
            EventCategoryClassifier.CATEGORY_MEDICAL,
            result
        )
    }

    // T-P95-2: 正常 - en: 医療関連キーワード（"checkup"/"clinic"）を含むタイトル → medical判定
    @Test
    fun tP95_2_classify_englishMedicalKeyword_returnsMedical() {
        val result = EventCategoryClassifier().classify("Annual checkup at the dental clinic", Locale.US)

        assertEquals(
            "\"checkup\"/\"clinic\"を含む英語タイトルはmedicalと判定されるべきです(T-P95-2)",
            EventCategoryClassifier.CATEGORY_MEDICAL,
            result
        )
    }

    // T-P95-3: 正常 - ja: 出張関連キーワード（「出張」）を含むタイトル → travel判定
    @Test
    fun tP95_3_classify_japaneseTravelKeyword_returnsTravel() {
        val result = EventCategoryClassifier().classify("大阪への出張", Locale.JAPAN)

        assertEquals(
            "「出張」を含む日本語タイトルはtravelと判定されるべきです(T-P95-3)",
            EventCategoryClassifier.CATEGORY_TRAVEL,
            result
        )
    }

    // T-P95-4: 正常 - en: 出張関連キーワード（"business trip"）を含むタイトル → travel判定
    @Test
    fun tP95_4_classify_englishTravelKeyword_returnsTravel() {
        val result = EventCategoryClassifier().classify("Business trip to Osaka", Locale.US)

        assertEquals(
            "\"business trip\"を含む英語タイトルはtravelと判定されるべきです(T-P95-4)",
            EventCategoryClassifier.CATEGORY_TRAVEL,
            result
        )
    }

    // T-P95-5: エッジ - ja/enともキーワード非一致タイトル → フォールバック値(CATEGORY_UNKNOWN)を
    // 返しクラッシュしない
    @Test
    fun tP95_5_classify_noKeywordMatch_returnsUnknownFallbackForBothLocales() {
        val japaneseResult = EventCategoryClassifier().classify("来週の予定", Locale.JAPAN)
        val englishResult = EventCategoryClassifier().classify("Something next week", Locale.US)

        assertEquals(
            "キーワード非一致の日本語タイトルはCATEGORY_UNKNOWNを返すべきです(T-P95-5)",
            EventCategoryClassifier.CATEGORY_UNKNOWN,
            japaneseResult
        )
        assertEquals(
            "キーワード非一致の英語タイトルはCATEGORY_UNKNOWNを返すべきです(T-P95-5)",
            EventCategoryClassifier.CATEGORY_UNKNOWN,
            englishResult
        )
    }

    // T-P95-6: 正常 - 複数カテゴリのキーワードを同時に含む場合の優先順位が決定的
    // （同一入力に同一出力、ja/en両方。CATEGORY_PRIORITY_ORDERの先頭＝socialが優先される想定）
    @Test
    fun tP95_6_classify_multipleMatchingCategories_resolvesDeterministicallyByPriorityOrder() {
        val japaneseTitle = "友人の結婚式後の検診" // social("結婚式") と medical("検診") の両方を含む
        val englishTitle = "Wedding checkup visit" // social("wedding") と medical("checkup") の両方を含む

        val japaneseFirstCall = EventCategoryClassifier().classify(japaneseTitle, Locale.JAPAN)
        val japaneseSecondCall = EventCategoryClassifier().classify(japaneseTitle, Locale.JAPAN)
        val englishFirstCall = EventCategoryClassifier().classify(englishTitle, Locale.US)
        val englishSecondCall = EventCategoryClassifier().classify(englishTitle, Locale.US)

        assertEquals(
            "social/medical両方に一致する日本語タイトルはCATEGORY_PRIORITY_ORDER先頭のsocialへ" +
                "決定的に解決されるべきです(T-P95-6)",
            EventCategoryClassifier.CATEGORY_SOCIAL,
            japaneseFirstCall
        )
        assertEquals(
            "同一入力への2回目の呼び出しも同一結果であるべきです(T-P95-6、決定性)",
            japaneseFirstCall,
            japaneseSecondCall
        )
        assertEquals(
            "social/medical両方に一致する英語タイトルはCATEGORY_PRIORITY_ORDER先頭のsocialへ" +
                "決定的に解決されるべきです(T-P95-6)",
            EventCategoryClassifier.CATEGORY_SOCIAL,
            englishFirstCall
        )
        assertEquals(
            "同一入力への2回目の呼び出しも同一結果であるべきです(T-P95-6、決定性)",
            englishFirstCall,
            englishSecondCall
        )
    }
}
