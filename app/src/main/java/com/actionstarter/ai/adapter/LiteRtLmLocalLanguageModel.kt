package com.actionstarter.ai.adapter

import com.actionstarter.ai.AIRecoveryResponse
import com.actionstarter.ai.LocalLanguageModel
import com.actionstarter.ai.SamplingPolicy
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext

/**
 * F86実装（計画書§7.1・§8.1・§8.3・§14 P7-C1／C5／P7契約確定）。[LocalLanguageModel]（§16）の
 * 初の実装。LiteRT-LM 0.15.0（`com.google.ai.edge.litertlm`）のKotlin APIをラップする。
 *
 * **依存方向の規律（§8.1・T-AIISO-9）**: `com.google.ai.edge.litertlm`を直接importしてよいのは
 * 本パッケージ（`ai/adapter/`）配下のみ。[com.actionstarter.ai.LocalAiGateway]を含む他の
 * `ai/`サブパッケージはこのクラスを型`LocalLanguageModel`としてのみ参照する。
 *
 * **[generatePlan]戻り値契約変更（Fable 5裁定3、2026-08-10、ADR-0045）**: `AIPlanResponse`
 * （パース済み）ではなく**LLM生JSONテキスト（`String`）をそのまま返す**契約になった。
 * パース・①形式検証・②内容sanity検証はいずれも[com.actionstarter.ai.LocalAiGateway]側の
 * 責務であり、本クラスは`sendMessage`が返したテキストを加工せず返す
 * （[LocalLanguageModel]のKDoc参照）。
 *
 * **retry契約（品質ハーネス§4/§6・Fable 5裁定・retry契約確定、2026-08-10、ADR-0049。
 * さらにFable 5裁定9・ADR-0050で解決済み）**:
 * [com.actionstarter.ai.LocalAiGateway]がスキーマ検証／内容sanityの不合格を検知して
 * 本メソッドをもう一度呼び出す場合（retry 1回、Gateway側の責務）、その2回目の生成は
 * 「**新規single-turnセッション＋微小摂動（`temperature 0.1〜0.2, topK=5`程度）＋静的な
 * 簡潔化制約文の追加**」（1回目の失敗出力を含む会話履歴を持たない、マルチターン自己修正では
 * ない）で行う（S-2是正・Gemini G1 CRITICAL #1）。**「Gateway起点の『これは何回目の呼び出しか』を
 * どう本クラスへ伝えるか」は、ADR-0049時点では未確定のまま残されていたが、Fable 5裁定9
 * （ADR-0050）で解決した**: §16の`LocalLanguageModel`インタフェースへ
 * [com.actionstarter.ai.SamplingPolicy]引数を追加し（ADR-0049「代替案と却下理由」が一度
 * 却下した設計をFable 5裁定9が明示的に覆した）、Gatewayが1回目=
 * [com.actionstarter.ai.SamplingPolicy.Primary]・2回目=
 * [com.actionstarter.ai.SamplingPolicy.Retry]を明示的に渡す設計へ確定した。本クラスは
 * 「これは何回目の呼び出しか」を自ら判断する内部カウンタを持つ必要がない——渡された
 * [com.actionstarter.ai.SamplingPolicy]の[com.actionstarter.ai.SamplingPolicy.topK]／
 * [com.actionstarter.ai.SamplingPolicy.temperature]をそのままLiteRT-LMの`SamplerConfig`へ
 * マップし、[com.actionstarter.ai.SamplingPolicy.appendConcisenessConstraint]が`true`のときのみ
 * data message末尾に固定の簡潔化制約文を追記する（`SamplerConfig`の`topP`・`seed`の具体値、
 * および新規single-turnセッション生成の実装自体はP7-C5で行う。[com.actionstarter.ai.
 * SamplingPolicy]のKDoc参照）。
 *
 * **P7-C0実測（GO判定、計画書§14 P7-C0行・§17 V-1〜V-4・V-8）で確認済みのAPI形状**
 * （実装はP7-C5、`LiteRtLmProbeTest`の実測パターンをそのまま踏襲する想定）:
 * - `Engine(EngineConfig(modelPath, backend = Backend.CPU(threadCount = N), maxNumTokens))`
 *   → `.initialize()`
 * - `engine.createConversation(ConversationConfig(enableResponseFormat = true,
 *   thinkingConfig = ThinkingConfig(enableThinking = false), maxOutputToken = N,
 *   systemInstruction = ..., initialMessages = ..., samplerConfig = ...,
 *   prefillPrefaceOnInit = true))`（品質ハーネス§1・§3・§4・§10で追加確認済みのフィールド。
 *   `systemInstruction`は[com.actionstarter.ai.prompt.PlanPromptBuilder.buildSystemInstruction]、
 *   `initialMessages`は[com.actionstarter.ai.prompt.PlanPromptBuilder.buildFewShot]の変換先）
 * - `conversation.sendMessage(text, maxOutputToken, thinkingConfig,
 *   responseFormat = ResponseFormat.json(schema))`（同期API）。cancellation協調が必要な経路では
 *   `sendMessageAsync(...): Flow<Message>`を使う（§8.3、§8.7原則3の作り込み先）。
 * - `Engine`／`Conversation`はいずれも`AutoCloseable`。`close()`は**冪等ではない**
 *   （V-2実測: close済み／未初期化への再呼び出しは`IllegalStateException`）。
 *
 * **モデルロードの遅延・再利用（R-7・T-GW-16）**: [modelPathProvider]は呼び出し時点で都度
 * パスを解決する関数として受け取る（`AppContainer`構築時点でモデルが未導入でもコンパイル・
 * DI結線が成立する）。`Engine`／`Conversation`はP7-C5で本クラスの内部状態として保持し、
 * 初回[generatePlan]呼び出しまで生成を遅延させ、2回目以降は再ロードせず再利用する
 * （KVキャッシュのみクリア）。OOM等の異常時に内部状態を破棄し次回呼び出しで再生成する
 * 「アンロード」もこの内部状態の一部として実装する（§13 #8）。この内部ライフサイクル管理は
 * [LocalLanguageModel]契約（§16、凍結）には現れない実装詳細であり、`close()`相当の公開APIは
 * 意図的に持たない（呼び出し元の[com.actionstarter.ai.LocalAiGateway]は`LocalLanguageModel`型の
 * みを介して本クラスを扱うため）。
 *
 * 契約scaffold（TDD厳守）時点では宣言のみであり、実装本体はP7-C5で行う。
 *
 * @param modelPathProvider 導入済みモデルの絶対パスを返す関数
 *   （[com.actionstarter.ai.model.ModelStorage.installedModelPath]相当）。呼び出し時点で
 *   未導入なら例外を送出する契約とし、[com.actionstarter.ai.LocalAiGateway]側の§8.6 #11
 *   チェックが先に走ることを前提に、ここでの例外送出はガードをすり抜けた場合の防御的
 *   フォールバックとして扱う。
 * @param maxNumTokens コンテキスト長（V-8。小コンテキスト・テストプロファイルは128〜256、
 *   §11.2）。既定値は本番運用値未確定のため暫定。
 * @param threadCount `Backend.CPU(threadCount = ...)`（V-3実測で存在確認済み）。既定4は
 *   P7-C0実測条件と同一値。
 */
