package com.actionstarter.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.typeOf

/**
 * P7-C2（計画書§8.5・§12.5 T-AIMET-1）。[AiMetrics]のフィールド許可リスト回帰ロック。
 *
 * **born-green（本タスクの制約により意図的にGreenとして追加）**: [AiMetrics]はP7-C1で
 * 全フィールドが確定・実装済み（`TODO()`を含まないデータクラス）であるため、本テストは
 * Redにならない。「契約が既に確定している型はborn-green回帰ガードとして許容する」という
 * 本タスクの制約に基づき、将来の変更でカレンダー本文・イベントタイトル等の自由文フィールドが
 * 誤って追加された場合に検知する回帰ガードとして追加する。
 *
 * **P7契約確定での更新（Fable 5裁定・retry契約確定、2026-08-10、ADR-0049）**: 品質ハーネス
 * UQ-5により`sanityPassed: Boolean`が[AiMetrics]へ追加されたため、期待フィールド集合を
 * 8→9件へ更新した（born-greenのまま。追加フィールドもBooleanでString型ではないため
 * `noFieldHasStringType_cannotHoldFreeTextOrPii`の判定結果に影響しない）。
 *
 * **§60の例示リストとの関係**: T-AIMET-1原文は「AiMetricsのフィールド集合が§60の許可リストに
 * 一致し」と書くが、仕様§60自体は「送ってよい：」の直後に`event_category_hash`等を
 * **例として**（「例：」の見出し付きで）示しているに過ぎず、`AiMetrics`のフィールド名と
 * 文字列として一致するものではない（計画書§8.5は`AiMetrics`を§57の性能指標に対応させており、
 * §60とは別の指標セットである）。したがって本テストは§60の字面一致ではなく、原文の
 * 実質的な意図（自由文・PII混入の禁止）を、確定済みの8フィールド名との**完全一致**、および
 * 全フィールドが数値／真偽値型でありStringフィールドを一切持たない（＝自由文を保持し得ない）
 * ことの2点で検証する。
 */
class AiMetricsTest {

    // 正常系: AiMetricsのフィールド集合が確定9フィールド（品質ハーネスUQ-5のsanityPassed追加後）
    // と完全一致する
    @Test
    fun fieldNames_matchConfirmedAllowList() {
        val expected = setOf(
            "modelLoadMs",
            "firstTokenMs",
            "totalMs",
            "outputTokens",
            "tokensPerSecond",
            "peakNativeHeapBytes",
            "retried",
            "schemaValid",
            "sanityPassed"
        )

        val actual = AiMetrics::class.memberProperties.map { it.name }.toSet()

        assertEquals(expected, actual)
    }

    // 正常系: 自由文フィールド（String型）を一切持たない（カレンダー本文・住所等の混入防止、§60）
    @Test
    fun noFieldHasStringType_cannotHoldFreeTextOrPii() {
        val stringType: KType = typeOf<String>()

        val stringTypedFields = AiMetrics::class.memberProperties.filter { it.returnType == stringType }

        assertTrue(
            "AiMetricsにString型フィールドが存在します（自由文・PII混入のリスク、§60）: " +
                stringTypedFields.map { it.name },
            stringTypedFields.isEmpty()
        )
    }
}
