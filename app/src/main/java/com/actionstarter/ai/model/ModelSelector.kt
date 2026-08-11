package com.actionstarter.ai.model

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
 */
class ModelSelectorImpl(
    private val deviceCapability: DeviceCapability,
    private val modelStorage: ModelStorage,
    override val candidates: List<ModelCatalogEntry> = ModelSelector.DEFAULT_AUTO_CANDIDATES
) : ModelSelector {

    override fun select(): ModelCatalogEntry? = candidates.firstOrNull { entry ->
        modelStorage.finalFile(entry).isFile &&
            deviceCapability.hasAvailableMemory(entry.defaultProfilePeakRamBytes + DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES)
    }
}
