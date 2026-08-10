package com.actionstarter.probe

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.actionstarter.ai.AiPreferencesImpl
import com.actionstarter.ai.AiResult
import com.actionstarter.ai.LocalAiGateway
import com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel
import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceCapabilityImpl
import com.actionstarter.ai.model.DeviceTier
import com.actionstarter.ai.model.ModelCatalog
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelStorageImpl
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * probe専用・正式テストではない（P7-C0 `LiteRtLmProbeTest`と同型: `@Ignore`既定・Log出力・
 * 目的と測定対象をKDocに明記）。
 *
 * **位置づけ（T-P7E2E-1〜5との違い）**: 計画書§14.6の記録どおり、正式なT-P7E2E-1〜5
 * （JNI疎通・機内モードE2E・StrictMode・破損モデル・画面回転耐性）はP7-C7（統合サイクル、
 * AI隔離ガード拡張と同時期）のスコープであり、本ファイルはそれらを代替しない。本プローブは
 * P7-C5（`LiteRtLmLocalLanguageModel`実装＋`LocalAiGateway`統合配線）自身の検証として、
 * 「実装した`generatePlan`本体が実機で本当に動くか」「Basic版の汎用固定文言と異なる
 * Semantic Contextualizationが得られるか」を実測することが目的。
 *
 * **測定対象**:
 * - `LocalAiGateway.generatePlan()`を**Gateway経由**（`SchemaValidator`→`ContentSanityChecker`の
 *   3段検証パイプラインを含む実配線）で実行し、(a) スキーマ適合JSON生成 (b) `AIPlanResponse`
 *   へのパース成功 (c) `ContentSanityChecker`通過 (d) 実際に生成された`display_text`、を確認する。
 * - `AiMetrics`（P7-C5 ADR-0055で配線した実測値）: `modelLoadMs`／`firstTokenMs`／
 *   `tokensPerSecond`／`peakNativeHeapBytes`／`totalMs`。
 * - 日本語の合成予定3件（歯科検診／友人の結婚式／チームMTG。実在の予定ではなくPIIを含まない）で
 *   Basic版の汎用固定文言（`step_title_preparation`="出かける準備をする"等、
 *   `features/common/StepTitle.kt`）との差（予定固有の文脈化行動が出るか）を観測する。
 * - Engine再利用（R-7・T-GW-16）: 3件を同一プロセス内で連続実行し、2件目以降で
 *   `modelLoadMs`が実際に`0`（再ロードなし）になることを確認する。
 *
 * **`DeviceCapability`の意図的なラップ（透明性のため明記）**: AVD `actionstarter_test`は
 * `hw.ramSize=4096`（実測`/proc/meminfo`の`MemTotal`は約3.92GB）であり、これは
 * `DeviceCapability.TIER_0_MAX_TOTAL_MEM_BYTES`（6GB、§95.3）を下回るため、本番の
 * `DeviceCapabilityImpl.classify()`をそのまま使うと`LocalAiGateway.generatePlan()`は
 * **常に**`Fallback(UNSUPPORTED_DEVICE)`を返し、推論経路を一度も通過できない（これは
 * §5.3の段0設計が正しく機能している証拠であり、バグではない）。本プローブの目的は
 * 「推論本体が実機で動くか」の実測であるため、[TierOverrideDeviceCapability]で`classify()`
 * のみ`TIER_1_STANDARD`へ固定し、`isAbiSupported()`／`hasAvailableMemory()`は実値
 * （[DeviceCapabilityImpl]）をそのまま使う。§8.6 #7のOOM事前ガードは実値のまま働くため、
 * 実際に空きメモリが不足していれば依然として`Fallback(OUT_OF_MEMORY_PREVENTED)`になりうる。
 * これは`LocalAiGateway`本体のロジックを一切変更しない、本プローブ内のfixtureに閉じた選択。
 *
 * 前提（実行前に手動で満たすこと。本ファイル単体では満たせない）:
 * 1. ホスト側`build/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`を用意済みであること。
 * 2. `adb push build/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /data/local/tmp/`を
 *    実行済みであること（本クラスはこの固定パスから、`ModelStorage`の本番配置規約
 *    〔`noBackupFilesDir/models/<id>.litertlm`、ADR-0053〕へコピーする）。
 *
 * 実行方法:
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.probe.LiteRtLmAdapterE2EProbeTest`
 *
 * 結果はLogcat（TAG=P7C5_E2E）へ出力する。カレンダー実データ・個人情報はプロンプトに
 * 一切含めない（合成の汎用予定タイトルのみ）。
 *
 * **cleanup**: `finally`で(1) コピーしたモデルファイルをアプリ内部ストレージから削除、
 * (2) `AiPreferencesImpl.aiEnabled`を実行前の状態（既定`false`）へ復元する。
 * `/data/local/tmp/`へpushした328MBファイル自体は本クラスの対象外（アプリのストレージでは
 * ないため放置してもアプリ動作に影響しないが、`adb shell rm /data/local/tmp/<filename>`で
 * 手動削除可能。実行者向けメモとして明記）。
 *
 * **実測済み（2026-08-10、AVD `actionstarter_test` x86_64/API35、3メソッドとも実行済み）**:
 *
 * 1. [probeAdapterThroughGateway_defaultCatalog]（`maxNumTokens`=256・本番`peakRamBytes`=2,890MB
 *    そのまま）: 3件とも`Fallback(OUT_OF_MEMORY_PREVENTED)`、`wallMs`はいずれも1秒未満で
 *    **推論を一度も開始せず**安全側に停止した（§8.6 #7主防御が意図どおり機能）。原因は
 *    `ModelCatalogEntry.peakRamBytes`がコンテキスト長非依存の単一値（フルコンテキスト実測値）
 *    であり、実際に使われる小コンテキスト・テストプロファイルの実要求量とは無関係に判定される
 *    構造にあるため（P7-C6/C8への申し送り、下記参照）。
 * 2. [probeAdapterThroughGateway_smallContextProfile]（`maxNumTokens`=256のまま・`peakRamBytes`
 *    fixtureを1GiBへ下げてOOMガードのみ迂回）: OOMガードは通過したが、3件とも
 *    `Fallback(UNKNOWN)`、detail=`LiteRtLmJniException: Failed to call nativeSendMessage:
 *    FAILED_PRECONDITION: Chosen prefill work group size exceeds available state entries (73).`
 *    ——本番`PlanPromptBuilder.buildSystemInstruction`＋`buildFewShot`（既定2-shot）＋`build`
 *    （data message）を合算した実プロンプトが、`maxNumTokens`=256のコンテキスト予算を
 *    **prefill時点で**超過しネイティブ層が例外を送出した。P7-C0のプローブはsystem
 *    instruction・few-shotを一切使わない単発の短い日本語文だけで検証していたため、この
 *    超過は基盤計画・品質ハーネスのいずれにも実測記録がなかった新規発見である。
 * 3. [probeAdapterThroughGateway_widerContextDiagnostic]（`maxNumTokens`=1024へ引き上げ・
 *    `peakRamBytes`fixture=1.25GiB）: **3件とも`AiResult.Success`**（`schemaValid=true`・
 *    `sanityPassed=true`）。実測値: `modelLoadMs`=4,073ms（1件目のみ、2・3件目は`0`＝
 *    Engine再利用がR-7/T-GW-16どおり機能）、`firstTokenMs`=1,534〜1,878ms、
 *    `tokensPerSecond`=25.7〜35.2、`peakNativeHeapBytes`=約536〜545MB、`totalMs`=5,326〜
 *    13,265ms（1件目はEngineロード込み、2・3件目はロードなしで5〜7秒）。生成された
 *    `display_text`（実例、Basic版`step_title_preparation`="出かける準備をする"との比較は
 *    本体タスクの最終報告参照）: 歯科検診→`action_type=prepare_items`
 *    `display_text="歯科検診に手順を計らる"`（予定名に反応しているが文法がやや不自然）、
 *    友人の結婚式→`action_type=commute` `display_text="結婚式に参加する"`（自然で予定固有）、
 *    チームMTG→`action_type=prepare_items` `display_text="チームMTGの準備"`（タイトルが
 *    ほぼそのまま出現するがContentSanityCheckerのコピー閾値0.8を僅かに下回り合格＝
 *    occupancyRatio 6/8=0.75）。**3件とも`steps`は1件のみ**（few-shot例が示す3ステップ構成
 *    より少ない。既定`SamplingPolicy.Primary`＝実質greedyの保守性・0.6Bモデルの限界の
 *    いずれかが疑われるが本実測（n=3）だけでは断定できない、P7-C8実測での確認事項として
 *    申し送る）。
 *
 * **P7-C6/C8への申し送り（本実測が明らかにした構造的な知見）**:
 * - `ModelCatalogEntry.peakRamBytes`をコンテキストプロファイル（`maxNumTokens`）非依存の
 *   単一値のまま本番`AppContainer`配線（`DEFAULT_MAX_NUM_TOKENS`=256のまま）で使うと、
 *   Galaxy A実機でも同種の過大なOOM事前ガード判定が起きうる。プロファイル別
 *   `peakRamBytes`を持たせるか、`maxNumTokens`を`ModelCatalogEntry`から実行時に導出する設計の
 *   要否をP7-C8実機実測（§11.3）と合わせて検討すること。
 * - **`DEFAULT_MAX_NUM_TOKENS`=256（P7-C1が定めた暫定値）は、本番の`PlanPromptBuilder`
 *   （system instruction＋既定2-shot few-shot＋data message）と組み合わせるとネイティブ層の
 *   `FAILED_PRECONDITION`で推論そのものが失敗することを実機で確認した。**現状の
 *   `AppContainer`配線（既定値のまま）は、AI有効化後にこのままでは機能しない。`maxNumTokens`の
 *   引き上げ（本実測は1024で解決）、または`shotCount`を減らす（既定2→1／0、品質ハーネス§7が
 *   既に候補として挙げていた0-shot）の少なくとも一方が必要。最終値はP7-C8のGalaxy A実測で
 *   確定すること（§11.2・§17 V-8の対象時点では想定されていなかった新規制約のため）。
 */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore(
    "probe専用（P7-C5実測、本ファイルKDoc「実測済み」参照）。328MBモデルのadb push前提かつ" +
        "実行に数十秒〜1分超を要するため、connectedDebugAndroidTest一括実行やG4-Eの対象に" +
        "含めないため意図的にIgnore。実測結果はクラスKDocおよびbuild/agent-logs/" +
        "p7c5-e2e-logcat*.logに転記済み。再実行する場合は@Ignoreを外すか、" +
        "-Pandroid.testInstrumentationRunnerArguments.classで本クラス（または" +
        "#メソッド名で個別メソッド）を直接指定し、事前にadb push " +
        "build/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /data/local/tmp/ を行う。"
)
class LiteRtLmAdapterE2EProbeTest {

    /**
     * 本番`ModelCatalog.QWEN3_0_6B_INT4_BLOCK32`（フルコンテキスト実測のpeakRamBytes=2,890MB）を
     * そのまま使う実行。クラスKDoc「実測済み」の1.のとおり、AVDの実メモリでは§8.6 #7の
     * OOM事前ガードに阻まれ推論本体へ到達しないことを実測確認する。
     */
    @Test
    fun probeAdapterThroughGateway_defaultCatalog() {
        runProbe(
            scenarioLabel = "default-catalog",
            entry = ModelCatalog.QWEN3_0_6B_INT4_BLOCK32,
            maxNumTokens = LiteRtLmLocalLanguageModel.DEFAULT_MAX_NUM_TOKENS
        )
    }

    /**
     * §11.2の小コンテキスト・テストプロファイル（`maxNumTokens`=256、`LiteRtLmLocalLanguageModel.
     * DEFAULT_MAX_NUM_TOKENS`）が実際に必要とするピークRAM（P7-C0実測でピークPSS約700〜775MB、
     * 本メソッドはそれに安全マージンを載せた[SMALL_CONTEXT_PROFILE_PEAK_RAM_BYTES]=1GiBを使う）を
     * 反映したfixtureカタログエントリでOOM事前ガードを通過させ、実推論・実生成テキストを観測する。
     * モデルファイル本体・SHA-256は本番`ModelCatalog.QWEN3_0_6B_INT4_BLOCK32`と同一（`copy`で
     * `peakRamBytes`のみ差し替え）であり、`ModelVerifierImpl`による本物の検証も経る。
     */
    @Test
    fun probeAdapterThroughGateway_smallContextProfile() {
        val smallContextEntry = ModelCatalog.QWEN3_0_6B_INT4_BLOCK32.copy(
            peakRamBytes = SMALL_CONTEXT_PROFILE_PEAK_RAM_BYTES
        )
        runProbe(scenarioLabel = "small-context-profile", entry = smallContextEntry, maxNumTokens = LiteRtLmLocalLanguageModel.DEFAULT_MAX_NUM_TOKENS)
    }

    /**
     * 診断用（1回目実行の発見への追試）: [probeAdapterThroughGateway_smallContextProfile]は
     * `maxNumTokens`=256（既定）で`LiteRtLmJniException: FAILED_PRECONDITION: Chosen prefill
     * work group size exceeds available state entries (73)`を実測した——実運用のsystem
     * instruction＋2-shot few-shot＋data messageの合計プロンプト長が256トークンの
     * コンテキスト予算を超えていることが原因と推定される。本メソッドは`maxNumTokens`を1024へ
     * 引き上げるだけで同一プロンプトが完走するかを切り分ける（**本番の`DEFAULT_MAX_NUM_TOKENS`は
     * 変更しない**——診断目的でこのテストファイル内でのみ大きい値を明示的に渡す）。
     * `peakRamBytes`fixtureも1.25GiBへ引き上げる（ctx256のP7-C0実測～750MB peak PSSより
     * 増える前提だが、AVD実測`MemAvailable`約2.3GBに対し安全マージンを残す値として選定）。
     */
    @Test
    fun probeAdapterThroughGateway_widerContextDiagnostic() {
        val widerContextEntry = ModelCatalog.QWEN3_0_6B_INT4_BLOCK32.copy(
            peakRamBytes = WIDER_CONTEXT_DIAGNOSTIC_PEAK_RAM_BYTES
        )
        runProbe(scenarioLabel = "wider-context-diagnostic", entry = widerContextEntry, maxNumTokens = WIDER_CONTEXT_DIAGNOSTIC_MAX_NUM_TOKENS)
    }

    private fun runProbe(scenarioLabel: String, entry: ModelCatalogEntry, maxNumTokens: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        logLine("===== P7-C5 adapter/gateway E2E probe start ($scenarioLabel) =====")

        val pushedModel = File(PUSHED_MODEL_PATH)
        if (!pushedModel.exists()) {
            fail(
                "PRECONDITION_FAILED: pushed model not found at $PUSHED_MODEL_PATH. " +
                    "Run: adb push build/models/$MODEL_FILE_NAME /data/local/tmp/ " +
                    "before executing this probe."
            )
            return
        }

        val storage = ModelStorageImpl(context, catalog = listOf(entry))
        val preferences = AiPreferencesImpl(
            context.getSharedPreferences(AiPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE)
        )
        val originalAiEnabled = preferences.aiEnabled

        try {
            installModel(storage, entry, pushedModel)
            preferences.aiEnabled = true

            val gateway = LocalAiGateway(
                model = LiteRtLmLocalLanguageModel(
                    modelPathProvider = { storage.installedModelPath()!! },
                    maxNumTokens = maxNumTokens
                ),
                modelStorage = storage,
                modelVerifier = ModelVerifierImpl(),
                deviceCapability = TierOverrideDeviceCapability(DeviceCapabilityImpl(context)),
                preferences = preferences
            )

            val scenarios = listOf(
                "dental-checkup" to sampleContext(title = "歯科検診", eventType = "medical"),
                "friend-wedding" to sampleContext(title = "友人の結婚式", eventType = "social"),
                "team-meeting" to sampleContext(title = "チームMTG", eventType = "business_meeting")
            )

            scenarios.forEach { (label, planningContext) ->
                runScenario(gateway, "$scenarioLabel/$label", planningContext)
            }
        } finally {
            runCatching { storage.delete(entry) }
                .onFailure { Log.w(TAG, "cleanup: failed to delete installed model file", it) }
            preferences.aiEnabled = originalAiEnabled
            logLine("CLEANUP modelFileDeleted=true aiEnabledRestoredTo=$originalAiEnabled")
            logLine("===== P7-C5 adapter/gateway E2E probe end ($scenarioLabel) =====")
        }
    }

    /** `adb push`済みファイルを`ModelStorage`の本番配置規約（ADR-0053）へコピーする。 */
    private fun installModel(storage: ModelStorageImpl, entry: ModelCatalogEntry, pushedModel: File) {
        val dest = storage.finalFile(entry)
        dest.parentFile?.mkdirs()
        val copyStartMs = System.currentTimeMillis()
        FileInputStream(pushedModel).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
        }
        val copyMs = System.currentTimeMillis() - copyStartMs
        logLine(
            "INSTALL_MODEL doneMs=$copyMs destPath=${dest.absolutePath} " +
                "destSizeBytes=${dest.length()} expectedBytes=${entry.sizeBytes} " +
                "sizeMatches=${dest.length() == entry.sizeBytes}"
        )
    }

    private fun runScenario(gateway: LocalAiGateway, label: String, planningContext: PlanningContext) {
        logLine("--- scenario[$label] title=\"${planningContext.event.title}\" start ---")
        val wallStartMs = System.currentTimeMillis()
        val result = try {
            runBlocking { gateway.generatePlan(planningContext) }
        } catch (t: Throwable) {
            // LocalAiGatewayの契約（§8.5）は「CancellationException以外は外へ出さない」。
            // ここへ到達する場合は契約違反であり、probeとして明示的にfailさせる。
            Log.e(TAG, "scenario[$label] generatePlan threw unexpectedly (contract violation)", t)
            fail("scenario[$label]: LocalAiGateway.generatePlan threw ${t::class.qualifiedName}: ${t.message}")
            return
        }
        val wallMs = System.currentTimeMillis() - wallStartMs

        when (result) {
            is AiResult.Success -> {
                val response = result.value
                val metrics = result.metrics
                logLine(
                    "scenario[$label] RESULT=Success wallMs=$wallMs eventType=${response.eventType} " +
                        "stepCount=${response.steps.size} retried=${metrics.retried} " +
                        "modelLoadMs=${metrics.modelLoadMs} firstTokenMs=${metrics.firstTokenMs} " +
                        "tokensPerSecond=${metrics.tokensPerSecond} outputTokens=${metrics.outputTokens} " +
                        "peakNativeHeapBytes=${metrics.peakNativeHeapBytes} totalMs=${metrics.totalMs} " +
                        "schemaValid=${metrics.schemaValid} sanityPassed=${metrics.sanityPassed}"
                )
                response.steps.forEachIndexed { index, step ->
                    logLine(
                        "scenario[$label] STEP[$index] actionType=${step.actionType} " +
                            "displayText=\"${step.displayText}\" displayTextLength=${step.displayText.length}"
                    )
                }
            }

            is AiResult.Fallback -> {
                logLine(
                    "scenario[$label] RESULT=Fallback wallMs=$wallMs reason=${result.reason} " +
                        "detail=${result.detail}"
                )
            }
        }
        logLine("--- scenario[$label] end ---")
    }

    private fun sampleContext(title: String, eventType: String): PlanningContext {
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = title,
            notes = null,
            startDate = Instant.parse("2026-08-10T10:00:00Z"),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "p7c5-probe", displayName = "P7-C5 Probe Calendar")
        )
        return PlanningContext(
            event = event,
            now = Instant.parse("2026-08-10T08:00:00Z"),
            zoneId = ZoneId.of("Asia/Tokyo"),
            locale = Locale.JAPAN,
            transportMode = TransportMode.WALKING,
            travelEstimate = null,
            arrivalBuffer = Duration.ofMinutes(10),
            profile = null
        ).also {
            // eventTypeはプロンプトへ直接埋め込まれないためログにのみ残す（PlanPromptBuilder.build
            // はcategoryを受け取らない設計、P7-C3実装済み）。シナリオラベル用の参考情報。
            logLine("sampleContext title=\"$title\" intendedEventType=$eventType (reference only)")
        }
    }

    /**
     * [DeviceCapability.classify]のみ固定し、それ以外は[delegate]（実値）へ委譲するテスト用
     * ラッパー。クラスKDoc「`DeviceCapability`の意図的なラップ」参照。
     */
    private class TierOverrideDeviceCapability(private val delegate: DeviceCapability) : DeviceCapability {
        override fun classify(): DeviceTier = DeviceTier.TIER_1_STANDARD
        override fun isAbiSupported(): Boolean = delegate.isAbiSupported()
        override fun hasAvailableMemory(requiredBytes: Long): Boolean = delegate.hasAvailableMemory(requiredBytes)
    }

    private fun logLine(message: String) {
        Log.e(TAG, message)
    }

    private companion object {
        const val TAG = "P7C5_E2E"
        const val MODEL_FILE_NAME = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
        const val PUSHED_MODEL_PATH = "/data/local/tmp/$MODEL_FILE_NAME"

        /**
         * §11.2小コンテキスト・テストプロファイル（`maxNumTokens`=256）が実際に要するピークRAMの
         * fixture値。P7-C0実測（`LiteRtLmProbeTest`、ctx128/256、`ActivityManager.
         * getProcessMemoryInfo().totalPss`ピーク約700〜775KB…KB表記で724,384〜774,245KB＝
         * 約708〜756MB）に安全マージンを載せて1GiBへ切り上げた（§11.2「ピークネイティブRAMを
         * 1GB級に抑えた」という表現とも整合）。本番`ModelCatalog.QWEN3_0_6B_INT4_BLOCK32`の
         * `peakRamBytes`（2,890MB、フルコンテキスト実測）とは別物であることを明示するための
         * fixture専用定数。
         */
        const val SMALL_CONTEXT_PROFILE_PEAK_RAM_BYTES = 1L * 1024 * 1024 * 1024

        /** [probeAdapterThroughGateway_widerContextDiagnostic]のKDoc参照。 */
        const val WIDER_CONTEXT_DIAGNOSTIC_MAX_NUM_TOKENS = 1024
        const val WIDER_CONTEXT_DIAGNOSTIC_PEAK_RAM_BYTES = 1_342_177_280L // 1.25GiB
    }
}
