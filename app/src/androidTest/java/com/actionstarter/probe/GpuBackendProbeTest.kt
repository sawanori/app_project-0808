package com.actionstarter.probe

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel
import com.actionstarter.ai.model.ModelCatalog
import com.actionstarter.ai.model.ModelStorageImpl
import com.actionstarter.ai.prompt.PlanPromptBuilder
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.TransportMode
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
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * probe専用・正式テストではない（既存probe群——[LiteRtLmProbeTest]／[ModelComparisonProbeTest]
 * ／[PerformanceBaselineProbeTest]——と同型: `@Ignore`既定・Log出力・目的と測定対象をKDocに明記）。
 *
 * **Phase 9.5計画書（`docs/plans/phase9.5-performance-quality.md`§3.5）PR-1: GPUバックエンド
 * 可否プローブ**。事前調査（実装時、litertlm-android 0.15.0のAAR実体をjavapで確認）で
 * `com.google.ai.edge.litertlm.Backend.GPU`（引数なしコンストラクタ、`EngineConfig.backend`
 * パラメータへそのまま渡せる）の存在を確認済みのため、API公開の有無を問う段階は完了しており、
 * 本ファイルは「Mali-G68（A54搭載GPU）上で実際にEngineの初期化・推論が成功するか」という
 * 実機Go/No-Go判定そのものを行う。
 *
 * **CPU/GPU同一プロンプトでの直接比較**: [Backend.CPU]・[Backend.GPU]それぞれでEngineを構築し
 * （プロセス内で順に生成・破棄するため[LiteRtLmLocalLanguageModel]のR-7シングルトン制約とは
 * 無関係——本プローブは低レベルAPIを直接叩く使い捨てEngine）、**完全に同一の`EngineConfig`
 * （`modelPath`・`maxNumTokens`）・同一の`ConversationConfig`（`systemInstruction`・
 * `initialMessages`・`samplerConfig`）・同一のdata messageで`sendMessage`する**。preface・
 * data messageは本番と同じ[PlanPromptBuilder]（M実測・[PerformanceBaselineProbeTest]が使う
 * ものと同一クラス）で組み立てるため、Mベースライン実測（計画書§14、CPU実測値）とTTFT・
 * decode tok/sを直接比較できる。
 *
 * **GPU初期化・推論の失敗は想定内の結果（成否そのものがGo/No-Go判定の対象）**: モバイルGPU
 * delegateは特定の量子化方式・演算子に対応しないことが珍しくない（INT4 block-32量子化への
 * Mali-G68 GPU delegate対応は本プローブ実行まで未確認）。Engine初期化・`sendMessage`の両方を
 * 個別にtry-catchし、例外発生時は`GPU_RESULT=NO_GO`として例外種別・メッセージをログに残す
 * （プローブ自体をクラッシュさせない。§3.5「API自体が非公開ならNo-Go記録」と対になる
 * 「APIは公開されているが実機で機能しない」場合のNo-Go記録）。
 *
 * 実行方法（`-e class`で個別指定、`PerformanceBaselineProbeTest`と同じ理由——
 * `connectedDebugAndroidTest`一括実行の対象に含めない）:
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.probe.GpuBackendProbeTest#probeGpuVsCpuBackend`
 *
 * 結果はLogcat（TAG=P95_GPU_PROBE）へ出力する。カレンダー実データ・個人情報はプロンプトに
 * 一切含めない（[PlanPromptBuilder]は合成イベントに対してのみ動作させる）。
 *
 * 前提: 実際に導入済みの本番`ModelStorage`（`ModelCatalog.ALL`、`no_backup/models/`配下に
 * 既に導入済みのモデルをそのまま使う。[PerformanceBaselineProbeTest]と同じ前提、probe専用の
 * push/copyは行わない）。
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore(
    "probe専用（Phase 9.5 PR-1、docs/plans/phase9.5-performance-quality.md §3.5）。" +
        "実行に数十秒〜数分を要し実機依存（Mali-G68 GPU delegateの対応状況は未確認）のため、" +
        "connectedDebugAndroidTest一括実行の対象に含めない。再実行する場合は" +
        "`PerformanceBaselineProbeTest`のKDoc「実行方法」と同じ罠——discoveryの時点で" +
        "@Ignoreクラスごと除外されるため、再実行時は本クラスの@Ignoreを一時的に" +
        "コメントアウトするか`-e class`で直接指定する。"
)
class GpuBackendProbeTest {

