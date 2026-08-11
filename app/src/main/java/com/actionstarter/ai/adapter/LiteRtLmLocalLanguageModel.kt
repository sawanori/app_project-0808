package com.actionstarter.ai.adapter

import android.os.Debug
import android.os.SystemClock
import com.actionstarter.ai.BenchmarkMetricsSource
import com.actionstarter.ai.EngineLoadStateSource
import com.actionstarter.ai.InferenceBenchmarkSnapshot
import com.actionstarter.ai.LocalLanguageModel
import com.actionstarter.ai.SamplingPolicy
import com.actionstarter.ai.prompt.PlanPromptBuilder
import com.actionstarter.ai.prompt.RecoveryPromptBuilder
import com.actionstarter.ai.schema.PlanJsonSchema
import com.actionstarter.ai.schema.RecoveryJsonSchema
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * F86実装（計画書§7.1・§8.1・§8.3・§14 P7-C1／C5／P7契約確定）。[LocalLanguageModel]（§16）の
 * 初の実装。LiteRT-LM 0.15.0（`com.google.ai.edge.litertlm`）のKotlin APIをラップする。
 *
 * **依存方向の規律（§8.1・T-AIISO-9）**: `com.google.ai.edge.litertlm`を直接importしてよいのは
 * 本パッケージ（`ai/adapter/`）配下のみ。[com.actionstarter.ai.LocalAiGateway]を含む他の
 * `ai/`サブパッケージはこのクラスを型`LocalLanguageModel`（および任意実装の
 * [BenchmarkMetricsSource]、litertlm型を一切含まない）としてのみ参照する。
 *
 * **[generatePlan]戻り値契約（Fable 5裁定3、ADR-0045）**: `AIPlanResponse`ではなく**LLM生JSON
 * テキスト（`String`）をそのまま返す**。パース・①形式検証・②内容sanity検証はいずれも
 * [com.actionstarter.ai.LocalAiGateway]側の責務であり、本クラスは`sendMessage`が返した
 * テキストを加工せず返す。
 *
 * **retry契約の実装（品質ハーネス§4/§6、ADR-0049・ADR-0050、P7-C5で確定）**: [model]呼び出しの
 * 「これは何回目か」は[com.actionstarter.ai.SamplingPolicy]引数で明示的に渡されるため、本クラスは
 * 呼び出し回数を追跡しない。[samplingPolicy]の[SamplingPolicy.topK]／[SamplingPolicy.temperature]を
 * [toSamplerConfig]でLiteRT-LMの`SamplerConfig(topK, topP, temperature, seed)`へマップし
 * （`topP`／`seed`の具体値はADR-0056参照）、[SamplingPolicy.appendConcisenessConstraint]が
 * `true`のときのみ[buildDataMessage]がdata message末尾へ固定簡潔化制約文を追記する。**さらに
 * 「新規single-turnセッション」の要求（S-2是正・Gemini G1 CRITICAL #1）は、[generatePlan]が
 * 呼び出しのたびに新しい[Conversation]を生成する設計（下記「Engine/Conversationのライフサイクル」）
 * によって自動的に満たされる**——2回目（Retry）の呼び出しも独立した新規Conversationであり、
 * 1回目の失敗出力を含む会話履歴を一切引き継がない。
 *
 * **Engine/Conversationのライフサイクル（R-7・T-GW-16・§13 #8、P7-C5で確定）**:
 * - **Engine**: プロセス内で高々1個。[obtainEngine]が[engineLifecycleMutex]配下で遅延生成し、
 *   以後の呼び出しは同一インスタンスを再利用する（モデルロードは通常プロセス生涯で1回のみ）。
 * - **Conversation**: [generatePlan]の呼び出しごとに新規生成し、`finally`で必ず`close()`する。
 *   毎回`systemInstruction`＋`initialMessages`（few-shot）のprefaceを`prefillPrefaceOnInit=true`で
 *   再prefillする（品質ハーネス§3「動的単一言語few-shot」・§7「各generatePlanでpreface再prefill」）。
 *   これにより「KVキャッシュのみクリアして再利用する」（Engineの重みは保持しつつ会話状態は
 *   都度リセットする）というR-7の要求と、retryの新規single-turnセッション要求を同一の仕組みで
 *   満たす。
 * - **`close()`は非冪等**（V-2実測、`LiteRtLmProbeTest`）。Conversationは1回だけ`close()`し、
 *   Engineは`OutOfMemoryError`発生時の「アンロード」（[unloadEngine]、§13 #8二次防御）以外では
 *   閉じない。
 *
 * **PII最小化（§10・§34、Gateway統合配線タスク指示）**: [PlanPromptBuilder.build]は
 * カレンダー本文（`notes`）を含まない設計（P7-C3実装済み）であり、本クラスはこれをそのまま
 * data messageとして使う。few-shot例（[PlanPromptBuilder.buildFewShot]）も固定の合成例であり
 * 実データを含まない。
 *
 * **実測メトリクス（ADR-0055、AiMetrics実測差し替え）**: 本クラスは新設[BenchmarkMetricsSource]を
 * 追加実装し、直近の[generatePlan]呼び出しで得た[InferenceBenchmarkSnapshot]を保持する。
 * [com.actionstarter.ai.LocalAiGateway]はこれを型検査（`as?`）で任意に読み出す
 * （[BenchmarkMetricsSource]のKDoc参照。§16の凍結`LocalLanguageModel`interfaceには一切
 * 手を加えていない）。`modelLoadMs`はP7-C0 `LiteRtLmProbeTest`実測に倣い`SystemClock.
 * elapsedRealtime()`のwall-clock差分を使う——同実測が`BenchmarkInfo.initTimeInSecond`について
 * 「自前のwall-clockロード時間や試験全体の経過時間と桁が一致しない」（数値の意味を断定できない）
 * ことを発見済みのため、これを`modelLoadMs`の情報源として採用しない（ADR-0056）。
 * `firstTokenMs`／`outputTokens`／`tokensPerSecond`は`Conversation.getBenchmarkInfo()`
 * （`ExperimentalFlags.enableBenchmark = true`が前提、同じくP7-C0実測で発見）から取得する。
 * `peakNativeHeapBytes`はP7-C0 `LiteRtLmProbeTest`の`RamSampler`と同一方式（バックグラウンド
 * スレッドによる定期サンプリング）の[NativeHeapPeakSampler]で取得する。
 *
 * **Phase 8.5での変更（計画書`docs/plans/phase8.5-adaptive-model-selection.md`§3設計5・6、
 * ADR-0062、アーキテクトレビューPass 1 CRITICAL対応、Step 4で実装完了）**:
 * 旧`modelPathProvider: () -> String`コンストラクタ引数（[com.actionstarter.ai.LocalAiGateway]が
 * 検証・選択したモデルとは独立に`ModelStorage`を再解決してしまい、auto選択時に乖離しうる欠陥が
 * あった）を廃止した。モデルパスは[generatePlan]の`modelPath`引数として毎回明示的に渡される
 * （[LocalLanguageModel.generatePlan]のKDoc参照）。[obtainEngine]は要求パスの変化を
 * `EngineLoadPolicy.requiresEngineReload`で検知し、変化時のみEngineを再生成する
 * （T-P85-25〜29で検証済み）。
 * @param maxNumTokens コンテキスト長（V-8。§11.2）。**P7-C5b（ADR-0057）でP7-C1の固定値256から
 *   変更した**——P7-C5実機実測（ADR-0056）が、本番プロンプト一式（`systemInstruction`＋
 *   既定2-shot few-shot＋data message）では256では実機`FAILED_PRECONDITION`により推論が
 *   一度も成功しないことを発見したため（256のままでは§8.6 #7のOOM事前ガード修正〔ADR-0057〕
 *   と無関係に、推論そのものが機能しない状態だった）。既定値は
 *   [PlanPromptBuilder.estimateMaxNumTokens]（実際のprefaceの文字数＋出力上限から算出、
 *   P7-C5実機実測で成功確認済みの1024を下限にclamp）を使う。本番運用値の絶対的な最終確定は
 *   引き続きP7-C8実機プローブの責務（計画書§17 V-8・§11.3、Galaxy A実機での再検証）だが、
 *   「実機で推論が一度も成功しない既定値のまま放置する」ことは基盤バグでありP7-C8を待たずに
 *   P7-C5bで是正した（ADR-0057「代替案と却下理由」参照）。
 * @param shotCount ADR-0057新設。[buildConversationConfig]が[PlanPromptBuilder.buildFewShot]へ
 *   渡すfew-shot件数（既定[PlanPromptBuilder.DEFAULT_SHOT_COUNT]）。品質ハーネスQH-16
 *   （shotCount別のTTFT×品質比較、計画書§7）をアプリ本体のコードを分岐させずに実測できるよう
 *   注入可能にした。**[maxNumTokens]の既定値は本パラメータに追従する**（下記参照）ため、
 *   `shotCount`のみを変えて`maxNumTokens`を省略しても、その`shotCount`向けに算出された
 *   コンテキスト予算がそのまま使われ、既定値のまま強い設定と弱い設定が食い違って
 *   `FAILED_PRECONDITION`を再発することはない。
 * @param maxNumTokens コンテキスト長（V-8。§11.2）。**P7-C5b（ADR-0057）でP7-C1の固定値256から
 *   変更した**——P7-C5実機実測（ADR-0056）が、本番プロンプト一式（`systemInstruction`＋
 *   既定2-shot few-shot＋data message）では256では実機`FAILED_PRECONDITION`により推論が
 *   一度も成功しないことを発見したため（256のままでは§8.6 #7のOOM事前ガード修正〔ADR-0057〕
 *   と無関係に、推論そのものが機能しない状態だった）。既定値は[shotCount]に応じて
 *   [PlanPromptBuilder.estimateMaxNumTokens]（実際に組み立てるprefaceの文字数＋出力上限から
 *   算出、P7-C5実機実測で成功確認済みの1024を下限にclamp）を呼んだ結果を使う。本番運用値の
 *   絶対的な最終確定は引き続きP7-C8実機プローブの責務（計画書§17 V-8・§11.3、Galaxy A実機での
 *   再検証）だが、「実機で推論が一度も成功しない既定値のまま放置する」ことは基盤バグであり
 *   P7-C8を待たずにP7-C5bで是正した（ADR-0057「代替案と却下理由」参照）。
 * @param threadCount `Backend.CPU(threadCount = ...)`（V-3実測で存在確認済み）。既定4は
 *   P7-C0実測条件と同一値。
 */
