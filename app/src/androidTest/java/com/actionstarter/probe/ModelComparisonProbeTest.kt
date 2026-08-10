package com.actionstarter.probe

import android.app.ActivityManager
import android.content.Context
import android.os.Process
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
import com.actionstarter.ai.prompt.PlanPromptBuilder
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * probe専用・正式テストではない（[LiteRtLmAdapterE2EProbeTest]と同型: `@Ignore`既定・Log出力・
 * 目的と測定対象をKDocに明記）。
 *
 * **位置づけ（P7-C8、計画書§11.3・§14 P7-C8・本体タスク指示）**: P7-C5b（[LiteRtLmAdapterE2EProbeTest]
 * クラスKDoc「実測済み」・計画書§14.9）がQwen3-0.6Bで実測した「文法は改善できたが、few-shotが示す
 * 具体性〔ご祝儀・保険証級〕には到達しなかった」という品質天井が、より大きいモデル
 * （Qwen3-1.7B・Gemma4-E2B）で解消するかを実機（AVD、RAM 4096MB）で比較する。**Qwen3-0.6Bは
 * 本ファイルでは再実行しない**——[LiteRtLmAdapterE2EProbeTest.probeAdapterThroughGateway_productionDefaultsAfterFix]
 * が同一5シナリオ・同一production defaults設定で既に実測済み（`build/agent-logs/p7c5b-e2e.log`
 * SECTION 1）であり、本ファイルの2モデルはこれと横並び比較するために**同一の5シナリオ・
 * 同一のPlanningContext構築ロジック**を使う。
 *
 * **測定対象は[LiteRtLmAdapterE2EProbeTest]と同一**（`LocalAiGateway.generatePlan()`をGateway
 * 経由・3段検証パイプライン込みで実行、`AiMetrics`実測値、実際の`display_text`）に加え、本ファイルは
 * **`ActivityManager.getProcessMemoryInfo`ベースのPSSピークサンプリング**（[PssPeakSampler]）を
 * 新設する。理由: 本番[LiteRtLmLocalLanguageModel]が内蔵する`peakNativeHeapBytes`実測は
 * `Debug.getNativeHeapAllocatedSize()`（bionic mallocアリーナの割当量）ベースであり、LiteRT-LMが
 * モデル重みをmmapで読み込む場合、mmapされたページはmallocアリーナに現れずこの指標に反映されない
 * 可能性がある（本体タスク指示）。[PssPeakSampler]はプロセス全体のPSS（`totalPss`）を定期
 * サンプリングすることでmmap分を含めた真の物理メモリ使用量ピークを捕捉する。両指標を併記し、
 * 乖離があればそれ自体をmmap使用の傍証として報告する。
 *
 * **プロセス分離の必要性（[LiteRtLmAdapterE2EProbeTest.runShotCountComparisonScenario]のKDocと
 * 同一理由）**: 本クラスの各`@Test`メソッドは独立した`Engine`（プロセス内高々1個の遅延
 * シングルトン、R-7）を生成する。同一プロセス内で複数メソッドを連続実行すると、先に生成された
 * Engineがunloadされないまま後続Engineの生成とメモリを奪い合い、`peakNativeHeapBytes`／PSS
 * 実測値に蓄積分が混入し、Gemma4-E2B（2.59GBファイル）のような大きいモデルでは無関係な
 * `OUT_OF_MEMORY_PREVENTED`を誤発動しうる。**各`@Test`メソッドは必ず個別のGradle/adb起動
 * （`-Pandroid.testInstrumentationRunnerArguments.class=...ModelComparisonProbeTest#メソッド名`）
 * で1つずつ実行すること**。
 *
 * **モデルファイルの取り扱い（ディスク容量への配慮）**: Qwen3-1.7B（932MiB）・Gemma4-E2B
 * （2.59GB）を同時にAVDへpushしない。1モデルずつ「push→本クラスの該当メソッド実行→
 * `adb shell rm /data/local/tmp/<file>`」の順で処理すること（AVD `/data`空き容量は実測約5.9GB、
 * pushしたファイルとアプリ内部への実コピーが同時に存在する間はモデル1個分の約2倍のディスクを
 * 一時的に消費するため、Gemma4-E2Bで特に余裕がない）。
 *
 * 前提（実行前に手動で満たすこと。本ファイル単体では満たせない）:
 * 1. ホスト側`build/models/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm`・
 *    `build/models/gemma-4-E2B-it.litertlm`を用意済みであること（P7-C8実測でDL・SHA-256照合済み、
 *    本体タスク最終報告参照）。
 * 2. 実行するメソッドに応じて対応するモデルファイルのみを
 *    `adb push build/models/<file> /data/local/tmp/`済みであること。
 *
 * 実行方法（メソッドごとに個別実行）:
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.probe.ModelComparisonProbeTest#probeQwen17B_productionDefaults`
 *
 * 結果はLogcat（TAG=P7C8_CMP）へ出力する。カレンダー実データ・個人情報はプロンプトに一切
 * 含めない（[LiteRtLmAdapterE2EProbeTest]と同一の合成予定5件を使う）。
 *
 * **cleanup**: `finally`で(1) コピーしたモデルファイルをアプリ内部ストレージから削除、
 * (2) `AiPreferencesImpl.aiEnabled`を実行前の状態へ復元する。`/data/local/tmp/`へpushした
 * ファイル自体は本クラスの対象外（クラスKDoc「モデルファイルの取り扱い」のとおり、実行者が
 * `adb shell rm`で削除する）。
 *
 * **実測済み（2026-08-10、AVD `actionstarter_test` x86_64/API35、RAM 4096MB。3メソッドとも実行済み）**:
 *
 * 1. [probeQwen17B_productionDefaults]: 5シナリオ全件`AiResult.Success`（Fallback 0件）。
 *    `tokensPerSecond`=12.9〜16.6（Qwen3-0.6B実測25〜32より明確に遅い）、`firstTokenMs`=
 *    4,423〜5,249ms（0.6Bの1,552〜2,460msより明確に遅い）、`modelLoadMs`=4,620ms（1件目のみ）、
 *    `peakNativeHeapBytes`=712〜750MB、**`PssPeakSampler`実測ピークPSS=1,945,677,824バイト
 *    （約1.81GiB）**——`peakNativeHeapBytes`との乖離が約1.2GBあり、mallocアリーナに現れない
 *    mmap分の存在を強く示唆する。品質面は深刻: greedy（`SamplingPolicy.Primary`＝`topK=1`,
 *    `temperature=0.0`）が同一プロンプト構造に対して収束しやすいためか、friend-wedding・
 *    team-meetingの2件が**全く無関係な同一文言**`displayText="歯科検診の準備"`
 *    （dental-checkupの話題）を出力した。dental-checkup自身は`"歯科検診用のスケールを準備"`
 *    （「スケール」の語選択が不自然）、osaka-business-tripは`"出張先の資料の準備"`
 *    （具体的で妥当）、friend-birthday-partyは`"友人を歓迎する"`（当たり障りない）。
 * 2. [probeGemma4E2B_reducedContext]（`shotCount=1`・`maxNumTokens=1024`、production defaults
 *    実行前の安全側の初回試行）: **AVD 4096MBでOOM・クラッシュとも発生せず完走**。5シナリオ中
 *    2件`Success`（dental-checkup: `prepare_items`「検診に必要な書類を準備する」＋
 *    `leave`「歯科医院へ向かう」の2ステップ／friend-birthday-party:
 *    `prepare_items`「飲み物を準備する」＋`leave`「会場へ向かう」の2ステップ、後者は1回retry）、
 *    3件`Fallback(SCHEMA_INVALID)`「Duplicate action_type detected across steps」
 *    （friend-wedding・team-meeting・osaka-business-trip、いずれも3ステップ中`prepare_items`が
 *    2回出現しContentSanityCheckerが検出）。`PssPeakSampler`実測ピークPSS=1,972,925,440バイト。
 * 3. [probeGemma4E2B_productionDefaults]（`shotCount=2`、他モデルと同一条件）: **5シナリオ全件
 *    `AiResult.Success`（Fallback 0件、retried=trueが2件）**。全件2〜3ステップの複数ステップ
 *    構成（0.6B/1.7Bはほぼ全件1ステップに収束していたのと対照的）。dental-checkup→
 *    `prepare_items`「保険証を持って行く」（few-shot模範「保険証を持って出る」とほぼ同水準の
 *    具体性）、friend-wedding→`prepare_items`「招待状を確認する」（模範の「ご祝儀」とは異なるが
 *    独自に妥当な具体物）、team-meeting→`prepare_items`「資料を準備する」（**0.6B/1.7Bで横断的に
 *    観測された無関係文言・誤分類が一切発生せず**）、osaka-business-trip→`prepare_items`
 *    「出張に必要な書類をまとめる」、friend-birthday-party→`prepare_items`「手土産を用意する」。
 *    `tokensPerSecond`=22.8〜25.5（Qwen3-0.6Bと同水準、Qwen3-1.7Bより明確に速い）、
 *    `firstTokenMs`=1,567〜2,043ms（0.6Bと同水準）、`peakNativeHeapBytes`=約641〜642MB、
 *    **`PssPeakSampler`実測ピークPSS=1,980,168,192バイト**（reduced contextとほぼ同一、
 *    shotCount差によるプロンプト長差より常駐重みページの方が支配的であることを示唆）。
 *    HFモデルカード記載のSamsung S26 Ultra CPU実測（ピーク約1,733MB）と近い値。
 *
 * 詳細な比較表・品質評価・「Gemma4-E2Bの公式8GB宣言」の検証結果は本体タスク最終報告・
 * 計画書§14.10（P7-C8）参照。
 *
 * **再実行時の注意（実機実測で発見した罠）**: AndroidJUnitRunnerは、クラスに`@Ignore`が付与
 * されている状態で`-Pandroid.testInstrumentationRunnerArguments.class=...#メソッド名`により
 * 特定メソッドを個別指定しても、**discoveryの時点でクラスごと除外され「Starting 0 tests」に
 * なる**ことを実機で確認した（[LiteRtLmAdapterE2EProbeTest]のKDocが「@Ignoreを外すか、
 * classで直接指定し」と両者を独立した代替手段のように書いているが、少なくとも本プロジェクトの
 * AGP/AndroidX Testバージョンでは後者単独では機能しない）。**再実行する場合は本クラスの
 * `@Ignore`を一時的にコメントアウトし、実行後に必ず復元すること**（`installDebug`／
 * `installDebugAndroidTest`タスクでのAPK再インストールも、`connectedDebugAndroidTest`自体が
 * 0件のときは実行されないため別途必要になる場合がある）。
 */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore(
    "probe専用（P7-C8モデル比較、実測済み・クラスKDoc「実測済み」参照）。932MB〜2.59GBのモデルの" +
        "adb push前提かつ実行に数十秒〜数分を要するため、connectedDebugAndroidTest一括実行やG4-Eの" +
        "対象に含めないため意図的にIgnore。実行方法・プロセス分離の必要性・再実行時の罠は" +
        "クラスKDoc参照。再実行する場合は本クラスの@Ignoreを一時的にコメントアウトしたうえで" +
        "-Pandroid.testInstrumentationRunnerArguments.classで本クラス#メソッド名を直接指定し、" +
        "事前に対応するモデルファイルのみをadb pushすること（クラスKDoc「再実行時の注意」参照、" +
        "@Ignoreを残したままでは0 testsになる）。"
)
class ModelComparisonProbeTest {

