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
    // 交換可能性の前提となる、ALLへのエントリ追加のみで拡張できる構造の回帰ガード）。
    // P7-C8でQWEN3_1_7B_INT4_BLOCK32・GEMMA_4_E2B_ITの2件を追加したためlistOf 1件比較から
    // 更新した（ModelCatalog.ktの更新に伴うテスト更新、既存アサーションの意図＝「ALLの中身が
    // 期待どおりである」自体は変更していない）。
    @Test
    fun all_containsQwen3Entry() {
        assertEquals(
            listOf(
                ModelCatalog.QWEN3_0_6B_INT4_BLOCK32,
                ModelCatalog.QWEN3_1_7B_INT4_BLOCK32,
                ModelCatalog.GEMMA_4_E2B_IT
            ),
            ModelCatalog.ALL
        )
    }

    // 正常系: QWEN3_0_6B_INT4_BLOCK32が引き続きALLの先頭である（ModelStorageImpl.installedEntry
    // はcatalog.firstOrNullで導入済みを決めるため、本番の既定モデルが先頭でなくなると
    // Settings未実装のまま複数モデルが端末にインストールされた場合にfirstOrNullの解決結果が
    // 変わりうる。P7-C8がALLへ2件追加したことで既定モデル選択に影響しないことの回帰ガード）
    @Test
    fun all_qwen3_0_6bRemainsFirst_forInstalledEntryResolutionOrder() {
        assertEquals(ModelCatalog.QWEN3_0_6B_INT4_BLOCK32, ModelCatalog.ALL.first())
    }

    // ------------------------------------------------------------------
    // P7-C8: QWEN3_1_7B_INT4_BLOCK32 / GEMMA_4_E2B_IT（モデル比較用エントリ）
    // ------------------------------------------------------------------

    // 正常系: QWEN3_1_7B_INT4_BLOCK32がP7-C8実測値（開発者自身がsha256sumで計算し、HF側
    // x-linked-etagとも一致確認済み。U-6方針）と一致する
    @Test
    fun qwen17bEntry_matchesP7C8MeasuredValues() {
        val entry = ModelCatalog.QWEN3_1_7B_INT4_BLOCK32

        assertEquals("qwen3-1.7b-int4-block32", entry.id)
        assertEquals(977_184_032L, entry.sizeBytes)
        assertEquals(
            "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b",
            entry.sha256
        )
        assertEquals(ModelLicense.APACHE_2_0, entry.license)
        assertFalse("Apache-2.0モデルはNoticeファイル同梱不要のはずです(§13 #24)", entry.requiresNoticeFile)
    }

    // 正常系: findByIdがqwen3-1.7b-int4-block32に対して正しいエントリを返す
    @Test
    fun findById_qwen17bId_returnsMatchingEntry() {
        val found = ModelCatalog.findById("qwen3-1.7b-int4-block32")

        assertEquals(ModelCatalog.QWEN3_1_7B_INT4_BLOCK32, found)
    }

    // 正常系: GEMMA_4_E2B_ITがP7-C8実測値（開発者自身がsha256sumで計算し、HF側x-linked-etagとも
    // 一致確認済み。U-6方針）と一致する
    @Test
    fun gemma4E2bEntry_matchesP7C8MeasuredValues() {
        val entry = ModelCatalog.GEMMA_4_E2B_IT

        assertEquals("gemma-4-e2b-it", entry.id)
        assertEquals(2_588_147_712L, entry.sizeBytes)
        assertEquals(
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            entry.sha256
        )
        assertEquals(ModelLicense.APACHE_2_0, entry.license)
        assertFalse("Apache-2.0モデルはNoticeファイル同梱不要のはずです(§13 #24)", entry.requiresNoticeFile)
    }

    // 正常系: findByIdがgemma-4-e2b-itに対して正しいエントリを返す
    @Test
    fun findById_gemma4E2bId_returnsMatchingEntry() {
        val found = ModelCatalog.findById("gemma-4-e2b-it")

        assertEquals(ModelCatalog.GEMMA_4_E2B_IT, found)
    }

    // 正常系: QWEN3_1_7B_INT4_BLOCK32のdefaultProfilePeakRamBytesは、P7-C8実機PSS実測
    // （ModelComparisonProbeTest.probeQwen17B_productionDefaults、実測ピークPSS=1,945,677,824
    // バイト）に安全マージンを載せて切り上げた2.0GiB（2,147,483,648バイト）である
    @Test
    fun qwen17bEntry_defaultProfilePeakRamBytes_matchesP7C8MeasuredPssPeak() {
        val entry = ModelCatalog.QWEN3_1_7B_INT4_BLOCK32

        assertEquals(
            "defaultProfilePeakRamBytesはP7-C8実機PSS実測から切り上げた2.0GiBであるべきです",
            2_147_483_648L,
            entry.defaultProfilePeakRamBytes
        )
    }

    // 正常系: GEMMA_4_E2B_ITのdefaultProfilePeakRamBytesは、P7-C8実機PSS実測
    // （ModelComparisonProbeTest.probeGemma4E2B_productionDefaults、実測ピークPSS=1,980,168,192
    // バイト）に安全マージンを載せて切り上げた2.0GiB（2,147,483,648バイト）である
    @Test
    fun gemma4E2bEntry_defaultProfilePeakRamBytes_matchesP7C8MeasuredPssPeak() {
        val entry = ModelCatalog.GEMMA_4_E2B_IT

        assertEquals(
            "defaultProfilePeakRamBytesはP7-C8実機PSS実測から切り上げた2.0GiBであるべきです",
            2_147_483_648L,
            entry.defaultProfilePeakRamBytes
        )
    }

    // 備考: 「defaultProfilePeakRamBytesがAVDのavailMemを安全マージン込みで下回るか」は
    // ActivityManager.MemoryInfo.availMemが実行時のシステム状態に依存し動的に変動するため
    // （P7-C8実測でも2.62GB〜2.94GBの幅があった）、JVM単体テストで固定閾値として回帰ロック
    // することはしない（実測値を偽って「余裕がある」と主張しない）。実機での成立可否は
    // ModelComparisonProbeTestの実行結果（本体タスク最終報告・計画書§14.10）が正とする。

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