@OptIn(ExperimentalApi::class)
class LiteRtLmLocalLanguageModel(
    private val shotCount: Int = PlanPromptBuilder.DEFAULT_SHOT_COUNT,
    private val maxNumTokens: Int = defaultMaxNumTokensFor(shotCount),
    private val threadCount: Int = DEFAULT_THREAD_COUNT
) : LocalLanguageModel, BenchmarkMetricsSource, EngineLoadStateSource {

    override val modelIdentifier: String = "litert-lm:qwen3-0.6b-dynamic-int4-block32"

    private val promptBuilder = PlanPromptBuilder()

    /** Phase 9（計画書§3.2）。[generateRecovery]専用の[RecoveryPromptBuilder]（[promptBuilder]のRecovery版）。 */
    private val recoveryPromptBuilder = RecoveryPromptBuilder()

    /** [engineLifecycleMutex]配下でのみ読み書きする遅延生成Engine（R-7）。 */
    private var engine: Engine? = null

    /**
     * Phase 8.5新設（計画書§3設計6、ADR-0062）。[engine]が現在ロード済みのモデルパス。
     * **書き込みは[engineLifecycleMutex]配下でのみ行う**（[engine]と同じ規律。単一書き込み元を
     * [obtainEngine]／[closeEngineAndClearLocked]の2箇所に限定する）。[engine]が`null`のときは
     * 必ず`null`（[closeEngineAndClearLocked]が両方を同時にクリアする）。
     *
     * **`@Volatile`化（Phase 9.5、計画書§3.10 F-5）**: [loadedModelPath]（override関数、
     * [EngineLoadStateSource]実装）が[LocalAiGateway]／[com.actionstarter.ai.model.
     * ModelSelectorImpl]から`engineLifecycleMutex`を取得せずに読まれるようになった
     * （読み取り専用の別コルーチン／別スレッドから、書き込み側のMutexをまたいで参照される）。
     * Kotlin/JVMのMutexはスレッド非依存でコルーチンを跨ぐため、`@Volatile`なしでは書き込みが
     * 他スレッドから見えるとは限らない（メモリ可視性の保証がない）。書き込み側の直列化
     * （[engineLifecycleMutex]による単一書き込み元）自体は変更せず、[lastMetrics]と同型の
     * 「単一書き込み元＋`@Volatile`読み取り」パターンを踏襲することで、読み取り側にロック
     * 取得（デッドロック・再入不可の`Mutex`の性質に起因するリスク）を要求せずに可視性のみを
     * 保証する。
     */
    @Volatile
    private var loadedModelPath: String? = null

    /** Engineの生成・破棄（アンロード）を直列化する（§13 #8）。 */
    private val engineLifecycleMutex = Mutex()

    @Volatile
    private var lastMetrics: InferenceBenchmarkSnapshot? = null

    override fun lastInferenceMetrics(): InferenceBenchmarkSnapshot? = lastMetrics

    /**
     * Phase 9.5新設（計画書§3.10 F-5、[EngineLoadStateSource]実装、Green実装済み）。
     * [LocalAiGateway]のOOM事前ガード（[LocalAiGateway.isEntryAlreadyLoaded]）・
     * [com.actionstarter.ai.model.ModelSelectorImpl.select]の双方が、ロード済みモデルパスに
     * 対してavailMemガードをスキップするかどうかの判定に使う。
     *
     * **実装は[loadedModelPath]（`private var`、`@Volatile`）フィールドの単純な委譲**
     * （プロパティ`loadedModelPath`への裸のアクセスであり、この関数自身への再帰呼び出しでは
     * ない——Kotlinはプロパティ名とインターフェースが要求する関数名`loadedModelPath()`の字面が
     * 一致していても、呼び出し構文`()`の有無で両者を区別する）。書き込み元は
     * [engineLifecycleMutex]配下の[obtainEngine]／[closeEngineAndClearLocked]の2箇所に限定した
     * ままであり、本関数はロックを取得せずに読む（[loadedModelPath]フィールドのKDoc
     * 「`@Volatile`化」参照——可視性は`@Volatile`が保証するため、読み取り側でのロック取得は
     * 不要かつ意図的に行わない）。
     *
     * **本ファイルはJVMテストで一切検証できない**（class file version不一致、
     * `AiGatewayTestFixtures`のKDoc参照）ため、本メソッドの正しさは実機androidTestでのみ確認可能。
     */
    override fun loadedModelPath(): String? = loadedModelPath

    override suspend fun generatePlan(context: PlanningContext, modelPath: String, samplingPolicy: SamplingPolicy): String =
        withContext(Dispatchers.IO) {
            val (activeEngine, modelLoadMs) = obtainEngine(modelPath)

            var conversation: Conversation? = null
            try {
                conversation = activeEngine.createConversation(buildConversationConfig(context, samplingPolicy))
                val dataMessage = buildDataMessage(context, samplingPolicy)

                val ramSampler = NativeHeapPeakSampler().apply { start() }
                val response = try {
                    conversation.sendMessage(
                        dataMessage,
                        maxOutputToken = MAX_OUTPUT_TOKEN,
                        thinkingConfig = DISABLED_THINKING_CONFIG,
                        responseFormat = ResponseFormat.json(PlanJsonSchema.TEXT)
                    )
                } finally {
                    ramSampler.stop()
                }

                val benchmarkInfo = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
                lastMetrics = InferenceBenchmarkSnapshot(
                    modelLoadMs = modelLoadMs,
                    firstTokenMs = ((benchmarkInfo?.timeToFirstTokenInSecond ?: 0.0) * MILLIS_PER_SECOND).toLong(),
                    outputTokens = benchmarkInfo?.lastDecodeTokenCount ?: 0,
                    tokensPerSecond = benchmarkInfo?.lastDecodeTokensPerSecond ?: 0.0,
                    peakNativeHeapBytes = ramSampler.peakBytes()
                )

                extractText(response)
            } catch (t: OutOfMemoryError) {
                // §13 #8二次防御: 事前ガード（Gateway側§8.6 #7）をすり抜けた残余ケース。
                // 内部状態（Engine）を破棄し、次回呼び出しで再生成させる（アンロード）。
                unloadEngine()
                throw t
            } finally {
                try {
                    conversation?.close()
                } catch (t: Throwable) {
                    // close()は非冪等（V-2実測）。後続呼び出しは新規Conversationを生成するため
                    // ここでの失敗は次回呼び出しに影響しない。呼び出し元の結果を壊さないよう
                    // 握り潰す（Engine自体はunloadしていないため利用可能なまま）。
                }
            }
        }

    /**
     * Phase 9実装（計画書§3.2・§18申し送り5）。[generatePlan]と同型の構造
     * （[obtainEngine]→[Conversation]生成→[Conversation.sendMessage]→`finally`で必ず`close()`）を
     * 踏襲する。**[RecoveryContext]は`locale`を持たないため（計画書§5「非変更」との整合、
     * `RecoveryPromptBuilder`のクラスKDoc「実装調査で判明した設計ギャップ」参照）、
     * `Locale.getDefault()`を直接使う**。
     */
    override suspend fun generateRecovery(
        context: RecoveryContext,
        options: List<RecoveryOption>,
        modelPath: String,
        samplingPolicy: SamplingPolicy
    ): String = withContext(Dispatchers.IO) {
        val (activeEngine, modelLoadMs) = obtainEngine(modelPath)
        val locale = Locale.getDefault()

        var conversation: Conversation? = null
        try {
            conversation = activeEngine.createConversation(buildRecoveryConversationConfig(locale, samplingPolicy))
            val dataMessage = buildRecoveryDataMessage(context, options, samplingPolicy)

            val ramSampler = NativeHeapPeakSampler().apply { start() }
            val response = try {
                conversation.sendMessage(
                    dataMessage,
                    maxOutputToken = MAX_OUTPUT_TOKEN,
                    thinkingConfig = DISABLED_THINKING_CONFIG,
                    responseFormat = ResponseFormat.json(RecoveryJsonSchema.TEXT)
                )
            } finally {
                ramSampler.stop()
            }

            val benchmarkInfo = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
            lastMetrics = InferenceBenchmarkSnapshot(
                modelLoadMs = modelLoadMs,
                firstTokenMs = ((benchmarkInfo?.timeToFirstTokenInSecond ?: 0.0) * MILLIS_PER_SECOND).toLong(),
                outputTokens = benchmarkInfo?.lastDecodeTokenCount ?: 0,
                tokensPerSecond = benchmarkInfo?.lastDecodeTokensPerSecond ?: 0.0,
                peakNativeHeapBytes = ramSampler.peakBytes()
            )

            extractText(response)
        } catch (t: OutOfMemoryError) {
            // §13 #8二次防御（generatePlanと同型）。
            unloadEngine()
            throw t
        } finally {
            try {
                conversation?.close()
            } catch (t: Throwable) {
                // close()は非冪等（V-2実測、generatePlanと同型）。
            }
        }
    }

    /**
     * [engine]が未生成、または[requiresEngineReload]が再ロード要と判定した場合に[requestedPath]・
     * [maxNumTokens]・[threadCount]から新規生成し`initialize()`する（R-7、遅延初期化）。
     * 要求パスが[loadedModelPath]と同一なら既存Engineをそのまま再利用し、ロード時間は`0`を
     * 返す（[InferenceBenchmarkSnapshot.modelLoadMs]のKDoc「再利用した場合は0」参照）。
     *
     * **Phase 8.5 Step 4（Green）で実装（計画書§3設計6、ADR-0062、アーキテクトレビューPass 1
     * CRITICAL対応）**: 要求パスが変化した場合は[closeEngineAndClearLocked]で既存Engineを
     * 破棄してから新パスで再生成する。`EngineLoadPolicy.requiresEngineReload`はT-P85-25〜27で
     * 検証済みの判定ロジックをそのまま再利用する。
     *
     * @param requestedPath ロード対象モデルの絶対パス（[LocalAiGateway]が検証済みの値を
     *   [generatePlan]経由でそのまま渡す）。
     */
    private suspend fun obtainEngine(requestedPath: String): Pair<Engine, Long> = engineLifecycleMutex.withLock {
        val existing = engine
        if (existing != null && !requiresEngineReload(loadedModelPath, requestedPath)) {
            return@withLock existing to 0L
        }
        if (existing != null) {
            // 要求パスが変化した（モデル切替）。既存Engineを破棄してから新パスで再生成する
            // （T-GW-16／計画書§3設計6・§7「モデル切替直後の初回推論」）。
            closeEngineAndClearLocked()
        }

        ExperimentalFlags.enableBenchmark = true

        val engineConfig = EngineConfig(
            modelPath = requestedPath,
            backend = Backend.CPU(threadCount = threadCount),
            maxNumTokens = maxNumTokens
        )
        val loadStartMs = SystemClock.elapsedRealtime()
        val created = Engine(engineConfig)
        created.initialize()
        val modelLoadMs = SystemClock.elapsedRealtime() - loadStartMs
        engine = created
        loadedModelPath = requestedPath
        created to modelLoadMs
    }

    /** OOM二次防御（§13 #8）。[engine]を破棄し、次回[obtainEngine]で再生成させる。 */
    private suspend fun unloadEngine() = engineLifecycleMutex.withLock {
        closeEngineAndClearLocked()
    }

    /**
     * Phase 8.5新設（計画書§3設計6、ADR-0062）。[engine]の破棄と[loadedModelPath]のクリアを行う
     * 共有ロジック。**[engineLifecycleMutex]を保持中の呼び出し元専用**——`Mutex`は再入不可のため、
     * [obtainEngine]（既にロック取得済み）から直接呼び、[unloadEngine]（ロックを別途取得する
     * 既存の公開エントリ）からはロック取得後に呼ぶ。呼び出し元を問わずこの2箇所からのみ呼ぶこと。
     */
    private fun closeEngineAndClearLocked() {
        try {
            engine?.close()
        } catch (t: Throwable) {
            // close()は非冪等（V-2）。アンロード目的の破棄では失敗しても内部参照だけ確実にnull化する。
        } finally {
            engine = null
            loadedModelPath = null
        }
    }

    /**
     * `systemInstruction`＋`initialMessages`（few-shot）＋[samplingPolicy]から導いた
     * `samplerConfig`を持つ[ConversationConfig]を組み立てる（品質ハーネス§3・§4・§10）。
     * `prefillPrefaceOnInit = true`でこのprefaceを会話生成時にprefillする（品質ハーネス§3末尾）。
     *
     * **Phase 9.5 F-1実配線（計画書§3.2、Step 4 Green）**: [PlanPromptBuilder.buildFewShot]の
     * `eventTitle`引数へ`context.event.title`（生タイトル）を渡す。[PlanPromptBuilder.build]の
     * `title`（[com.actionstarter.ai.prompt.PlanPromptBuilder]内部の`truncateForPrompt`で
     * 切り詰め）とは異なり、こちらはLLMへ埋め込まれず`EventCategoryClassifier.classify`の
     * キーワード照合にのみ使われるKotlin側の判定材料であるため、切り詰めは行わない。
     */
    private fun buildConversationConfig(context: PlanningContext, samplingPolicy: SamplingPolicy): ConversationConfig {
        val fewShotMessages = promptBuilder.buildFewShot(context.locale, shotCount, eventTitle = context.event.title).flatMap { example ->
            listOf(Message.user(example.userTurn), Message.model(example.modelTurn))
        }
        return ConversationConfig(
            enableResponseFormat = true,
            systemInstruction = Contents.of(promptBuilder.buildSystemInstruction(context.locale)),
            initialMessages = fewShotMessages,
            samplerConfig = toSamplerConfig(samplingPolicy),
            thinkingConfig = DISABLED_THINKING_CONFIG,
            maxOutputToken = MAX_OUTPUT_TOKEN,
            prefillPrefaceOnInit = true
        )
    }

    /**
     * [PlanPromptBuilder.build]（data message）に、[SamplingPolicy.appendConcisenessConstraint]が
     * `true`のときのみ固定簡潔化制約文を追記する（品質ハーネス§6「静的制約の追加（2回目のみ）」・
     * [SamplingPolicy]のKDoc）。
     */
    private fun buildDataMessage(context: PlanningContext, samplingPolicy: SamplingPolicy): String {
        val dataMessage = promptBuilder.build(context)
        return if (samplingPolicy.appendConcisenessConstraint) {
            "$dataMessage\n$CONCISENESS_CONSTRAINT_SUFFIX"
        } else {
            dataMessage
        }
    }

    /** [buildConversationConfig]のRecovery版（計画書§3.2）。[locale]は呼び出し元が明示的に渡す。 */
    private fun buildRecoveryConversationConfig(locale: Locale, samplingPolicy: SamplingPolicy): ConversationConfig {
        val fewShotMessages = recoveryPromptBuilder.buildFewShot(locale, shotCount).flatMap { example ->
            listOf(Message.user(example.userTurn), Message.model(example.modelTurn))
        }
        return ConversationConfig(
            enableResponseFormat = true,
            systemInstruction = Contents.of(recoveryPromptBuilder.buildSystemInstruction(locale)),
            initialMessages = fewShotMessages,
            samplerConfig = toSamplerConfig(samplingPolicy),
            thinkingConfig = DISABLED_THINKING_CONFIG,
            maxOutputToken = MAX_OUTPUT_TOKEN,
            prefillPrefaceOnInit = true
        )
    }

    /** [buildDataMessage]のRecovery版（計画書§3.2）。 */
    private fun buildRecoveryDataMessage(
        context: RecoveryContext,
        options: List<RecoveryOption>,
        samplingPolicy: SamplingPolicy
    ): String {
        val dataMessage = recoveryPromptBuilder.build(context, options)
        return if (samplingPolicy.appendConcisenessConstraint) {
            "$dataMessage\n$CONCISENESS_CONSTRAINT_SUFFIX"
        } else {
            dataMessage
        }
    }

    /**
     * [SamplingPolicy.topK]／[SamplingPolicy.temperature]をそのまま転記し、[SamplingPolicy]自体が
     * 持たない`topP`／`seed`（品質ハーネス§4の値、ADR-0056で確定）を付与する。`when`は
     * [SamplingPolicy]の2値（[SamplingPolicy.Primary]／[SamplingPolicy.Retry]）に対して
     * 網羅的であり、将来値が増えた場合はコンパイルエラーで気づける。
     */
    private fun toSamplerConfig(samplingPolicy: SamplingPolicy): SamplerConfig = when (samplingPolicy) {
        SamplingPolicy.Primary -> SamplerConfig(
            topK = samplingPolicy.topK,
            topP = PRIMARY_TOP_P,
            temperature = samplingPolicy.temperature,
            seed = PRIMARY_SEED
        )
        SamplingPolicy.Retry -> SamplerConfig(
            topK = samplingPolicy.topK,
            topP = RETRY_TOP_P,
            temperature = samplingPolicy.temperature,
            seed = RETRY_SEED
        )
    }

    /** `LiteRtLmProbeTest.extractText`と同一方式。Textコンテンツのみを連結する。 */
    private fun extractText(message: Message): String =
        message.contents.contents.joinToString(separator = "") { content ->
            (content as? Content.Text)?.text.orEmpty()
        }

    /**
     * 推論中のネイティブヒープをバックグラウンドスレッドで定期サンプリングしピークを保持する
     * （§11.1、P7-C0 `LiteRtLmProbeTest.RamSampler`と同一方式をproduction向けに簡略化）。
     */
    private class NativeHeapPeakSampler {
        private val running = AtomicBoolean(false)
        private val peak = AtomicLong(0)
        private var thread: Thread? = null

        fun start() {
            running.set(true)
            thread = Thread {
                while (running.get()) {
                    val current = Debug.getNativeHeapAllocatedSize()
                    if (current > peak.get()) peak.set(current)
                    try {
                        Thread.sleep(SAMPLING_INTERVAL_MILLIS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running.set(false)
            thread?.join(SAMPLER_JOIN_TIMEOUT_MILLIS)
        }

        fun peakBytes(): Long = peak.get()

        private companion object {
            const val SAMPLING_INTERVAL_MILLIS = 200L
            const val SAMPLER_JOIN_TIMEOUT_MILLIS = 2_000L
        }
    }

    companion object {
        /**
         * 出力トークン上限（ADR-0056）。確定契約（ADR-0045・0046）でstepあたり
         * `action_type`／`display_text`の2フィールドのみに削減されたため、P7-C0実測
         * （6フィールド・1step固定で56 decodeトークン）より小さい単価になる見込みだが、
         * 安全側に維持しつつmaxItems=8まで許容する値として200を既定とする。
         */
        private const val MAX_OUTPUT_TOKEN = 200

        /**
         * [shotCount]から`maxNumTokens`の推奨既定値を算出する（ADR-0057）。
         * [PlanPromptBuilder.estimateMaxNumTokens]（実際に組み立てるpreface＋[MAX_OUTPUT_TOKEN]
         * から算出、P7-C5実機実測で成功確認済みの1024を下限にclamp）へ委譲する薄いラッパー。
         * 主コンストラクタの`maxNumTokens`パラメータの既定値式と[DEFAULT_MAX_NUM_TOKENS]の
         * 両方から呼ばれ、**「`shotCount`だけ変えてfew-shotを増減させたのに`maxNumTokens`は
         * 既定のまま」という食い違いを構造的に防ぐ**（[LiteRtLmLocalLanguageModel]クラスKDoc
         * 「`@param shotCount`」参照）。
         */
        private fun defaultMaxNumTokensFor(shotCount: Int): Int =
            PlanPromptBuilder().estimateMaxNumTokens(shotCount = shotCount, maxOutputToken = MAX_OUTPUT_TOKEN)

        /**
         * §11.2コンテキスト長プロファイルの既定値（[PlanPromptBuilder.DEFAULT_SHOT_COUNT]向け、
         * ADR-0057）。**P7-C1が定めた固定256からP7-C5b（本ADR）で変更した**。`const val`に
         * できないのは関数呼び出しの結果でありコンパイル時定数式ではないため（`256`の頃と異なる
         * 制約）。外部（テスト・プローブ）から既定値を参照する用途に残す
         * （[LiteRtLmLocalLanguageModel]クラスKDoc「`@param maxNumTokens`」参照）。
         */
        val DEFAULT_MAX_NUM_TOKENS: Int = defaultMaxNumTokensFor(PlanPromptBuilder.DEFAULT_SHOT_COUNT)

        /** P7-C0実測条件（`Backend.CPU(threadCount = 4)`）と同一値。 */
        const val DEFAULT_THREAD_COUNT: Int = 4

        private const val MILLIS_PER_SECOND = 1000.0

        /** 品質ハーネス§4「1回目（既定）」の`SamplerConfig(topK=1, topP=1.0, temperature=0.0, seed=0)`。 */
        private const val PRIMARY_TOP_P = 1.0
        private const val PRIMARY_SEED = 0

        /** 品質ハーネス§4「2回目（再試行）」。1回目と異なるseedにする（S-2是正の「条件を変える」の一部）。 */
        private const val RETRY_TOP_P = 0.95
        private const val RETRY_SEED = 1

        private val DISABLED_THINKING_CONFIG = ThinkingConfig(enableThinking = false)

        /**
         * 品質ハーネス§6・[SamplingPolicy]のKDocが指定する固定簡潔化制約文（逐語一致）。
         * 「1回目の失敗を参照させる動的な是正指示」ではなく、静的な文言のみを追加する
         * （Gemini G1 CRITICAL #1）。
         */
        private const val CONCISENESS_CONSTRAINT_SUFFIX =
            "Be extra concise. Do not include any time, date, number, address, or duplicate " +
                "action_type. Output valid JSON only, following the schema exactly."
    }
}
