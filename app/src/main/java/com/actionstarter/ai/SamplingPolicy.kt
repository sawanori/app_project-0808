package com.actionstarter.ai

/**
 * F86/F96隣接・新設（Fable 5裁定9、2026-08-10、ADR-0050）。品質ハーネス
 * （`docs/plans/phase7-quality-harness.md`§4「サンプリング設計」・§6「3段検証＋再試行1回」・
 * §11 QH-9）が定めるサンプリング条件を、ランタイム非依存の形で表現するenum。
 *
 * **契約変更の経緯（重要）**: 本enumの新設と[com.actionstarter.ai.LocalLanguageModel.generatePlan]
 * への引数追加は、ADR-0049「代替案と却下理由」が一度**却下した**設計
 * （`generatePlan(context, isRetry: Boolean)`のような retry 制御パラメータの追加。理由:
 * 「§16の`LocalLanguageModel`は凍結interfaceであり、裁定1〜8はいずれもパラメータ追加を
 * 明示的に指示していない」）を、**Fable 5裁定9が明示的に覆した**ことによる。旧裁定が想定した
 * 代替案（[com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]が呼び出し回数を内部状態
 * として追跡する）は不要になった——[com.actionstarter.ai.LocalAiGateway]が1回目/2回目のどちらの
 * 呼び出しかを型で明示的に伝えるため。詳細はADR-0050参照。
 *
 * **責務分担**: どちらの[SamplingPolicy]を使うか決めるのは[com.actionstarter.ai.LocalAiGateway]
 * （検証パイプライン①②が不合格のとき2回目=[Retry]で呼び直す。品質ハーネス§6）。
 * [com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]（P7-C5）は渡された方針に従うだけで、
 * 「これは何回目の呼び出しか」を自分で判断しない。[com.actionstarter.ai.adapter.
 * LiteRtLmLocalLanguageModel]が本enumの[topK]／[temperature]をLiteRT-LMの実際の
 * `SamplerConfig(topK, topP, temperature, seed)`へどうマップするか（`topP`・`seed`の具体値を
 * 含む）はP7-C5の実装詳細であり、本scaffoldは意図的に含めない（品質ハーネス§4の`topP`/`seed`は
 * adapter内部でのマップ先として申し送る）。
 *
 * **[topK]・[temperature]の根拠（品質ハーネス§4の表）**:
 * - [Primary]（1回目・既定）: `topK=1, temperature=0.0`。LiteRT-LMのJNIは`SamplerConfig`設定時に
 *   必ず`TOP_P`型のサンプラへ切り替える実装のため、Kotlin側からGREEDY enumへは到達不能
 *   （品質ハーネス§1「サンプラ種別の選択」）。`topK=1`で候補を最尤1トークンへ絞ることで
 *   **実質greedy**を近似する。
 * - [Retry]（2回目・検証①②不合格時のみ）: `topK=5, temperature=0.15`（0.1〜0.2の範囲内）。
 *   1回目が決定的（greedy近似）で失敗した場合、同一条件での再生成は同一失敗を再現するだけで
 *   無効（旧基盤計画S-2の欠陥、品質ハーネス§0）。ただし0.6B/INT4級の超小型量子化モデルは
 *   温度を大きく上げると出力が崩壊しやすいため、**微小摂動**にとどめる（品質ハーネス§4
 *   「なぜ『微小』摂動にとどめるか」）。
 *
 * **[appendConcisenessConstraint]（静的簡潔化制約フラグ）**: `true`のとき、
 * [com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]がdata message（[PlanningContext]
 * から生成する予定データ部）の末尾に固定文（例:
 * "Be extra concise. Do not include any time, date, number, address, or duplicate
 * action_type. Output valid JSON only, following the schema exactly."）を追記する契約
 * （品質ハーネス§6「静的制約の追加（2回目のみ）」）。**[Primary]は`false`・[Retry]のみ`true`**。
 * 「1回目の失敗を参照させる動的な是正指示」（例:"Previous output was rejected"）は使わない
 * （0.6B/INT4はマルチターン自己修正がほぼ機能しないため、固定・静的な文言のみを追加する。
 * 品質ハーネス§4「なぜマルチターン自己修正を採用しないか」）。
 *
 * 契約scaffold（TDD厳守）時点では全プロパティが確定済みのenum定数であり、[SamplingPolicy]
 * 自体に`TODO()`は含まない（born-green契約。[com.actionstarter.ai.AiFallbackReason]・
 * [com.actionstarter.ai.schema.PlanEventType]・[com.actionstarter.ai.schema.PlanActionType]と
 * 同型の「値が確定した契約はenum定数として即座に確定させる」パターン）。実際に
 * [com.actionstarter.ai.LocalAiGateway]・[com.actionstarter.ai.adapter.
 * LiteRtLmLocalLanguageModel]がこれを読んで動作を変える実装はP7-C3以降（Green）で行う。
 */
enum class SamplingPolicy(
    val topK: Int,
    val temperature: Double,
    val appendConcisenessConstraint: Boolean
) {
    /** 1回目（既定）。greedy近似（品質ハーネス§4「1回目（既定）」）。 */
    Primary(topK = 1, temperature = 0.0, appendConcisenessConstraint = false),

    /** 2回目（検証①②不合格時のみ）。微小摂動＋静的簡潔化制約（品質ハーネス§4「2回目（再試行）」）。 */
    Retry(topK = 5, temperature = 0.15, appendConcisenessConstraint = true)
}
