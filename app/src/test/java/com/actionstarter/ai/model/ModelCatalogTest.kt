package com.actionstarter.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ------------------------------------------------------------------
    // ADR-0057: ModelCatalogEntry.defaultProfilePeakRamBytes
    // ------------------------------------------------------------------
    //
    // P7-C5実機実測（ADR-0056）が発見した「peakRamBytesがコンテキストプロファイル非依存の
    // 単一値（フルコンテキスト4096実測=2,890MB）のため、実際に使う小コンテキスト・
    // 本番プロファイル（maxNumTokens≈1024〜）ではOOM事前ガード（§8.6 #7）が過大判定する」
    // 問題への対処。新設フィールドdefaultProfilePeakRamBytesは「実際に使う既定プロファイルでの
    // 実効ピークRAM」を表し、LocalAiGatewayのOOM事前ガードはこちらを参照する
    // （peakRamBytesはフルコンテキスト参考値として温存）。

    // 正常系: 本番QWEN3_0_6B_INT4_BLOCK32のdefaultProfilePeakRamBytesは、P7-C5診断実測
    // （probeAdapterThroughGateway_widerContextDiagnostic、maxNumTokens=1024・peakRamBytes
    // fixture=1.25GiBで3件とも実推論成功）で実際に検証済みの1.25GiB
    // （1,342,177,280バイト）である
    @Test
    fun qwen3Entry_defaultProfilePeakRamBytes_matchesP7C5ValidatedSmallContextValue() {
        val entry = ModelCatalog.QWEN3_0_6B_INT4_BLOCK32

        assertEquals(
            "defaultProfilePeakRamBytesはP7-C5診断実測で検証済みの1.25GiBであるべきです(ADR-0057)",
            1_342_177_280L,
            entry.defaultProfilePeakRamBytes
        )
    }

    // 正常系: defaultProfilePeakRamBytesはフルコンテキストのpeakRamBytes（2,890MB）より
    // 明確に小さい（プロファイル別の実効ピークを持つことの回帰ロック。等しいままだと
    // 「プロファイル依存の是正」が効いていない）
    @Test
    fun qwen3Entry_defaultProfilePeakRamBytes_isMeaningfullySmallerThanFullContextPeakRamBytes() {
        val entry = ModelCatalog.QWEN3_0_6B_INT4_BLOCK32

        assertTrue(
            "defaultProfilePeakRamBytes(${entry.defaultProfilePeakRamBytes})はフルコンテキストの" +
                "peakRamBytes(${entry.peakRamBytes})より小さいべきです(ADR-0057、プロファイル別の実効ピーク)",
            entry.defaultProfilePeakRamBytes < entry.peakRamBytes
        )
    }

    // エッジ: defaultProfilePeakRamBytesを明示指定しない場合、既存呼び出し元（テストfixture等）の
    // 挙動を変えないようpeakRamBytesと同値へ既定される（データクラスの既定値式の回帰ロック。
    // §8.6 #7のOOM事前ガードにdefaultProfilePeakRamBytesを未指定のfixtureエントリを渡しても
    // 従来どおりpeakRamBytes基準で判定される後方互換性の保証）
    @Test
    fun modelCatalogEntry_defaultProfilePeakRamBytesOmitted_defaultsToSameAsPeakRamBytes() {
        val entry = ModelCatalogEntry(
            id = "adr-0057-default-value-check",
            displayName = "ADR-0057 Default Value Check",
            downloadUrl = "https://example.invalid/adr-0057.litertlm",
            sha256 = "0".repeat(64),
            sizeBytes = 1L,
            peakRamBytes = 999_888_777L,
            contextLength = 1,
            quantization = "test",
            license = ModelLicense.APACHE_2_0,
            requiresNoticeFile = false
        )

        assertEquals(
            "defaultProfilePeakRamBytesを省略した場合はpeakRamBytesと同値になるべきです" +
                "(ADR-0057、既存fixtureの後方互換性)",
            entry.peakRamBytes,
            entry.defaultProfilePeakRamBytes
        )
    }
}
