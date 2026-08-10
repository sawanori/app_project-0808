package com.actionstarter.ai

/**
 * 仕様§20／§95.6準拠（Basic Engineへのフォールバック理由）。[LocalAiGateway.generatePlan]／
 * [LocalAiGateway.generateRecovery]が`AiResult.Fallback`を返す際の理由コード（計画書§8.5・
 * §8.6・§14 P7-C1「`AiFallbackReason`は全値を確定させる」）。
 *
 * **Analyticsキーとの対応**（§7.1フットプリント「Analyticsキーと1:1」）: 本enumの`name`
 * （例 `"OUT_OF_MEMORY_PREVENTED"`）自体をAnalyticsキーとして1:1で用いる設計とし、
 * 別途のキー文字列マッピングは持たない。§60が列挙するのはメトリクス値側のフィールド名
 * （[AiMetrics]参照）であり、フォールバック理由コードの命名規則を別途定めていないため、
 * enum定数名をそのまま採用するのが最も単純で漏れがない。
 *
 * 各値の根拠（計画書§8.6発動条件表の行番号・§12.5テストIDに対応）:
 * - [AI_DISABLED]: §8.6 #10・T-GW-2（AI OFFが既定、§19）
 * - [MODEL_NOT_INSTALLED]: §8.6 #11・T-GW-3
 * - [UNSUPPORTED_DEVICE]: §8.6 #1（RAM不足、端末非対応）・§13 #19・T-GW-9
 * - [UNSUPPORTED_ABI]: §8.6 #2（`arm64-v8a`非搭載）・T-GW-10
 * - [INSUFFICIENT_STORAGE]: T-GW-11（DL開始前のStatFsガード、§95.6）
 * - [MODEL_LOAD_FAILED]: §13 #1・T-GW-4
 * - [MODEL_CORRUPTED]: §8.6 #12（ロード前再検証）・T-GW-18・Gemini G1 CRITICAL #2
 * - [OUT_OF_MEMORY_PREVENTED]: §8.6 #7（能動的メモリガード・主防御）・T-GW-5・
 *   Gemini G1 CRITICAL #3
 * - [OUT_OF_MEMORY]: §8.6 #13（Java `catch`による二次防御）・T-GW-17
 * - [TIMEOUT]: §8.6 #8・T-GW-6
 * - [SCHEMA_INVALID]: §8.6 #9（retry 1回後も不合格）・T-GW-8・§20。Fable 5裁定3・4
 *   （2026-08-10、ADR-0047）により、①形式検証（[com.actionstarter.ai.schema.SchemaValidator]）
 *   ・②内容sanity検証（[com.actionstarter.ai.schema.ContentSanityChecker]）のいずれの不合格も
 *   本値へ集約する（品質ハーネス§6のフロー図「①または②失敗→retry1回→なお失敗→
 *   Fallback(SCHEMA_INVALID)」。②専用の理由コードは新設しない）
 * - [NOT_IMPLEMENTED_IN_PHASE7]: §13 #18・U-8（`generateRecovery`のPhase 7未実装）
 * - [UNKNOWN]: T-GW-12（未分類の`RuntimeException`。`detail`に例外クラス名を残し
 *   サイレント握り潰しを避ける）
 *
 * **意図的に含めない値**:
 * - `CANCELLED`。§8.7原則3・T-GW-13により、呼び出し側のコルーチンキャンセルは
 *   `CancellationException`を握り潰さず再送出する契約であり、`AiResult.Fallback`には
 *   化けさせない（Analytics上の記録経路は別途P7-C5で設計する）。
 * - §8.6 #4（DL失敗）・#5（DL直後の1回限り検証失敗）に対応する理由コード。これらは
 *   [LocalAiGateway]の呼び出し結果ではなく、[com.actionstarter.ai.model.ModelDownloader]（F88）／
 *   [com.actionstarter.ai.model.ModelVerifier]（F89）自身の戻り値型
 *   （[com.actionstarter.ai.model.ModelDownloadResult]／
 *   [com.actionstarter.ai.model.ModelVerificationResult]）で表現する別の関心事のため。
 * - `BUSY`。§13 #11の原文は「Mutexで直列化。2本目は待つか即Fallback(BUSY)」と両論併記だが、
 *   §12.5 T-GW-15が確定させた仕様は「同時呼び出しはMutexで直列化（待機）」であり、
 *   即時拒否は採用しない。したがって`BUSY`理由コードは不要と判断した。
 */
enum class AiFallbackReason {
    AI_DISABLED,
    MODEL_NOT_INSTALLED,
    UNSUPPORTED_DEVICE,
    UNSUPPORTED_ABI,
    INSUFFICIENT_STORAGE,
    MODEL_LOAD_FAILED,
    MODEL_CORRUPTED,
    OUT_OF_MEMORY_PREVENTED,
    OUT_OF_MEMORY,
    TIMEOUT,
    SCHEMA_INVALID,
    NOT_IMPLEMENTED_IN_PHASE7,
    UNKNOWN
}
