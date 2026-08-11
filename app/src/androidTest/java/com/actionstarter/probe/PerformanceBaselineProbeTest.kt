package com.actionstarter.probe

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.actionstarter.ai.AiFallbackReason
import com.actionstarter.ai.AiPreferencesImpl
import com.actionstarter.ai.AiResult
import com.actionstarter.ai.LocalAiGateway
import com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel
import com.actionstarter.ai.model.DeviceCapabilityImpl
import com.actionstarter.ai.model.ModelCatalog
import com.actionstarter.ai.model.ModelStorageImpl
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.runBlocking
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
 * ／[LiteRtLmAdapterE2EProbeTest]——と同型: `@Ignore`既定・Log出力・目的と測定対象をKDocに明記）。
 *
 * **Phase 9.5計画書（`docs/plans/phase9.5-performance-quality.md`§3.1・§4・§10）M（ベースライン
 * 計測・最初に実施）**。F-1〜F-3の改善実装前の基準点を、実際に導入済みの本番`ModelStorage`
 * （`ModelCatalog.ALL`、`no_backup/models/`配下に既に導入済みのモデルをそのまま使う。probe専用の
 * push/copyは行わない）・本番`AiPreferences`（`selected_model_id`は実運用値をそのまま使い、
 * `auto`選択の実際の結果を記録する。書き換えるのは`aiEnabled`のみ、`finally`で必ず復元）を通して
 * 実測する。
 *
 * **コールド/ウォームのペア計測（Phase 9.5敵対的レビューA-2）**: [probePlanBaseline]・
 * [probeRecoveryBaseline]はそれぞれ独立した`@Test`であり、**各メソッドを個別に5回、プロセスを
 * 毎回再起動して実行する**（`ModelComparisonProbeTest`「プロセス分離の必要性」と同じ制約
 * ——Engineはプロセス内シングルトン、R-7）。各プロセス内で「1回目=コールド（Engine新規ロード）・
 * 2〜3回目=ウォーム（Engine再利用）」の3回を連続実行し、同一プロセス内でのavailMem変動を
 * コールド/ウォーム間で均等化する（§4.1）。
 *
 * **時間効率のための設計判断（実測時の記録）**: Plan/Recoveryをそれぞれ独立した5プロセスサイクル
 * （計10プロセス起動）で計測する。計画書§4.1の記述どおり両ドメインとも「1コールド+2ウォーム」を
 * 得る。Engineのロード（`modelLoadMs`）はモデル・エンジンの初期化そのものでありPlan/Recovery
 * どちらが最初の呼び出しかに依存しないため、両ドメインを独立サイクルにしても「コールドの意味」は
 * 変わらない。
 *
 * 実行方法（メソッドごとに個別実行、5回）:
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.probe.PerformanceBaselineProbeTest#probePlanBaseline`
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.probe.PerformanceBaselineProbeTest#probeRecoveryBaseline`
 *
 * 結果はLogcat（TAG=P95_PERF_PROBE）へ出力する。カレンダー実データ・個人情報はプロンプトに
 * 一切含めない（合成イベントのみ使用）。
 *
 * **変動要因の統制（計画書§4.3）**: 各実行の冒頭で`ActivityManager.MemoryInfo`・充電状態
 * （`BatteryManager`スティッキーIntent）をログする。充電状態・`svc power stayon`設定・
 * プロセスのforce-stop／180秒クールダウンは実行者（オーケストレーター）がプロセス起動の外側で
 * 統制する（本ファイル単体では満たせない前提、`LiteRtLmProbeTest`の「前提」節と同型の切り分け）。
 */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore(
    "probe専用（Phase 9.5 M、ベースライン計測、docs/plans/phase9.5-performance-quality.md §3.1）。" +
        "実測はコミットM（計画書§14）で完了済み。1回の実行に数十秒を要し、5回のプロセス再起動を" +
        "要するためconnectedDebugAndroidTest一括実行の対象に含めない。再実行する場合はクラスKDoc" +
        "「実行方法」のとおり`-e class`でメソッドを個別に5回実行する（`ModelComparisonProbeTest`の" +
        "KDoc「再実行時の注意」と同じ罠——discoveryの時点で@Ignoreクラスごと除外されるため、" +
        "再実行時は本クラスの@Ignoreを一時的にコメントアウトする）。"
)
class PerformanceBaselineProbeTest {

    @Test
    fun probePlanBaseline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        logLine("===== P95 M baseline probe start (plan) =====")
        logEnvironment(context)