    /**
     * P7-C8メイン実測: Qwen3-1.7B・production defaults（`LiteRtLmLocalLanguageModel`の
     * `shotCount`／`maxNumTokens`いずれも明示指定せず既定値のまま、[LiteRtLmAdapterE2EProbeTest.
     * probeAdapterThroughGateway_productionDefaultsAfterFix]のQwen3-0.6Bと同一設定）。
     * OOM事前ガードのfixture値は[DIAGNOSTIC_PEAK_RAM_BYTES_TO_PASS_GUARD]（実測前の暫定値、
     * ガードを通過させ実推論を試行させることだけが目的で、真のピークRAMの主張ではない
     * ——[LiteRtLmAdapterE2EProbeTest.probeAdapterThroughGateway_widerContextDiagnostic]と
     * 同じ診断手法）。実測後の正式値は`ModelCatalog.QWEN3_1_7B_INT4_BLOCK32.
     * defaultProfilePeakRamBytes`へ反映する。
     */
    @Test
    fun probeQwen17B_productionDefaults() {
        val entry = ModelCatalog.QWEN3_1_7B_INT4_BLOCK32.copy(
            defaultProfilePeakRamBytes = DIAGNOSTIC_PEAK_RAM_BYTES_TO_PASS_GUARD
        )
        runProbe(
            scenarioLabel = "qwen17b-production-defaults",
            entry = entry,
            pushedModelFileName = QWEN17B_MODEL_FILE_NAME,
            maxNumTokens = null,
            shotCount = null
        )
    }