class LiteRtLmLocalLanguageModel(
    private val modelPathProvider: () -> String,
    private val maxNumTokens: Int = DEFAULT_MAX_NUM_TOKENS,
    private val threadCount: Int = DEFAULT_THREAD_COUNT
) : LocalLanguageModel {

    override val modelIdentifier: String = "litert-lm:qwen3-0.6b-dynamic-int4-block32"

    override suspend fun generatePlan(context: PlanningContext, samplingPolicy: SamplingPolicy): String {
        TODO(
            "P7-C5で実装。samplingPolicy（Fable 5裁定9・ADR-0050、既定Primary/retry時Retry）の" +
                "topK/temperatureをSamplerConfigへマップし、appendConcisenessConstraint=trueのときのみ" +
                "data message末尾に固定簡潔化制約文を追記する。Engine/Conversationの遅延生成・" +
                "ResponseFormat.json(PlanJsonSchema.TEXT)・PlanPromptBuilder.build/" +
                "buildSystemInstruction/buildFewShotの結果をsendMessageへ渡し、生成された" +
                "生JSONテキストをそのまま返す（パース・検証はしない。計画書§8.3・§8.4、" +
                "Fable 5裁定3・ADR-0045）"
        )
    }

    override suspend fun generateRecovery(context: RecoveryContext): AIRecoveryResponse {
        TODO(
            "Phase 7では呼び出されない契約（LocalAiGateway.generateRecoveryがFallback" +
                "(NOT_IMPLEMENTED_IN_PHASE7)で早期リターンするため）。Phase 9（§18申し送り5）で実装"
        )
    }

    companion object {
        /** §11.2小コンテキスト・テストプロファイルの上限（暫定既定値）。 */
        const val DEFAULT_MAX_NUM_TOKENS: Int = 256

        /** P7-C0実測条件（`Backend.CPU(threadCount = 4)`）と同一値。 */
        const val DEFAULT_THREAD_COUNT: Int = 4
    }
}