    @Test
    fun probeGpuVsCpuBackend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ExperimentalFlags.enableBenchmark = true
        logLine("===== P95 PR-1 GPU backend probe start =====")

        val storage = ModelStorageImpl(context, catalog = ModelCatalog.ALL)
        val installedEntry = storage.installedEntry()
        if (installedEntry == null) {
            logLine("PRECONDITION_FAILED installedEntry=null（本番ModelStorageにモデルが導入されていません）")
            logLine("GPU_RESULT=NO_GO reason=PRECONDITION_FAILED")
            return
        }
        val modelPath = storage.finalFile(installedEntry).absolutePath
        logLine("PRECONDITION_OK selectedModelId=${installedEntry.id} modelPath=$modelPath")

        val planningContext = samplePlanningContext()
        val promptBuilder = PlanPromptBuilder()
        val systemInstruction = promptBuilder.buildSystemInstruction(planningContext.locale)
        val fewShotMessages = promptBuilder.buildFewShot(planningContext.locale).flatMap { example ->
            listOf(Message.user(example.userTurn), Message.model(example.modelTurn))
        }
        val dataMessage = promptBuilder.build(planningContext)
        val maxNumTokens = LiteRtLmLocalLanguageModel.DEFAULT_MAX_NUM_TOKENS
        logLine(
            "PROMPT_PREPARED maxNumTokens=$maxNumTokens systemInstructionChars=${systemInstruction.length} " +
                "fewShotMessageCount=${fewShotMessages.size} dataMessageChars=${dataMessage.length}"
        )

        val cpuResult = runBackendTrial(
            context = context,
            label = "cpu",
            backend = Backend.CPU(threadCount = LiteRtLmLocalLanguageModel.DEFAULT_THREAD_COUNT),
            modelPath = modelPath,
            maxNumTokens = maxNumTokens,
            systemInstruction = systemInstruction,
            fewShotMessages = fewShotMessages,
            dataMessage = dataMessage
        )
        logResult(cpuResult)

        val gpuResult = runBackendTrial(
            context = context,
            label = "gpu",
            backend = Backend.GPU(),
            modelPath = modelPath,
            maxNumTokens = maxNumTokens,
            systemInstruction = systemInstruction,
            fewShotMessages = fewShotMessages,
            dataMessage = dataMessage
        )
        logResult(gpuResult)