    /**
     * P7-C8メイン実測: Gemma4-E2B・production defaults（Qwen系と同一のshotCount=2で横並び
     * 比較する。本体タスク指示は「動かない場合は小コンテキストプロファイルで試行」であり、
     * まず他モデルと同一条件で試すことがOOM可否の最も直接的な実測になるため、本メソッドを
     * 最初に試す。HFモデルカードのSamsung S26 Ultra CPU実測ピーク約1,733MBが目安だが、
     * 端末条件が異なる（AVD RAM 4096MB）ためそのまま成立するとは限らない
     * （[com.actionstarter.ai.model.ModelCatalog.GEMMA_4_E2B_IT]のKDoc参照）。
     * OOMガード・native OOM・クラッシュいずれで失敗してもその事実をそのまま記録する
     * （本体タスク指示）。失敗した場合は[probeGemma4E2B_reducedContext]を試すこと。
     */
    @Test
    fun probeGemma4E2B_productionDefaults() {
        val entry = ModelCatalog.GEMMA_4_E2B_IT.copy(
            defaultProfilePeakRamBytes = DIAGNOSTIC_PEAK_RAM_BYTES_TO_PASS_GUARD
        )
        runProbe(
            scenarioLabel = "gemma4e2b-production-defaults",
            entry = entry,
            pushedModelFileName = GEMMA4E2B_MODEL_FILE_NAME,
            maxNumTokens = null,
            shotCount = null
        )
    }

