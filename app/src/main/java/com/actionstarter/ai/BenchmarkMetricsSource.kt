package com.actionstarter.ai

/**
 * F86/F96隣接・新設（P7-C5、ADR-0055）。直近の[LocalLanguageModel.generatePlan]呼び出しで得られた
 * 実測ベンチマーク値の非PIIスナップショット。
 *
 * **なぜ[LocalLanguageModel]（§16、凍結）へ戻り値追加しないか**: [LocalLanguageModel.generatePlan]の
 * 戻り値は`String`（生JSON、Fable 5裁定3・ADR-0045）に確定済みであり、これをさらに変更するのは
 * 「凍結interfaceの契約変更」に当たり、TEAMS §5契約変更フロー相当の裁定を要する重い変更になる。
 * 一方、実測メトリクス（ロード時間・TTFT・tok/s・ピークRAM）はP7-C5のスコープ内で確定させる
 * 実装詳細であり、既存の凍結契約を壊さずに届けたい。そこで**任意実装インターフェース
 * （interface segregation）**として本interfaceを新設した——[com.actionstarter.ai.adapter.
 * LiteRtLmLocalLanguageModel]のように実測値を持つ実装だけがこれを追加実装し、
 * [LocalAiGateway]は`model`を`LocalLanguageModel`型で保持したまま
 * `(model as? BenchmarkMetricsSource)?.lastInferenceMetrics()`で**任意に**問い合わせる。
 *
 * **後方互換性（既存528件のJVMテストに影響しない）**: `LocalAiGatewayTest`の
 * `FakeLocalLanguageModel`／`ConcurrencyTrackingFakeModel`はいずれも本interfaceを実装しない
 * ため、`as?`は常に`null`を返し、[LocalAiGateway]は従来どおりのプレースホルダ値（0）へ
 * フォールバックする（P7-C3のKDoc「いずれのフィールドもLocalAiGatewayTestで具体値を
 * 検証されておらず、後方互換に問題はない」と整合）。
 *
 * **T-AIISO-9との整合**: 本interface自体は`com.google.ai.edge.litertlm`を一切importしない
 * （Long/Double/Intのみで構成）ため、`ai/`パッケージ直下に置いても依存方向規律（§8.1）を
 * 破らない。
 */
interface BenchmarkMetricsSource {
    /**
     * 直近の[LocalLanguageModel.generatePlan]呼び出しで測定された値。まだ1度も呼び出されて
     * いない場合は`null`。
     */
    fun lastInferenceMetrics(): InferenceBenchmarkSnapshot?
}

/**
 * [BenchmarkMetricsSource.lastInferenceMetrics]が返す実測値（§57・[AiMetrics]に1:1対応）。
 * カレンダー本文・イベントタイトル・住所・座標は一切含めない（§60、[AiMetrics]と同じ非PII方針）。
 *
 * @param modelLoadMs `Engine`のロードに要した時間（ms）。**この呼び出しで新規ロードが発生した
 *   場合のみ実測値、既にロード済みのEngineを再利用した場合は`0`**（R-7・T-GW-16「2回目以降は
 *   再ロードせず再利用する」の忠実な表現。モデルロードは通常プロセス生涯で1回のみ発生する）。
 * @param firstTokenMs `Conversation.getBenchmarkInfo().timeToFirstTokenInSecond`をmsへ換算した値。
 * @param outputTokens `Conversation.getBenchmarkInfo().lastDecodeTokenCount`。
 * @param tokensPerSecond `Conversation.getBenchmarkInfo().lastDecodeTokensPerSecond`。
 * @param peakNativeHeapBytes 生成中に`Debug.getNativeHeapAllocatedSize()`をバックグラウンド
 *   スレッドで定期サンプリングした最大値（P7-C0 `LiteRtLmProbeTest`の`RamSampler`と同一方式）。
 */
data class InferenceBenchmarkSnapshot(
    val modelLoadMs: Long,
    val firstTokenMs: Long,
    val outputTokens: Int,
    val tokensPerSecond: Double,
    val peakNativeHeapBytes: Long
)
