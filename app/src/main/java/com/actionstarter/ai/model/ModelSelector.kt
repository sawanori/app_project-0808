package com.actionstarter.ai.model

import com.actionstarter.ai.EngineLoadStateSource

/**
 * Phase 8.5（計画書 docs/plans/phase8.5-adaptive-model-selection.md §3設計1、ADR-0062）。
 * 「自動」選択時に、導入済みかつ空きメモリに収まる最高品質のモデルを決定するinterface。
 *
 * **Step 4（Green）で実装済み**: [ModelSelectorImpl.select]はT-P85-1〜9で検証済み。
 *
 * [candidates]は品質順（現状Gemma4 > Qwen3-0.6B）の候補一覧。Qwen3-1.7Bは自動選択の対象に
 * 含めない（P7-C8実測: 0.6Bより遅く品質も退化、非推奨確定済み）。
 */
interface ModelSelector {
    /** 品質順の自動選択候補一覧（先頭ほど優先）。 */
    val candidates: List<ModelCatalogEntry>

    /**
     * [candidates]を順に見て、導入済み（[ModelStorage.finalFile]が実在）かつ空きメモリに
     * 収まる（[DeviceCapability.hasAvailableMemory]、[ModelCatalogEntry.defaultProfilePeakRamBytes]
     * ＋[DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES]）最初の1件を返す。1件も一致しなければ`null`。
     */
    fun select(): ModelCatalogEntry?

    companion object {
        /** 品質順・Qwen3-1.7B除外を固定する既定候補（[ModelSelectorImpl]の既定値）。 */
        val DEFAULT_AUTO_CANDIDATES: List<ModelCatalogEntry> = listOf(
            ModelCatalog.GEMMA_4_E2B_IT,
            ModelCatalog.QWEN3_0_6B_INT4_BLOCK32
        )
    }
}

/**
 * [ModelSelector]の実装。状態を持たず、呼び出しごとに[deviceCapability]／[modelStorage]の
 * 現在値を参照して独立評価する（副作用なし）。
 *
 * @param candidates 自動選択の候補（品質順）。既定は[ModelSelector.DEFAULT_AUTO_CANDIDATES]。
 *   テストは小さなfixtureエントリへ差し替え可能（[ModelStorageImpl]の`catalog`引数と同型）。
 * @param engineLoadStateSource Phase 9.5新設（計画書`docs/plans/phase9.5-performance-quality.md`
 *   §3.10 F-5、§14発見②、Red検収での差し戻し訂正）。既定`null`（後方互換、既存呼び出し元は
 *   無改修）。**M実測で判明した欠陥**: `select()`が候補ごとに`hasAvailableMemory`を無条件で
 *   要求すると、既にEngineへロード済みの候補自身が「これから新規ロードする」前提の閾値で
 *   再チェックされ、ロード済みEngine自身のメモリ消費によって不適合と誤判定されうる
 *   （`unresolvedEntryFallback()`の`OUT_OF_MEMORY_PREVENTED`「auto: no candidate fits」実測
 *   ログで確認済み）。[engineLoadStateSource]が非nullかつ、走査中の候補の
 *   `modelStorage.finalFile(entry).absolutePath`が現在ロード済みのパスと一致する場合、その候補は
 *   `hasAvailableMemory`チェックを**スキップして適合扱い**とする。品質順走査自体は変更しない
 *   （ロード済みでも、より高品質な未ロード候補が通常判定で適合するならそちらが優先される。
 *   T-P95-47参照）。
 *
 * **Step 4（Green、Red検収での差し戻し訂正）で実装済み**: [select]は候補ごとに
 * [modelStorage.finalFile]の絶対パスと[engineLoadStateSource]のロード済みパスを比較し、
 * 一致すれば`hasAvailableMemory`チェックをスキップして適合扱いとする（T-P95-46）。品質順走査
 * 自体は変更しないため、ロード済みでない上位候補が通常判定で適合する場合はそちらが優先される
 * （T-P95-47、born-green）。
 */
class ModelSelectorImpl(
    private val deviceCapability: DeviceCapability,
    private val modelStorage: ModelStorage,
    override val candidates: List<ModelCatalogEntry> = ModelSelector.DEFAULT_AUTO_CANDIDATES,
    private val engineLoadStateSource: EngineLoadStateSource? = null
) : ModelSelector {

    override fun select(): ModelCatalogEntry? = candidates.firstOrNull { entry ->
        val file = modelStorage.finalFile(entry)
        if (!file.isFile) {
            false
        } else {
            val alreadyLoaded = engineLoadStateSource?.loadedModelPath() == file.absolutePath
            alreadyLoaded ||
                deviceCapability.hasAvailableMemory(entry.defaultProfilePeakRamBytes + DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES)
        }
    }
}
