package com.actionstarter.ai.adapter

/**
 * Phase 8.5（計画書 docs/plans/phase8.5-adaptive-model-selection.md §3設計6、ADR-0062）。
 * [LiteRtLmLocalLanguageModel]のEngineキャッシュが、要求されたモデルパスに対して既存Engineを
 * 再利用してよいか（再ロードが必要か）を判定する純粋関数。
 *
 * **`com.google.ai.edge.litertlm`を一切importしない**（意図的）。[LiteRtLmLocalLanguageModel]
 * 自体はクラスファイルバージョン不一致（class file version 65）によりJVM単体テストでは
 * インスタンス化不可能なため（`AiGatewayTestFixtures`のKDoc「なぜlitertlm実装を使わないか」
 * 参照）、この判定ロジックだけを本ファイルへ抽出しJVM単体テストの対象にする。Engine自体の
 * 生成・破棄の実機動作は`androidTest`のプローブで別途検証する（計画書§6「検証境界の明記」）。
 *
 * **Step 4（Green）で実装済み**: T-P85-25〜27で検証済み。
 *
 * @param loadedModelPath 現在ロード済みのモデルパス。Engine未生成なら`null`。
 * @param requestedModelPath 今回要求されているモデルパス。
 * @return 再ロードが必要なら`true`（[loadedModelPath]が`null`、または[requestedModelPath]と異なる）。
 */
internal fun requiresEngineReload(loadedModelPath: String?, requestedModelPath: String): Boolean =
    loadedModelPath != requestedModelPath
