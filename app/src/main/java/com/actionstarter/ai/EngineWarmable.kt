package com.actionstarter.ai

/**
 * Phase 9.5新設（計画書`docs/plans/phase9.5-performance-quality.md`§3.3 F-2、敵対的レビュー
 * 採用A-5）。[BenchmarkMetricsSource]・[EngineLoadStateSource]と同型の任意実装interface
 * （interface segregation）。
 *
 * **背景（§3.3「ウォームアップ入口はGateway層」）**: `ai/adapter/`のEngine準備メソッドを
 * features層へ直接公開せず、[LocalAiGateway.warmUp]が`generatePlan`／`generateRecovery`と
 * 同じ事前ガード列（`preferences.aiEnabled`→`deviceCapability.classify()`→`isAbiSupported()`→
 * `resolveInstalledEntry()`→強化availMemガード）を**すべて通過した場合のみ**、この任意
 * interfaceを実装するadapter（[com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]）へ
 * Engine準備を委譲する。[LocalLanguageModel]（§16、凍結）へ戻り値・メソッド追加はできないため、
 * [BenchmarkMetricsSource]・[EngineLoadStateSource]と同じ設計（実測値／状態を持つ実装だけが
 * 追加実装し、[LocalAiGateway]は`model`を`LocalLanguageModel`型で保持したまま
 * `(model as? EngineWarmable)?.warmUpEngine(modelPath)`で**任意に**問い合わせる）を踏襲する。
 *
 * **後方互換性**: 本interfaceを実装しない[LocalLanguageModel]実装（既存の`FakeLocalLanguageModel`
 * 等）に対しては`as?`が常に`null`を返し、[LocalAiGateway.warmUp]は該当呼び出しを単純にスキップ
 * する（安全側のデフォルト、既存テストに影響しない）。
 */
interface EngineWarmable {
    /**
     * [modelPath]のEngineを準備する（未ロードなら生成、ロード済みなら何もしない——
     * [com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]の`obtainEngine`と同じ
     * 再利用判定に委ねる想定）。戻り値なし・副作用（Engineのロード）のみが目的。
     */
    suspend fun warmUpEngine(modelPath: String)
}
