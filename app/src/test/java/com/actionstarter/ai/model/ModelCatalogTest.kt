package com.actionstarter.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P7-C2（計画書§5.3・§8.6・§14.1・F87）。[ModelCatalog]の回帰ロックテスト。
 *
 * **born-green（本タスクの制約により意図的にGreenとして追加）**: [ModelCatalog]はP7-C1で
 * `QWEN3_0_6B_INT4_BLOCK32`エントリ（P7-C0実測値を含む）と`findById`が既に確定・実装済み
 * （`TODO()`を含まない）であるため、本テストはRedにならない。P7-C0実測値
 * （`build/agent-logs/p7c0-download.log`）がその後の変更で誤って書き換わらないことを
 * 検知する回帰ガードとして追加する。
 */
class ModelCatalogTest {

    // 正常系: QWEN3_0_6B_INT4_BLOCK32がP7-C0実測値と一致する
    @Test
    fun qwen3Entry_matchesP7C0MeasuredValues() {
        val entry = ModelCatalog.QWEN3_0_6B_INT4_BLOCK32

        assertEquals("qwen3-0.6b-int4-block32", entry.id)
        assertEquals(344_437_808L, entry.sizeBytes)
        assertEquals(
            "e3e290109da4388d65a17510a0c66af91c8039f52d2c465868dbc43c09a776cf",
            entry.sha256
        )
        assertEquals(ModelLicense.APACHE_2_0, entry.license)
        assertFalse("Apache-2.0モデルはNoticeファイル同梱不要のはずです(§13 #24)", entry.requiresNoticeFile)
    }

    // 正常系: findByIdが既知のIDに対して正しいエントリを返す
    @Test
    fun findById_knownId_returnsMatchingEntry() {
        val found = ModelCatalog.findById("qwen3-0.6b-int4-block32")

        assertEquals(ModelCatalog.QWEN3_0_6B_INT4_BLOCK32, found)
    }

    // 異常系: findByIdが未知のIDに対してnullを返す
    @Test
    fun findById_unknownId_returnsNull() {
        val found = ModelCatalog.findById("nonexistent-model-id")

        assertNull(found)
    }

    // 正常系: ALLがQWEN3_0_6B_INT4_BLOCK32を含む（§17「モデル名を製品仕様として固定しない」の
    // 交換可能性の前提となる、ALLへのエントリ追加のみで拡張できる構造の回帰ガード）
    @Test
    fun all_containsQwen3Entry() {
        assertEquals(listOf(ModelCatalog.QWEN3_0_6B_INT4_BLOCK32), ModelCatalog.ALL)
    }
}