    /**
     * P7-C8フォールバック実測: Gemma4-E2B・小コンテキストプロファイル
     * （`shotCount`=[REDUCED_SHOT_COUNT]、`maxNumTokens`は[PlanPromptBuilder.estimateMaxNumTokens]
     * で同じshotCountに対して算出した値）。本体タスク指示どおり、[probeGemma4E2B_productionDefaults]
     * がOOM等で失敗した場合にのみ実行する診断メソッド。
     */
    @Test
    fun probeGemma4E2B_reducedContext() {
        val reducedMaxNumTokens = PlanPromptBuilder().estimateMaxNumTokens(
            shotCount = REDUCED_SHOT_COUNT,
            // LiteRtLmLocalLanguageModel.MAX_OUTPUT_TOKEN(200、private)と同じ値を明示複製する。
            // 理由はLiteRtLmAdapterE2EProbeTest.runShotCountComparisonScenarioのKDoc参照。
            maxOutputToken = 200
        )
        val entry = ModelCatalog.GEMMA_4_E2B_IT.copy(
            defaultProfilePeakRamBytes = DIAGNOSTIC_PEAK_RAM_BYTES_TO_PASS_GUARD
        )
        runProbe(
            scenarioLabel = "gemma4e2b-reduced-context",
            entry = entry,
            pushedModelFileName = GEMMA4E2B_MODEL_FILE_NAME,
            maxNumTokens = reducedMaxNumTokens,
            shotCount = REDUCED_SHOT_COUNT
        )
    }

