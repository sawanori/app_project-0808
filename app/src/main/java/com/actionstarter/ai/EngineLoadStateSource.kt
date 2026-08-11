package com.actionstarter.ai

/**
 * Phase 9.5新設（計画書`docs/plans/phase9.5-performance-quality.md`§3.10 F-5、§14発見②）。
 * [BenchmarkMetricsSource]と同型の任意実装interface（interface segregation）。
 *
 * **背景**: M実測（§14発見②）で、Engineが既にロード済みの状態でも[LocalAiGateway]の
 * §8.6 #7 OOM事前ガードが「これから新規ロードする」前提の閾値をそのまま再適用し、ロード済み
 * Engine自身のメモリ消費によってガードが自爆的に発動する欠陥が判明した。この欠陥を修正するには
 * [LocalAiGateway]が「解決済みモデルパスが既にEngineへロード済みか」を知る必要があるが、
 * [LocalLanguageModel]（§16、凍結）へ戻り値追加はできない。[BenchmarkMetricsSource]と同じ
 * 理由・同じ設計で、実測値を持つ実装（[com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]）
 * だけがこれを追加実装し、[LocalAiGateway]は`model`を`LocalLanguageModel`型で保持したまま
 * `(model as? EngineLoadStateSource)?.loadedModelPath()`で**任意に**問い合わせる。
 *
 * **後方互換性**: 本interfaceを実装しない[LocalLanguageModel]実装（既存の`FakeLocalLanguageModel`
 * 等）に対しては`as?`が常に`null`を返し、[LocalAiGateway]は従来どおり無条件にOOM事前ガードを
 * 適用する（安全側のデフォルト、既存テストに影響しない）。
 */
interface EngineLoadStateSource {
    /**
     * 現在Engineへロード済みのモデルパス（絶対パス、[com.actionstarter.ai.model.ModelStorage.
     * finalFile]が返す形式と同一）。Engine未生成なら`null`。
     */
    fun loadedModelPath(): String?
}