        when {
            gpuResult.error != null ->
                logLine(
                    "GPU_RESULT=NO_GO reason=INIT_OR_INFERENCE_FAILED error=${gpuResult.error} " +
                        "（Mali-G68 GPU delegateが本モデルの量子化方式・演算子に対応していない可能性）"
                )
            !gpuResult.schemaValid ->
                logLine(
                    "GPU_RESULT=NO_GO reason=SCHEMA_INVALID " +
                        "（Engine初期化・推論は成功したがresponseFormat制約付き出力が不正）"
                )
            else -> {
                val ttftDeltaMs = ((gpuResult.firstTokenS ?: 0.0) - (cpuResult.firstTokenS ?: 0.0)) * MILLIS_PER_SECOND
                logLine(
                    "GPU_RESULT=GO cpuFirstTokenS=${cpuResult.firstTokenS} gpuFirstTokenS=${gpuResult.firstTokenS} " +
                        "ttftDeltaMs=$ttftDeltaMs cpuDecodeTokPerS=${cpuResult.decodeTokPerS} " +
                        "gpuDecodeTokPerS=${gpuResult.decodeTokPerS}（正値=GPUが速い）"
                )
            }
        }
        logLine("===== P95 PR-1 GPU backend probe end =====")
    }

    /** [backend]違いのみで[modelPath]・preface・data messageを完全に同一にした1回分の実測。 */
    private fun runBackendTrial(
        context: Context,
        label: String,
        backend: Backend,
        modelPath: String,
        maxNumTokens: Int,
        systemInstruction: String,
        fewShotMessages: List<Message>,
        dataMessage: String
    ): BackendTrialResult {
        logLine("--- run[$label] start ---")
        var engine: Engine? = null
        var conversation: Conversation? = null
        try {
            val engineConfig = EngineConfig(modelPath = modelPath, backend = backend, maxNumTokens = maxNumTokens)
            val loadStartMs = SystemClock.elapsedRealtime()
            val localEngine = try {
                Engine(engineConfig).apply { initialize() }
            } catch (t: Throwable) {
                logLine("run[$label] ENGINE_INIT_FAILED ${t.javaClass.simpleName}: ${t.message}")
                return BackendTrialResult.failure(label, "${t.javaClass.simpleName}: ${t.message}")
            }
            engine = localEngine
            val modelLoadMs = SystemClock.elapsedRealtime() - loadStartMs
            val afterInitPssKb = sampleTotalPssKb(context)
            logLine("run[$label] LOAD_DONE modelLoadMs=$modelLoadMs afterInitTotalPssKb=$afterInitPssKb")

            val conversationConfig = ConversationConfig(
                enableResponseFormat = true,
                systemInstruction = Contents.of(systemInstruction),
                initialMessages = fewShotMessages,
                samplerConfig = SamplerConfig(topK = PRIMARY_TOP_K, topP = PRIMARY_TOP_P, temperature = PRIMARY_TEMPERATURE, seed = PRIMARY_SEED),
                thinkingConfig = ThinkingConfig(enableThinking = false),
                maxOutputToken = MAX_OUTPUT_TOKEN,
                prefillPrefaceOnInit = true
            )
            val localConversation = localEngine.createConversation(conversationConfig)
            conversation = localConversation

            val sampler = PssPeakSampler(context)
            sampler.start()
            val sendStartMs = SystemClock.elapsedRealtime()
            val response = try {
                localConversation.sendMessage(
                    dataMessage,
                    maxOutputToken = MAX_OUTPUT_TOKEN,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    responseFormat = ResponseFormat.json(com.actionstarter.ai.schema.PlanJsonSchema.TEXT)
                )
            } catch (t: Throwable) {
                logLine("run[$label] SEND_MESSAGE_FAILED ${t.javaClass.simpleName}: ${t.message}")
                return BackendTrialResult.failure(label, "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                sampler.stop()
            }
            val sendMessageWallMs = SystemClock.elapsedRealtime() - sendStartMs

            val benchmarkInfo = runCatching { localConversation.getBenchmarkInfo() }.getOrNull()
            val rawText = extractText(response)
            val schemaValid = runCatching {
                com.actionstarter.ai.schema.SchemaValidator().validate(rawText) is
                    com.actionstarter.ai.schema.SchemaValidationResult.Valid
            }.getOrDefault(false)

            logLine(
                "run[$label] GENERATION_DONE wallMs=$sendMessageWallMs " +
                    "firstTokenS=${benchmarkInfo?.timeToFirstTokenInSecond} " +
                    "decodeTokPerS=${benchmarkInfo?.lastDecodeTokensPerSecond} " +
                    "decodeTokenCount=${benchmarkInfo?.lastDecodeTokenCount} " +
                    "peakTotalPssKb=${sampler.peakKb()} schemaValid=$schemaValid"
            )
            logLine("run[$label] RAW_RESPONSE_TEXT=$rawText")

            return BackendTrialResult(
                label = label,
                modelLoadMs = modelLoadMs,
                wallMs = sendMessageWallMs,
                firstTokenS = benchmarkInfo?.timeToFirstTokenInSecond,
                decodeTokPerS = benchmarkInfo?.lastDecodeTokensPerSecond,
                peakTotalPssKb = sampler.peakKb(),
                schemaValid = schemaValid,
                error = null
            )
        } finally {
            try {
                conversation?.close()
            } catch (t: Throwable) {
                // close()は非冪等（V-2実測、LiteRtLmProbeTestと同型）。
            }
            try {
                engine?.close()
            } catch (t: Throwable) {
                // 同上。
            }
        }
    }

    private fun logResult(r: BackendTrialResult) {
        logLine(
            "RESULT_SUMMARY label=${r.label} error=${r.error} modelLoadMs=${r.modelLoadMs} " +
                "wallMs=${r.wallMs} firstTokenS=${r.firstTokenS} decodeTokPerS=${r.decodeTokPerS} " +
                "peakTotalPssKb=${r.peakTotalPssKb} schemaValid=${r.schemaValid}"
        )
    }

    private fun extractText(message: Message): String =
        message.contents.contents.joinToString(separator = "") { content ->
            (content as? Content.Text)?.text.orEmpty()
        }

    private fun sampleTotalPssKb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val infos = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
        return infos.firstOrNull()?.totalPss?.toLong() ?: -1L
    }

    private fun samplePlanningContext(): PlanningContext {
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "定例ミーティング",
            notes = null,
            startDate = Instant.parse("2026-08-12T10:00:00Z"),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "p95-gpu-probe", displayName = "P95 GPU Probe Calendar")
        )
        return PlanningContext(
            event = event,
            now = Instant.parse("2026-08-12T08:00:00Z"),
            zoneId = ZoneId.of("Asia/Tokyo"),
            locale = Locale.JAPAN,
            transportMode = TransportMode.WALKING,
            travelEstimate = null,
            arrivalBuffer = Duration.ofMinutes(10),
            profile = null
        )
    }

    private fun logLine(message: String) {
        Log.e(TAG, message)
    }

    /** プロセス全体のPSS（KB単位）をバックグラウンドスレッドで定期サンプリングしピークを保持する
     * （[PerformanceBaselineProbeTest.PssPeakSampler]と同一方式の縮小版）。 */
    private class PssPeakSampler(private val context: Context) {
        private val activityManager =
            context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        private val pid = Process.myPid()
        private val running = AtomicBoolean(false)
        private val peakKb = AtomicLong(0)
        private var thread: Thread? = null

        fun start() {
            running.set(true)
            thread = Thread {
                while (running.get()) {
                    val totalPssKb = runCatching {
                        activityManager.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()?.totalPss ?: 0
                    }.getOrDefault(0)
                    if (totalPssKb > peakKb.get()) peakKb.set(totalPssKb.toLong())
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

        fun peakKb(): Long = peakKb.get()

        private companion object {
            const val SAMPLING_INTERVAL_MILLIS = 200L
            const val SAMPLER_JOIN_TIMEOUT_MILLIS = 2_000L
        }
    }

    private data class BackendTrialResult(
        val label: String,
        val modelLoadMs: Long = -1,
        val wallMs: Long = -1,
        val firstTokenS: Double? = null,
        val decodeTokPerS: Double? = null,
        val peakTotalPssKb: Long = -1,
        val schemaValid: Boolean = false,
        val error: String?
    ) {
        companion object {
            fun failure(label: String, error: String) = BackendTrialResult(label = label, error = error)
        }
    }

    private companion object {
        const val TAG = "P95_GPU_PROBE"
        const val MAX_OUTPUT_TOKEN = 200
        const val MILLIS_PER_SECOND = 1000.0

        // 品質ハーネス§4「1回目（既定）」のSamplerConfigと同値（LiteRtLmLocalLanguageModel.
        // toSamplerConfigのPrimary分岐・ADR-0056と同型、CPU/GPU比較の条件を完全に揃えるため
        // SamplingPolicy.Primaryの値をそのまま複製する）。
        const val PRIMARY_TOP_K = 1
        const val PRIMARY_TOP_P = 1.0
        const val PRIMARY_TEMPERATURE = 0.0
        const val PRIMARY_SEED = 0
    }
}