    /**
     * [LiteRtLmAdapterE2EProbeTest.fiveScenarios]と完全に同一の5件（タイトル・startDate・
     * zoneId・locale・transportMode・arrivalBufferすべて一致）。P7-C5bのQwen3-0.6B実測
     * （`build/agent-logs/p7c5b-e2e.log`）と横並び比較するための固定基準点。
     */
    private fun fiveScenarios(): List<Pair<String, PlanningContext>> = listOf(
        "dental-checkup" to sampleContext(title = "歯科検診"),
        "friend-wedding" to sampleContext(title = "友人の結婚式"),
        "team-meeting" to sampleContext(title = "チームMTG"),
        "osaka-business-trip" to sampleContext(title = "大阪出張"),
        "friend-birthday-party" to sampleContext(title = "友人の誕生日会")
    )

    /**
     * @param maxNumTokens `null`なら[LiteRtLmLocalLanguageModel]の既定値（`shotCount`から自動算出）
     *   をそのまま使う（production defaults）。
     * @param shotCount `null`なら[PlanPromptBuilder.DEFAULT_SHOT_COUNT]（本番と同一）を使う。
     */
    private fun runProbe(
        scenarioLabel: String,
        entry: ModelCatalogEntry,
        pushedModelFileName: String,
        maxNumTokens: Int?,
        shotCount: Int?
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        logLine(
            "===== P7-C8 model comparison probe start ($scenarioLabel, modelId=${entry.id}, " +
                "shotCount=${shotCount ?: "default"}, maxNumTokens=${maxNumTokens ?: "default"}) ====="
        )
        logDeviceMemoryInfo(context)

        val pushedModel = File("$PUSHED_MODEL_DIR/$pushedModelFileName")
        if (!pushedModel.exists()) {
            fail(
                "PRECONDITION_FAILED: pushed model not found at ${pushedModel.absolutePath}. " +
                    "Run: adb push build/models/$pushedModelFileName $PUSHED_MODEL_DIR/ " +
                    "before executing this probe."
            )
            return
        }

        val storage = ModelStorageImpl(context, catalog = listOf(entry))
        val preferences = AiPreferencesImpl(
            context.getSharedPreferences(AiPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE)
        )
        val originalAiEnabled = preferences.aiEnabled
        val pssSampler = PssPeakSampler(context)

        try {
            installModel(storage, entry, pushedModel)
            preferences.aiEnabled = true

            val model = if (shotCount != null && maxNumTokens != null) {
                LiteRtLmLocalLanguageModel(
                    modelPathProvider = { storage.installedModelPath()!! },
                    shotCount = shotCount,
                    maxNumTokens = maxNumTokens
                )
            } else {
                LiteRtLmLocalLanguageModel(modelPathProvider = { storage.installedModelPath()!! })
            }

            val gateway = LocalAiGateway(
                model = model,
                modelStorage = storage,
                modelVerifier = ModelVerifierImpl(),
                deviceCapability = TierOverrideDeviceCapability(DeviceCapabilityImpl(context)),
                preferences = preferences
            )

            pssSampler.start()
            fiveScenarios().forEach { (label, planningContext) ->
                runScenario(gateway, "$scenarioLabel/$label", planningContext)
                logLine("PSS_RUNNING_PEAK scenario=$scenarioLabel afterLabel=$label peakPssBytes=${pssSampler.peakBytes()}")
            }
        } finally {
            pssSampler.stop()
            logLine("PSS_FINAL_PEAK scenario=$scenarioLabel peakPssBytes=${pssSampler.peakBytes()}")
            runCatching { storage.delete(entry) }
                .onFailure { Log.w(TAG, "cleanup: failed to delete installed model file", it) }
            preferences.aiEnabled = originalAiEnabled
            logLine("CLEANUP modelFileDeleted=true aiEnabledRestoredTo=$originalAiEnabled")
            logLine("===== P7-C8 model comparison probe end ($scenarioLabel) =====")
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
            // ここへ到達する場合は契約違反であり、probeとして明示的にfailさせる
            // （native OOMによるプロセスクラッシュはこのcatchより先にプロセス自体が落ちるため、
            // その場合はGradle/logcat側に痕跡が残る。本体タスク最終報告参照）。
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

    private fun sampleContext(title: String): PlanningContext {
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = title,
            notes = null,
            startDate = Instant.parse("2026-08-10T10:00:00Z"),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "p7c8-probe", displayName = "P7-C8 Probe Calendar")
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
        )
    }

