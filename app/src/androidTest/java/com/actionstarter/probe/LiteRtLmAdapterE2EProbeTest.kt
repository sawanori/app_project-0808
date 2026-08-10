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
 *
 * **P7-C5b実測（2026-08-10、AVD `actionstarter_test` x86_64/API35。ADR-0057の基盤バグ修正後、
 * `build/agent-logs/p7c5b-e2e.log`）**: 上記の申し送り2件（`peakRamBytes`プロファイル依存・
 * `DEFAULT_MAX_NUM_TOKENS`=256）をADR-0057で是正した後の実測。詳細な数値・display_text
 * 前後比較表は本体タスクの最終報告参照。ここでは本ファイルの実行結果のみ記録する。
 *
 * 1. [probeAdapterThroughGateway_productionDefaultsAfterFix]（**`.copy()`等の手動迂回を一切
 *    使わない、`ModelCatalog.QWEN3_0_6B_INT4_BLOCK32`と`LiteRtLmLocalLanguageModel`の完全な
 *    既定値のみ**で5シナリオ実行）: 5件中4件`AiResult.Success`・1件`Fallback(SCHEMA_INVALID)`
 *    （friend-birthday-party、`ContentSanityChecker`のtitleコピー検出が両attempt分とも作動し
 *    Basicへ安全に降格——**捏造/コピー検出の安全網が実際に機能した観測事例**）。P7-C5の3メソッド
 *    が必要としていた`maxNumTokens`／`peakRamBytes`の手動オーバーライドが**一切不要**になった
 *    こと自体が、Part A基盤バグ修正の直接的な実機証拠である。
 * 2. [probeAdapterThroughGateway_shotCount0]・[probeAdapterThroughGateway_shotCount2]・
 *    [probeAdapterThroughGateway_shotCount3]（品質ハーネスQH-16、各々**独立プロセス**として
 *    実行——理由は[runShotCountComparisonScenario]のKDoc参照）: shotCount=0は3件中1件が
 *    `Fallback(SCHEMA_INVALID、locale不一致)`で2回とも再現、shotCount=2/3の2回目実行
 *    （独立プロセス版）はいずれも3件ともSuccess。`peakNativeHeapBytes`はshotCount 0→2→3で
 *    約558MB→624MB→723〜742MBと単調増加、`tokensPerSecond`はshotCount=3でのみ明確に低下
 *    （21〜26 vs 0/2の26〜32）——**shotCountを上げるほど遅く・重くなるという直感どおりの
 *    トレードオフを実測で確認**。品質面はshotCountで明確な優劣がつかず（0はdisplay_textの
 *    語句重複という固有の欠陥あり、2/3は概ね同水準）、**「チームMTG」入力がshotCount=0/2/3の
 *    いずれでも不安定**（0=locale不一致でFallback、2=無関係な「歯科検診」関連文言を生成、
 *    3=「歯科検診」を4文字だけ生成しeventTypeも誤分類のうえretry発生）という共通の弱点を
 *    横断的に確認した——0.6Bモデルの限界がshotCount調整だけでは解消しないことの実測証拠
 *    （本体タスク最終報告の「0.6Bの限界に関する正直な所見」参照）。
 */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore(
    "probe専用（P7-C5・P7-C5b実測、本ファイルKDoc「実測済み」参照）。328MBモデルのadb push前提" +
        "かつ実行に数十秒〜数分を要するため、connectedDebugAndroidTest一括実行やG4-Eの対象に" +
        "含めないため意図的にIgnore。実測結果はクラスKDocおよびbuild/agent-logs/" +
        "p7c5-e2e-logcat*.log・p7c5b-e2e*.logに転記済み。再実行する場合は@Ignoreを外すか、" +
        "-Pandroid.testInstrumentationRunnerArguments.classで本クラス（または" +
        "#メソッド名で個別メソッド）を直接指定し、事前にadb push " +
        "build/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /data/local/tmp/ を行う。" +
        "shotCount比較3メソッドはプロセス分離のため必ず1メソッドずつ個別に指定して実行すること" +
        "（[runShotCountComparisonScenario]のKDoc参照）。"
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

    /**
     * P7-C5と同一の3件（[com.actionstarter.probe.LiteRtLmAdapterE2EProbeTest]クラスKDoc
     * 「実測済み」参照）。改善前後比較の基準点として固定する。
     */
    private fun originalThreeScenarios(): List<Pair<String, PlanningContext>> = listOf(
        "dental-checkup" to sampleContext(title = "歯科検診", eventType = "medical"),
        "friend-wedding" to sampleContext(title = "友人の結婚式", eventType = "social"),
        "team-meeting" to sampleContext(title = "チームMTG", eventType = "business_meeting")
    )

    /**
     * P7-C5b（本体タスク「C. エミュ実機で改善前後比較」）向けに追加した2件。few-shot模範
     * （[com.actionstarter.ai.prompt.PlanPromptBuilder]の「出張」「結婚式」例）と**意図的に
     * 異なる具体的な言い回し**にし、模範の丸暗記ではなく汎化できているかを見る
     * （「出張」ではなく「大阪出張」、「結婚式」ではなく「友人の誕生日会」）。
     */
    private fun additionalP7C5bScenarios(): List<Pair<String, PlanningContext>> = listOf(
        "osaka-business-trip" to sampleContext(title = "大阪出張", eventType = "travel"),
        "friend-birthday-party" to sampleContext(title = "友人の誕生日会", eventType = "social")
    )

    private fun fiveScenarios(): List<Pair<String, PlanningContext>> =
        originalThreeScenarios() + additionalP7C5bScenarios()

    /**
     * @param shotCount ADR-0057・QH-16（品質ハーネス§7）。[LiteRtLmLocalLanguageModel]へ渡す
     *   few-shot件数。既定は[PlanPromptBuilder.DEFAULT_SHOT_COUNT]（本番と同一構成）。
     * @param scenarios 実行する予定シナリオ一覧（既定[originalThreeScenarios]、P7-C5との
     *   直接比較用）。
     */
    private fun runProbe(
        scenarioLabel: String,
        entry: ModelCatalogEntry,
        maxNumTokens: Int,
        shotCount: Int = PlanPromptBuilder.DEFAULT_SHOT_COUNT,
        scenarios: List<Pair<String, PlanningContext>> = originalThreeScenarios()
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        logLine("===== P7-C5b adapter/gateway E2E probe start ($scenarioLabel, shotCount=$shotCount, maxNumTokens=$maxNumTokens) =====")

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
                    shotCount = shotCount,
                    maxNumTokens = maxNumTokens
                ),
                modelStorage = storage,
                modelVerifier = ModelVerifierImpl(),
                deviceCapability = TierOverrideDeviceCapability(DeviceCapabilityImpl(context)),
                preferences = preferences
            )

            scenarios.forEach { (label, planningContext) ->
                runScenario(gateway, "$scenarioLabel/$label", planningContext)
            }
        } finally {
            runCatching { storage.delete(entry) }
                .onFailure { Log.w(TAG, "cleanup: failed to delete installed model file", it) }
            preferences.aiEnabled = originalAiEnabled
            logLine("CLEANUP modelFileDeleted=true aiEnabledRestoredTo=$originalAiEnabled")
            logLine("===== P7-C5b adapter/gateway E2E probe end ($scenarioLabel) =====")
        }
    }

    /**
     * P7-C5b（本体タスクA・C）: **基盤バグ修正後、production defaultsをそのまま使って**
     * （`ModelCatalog.QWEN3_0_6B_INT4_BLOCK32`を`.copy()`せず、`LiteRtLmLocalLanguageModel`も
     * `maxNumTokens`／`shotCount`を明示指定せず既定値のまま）5シナリオを実行する。P7-C5の
     * 3メソッド（[probeAdapterThroughGateway_defaultCatalog]・
     * [probeAdapterThroughGateway_smallContextProfile]・
     * [probeAdapterThroughGateway_widerContextDiagnostic]）はいずれも本番既定値256のままでは
     * 動かず、fixtureで`maxNumTokens`／`peakRamBytes`を手動で迂回する必要があった
     * （クラスKDoc「実測済み」参照）。本メソッドはADR-0057の是正後、**その手動迂回が一切不要に
     * なったこと自体を実証する**（=Part Aの基盤バグ修正が効いていることの直接証拠）。
     */
    @Test
    fun probeAdapterThroughGateway_productionDefaultsAfterFix() {
        runProbe(
            scenarioLabel = "production-defaults-after-fix",
            entry = ModelCatalog.QWEN3_0_6B_INT4_BLOCK32,
            maxNumTokens = LiteRtLmLocalLanguageModel.DEFAULT_MAX_NUM_TOKENS,
            scenarios = fiveScenarios()
        )
    }

    /**
     * P7-C5b（本体タスクC・品質ハーネスQH-16）: shotCount 0/2/3での品質×TTFT比較用の共通実装。
     * [probeAdapterThroughGateway_shotCount0]・[probeAdapterThroughGateway_shotCount2]・
     * [probeAdapterThroughGateway_shotCount3]それぞれから1回のGradle/adb起動＝**独立した
     * アプリプロセス**として呼ばれる（3メソッドを1メソッド内でforEachループにして同一プロセス内
     * で連続実行する設計を最初に試したが、各shotCountが独立した`LiteRtLmLocalLanguageModel`
     * インスタンス＝独立した`Engine`を持つにもかかわらず、**どのEngineも明示的にunloadしない**
     * ため2つ目・3つ目のEngine生成時点で1つ目のEngineがまだプロセスのネイティブヒープを
     * 占有したまま残り、`peakNativeHeapBytes`にその蓄積分が混入して過大に出ることを実測で
     * 発見した——本番は`AppContainer`の`by lazy`によりEngineが1個しか生成されないため
     * この蓄積は起きないが、本テストの単一プロセス内3連続構成ではプロセス全体のOOM事前ガードが
     * 誤って発動してしまう。**独立プロセス化がこの蓄積を構造的に断つ**ため本設計へ変更した。
     */
    private fun runShotCountComparisonScenario(shotCount: Int) {
        // 既定式（LiteRtLmLocalLanguageModelのコンストラクタのデフォルト式）と同じ計算をここでも
        // 使う。デフォルト式自体は`private`引数の初期化式であり外から読めないため、テスト側で
        // 明示指定する必要がある（PlanPromptBuilderの200＝LiteRtLmLocalLanguageModel.
        // MAX_OUTPUT_TOKENの複製値、両者のKDoc参照）。
        val maxNumTokensForShotCount = PlanPromptBuilder().estimateMaxNumTokens(
            shotCount = shotCount,
            maxOutputToken = 200
        )
        runProbe(
            scenarioLabel = "shotcount-comparison-shots$shotCount",
            entry = ModelCatalog.QWEN3_0_6B_INT4_BLOCK32,
            maxNumTokens = maxNumTokensForShotCount,
            shotCount = shotCount,
            scenarios = originalThreeScenarios()
        )
    }

    /** shotCount=0（few-shotなし、systemInstructionのみ）。[runShotCountComparisonScenario]参照。 */
    @Test
    fun probeAdapterThroughGateway_shotCount0() = runShotCountComparisonScenario(shotCount = 0)

    /** shotCount=2（既定、本番と同一構成）。[runShotCountComparisonScenario]参照。 */
    @Test
    fun probeAdapterThroughGateway_shotCount2() =
        runShotCountComparisonScenario(shotCount = PlanPromptBuilder.DEFAULT_SHOT_COUNT)

    /** shotCount=3（品質ハーネス§7の上限候補）。[runShotCountComparisonScenario]参照。 */
    @Test
    fun probeAdapterThroughGateway_shotCount3() = runShotCountComparisonScenario(shotCount = 3)

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