        val storage = ModelStorageImpl(context, catalog = ModelCatalog.ALL)
        val preferences = AiPreferencesImpl(
            context.getSharedPreferences(AiPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE)
        )
        val originalAiEnabled = preferences.aiEnabled
        val pssSampler = PssPeakSampler(context)

        try {
            preferences.aiEnabled = true
            logLine("SELECTED_MODEL_ID_AT_START value=${preferences.selectedModelId}")

            val gateway = LocalAiGateway(
                model = LiteRtLmLocalLanguageModel(),
                modelStorage = storage,
                modelVerifier = ModelVerifierImpl(),
                deviceCapability = DeviceCapabilityImpl(context),
                preferences = preferences
            )

            pssSampler.start()
            val planningContext = samplePlanningContext()
            repeat(3) { index ->
                val expectedColdOrWarm = if (index == 0) "cold" else "warm"
                runPlanTrial(gateway, planningContext, trialIndex = index, expected = expectedColdOrWarm)
                logLine("PSS_RUNNING_PEAK domain=plan afterTrial=$index peakPssBytes=${pssSampler.peakBytes()}")
            }
        } finally {
            pssSampler.stop()
            logLine("PSS_FINAL_PEAK domain=plan peakPssBytes=${pssSampler.peakBytes()}")
            preferences.aiEnabled = originalAiEnabled
            logLine("CLEANUP aiEnabledRestoredTo=$originalAiEnabled")
            logLine("===== P95 M baseline probe end (plan) =====")
        }
    }

    @Test
    fun probeRecoveryBaseline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        logLine("===== P95 M baseline probe start (recovery) =====")
        logEnvironment(context)

        val storage = ModelStorageImpl(context, catalog = ModelCatalog.ALL)
        val preferences = AiPreferencesImpl(
            context.getSharedPreferences(AiPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE)
        )
        val originalAiEnabled = preferences.aiEnabled
        val pssSampler = PssPeakSampler(context)

        try {
            preferences.aiEnabled = true
            logLine("SELECTED_MODEL_ID_AT_START value=${preferences.selectedModelId}")

            val gateway = LocalAiGateway(
                model = LiteRtLmLocalLanguageModel(),
                modelStorage = storage,
                modelVerifier = ModelVerifierImpl(),
                deviceCapability = DeviceCapabilityImpl(context),
                preferences = preferences
            )

            pssSampler.start()
            val recoveryContext = sampleRecoveryContext()
            val options = sampleRecoveryOptions()
            repeat(3) { index ->
                val expectedColdOrWarm = if (index == 0) "cold" else "warm"
                runRecoveryTrial(gateway, recoveryContext, options, trialIndex = index, expected = expectedColdOrWarm)
                logLine("PSS_RUNNING_PEAK domain=recovery afterTrial=$index peakPssBytes=${pssSampler.peakBytes()}")
            }
        } finally {
            pssSampler.stop()
            logLine("PSS_FINAL_PEAK domain=recovery peakPssBytes=${pssSampler.peakBytes()}")
            preferences.aiEnabled = originalAiEnabled
            logLine("CLEANUP aiEnabledRestoredTo=$originalAiEnabled")
            logLine("===== P95 M baseline probe end (recovery) =====")
        }
    }

    private fun runPlanTrial(gateway: LocalAiGateway, planningContext: PlanningContext, trialIndex: Int, expected: String) {
        val wallStartMs = System.currentTimeMillis()
        val result = runBlocking { gateway.generatePlan(planningContext) }
        val wallMs = System.currentTimeMillis() - wallStartMs
        logResult(domain = "plan", trialIndex = trialIndex, expected = expected, wallMs = wallMs, result = result)
    }

    private fun runRecoveryTrial(
        gateway: LocalAiGateway,
        recoveryContext: RecoveryContext,
        options: List<RecoveryOption>,
        trialIndex: Int,
        expected: String
    ) {
        val wallStartMs = System.currentTimeMillis()
        val result = runBlocking { gateway.generateRecovery(recoveryContext, options) }
        val wallMs = System.currentTimeMillis() - wallStartMs
        logResult(domain = "recovery", trialIndex = trialIndex, expected = expected, wallMs = wallMs, result = result)
    }

    private fun logResult(domain: String, trialIndex: Int, expected: String, wallMs: Long, result: AiResult<*>) {
        when (result) {
            is AiResult.Success -> {
                val metrics = result.metrics
                val actualColdOrWarm = if (metrics.modelLoadMs > 0) "cold" else "warm"
                logLine(
                    "TRIAL domain=$domain trialIndex=$trialIndex expected=$expected " +
                        "actualColdOrWarm=$actualColdOrWarm RESULT=Success wallMs=$wallMs " +
                        "modelLoadMs=${metrics.modelLoadMs} firstTokenMs=${metrics.firstTokenMs} " +
                        "tokensPerSecond=${metrics.tokensPerSecond} outputTokens=${metrics.outputTokens} " +
                        "totalMs=${metrics.totalMs} peakNativeHeapBytes=${metrics.peakNativeHeapBytes} " +
                        "retried=${metrics.retried} schemaValid=${metrics.schemaValid} " +
                        "sanityPassed=${metrics.sanityPassed} selectedModelId=${metrics.selectedModelId} " +
                        "sanityRejectCount=${metrics.sanityRejectCount} " +
                        "lastSanityRejectReason=${metrics.lastSanityRejectReason}"
                )
            }

            is AiResult.Fallback -> {
                logLine(
                    "TRIAL domain=$domain trialIndex=$trialIndex expected=$expected " +
                        "RESULT=Fallback wallMs=$wallMs reason=${result.reason} detail=${result.detail} " +
                        "metricsPresent=${result.metrics != null} " +
                        "sanityRejectCount=${result.metrics?.sanityRejectCount} " +
                        "lastSanityRejectReason=${result.metrics?.lastSanityRejectReason}"
                )
                if (result.reason == AiFallbackReason.AI_DISABLED || result.reason == AiFallbackReason.MODEL_NOT_INSTALLED) {
                    Log.e(
                        TAG,
                        "TRIAL domain=$domain PRECONDITION_WARNING reason=${result.reason}: " +
                            "計測の前提（AI ON・モデル導入済み）が満たされていない可能性があります。"
                    )
                }
            }
        }
    }

    /** [ActivityManager.MemoryInfo]・充電状態をログへ残す（計画書§4.3変動要因の統制）。 */
    private fun logEnvironment(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        logLine(
            "DEVICE_MEMORY_INFO totalMem=${info.totalMem} availMem=${info.availMem} " +
                "lowMemory=${info.lowMemory} threshold=${info.threshold}"
        )

        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
        logLine(
            "BATTERY_STATE status=$status plugged=$plugged level=$level isChargingOrPlugged=$isCharging " +
                "(計画書§4.3: 全試行を充電状態に統一する前提。isChargingOrPlugged=falseの場合は" +
                "計測条件の前提が崩れているため結果の解釈に注意)"
        )
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
            sourceCalendar = CalendarSource(id = "p95-baseline-probe", displayName = "P95 Baseline Probe Calendar")
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

    private fun sampleRecoveryContext(): RecoveryContext {
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "定例ミーティング",
            notes = null,
            startDate = Instant.parse("2026-08-12T10:00:00Z"),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "p95-baseline-probe", displayName = "P95 Baseline Probe Calendar")
        )
        return RecoveryContext(
            currentTime = Instant.parse("2026-08-12T09:15:00Z"),
            currentLocation = null,
            event = event,
            unfinishedSteps = emptyList(),
            latestTravelEstimate = Duration.ofMinutes(20),
            plannedDepartureTime = Instant.parse("2026-08-12T09:30:00Z")
        )
    }

    private fun sampleRecoveryOptions(): List<RecoveryOption> = listOf(
        RecoveryOption(
            id = UUID.randomUUID(),
            semanticAction = "keep_all_steps",
            title = "",
            explanation = "",
            estimatedArrival = Instant.parse("2026-08-12T09:55:00Z"),
            skippedStepIds = emptyList()
        ),
        RecoveryOption(
            id = UUID.randomUUID(),
            semanticAction = "skip_optional_steps",
            title = "",
            explanation = "",
            estimatedArrival = Instant.parse("2026-08-12T09:50:00Z"),
            skippedStepIds = emptyList()
        )
    )

    private fun logLine(message: String) {
        Log.e(TAG, message)
    }

    /**
     * プロセス全体のPSS（`ActivityManager.getProcessMemoryInfo().totalPss`、KB単位）を
     * バックグラウンドスレッドで定期サンプリングしピークを保持する
     * （[ModelComparisonProbeTest.PssPeakSampler]と同一実装、計画書§3.1「転用」）。
     */
    private class PssPeakSampler(context: Context) {
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

        /** ピークPSSをバイト単位で返す（`totalPss`はKB単位のためここで1024倍する）。 */
        fun peakBytes(): Long = peakKb.get() * 1024L

        private companion object {
            const val SAMPLING_INTERVAL_MILLIS = 200L
            const val SAMPLER_JOIN_TIMEOUT_MILLIS = 2_000L
        }
    }

    private companion object {
        const val TAG = "P95_PERF_PROBE"
    }
}