    /** [ActivityManager.MemoryInfo]をログへ残す（§11.3端末情報記録の意図をAVDへ適用）。 */
    private fun logDeviceMemoryInfo(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        logLine(
            "DEVICE_MEMORY_INFO totalMem=${info.totalMem} availMem=${info.availMem} " +
                "lowMemory=${info.lowMemory} threshold=${info.threshold}"
        )
    }

    /**
     * [DeviceCapability.classify]のみ固定し、それ以外は[delegate]（実値）へ委譲するテスト用
     * ラッパー（[LiteRtLmAdapterE2EProbeTest.TierOverrideDeviceCapability]と同一意図、別ファイルの
     * `private class`は共有できないためここで複製する）。
     */
    private class TierOverrideDeviceCapability(private val delegate: DeviceCapability) : DeviceCapability {
        override fun classify(): DeviceTier = DeviceTier.TIER_1_STANDARD
        override fun isAbiSupported(): Boolean = delegate.isAbiSupported()
        override fun hasAvailableMemory(requiredBytes: Long): Boolean = delegate.hasAvailableMemory(requiredBytes)
    }

    /**
     * プロセス全体のPSS（`ActivityManager.getProcessMemoryInfo().totalPss`、KB単位）を
     * バックグラウンドスレッドで定期サンプリングしピークを保持する。クラスKDoc「測定対象」参照
     * ——`Debug.getNativeHeapAllocatedSize()`ベースの本番`peakNativeHeapBytes`が捕捉しない
     * mmap分を含めた真の物理メモリ使用量ピークを見るために新設した、[LiteRtLmLocalLanguageModel.
     * NativeHeapPeakSampler]と対をなすprobe専用サンプラ。
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

    private fun logLine(message: String) {
        Log.e(TAG, message)
    }

    private companion object {
        const val TAG = "P7C8_CMP"
        const val PUSHED_MODEL_DIR = "/data/local/tmp"
        const val QWEN17B_MODEL_FILE_NAME = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
        const val GEMMA4E2B_MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"

        /** [probeGemma4E2B_reducedContext]の`shotCount`。本体タスク指示「shotCount 0や1」の1側。 */
        const val REDUCED_SHOT_COUNT = 1

        /**
         * §8.6 #7 OOM事前ガードを通過させ実推論を試行させるためだけの診断用fixture値
         * （[LiteRtLmAdapterE2EProbeTest.WIDER_CONTEXT_DIAGNOSTIC_PEAK_RAM_BYTES]と同じ診断手法）。
         * AVD実測`availMem`（約2.5〜2.7GB）から安全マージン512MBを引いた値を明確に下回る
         * 1.3GiB(≈1,395,864,371バイト)を採用し、実際のピークRAM主張はしない——真のピークは
         * [PssPeakSampler]／`AiMetrics.peakNativeHeapBytes`の実測値を採用し、事後に
         * `ModelCatalog`の該当エントリへ反映する。
         */
        const val DIAGNOSTIC_PEAK_RAM_BYTES_TO_PASS_GUARD = 1_395_864_371L
    }
}
