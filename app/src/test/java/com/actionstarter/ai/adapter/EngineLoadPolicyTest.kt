package com.actionstarter.ai.adapter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8.5 Step 3（Red）。計画書`docs/plans/phase8.5-adaptive-model-selection.md`§6
 * T-P85-25〜27。[requiresEngineReload]の失敗テスト。
 *
 * **現状のRed原因**: [requiresEngineReload]の本体は`TODO()`のため、以下は全件
 * `NotImplementedError`によりRedになるのが正しい。
 *
 * **検証境界（計画書§6「検証境界の明記」参照）**: 本ファイルが検証するのは「再ロードすべきか」
 * という決定ロジックのみ。[LiteRtLmLocalLanguageModel.obtainEngine]が実際に行うEngine生成回数の
 * 実機動作は、クラス自体がJVM単体テストでインスタンス化不可（class file version 65）のため
 * `androidTest`のプローブで別途検証する。
 */
class EngineLoadPolicyTest {

    // T-P85-25: エッジ - Engine未生成（loadedModelPath=null）→ 再ロード要
    @Test
    fun requiresEngineReload_noEngineLoadedYet_returnsTrue() {
        val result = requiresEngineReload(loadedModelPath = null, requestedModelPath = "/fake/models/a.litertlm")

        assertTrue(result)
    }

    // T-P85-26: 正常 - 同一パスへの要求 → 再利用（再ロード不要）
    @Test
    fun requiresEngineReload_samePathRequested_returnsFalse() {
        val result = requiresEngineReload(
            loadedModelPath = "/fake/models/a.litertlm",
            requestedModelPath = "/fake/models/a.litertlm"
        )

        assertFalse(result)
    }

    // T-P85-27: 異常 - 別パスへの要求（モデル切替）→ 再ロード要
    @Test
    fun requiresEngineReload_differentPathRequested_returnsTrue() {
        val result = requiresEngineReload(
            loadedModelPath = "/fake/models/a.litertlm",
            requestedModelPath = "/fake/models/b.litertlm"
        )

        assertTrue(result)
    }
}
