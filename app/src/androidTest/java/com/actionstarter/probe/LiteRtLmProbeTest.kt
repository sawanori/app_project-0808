package com.actionstarter.probe

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * probe専用・正式テストではない。
 *
 * Phase 7計画書（docs/plans/phase7-local-llm-foundation.md）§14 P7-C0の
 * Go/No-Go判定スパイク。LiteRT-LM 0.15.0のKotlin `ResponseFormat.json(schema)`による
 * constrained decodingがエミュレータ実機で実際に機能するかを実測する使い捨てクラス
 * （既存 `AlarmExactAlarmProbeTest` と同型: probe専用KDoc・Log出力。計測完了後に
 * `@Ignore`を付与し通常の`connectedDebugAndroidTest`一括実行対象から外す）。
 *
 * 測定対象（計画書§11.1・§14 P7-C0・§17 V-1〜V-8）:
 * - ④ `ResponseFormat.json(schema)` が小コンテキスト・テストプロファイル
 *   （`maxNumTokens` 128/256、§11.2）でスキーマ準拠JSONを返すか（本Phase最大の論点）
 * - V-1: 実機ABI/minSdkの自プロジェクトでの再確認（`Build.SUPPORTED_ABIS`）
 * - V-2: `Engine`/`Conversation`の解放API（`close()`）の実効性（再利用時の挙動）
 * - V-3: `Backend.CPU()`のスレッド数指定可否（`threadCount`パラメータの実測）
 * - V-4: Qwen3のthinking無効化の指定方法（`ThinkingConfig(enableThinking=false)`）と
 *   無効化時の実出力トークン数・`<think>`タグ混入有無
 * - V-8: `maxNumTokens` 128 vs 256 のピークRAM差（ロード直後・会話生成直後・生成中ピークの
 *   3時点で比較）
 *
 * 前提（実行前に手動で満たすこと。本ファイル単体では満たせない）:
 * 1. ホスト側 `build/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`
 *    （HuggingFace `litert-community/Qwen3-0.6B`。SHA-256・サイズは
 *    `build/agent-logs/p7c0-download.log` 参照）を用意済みであること。
 * 2. `adb push build/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /data/local/tmp/`
 *    を実行済みであること（本クラスはこの固定パスから内部ストレージへコピーする。
 *    計画書§11の「adb pushしてからアプリ内部へコピー」方式）。
 *
 * 実行方法:
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.probe.LiteRtLmProbeTest`
 *
 * 結果はLogcat（TAG=P7C0_PROBE）へ出力する。カレンダー実データ・個人情報はプロンプトに
 * 一切含めない（プロンプトはハードコードされた汎用文言のみ）。
 *
 * 実測済み（2026-08-10、AVD actionstarter_test x86_64/API35、2回連続実行で再現確認）:
 * GO_NO_GO=GO。`{"event_type":"social","steps":[{"action_type":"prepare_item",
 * "display_text":"会議の準備","type":"preparation","estimated_minutes":15,
 * "priority":"important","skippable":false}]}` がスキーマ完全準拠で生成された
 * （ctx128・ctx256とも、`<think>`混入なし）。詳細測定値は
 * `build/agent-logs/p7c0-logcat.log`・計画書§14 P7-C0行・§17 V-1〜V-8を参照。
 * `getBenchmarkInfo()`は`ExperimentalFlags.enableBenchmark = true`を明示設定しないと
 * `LiteRtLmJniException`（"Benchmark is not enabled"）を送出する一次ソース未記載の挙動を発見。
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore(
    "probe専用（P7-C0実測、docs/plans/phase7-local-llm-foundation.md §14）。" +
        "328MBモデルのadb push前提かつ実行に約20秒を要するため、connectedDebugAndroidTest" +
        "一括実行やG4-Eの対象に含めないため意図的にIgnore。実測結果はクラスKDocおよび" +
        "build/agent-logs/p7c0-logcat.log・計画書§14 P7-C0行に転記済み。再実行する場合は" +
        "@Ignoreを外すか `-e class` で本クラスを直接指定し、事前に" +
        "`adb push build/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /data/local/tmp/` を行う。"
)
class LiteRtLmProbeTest {

    @Test
    fun probeResponseFormatGoNoGo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 実測で判明した点（1回目実行で発見）: getBenchmarkInfo()は既定では
        // `LiteRtLmJniException: ... Benchmark is not enabled. Please make sure the
        // BenchmarkParams is set in the EngineSettings.` を送出する。ExperimentalFlags側の
        // enableBenchmarkを明示的にtrueへ設定する必要がある（一次ソース未記載の追加発見）。
        ExperimentalFlags.enableBenchmark = true
        logLine("===== P7-C0 probe start =====")
        logLine(
            "V1_STATIC sdkInt=${Build.VERSION.SDK_INT} " +
                "supportedAbis=${Build.SUPPORTED_ABIS.joinToString(",")} device=${Build.MODEL}"
        )

        val pushedModel = File(PUSHED_MODEL_PATH)
        if (!pushedModel.exists()) {
            fail(
                "PRECONDITION_FAILED: pushed model not found at $PUSHED_MODEL_PATH. " +
                    "Run: adb push build/models/$MODEL_FILE_NAME /data/local/tmp/ " +
                    "before executing this probe."
            )
            return
        }
        logLine(
            "PRECONDITION_OK pushedModelSizeBytes=${pushedModel.length()} " +
                "expectedBytes=$EXPECTED_MODEL_BYTES sizeMatches=${pushedModel.length() == EXPECTED_MODEL_BYTES}"
        )

        val internalModel = File(context.noBackupFilesDir, "p7c0-probe-model.litertlm")
        val copyStartMs = SystemClock.elapsedRealtime()
        copyFile(pushedModel, internalModel)
        val copyMs = SystemClock.elapsedRealtime() - copyStartMs
        logLine(
            "COPY_TO_INTERNAL doneMs=$copyMs internalPath=${internalModel.absolutePath} " +
                "internalSizeBytes=${internalModel.length()} " +
                "sizeMatches=${internalModel.length() == pushedModel.length()}"
        )

        val results = mutableListOf<ProbeRunResult>()
        try {
            results += runSingleProbe(context, label = "ctx128", modelPath = internalModel.absolutePath, maxNumTokens = 128)
        } catch (t: Throwable) {
            Log.e(TAG, "ctx128 run threw unexpectedly", t)
            results += ProbeRunResult.failure("ctx128", 128, "unhandled ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            results += runSingleProbe(context, label = "ctx256", modelPath = internalModel.absolutePath, maxNumTokens = 256)
        } catch (t: Throwable) {
            Log.e(TAG, "ctx256 run threw unexpectedly", t)
            results += ProbeRunResult.failure("ctx256", 256, "unhandled ${t.javaClass.simpleName}: ${t.message}")
        }

        results.forEach { logResultSummary(it) }

        val r128 = results.firstOrNull { it.label == "ctx128" }
        val r256 = results.firstOrNull { it.label == "ctx256" }
        if (r128 != null && r256 != null) {
            logLine(
                "V8_RAM_DELTA " +
                    "ctx128_afterInitTotalPssKb=${r128.afterInitTotalPssKb} " +
                    "ctx256_afterInitTotalPssKb=${r256.afterInitTotalPssKb} " +
                    "ctx128_afterConvTotalPssKb=${r128.afterConvTotalPssKb} " +
                    "ctx256_afterConvTotalPssKb=${r256.afterConvTotalPssKb} " +
                    "ctx128_peakTotalPssKb=${r128.peakTotalPssKb} " +
                    "ctx256_peakTotalPssKb=${r256.peakTotalPssKb} " +
                    "peakDeltaKb=${r256.peakTotalPssKb - r128.peakTotalPssKb} " +
                    "ctx128_peakNativeHeapBytes=${r128.peakNativeHeapBytes} " +
                    "ctx256_peakNativeHeapBytes=${r256.peakNativeHeapBytes}"
            )
        }

        val internalDeleted = internalModel.delete()
        logLine("CLEANUP internalModelDeleted=$internalDeleted")
        logLine("===== P7-C0 probe end =====")

        val primary = r128
        when {
            primary == null || primary.error != null ->
                fail("GO_NO_GO=NO_GO ctx128 run did not complete cleanly: ${primary?.error ?: "no result recorded"}")
            !primary.schemaValid ->
                fail(
                    "GO_NO_GO=NO_GO ResponseFormat did not produce schema-conformant JSON on ctx128 profile: " +
                        "${primary.schemaValidationDetail}. rawResponse=${primary.rawResponseText}"
                )
            else ->
                logLine("GO_NO_GO=GO ResponseFormat produced schema-conformant JSON on ctx128 profile.")
        }
    }

    /** 1回分の実測（Engine生成〜close）をラベル付きで実行する。 */
    private fun runSingleProbe(
        context: Context,
        label: String,
        modelPath: String,
        maxNumTokens: Int
    ): ProbeRunResult {
        logLine("--- run[$label] start maxNumTokens=$maxNumTokens ---")
        var engine: Engine? = null
        var conversation: Conversation? = null
        try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(threadCount = 4), // V-3: スレッド数指定APIの実測
                maxNumTokens = maxNumTokens
            )
            val loadStart = SystemClock.elapsedRealtime()
            val localEngine = Engine(engineConfig)
            localEngine.initialize()
            engine = localEngine
            val modelLoadMs = SystemClock.elapsedRealtime() - loadStart
            val (afterInitNativeHeap, afterInitTotalPssKb) = sampleRamOnce(context)
            logLine(
                "run[$label] LOAD_DONE modelLoadMs=$modelLoadMs " +
                    "afterInitNativeHeapBytes=$afterInitNativeHeap afterInitTotalPssKb=$afterInitTotalPssKb"
            )

            val conversationConfig = ConversationConfig(
                enableResponseFormat = true,
                thinkingConfig = ThinkingConfig(enableThinking = false), // V-4: thinking無効化API
                maxOutputToken = MAX_OUTPUT_TOKEN
            )
            val localConversation = localEngine.createConversation(conversationConfig)
            conversation = localConversation
            val (afterConvNativeHeap, afterConvTotalPssKb) = sampleRamOnce(context)
            logLine(
                "run[$label] CONVERSATION_CREATED afterConvNativeHeapBytes=$afterConvNativeHeap " +
                    "afterConvTotalPssKb=$afterConvTotalPssKb"
            )

            val sampler = RamSampler(context)
            sampler.start()
            val sendStart = SystemClock.elapsedRealtime()
            val response = localConversation.sendMessage(
                PROBE_PROMPT_JA,
                maxOutputToken = MAX_OUTPUT_TOKEN,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                responseFormat = ResponseFormat.json(PLANNING_SCHEMA_SIMPLIFIED)
            )
            val sendMessageWallMs = SystemClock.elapsedRealtime() - sendStart
            val (peakNativeHeap, peakTotalPssKb) = sampler.stopAndGetPeak()

            val benchmarkInfo = try {
                localConversation.getBenchmarkInfo()
            } catch (t: Throwable) {
                Log.w(TAG, "run[$label] benchmarkInfo read failed", t)
                null
            }

            val rawText = extractText(response)
            val containsThinkTag = rawText.contains("<think", ignoreCase = true)
            val (schemaValid, schemaDetail) = validateAgainstSimplifiedPlanningSchema(rawText)

            val nativeHeapSummaryStatKb = tryNativeHeapSummaryStat()
            val meminfoDump = tryDumpsysMeminfo(context)

            logLine(
                "run[$label] GENERATION_DONE sendMessageWallMs=$sendMessageWallMs " +
                    "benchmarkInitTimeS=${benchmarkInfo?.initTimeInSecond} " +
                    "benchmarkTtftS=${benchmarkInfo?.timeToFirstTokenInSecond} " +
                    "benchmarkPrefillTokPerS=${benchmarkInfo?.lastPrefillTokensPerSecond} " +
                    "benchmarkDecodeTokPerS=${benchmarkInfo?.lastDecodeTokensPerSecond} " +
                    "benchmarkPrefillTokenCount=${benchmarkInfo?.lastPrefillTokenCount} " +
                    "benchmarkDecodeTokenCount=${benchmarkInfo?.lastDecodeTokenCount} " +
                    "peakNativeHeapBytes=$peakNativeHeap peakTotalPssKb=$peakTotalPssKb " +
                    "nativeHeapSummaryStatKb=$nativeHeapSummaryStatKb " +
                    "containsThinkTag=$containsThinkTag schemaValid=$schemaValid detail=$schemaDetail"
            )
            logLine("run[$label] RAW_RESPONSE_TEXT=$rawText")
            if (meminfoDump != null) {
                logLine("run[$label] DUMPSYS_MEMINFO_NATIVE_HEAP_LINE=${extractNativeHeapLine(meminfoDump)}")
            } else {
                logLine("run[$label] DUMPSYS_MEMINFO unavailable (exec restricted or failed; see preceding warning)")
            }

            // V-2実測: close()後にEngineが再利用不能（例外を送出）になることを確認する。
            localConversation.close()
            localEngine.close()
            conversation = null
            engine = null
            var reuseAfterCloseThrew = false
            var reuseAfterCloseException: String? = null
            try {
                localEngine.createConversation(ConversationConfig())
            } catch (t: Throwable) {
                reuseAfterCloseThrew = true
                reuseAfterCloseException = "${t.javaClass.simpleName}: ${t.message}"
            }
            logLine(
                "run[$label] V2_CLOSE_BEHAVIOR reuseAfterCloseThrew=$reuseAfterCloseThrew " +
                    "reuseAfterCloseException=$reuseAfterCloseException"
            )

            return ProbeRunResult(
                label = label,
                maxNumTokens = maxNumTokens,
                modelLoadMs = modelLoadMs,
                afterInitNativeHeapBytes = afterInitNativeHeap,
                afterInitTotalPssKb = afterInitTotalPssKb,
                afterConvNativeHeapBytes = afterConvNativeHeap,
                afterConvTotalPssKb = afterConvTotalPssKb,
                sendMessageWallMs = sendMessageWallMs,
                benchmarkInitTimeS = benchmarkInfo?.initTimeInSecond,
                benchmarkTtftS = benchmarkInfo?.timeToFirstTokenInSecond,
                benchmarkPrefillTokPerS = benchmarkInfo?.lastPrefillTokensPerSecond,
                benchmarkDecodeTokPerS = benchmarkInfo?.lastDecodeTokensPerSecond,
                benchmarkPrefillTokenCount = benchmarkInfo?.lastPrefillTokenCount,
                benchmarkDecodeTokenCount = benchmarkInfo?.lastDecodeTokenCount,
                peakNativeHeapBytes = peakNativeHeap,
                peakTotalPssKb = peakTotalPssKb,
                rawResponseText = rawText,
                containsThinkTag = containsThinkTag,
                schemaValid = schemaValid,
                schemaValidationDetail = schemaDetail,
                error = null
            )
        } catch (t: Throwable) {
            Log.e(TAG, "run[$label] failed", t)
            return ProbeRunResult.failure(label, maxNumTokens, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // 正常系はconversation/engineを既にnull化しclose済み。例外パスのみここで後始末する。
            try {
                conversation?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "run[$label] conversation cleanup close failed", t)
            }
            try {
                engine?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "run[$label] engine cleanup close failed", t)
            }
        }
    }

    private fun extractText(message: Message): String {
        return message.contents.contents.joinToString(separator = "") { content ->
            (content as? Content.Text)?.text.orEmpty()
        }
    }

    private fun validateAgainstSimplifiedPlanningSchema(jsonText: String): Pair<Boolean, String> {
        return try {
            val root = JSONObject(jsonText)
            val allowedEventTypes = setOf("business_meeting", "medical", "social", "travel", "other")
            val eventType = root.optString("event_type", "")
            if (eventType !in allowedEventTypes) {
                return false to "event_type invalid or missing: '$eventType'"
            }
            val steps = root.optJSONArray("steps") ?: return false to "steps missing or not an array"
            if (steps.length() != 1) {
                return false to "steps length=${steps.length()} expected exactly 1 (simplified schema)"
            }
            val allowedActionTypes = setOf(
                "prepare_item", "travel_to_location", "review_document", "confirm_attendance", "other_action"
            )
            val allowedStepTypes = setOf("transition", "preparation", "departure", "travel")
            val allowedPriorities = setOf("required", "important", "optional")
            val step = steps.optJSONObject(0) ?: return false to "steps[0] is not an object"

            val actionType = step.optString("action_type", "")
            if (actionType !in allowedActionTypes) return false to "steps[0].action_type invalid: '$actionType'"

            val displayText = step.optString("display_text", "")
            if (displayText.isEmpty() || displayText.length > 40) {
                return false to "steps[0].display_text length invalid: ${displayText.length}"
            }

            val type = step.optString("type", "")
            if (type !in allowedStepTypes) return false to "steps[0].type invalid: '$type'"

            if (!step.has("estimated_minutes")) return false to "steps[0].estimated_minutes missing"
            val minutes = step.optInt("estimated_minutes", -1)
            if (minutes !in 1..120) return false to "steps[0].estimated_minutes out of range: $minutes"

            val priority = step.optString("priority", "")
            if (priority !in allowedPriorities) return false to "steps[0].priority invalid: '$priority'"

            if (!step.has("skippable")) return false to "steps[0].skippable missing"

            true to "OK"
        } catch (e: Exception) {
            false to "JSON parse failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun sampleRamOnce(context: Context): Pair<Long, Long> {
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val infos = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
        val totalPssKb = infos.firstOrNull()?.totalPss?.toLong() ?: -1L
        return nativeHeap to totalPssKb
    }

    private fun tryNativeHeapSummaryStat(): String? {
        return try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.getMemoryStat("summary.native-heap")
        } catch (t: Throwable) {
            Log.w(TAG, "native heap summary stat read failed", t)
            null
        }
    }

    private fun tryDumpsysMeminfo(context: Context): String? {
        return try {
            val process = ProcessBuilder("dumpsys", "meminfo", context.packageName)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (t: Throwable) {
            Log.w(TAG, "dumpsys meminfo exec failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun extractNativeHeapLine(dump: String): String {
        return dump.lineSequence().firstOrNull { it.contains("Native Heap", ignoreCase = true) }
            ?.trim()
            ?: "not found in dumpsys output"
    }

    private fun copyFile(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun logLine(message: String) {
        Log.e(TAG, message)
    }

    private fun logResultSummary(r: ProbeRunResult) {
        logLine(
            "RESULT_SUMMARY label=${r.label} maxNumTokens=${r.maxNumTokens} error=${r.error} " +
                "modelLoadMs=${r.modelLoadMs} sendMessageWallMs=${r.sendMessageWallMs} " +
                "benchmarkTtftS=${r.benchmarkTtftS} benchmarkDecodeTokPerS=${r.benchmarkDecodeTokPerS} " +
                "peakTotalPssKb=${r.peakTotalPssKb} peakNativeHeapBytes=${r.peakNativeHeapBytes} " +
                "schemaValid=${r.schemaValid} containsThinkTag=${r.containsThinkTag}"
        )
    }

    /** 推論中のRAMをバックグラウンドスレッドで定期サンプリングしピークを記録する（§11.1）。 */
    private class RamSampler(private val context: Context) {
        private val running = AtomicBoolean(false)
        private val peakNativeHeapBytes = AtomicLong(0)
        private val peakTotalPssKb = AtomicLong(0)
        private var thread: Thread? = null

        fun start() {
            running.set(true)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            thread = Thread {
                while (running.get()) {
                    val native = Debug.getNativeHeapAllocatedSize()
                    if (native > peakNativeHeapBytes.get()) peakNativeHeapBytes.set(native)
                    try {
                        val infos = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
                        val pss = infos.firstOrNull()?.totalPss?.toLong() ?: -1L
                        if (pss > peakTotalPssKb.get()) peakTotalPssKb.set(pss)
                    } catch (_: Throwable) {
                        // サンプリング失敗は致命的ではない。次周期に委ねる。
                    }
                    Thread.sleep(300)
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun stopAndGetPeak(): Pair<Long, Long> {
            running.set(false)
            thread?.join(3000)
            return peakNativeHeapBytes.get() to peakTotalPssKb.get()
        }
    }

    private data class ProbeRunResult(
        val label: String,
        val maxNumTokens: Int,
        val modelLoadMs: Long = -1,
        val afterInitNativeHeapBytes: Long = -1,
        val afterInitTotalPssKb: Long = -1,
        val afterConvNativeHeapBytes: Long = -1,
        val afterConvTotalPssKb: Long = -1,
        val sendMessageWallMs: Long = -1,
        val benchmarkInitTimeS: Double? = null,
        val benchmarkTtftS: Double? = null,
        val benchmarkPrefillTokPerS: Double? = null,
        val benchmarkDecodeTokPerS: Double? = null,
        val benchmarkPrefillTokenCount: Int? = null,
        val benchmarkDecodeTokenCount: Int? = null,
        val peakNativeHeapBytes: Long = -1,
        val peakTotalPssKb: Long = -1,
        val rawResponseText: String = "",
        val containsThinkTag: Boolean = false,
        val schemaValid: Boolean = false,
        val schemaValidationDetail: String = "",
        val error: String? = null
    ) {
        companion object {
            fun failure(label: String, maxNumTokens: Int, error: String) =
                ProbeRunResult(label = label, maxNumTokens = maxNumTokens, error = error)
        }
    }

    private companion object {
        private const val TAG = "P7C0_PROBE"
        private const val MODEL_FILE_NAME = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
        private const val PUSHED_MODEL_PATH = "/data/local/tmp/$MODEL_FILE_NAME"
        private const val EXPECTED_MODEL_BYTES = 344437808L

        // 計画書§14 P7-C0は「1回の生成は最大64トークン程度に制限」と指示するが、簡易スキーマ
        // （event_type + steps[1件]・6フィールド）でも骨格のみで約50〜60トークンを要するため、
        // 64ちょうどでは有効JSONの閉じ括弧前に打ち切られ「制約が機能しなかった」という
        // 偽陰性を生みかねない。指示のタイムアウト安全側の意図（生成量を小さく保つ）は
        // 維持しつつ100トークンへ引き上げる（数十秒オーダーの増分に留まり、全体の
        // タイムアウト予算への影響は軽微と判断）。
        private const val MAX_OUTPUT_TOKEN = 100

        // カレンダー実データ・個人情報は一切含まない汎用プロンプト（計画書指示の例文相当）。
        private const val PROBE_PROMPT_JA =
            "会議の準備について、短い提案を1件だけJSON形式で出力してください。" +
                "個人情報や実在の予定は使用しないでください。"

        // 計画書§8.4のPlanningスキーマの簡易版。steps を1件固定にした以外はフィールド構成・
        // enum・範囲制約を§8.4に合わせている（action_typeの具体的な英語ID語彙は正仕様書§21の
        // 確定値ではなく、constrained decodingの構造的成立を確認するためのプローブ用暫定値）。
        private const val PLANNING_SCHEMA_SIMPLIFIED = """
        {
          "type": "object",
          "properties": {
            "event_type": {
              "type": "string",
              "enum": ["business_meeting", "medical", "social", "travel", "other"]
            },
            "steps": {
              "type": "array",
              "minItems": 1,
              "maxItems": 1,
              "items": {
                "type": "object",
                "properties": {
                  "action_type": {
                    "type": "string",
                    "enum": ["prepare_item", "travel_to_location", "review_document", "confirm_attendance", "other_action"]
                  },
                  "display_text": { "type": "string", "minLength": 1, "maxLength": 40 },
                  "type": {
                    "type": "string",
                    "enum": ["transition", "preparation", "departure", "travel"]
                  },
                  "estimated_minutes": { "type": "integer", "minimum": 1, "maximum": 120 },
                  "priority": { "type": "string", "enum": ["required", "important", "optional"] },
                  "skippable": { "type": "boolean" }
                },
                "required": ["action_type", "display_text", "type", "estimated_minutes", "priority", "skippable"],
                "additionalProperties": false
              }
            }
          },
          "required": ["event_type", "steps"],
          "additionalProperties": false
        }
        """
    }
}
