package com.actionstarter.ai

import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext

/**
 * 仕様§16準拠（Local LLM Runtime）。Model Adapter方式の契約interface。
 *
 * 特定モデル（gguf / LiteRT等）依存コードをUIやDomain層へ漏らさないための境界。
 * モデルは技術検証で交換可能とする。
 *
 * **§16契約変更（Fable 5裁定3、2026-08-10、ADR-0045。TEAMS §5契約変更フローに基づく）**:
 * [generatePlan]の戻り値を`AIPlanResponse`（パース済みオブジェクト）から**`String`
 * （LLM生JSONテキスト）**へ変更した。変更理由: P7-C2完了記録が発見した統合ギャップ
 * ——本interfaceが`AIPlanResponse`を返す契約である一方、[com.actionstarter.ai.schema.
 * SchemaValidator.validate]は`rawJson: String`を受け取る契約であり、両者の間に
 * 「誰が・どうやってAIPlanResponseを生JSON文字列へ再シリアライズするのか」という
 * 不要な往復が生じていた。生JSONを返す契約へ変更することで、検証パイプラインを
 * `LLM生JSON(String) → SchemaValidator[形式] → ContentSanityChecker[内容] → パース済み
 * AIPlanResponse`という一方向の流れに統一する（[com.actionstarter.ai.schema.SchemaValidator]の
 * KDoc参照）。
 *
 * [generateRecovery]の戻り値は本裁定の対象外であり無変更（Phase 7では
 * [com.actionstarter.ai.LocalAiGateway.generateRecovery]が[AiFallbackReason.
 * NOT_IMPLEMENTED_IN_PHASE7]を返すのみで本メソッドを呼び出さない契約、U-8）。
 *
 * 戻り値の[AIRecoveryResponse]はSchema Validation前の生の構造化出力であり、Domain Logicへ
 * 直接使用してはならない（仕様§20）。
 *
 * **[generatePlan]への`samplingPolicy`引数追加（Fable 5裁定9、2026-08-10、ADR-0050。
 * TEAMS §5契約変更フローに基づく）**: [SamplingPolicy]引数を追加した（既定値
 * [SamplingPolicy.Primary]）。**この変更はADR-0049「代替案と却下理由」が一度却下した設計
 * （retry制御パラメータを本interfaceへ追加すること）を明示的に覆すものである**——ADR-0049は
 * 「§16の`LocalLanguageModel`は凍結interfaceであり、裁定1〜8はいずれもパラメータ追加を
 * 指示していない」ことを却下理由としていたが、Fable 5裁定9はこの制約を明示的に解除した。
 * これにより、[com.actionstarter.ai.LocalAiGateway]が1回目は[SamplingPolicy.Primary]・
 * 検証パイプライン（[com.actionstarter.ai.schema.SchemaValidator]→
 * [com.actionstarter.ai.schema.ContentSanityChecker]）不合格後の2回目は[SamplingPolicy.Retry]で
 * 本メソッドを呼び分ける（Gatewayの責務。[SamplingPolicy]のKDoc参照）。
 * [com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]（P7-C5）は渡された方針に従うだけで、
 * 「これは何回目の呼び出しか」を自ら判断する内部カウンタは不要になった。
 *
 * 契約scaffold時点では宣言のみであり、実装は行わない。
 */
interface LocalLanguageModel {
    val modelIdentifier: String

    /**
     * [context]から生成した予定準備プランのLLM生JSONテキストを返す（Fable 5裁定3・9）。
     * 呼び出し側（[com.actionstarter.ai.LocalAiGateway]）が
     * [com.actionstarter.ai.schema.SchemaValidator.validate]・
     * [com.actionstarter.ai.schema.ContentSanityChecker.check]の順で検証しパースする。
     * 本メソッド自身はパース・検証を行わない（信頼境界、仕様§20）。
     *
     * @param samplingPolicy 使用するサンプリング方針（Fable 5裁定9、既定[SamplingPolicy.Primary]）。
     *   [com.actionstarter.ai.LocalAiGateway]が1回目=[SamplingPolicy.Primary]、
     *   検証不合格による2回目=[SamplingPolicy.Retry]で呼び分ける（クラスKDoc参照）。
     */
    suspend fun generatePlan(context: PlanningContext, samplingPolicy: SamplingPolicy = SamplingPolicy.Primary): String

    suspend fun generateRecovery(context: RecoveryContext): AIRecoveryResponse
}
