package com.actionstarter.ai

import android.app.ActivityManager
import android.content.Context
import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceCapabilityImpl
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelLicense
import com.actionstarter.ai.model.ModelStorage
import com.actionstarter.ai.model.ModelStorageImpl
import com.actionstarter.ai.model.ModelVerificationResult
import com.actionstarter.ai.model.ModelVerifier
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * LLM生JSONフィクスチャ（Fable 5裁定1・3・ADR-0045: event_type + steps[action_type,
 * display_text]のみの縮小スキーマ。org.jsonで組み立てる——SchemaValidatorTestと同じ理由で
 * Robolectric下でも実クラスとして機能する）。ファイルスコープ関数にしてあるのは、
 * ネストしたクラス[LocalAiGatewayTest.ConcurrencyTrackingFakeModel]（非inner）から
 * 外側インスタンスのレシーバなしで呼び出せるようにするため。
 */
private fun singleStepPlanJson(eventType: String = "business_meeting", actionType: String = "prepare_items"): String =
    JSONObject().apply {
        put("event_type", eventType)
        put(
            "steps",
            JSONArray().put(
                JSONObject().apply {
                    put("action_type", actionType)
                    put("display_text", "Prepare documents")
                }
            )
        )
    }.toString()

/**
 * P7-C4（ADR-0053）: [installedModelStorage]／[notInstalledModelStorage]／[fakeInstalledEntry]が
 * 共有する「導入済みモデル」fixtureの内容。実モデル（328MB・SHA-256実測値）を使わずに
 * `ModelVerifierImpl`（本物）でのSHA-256照合を高速に完走させるための小さなバイト列。
 * ファイルスコープにしてあるのは[sha256Hex]と同じ理由（ネストしたクラスからも参照するため）。
 */
private val FAKE_INSTALLED_MODEL_BYTES: ByteArray = "p7c4-fake-installed-model-fixture-bytes".toByteArray()

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

/**
 * `installedModelStorage()`／`notInstalledModelStorage()`／T-GW-18が使う「導入済みモデル」の
 * [ModelCatalogEntry]。[ModelStorageImpl]の`catalog`引数（ADR-0053で新設）へ本番の
 * `ModelCatalog.ALL`の代わりに渡すことで、実モデルのバイト列を持たずに§8.6 #11・#12を
 * 本物の[com.actionstarter.ai.model.ModelVerifierImpl]で検証できる。
 */
private fun fakeInstalledEntry(): ModelCatalogEntry = ModelCatalogEntry(
    id = "p7c4-test-installed-model",
    displayName = "P7-C4 Test Installed Model",
    downloadUrl = "https://example.invalid/p7c4-test-installed-model.litertlm",
    sha256 = sha256Hex(FAKE_INSTALLED_MODEL_BYTES),
    sizeBytes = FAKE_INSTALLED_MODEL_BYTES.size.toLong(),
    peakRamBytes = 1L * 1024 * 1024,
    contextLength = 1,
    quantization = "test",
    license = ModelLicense.APACHE_2_0,
    requiresNoticeFile = false
)

/**
 * P7-C2／P7契約確定（計画書§12.5・T-GW-1〜18・T-AIMET-1・F96）。[LocalAiGateway.generatePlan]の
 * 5系統フォールバック（ロード失敗／OOM能動ガード／タイムアウト／スキーマ検証失敗／端末非対応）
 * を中心とした失敗テスト（Red）。E2（Robolectric、`AiPreferencesImpl`/`DeviceCapabilityImpl`/
 * `ModelStorageImpl`がいずれも`android.content.Context`を要求するため）。
 *
 * **現状のRed原因**: [LocalAiGateway.generatePlan]の本体は無条件の`TODO()`のため、以下は
 * 全件`NotImplementedError`によりRedになるのが正しい（[com.actionstarter.planning.
 * BasicPlanningEngineTest]と同じ確立された規約）。
 *
 * **P7契約確定での主要な調整（Fable 5裁定、2026-08-10。ADR-0045・ADR-0047・ADR-0048）**:
 * 1. **[LocalLanguageModel.generatePlan]の戻り値契約変更（裁定3・ADR-0045）**: `AIPlanResponse`
 *    （パース済み）ではなく生JSON`String`を返す契約になったため、[FakeLocalLanguageModel]・
 *    [ConcurrencyTrackingFakeModel]は`String`を返すよう変更し、[PlanCallOutcome.Respond]は
 *    `AIPlanResponse`ではなく生JSON文字列（`rawJson`）を保持する形へ変更した。
 *    [validSingleStepResponse]／[schemaInvalidNineStepResponse]はorg.jsonで生JSON文字列を
 *    組み立てる実装へ差し替えた（関数名・呼び出し箇所は不変、戻り値の中身のみ変更）。
 * 2. **`AIPlanResponse`／`AIPlanStepResponse`のフィールド削減（裁定1・ADR-0045）**:
 *    `estimated_minutes`／`priority`／`skippable`／`type`をLLM出力契約から除去したため、
 *    上記フィクスチャのJSON構造も`event_type`＋`steps[action_type, display_text]`のみへ
 *    縮小した。
 * 3. **4型のinterface化（裁定5・ADR-0048）**: `ModelStorage`／`ModelVerifier`／
 *    `DeviceCapability`／`AiPreferences`は具象クラスからinterfaceへ変更され、実装は
 *    `XxxImpl`へ分離された。本ファイルの構築ヘルパーは`XxxImpl`を構築して返す形へ更新した
 *    （Robolectric実Context・実shadowで状態を制御する既存方式は維持。fakeベースの
 *    フィクスチャへの全面移行はここでは行わない——下記「今後の検討」参照）。
 *
 * **今後の検討（本サイクルでは変更しない）**: ruling5でinterface化されたことにより、
 * [DeviceCapability]／[AiPreferences]をRobolectric非依存の軽量fakeへ置き換える余地が
 * 生まれたが、これはP7-C2完了記録の差し戻し事項4が問うていた「fake注入性を高める設計変更を
 * 検討するか、Robolectric実状態操作を正式な方式として採用するかの判断」そのものであり、
 * ruling5自体が答えたのは前者の**前提（interface化）**のみである。フィクスチャの実装方式
 * そのものを差し替えるかどうかはP7-C4／P7-C5（各インタフェースのGreen実装サイクル）の
 * 判断に委ね、本契約確定サイクルでは構築箇所の型名変更に留める（挙動を変えない最小差分）。
 *
 * **P7-C2c（品質ハーネス由来の新設部品へのRed補完、2026-08-10、test-writer）での追加調整**:
 * Fable 5裁定9（ADR-0050）により[LocalLanguageModel.generatePlan]へ[SamplingPolicy]引数
 * （既定[SamplingPolicy.Primary]）が追加されたため、[FakeLocalLanguageModel]・
 * [ConcurrencyTrackingFakeModel]のoverride署名を更新した（override側はKotlinの規約により
 * 既定値を再宣言できないため`samplingPolicy: SamplingPolicy`のみ受け取る）。
 * [FakeLocalLanguageModel]は呼び出しごとに使われた[SamplingPolicy]を
 * [FakeLocalLanguageModel.recordedSamplingPolicies]へ記録するよう拡張し、「1回目はPrimary・
 * 検証不合格による2回目はRetryで呼び分ける」契約（Gatewayの責務、[SamplingPolicy]のKDoc
 * 「責務分担」参照）を検証する新規テスト（T-GW-19・T-GW-20、系統6）を追加した。
 *
 * **fake注入の設計**: [model]は§16の凍結`interface LocalLanguageModel`のため
 * [FakeLocalLanguageModel]で完全に差し替え可能（他プロジェクト内の`CalendarService`／
 * `RoutingService`等と同じ、本プロジェクトの確立されたfakeパターン）。[modelStorage]の
 * 「導入済み」状態は、P7-C4（ADR-0053）で`ModelStorage`のファイル配置規約が確定したことに伴い、
 * [installedModelStorage]／[notInstalledModelStorage]が実際にファイルを配置・非配置する形へ
 * 更新済み（[installedModelStorage]のKDoc参照。ADR-0051の再検討トリガーへの回答）。
 *
 * **P7-C4（2026-08-10、domain-implementer）での追加調整**: `ModelStorage`のファイル配置規約
 * （ADR-0053）確定に伴い、[LocalAiGateway.generatePlan]へ§8.6 #11（`modelStorage.
 * installedEntry`チェック）・#12（`modelVerifier`ロード前再検証）を配線した（ADR-0054）。
 * これにより**T-GW-3が本サイクルでGreen化**した（ADR-0051の再検討トリガーへの回答）。
 * 併せて、下記「対象外ID」が挙げていた**T-GW-18も本サイクルで骨格を作成しGreen化**した
 * （Fable 5裁定8・ADR-0049が据え置いていたブロッカー〔`ModelStorage`内部規約未確定〕が
 * 解消されたため。[tGw18a_installedModelFailsPreLoadReverification_returnsFallbackModelCorrupted_deletesFile]・
 * [tGw18b_secondCallWithinSameProcess_doesNotRecomputeSha256]参照、系統7）。
 *
 * **本ファイルが対象外とするIDとその理由（Fable 5確認事項・裁定6/7で確定。T-GW-18は上記の
 * とおりP7-C4で解消済みのため以下からは除外した）**:
 * - **T-GW-11**（容量不足→DL開始しない）: `LocalAiGateway`の公開APIは`generatePlan`／
 *   `generateRecovery`のみで、DLを開始するメソッドを持たない。§8.6 #3の判定タイミングも
 *   「DL開始前」（Settings／ModelDownloader起点）であり、本クラスの責務外と判断した。
 *   **Fable 5裁定6（2026-08-10、ADR-0049）でこの判断を追認・確定**（T-GW-11は
 *   `ModelDownloader`／Settings領域のテストとして別途扱う）。
 * - **T-GW-14**（全Fallback経路でAnalytics記録が1回呼ばれる）: 本プロジェクトにAnalytics
 *   基盤が存在しない（`AnalyticsStore`はPhase 10、Analytics実装はPhase 12）。
 *   **Fable 5裁定7（2026-08-10、ADR-0049）**: Analytics collaboratorは追加せず、Phase 10/12の
 *   Analytics基盤と共に実装することを確定した（コンストラクタへの引数追加は行わない）。
 * - **T-GW-16**（2回目呼び出しでモデルの再ロードが起きない）: 「再ロード」は
 *   `LiteRtLmLocalLanguageModel`（P7-C5）の内部状態であり、§16凍結interfaceの
 *   `LocalLanguageModel.generatePlan`呼び出し回数からは観測できない（`Engine`/
 *   `Conversation`の生成有無を`LocalAiGateway`境界から見る手段がない）。P7-C5のadapter単体
 *   テストのスコープと判断した。
 *
 * これら3件は自己解釈で代替実装をでっち上げず、書ける範囲のみを書いて報告する
 * （本タスクの制約「計画書のケースが曖昧でテスト化できない場合は差し戻し報告」に基づく）。
 */
@RunWith(RobolectricTestRunner::class)
class LocalAiGatewayTest {

    // ------------------------------------------------------------------
    // fakeモデル（唯一のinterfaceベースDI境界）
    // ------------------------------------------------------------------

    private sealed interface PlanCallOutcome {
        /** [rawJson]はLLM生出力テキスト（Fable 5裁定3・ADR-0045、generatePlanのString化）。 */
        data class Respond(val rawJson: String) : PlanCallOutcome
        data class ThrowError(val error: Throwable) : PlanCallOutcome
    }

    private class FakeLocalLanguageModel(
        private val outcomes: List<PlanCallOutcome>,
        private val delayMillisPerCall: Long = 0L
    ) : LocalLanguageModel {
        override val modelIdentifier: String = "fake-model"

        var generatePlanCallCount: Int = 0
            private set

        /**
         * Fable 5裁定9（ADR-0050、T-GW-19・T-GW-20）: 呼び出しごとに使われた[SamplingPolicy]を
         * 記録する。「1回目はPrimary・検証不合格による2回目はRetry」という呼び分けは
         * [LocalAiGateway]の責務であり、本fakeは渡された値をそのまま記録するだけ
         * （[SamplingPolicy]のKDoc「責務分担」参照）。
         */
        val recordedSamplingPolicies: MutableList<SamplingPolicy> = mutableListOf()

        override suspend fun generatePlan(context: PlanningContext, samplingPolicy: SamplingPolicy): String {
            recordedSamplingPolicies.add(samplingPolicy)
            val outcome = outcomes.getOrElse(generatePlanCallCount) { outcomes.last() }
            generatePlanCallCount += 1
            if (delayMillisPerCall > 0) {
                delay(delayMillisPerCall)
            }
            return when (outcome) {
                is PlanCallOutcome.Respond -> outcome.rawJson
                is PlanCallOutcome.ThrowError -> throw outcome.error
            }
        }

        override suspend fun generateRecovery(context: RecoveryContext): AIRecoveryResponse {
            throw UnsupportedOperationException(
                "Phase 7ではLocalAiGateway.generateRecoveryがmodelを呼び出さない契約のため未使用（U-8）"
            )
        }
    }

    /** T-GW-15専用: fakeモデル内での同時アクティブ呼び出し数を観測する。 */
    private class ConcurrencyTrackingFakeModel(private val delayMillis: Long) : LocalLanguageModel {
        override val modelIdentifier: String = "concurrency-tracking-fake-model"

        private val activeCalls = AtomicInteger(0)

        var maxObservedConcurrency: Int = 0
            private set

        override suspend fun generatePlan(context: PlanningContext, samplingPolicy: SamplingPolicy): String {
            val current = activeCalls.incrementAndGet()
            maxObservedConcurrency = maxOf(maxObservedConcurrency, current)
            delay(delayMillis)
            activeCalls.decrementAndGet()
            return singleStepPlanJson(eventType = "business_meeting", actionType = "prepare_items")
        }

        override suspend fun generateRecovery(context: RecoveryContext): AIRecoveryResponse =
            throw UnsupportedOperationException("T-GW-15では未使用")
    }

    // ------------------------------------------------------------------
    // 実コラボレータのフィクスチャヘルパー（Robolectric実Context/実SharedPreferences/実shadow）
    // ------------------------------------------------------------------

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun activityManager(): ActivityManager =
        context().getSystemService(ActivityManager::class.java)

    private fun preferences(aiEnabled: Boolean, prefsFileName: String): AiPreferences {
        val prefs = context().getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean(AiPreferencesImpl.KEY_AI_ENABLED, aiEnabled).commit()
        return AiPreferencesImpl(prefs)
    }

    private fun deviceCapabilityWith(totalMemBytes: Long, availMemBytes: Long, vararg abis: String): DeviceCapability {
        ShadowBuild.setSupportedAbis(abis)
        val info = ActivityManager.MemoryInfo().apply {
            totalMem = totalMemBytes
            availMem = availMemBytes
            threshold = 0L
            lowMemory = false
        }
        shadowOf(activityManager()).setMemoryInfo(info)
        return DeviceCapabilityImpl(context())
    }

    private fun supportedDeviceCapability(availMemBytes: Long = 4L * GB): DeviceCapability =
        deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = availMemBytes, "arm64-v8a")

    private fun unsupportedRamDeviceCapability(): DeviceCapability =
        deviceCapabilityWith(totalMemBytes = 2L * GB, availMemBytes = 2L * GB, "arm64-v8a")

    private fun unsupportedAbiDeviceCapability(): DeviceCapability =
        deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = 8L * GB, "armeabi-v7a")

    /**
     * 「未導入」状態のModelStorage（自然な既定状態。ファイル配置を一切行わない）。
     * T-GW-3の対象そのもの。[fakeInstalledEntry]と同じcatalogを与えるが、対応する
     * [ModelStorage.finalFile]へは何も書き込まないため[ModelStorage.installedEntry]は
     * 常に`null`を返す。
     */
    private fun notInstalledModelStorage(): ModelStorage =
        ModelStorageImpl(context(), catalog = listOf(fakeInstalledEntry()))

    /**
     * 「モデル導入済み」状態を意図したModelStorage（P7-C4・ADR-0053で実配置形へ更新）。
     *
     * **実配置形（P7-C4）**: [ModelStorageImpl]の`catalog`引数（ADR-0053で新設）へ本番の
     * `ModelCatalog.ALL`（実モデル328MB・SHA-256実測値）ではなく、本ヘルパー専用の小さな
     * fixtureエントリ（[fakeInstalledEntry]）を差し替えて渡す。これにより、実モデルの
     * バイト列を持たなくても`ModelVerifierImpl`（本物、fakeに差し替えない）による本物の
     * SHA-256照合を高速に完走させたうえで「導入済み」状態を表現できる
     * （§8.6 #11・#12を本物のロジックで検証する。ADR-0053「導入済みモデルの解決方法」参照）。
     * [ModelStorage.finalFile]へ[FAKE_INSTALLED_MODEL_BYTES]をそのまま書き込むことで、
     * `installedEntry()`／`installedModelPath()`が非nullを返し、`ModelVerifier.verify`も
     * 合格する状態を作る。
     */
    private fun installedModelStorage(): ModelStorage {
        val entry = fakeInstalledEntry()
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))
        storage.finalFile(entry).apply {
            parentFile?.mkdirs()
            writeBytes(FAKE_INSTALLED_MODEL_BYTES)
        }
        return storage
    }

    private fun verifier(): ModelVerifier = ModelVerifierImpl()

    private fun sampleEvent(): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Quarterly Planning Meeting",
        notes = null,
        startDate = Instant.parse("2026-08-10T10:00:00Z"),
        locationName = "Shibuya Office",
        coordinates = Coordinate(lat = 35.6595, lon = 139.7005),
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun planningContext(): PlanningContext = PlanningContext(
        event = sampleEvent(),
        now = Instant.parse("2026-08-10T07:00:00Z"),
        zoneId = ZoneId.of("UTC"),
        locale = Locale.US,
        transportMode = TransportMode.WALKING,
        travelEstimate = Duration.ofMinutes(20),
        arrivalBuffer = Duration.ofMinutes(10),
        profile = null
    )

    // ------------------------------------------------------------------
    // LLM生JSONフィクスチャ（[singleStepPlanJson]はファイルスコープ関数として上部で定義済み）
    // ------------------------------------------------------------------

    /** §20の公式JSON例をFable 5裁定1・3後の縮小スキーマへ適合させた1step正常応答。 */
    private fun validSingleStepResponse(): String = singleStepPlanJson()

    /** maxItems=8を超える9件のsteps。SchemaValidator観点でのスキーマ不合格を意図する。 */
    private fun schemaInvalidNineStepResponse(): String {
        val steps = JSONArray()
        repeat(9) { index ->
            steps.put(
                JSONObject().apply {
                    put("action_type", "action_$index")
                    put("display_text", "Step $index")
                }
            )
        }
        return JSONObject().apply {
            put("event_type", "business_meeting")
            put("steps", steps)
        }.toString()
    }

    companion object {
        private const val GB = 1024L * 1024 * 1024
    }

    // ------------------------------------------------------------------
    // 系統1: ロード失敗
    // ------------------------------------------------------------------

    // T-GW-4: 異常系 - fakeモデルがUnsatisfiedLinkErrorを投げる → Fallback(MODEL_LOAD_FAILED)、
    // 例外は外へ出ない
    @Test
    fun tGw4_modelThrowsUnsatisfiedLinkError_returnsFallbackModelLoadFailed() = runTest {
        val model = FakeLocalLanguageModel(
            listOf(PlanCallOutcome.ThrowError(UnsatisfiedLinkError("fake .so load failure")))
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw4")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.MODEL_LOAD_FAILED, (result as AiResult.Fallback).reason)
    }

    // ------------------------------------------------------------------
    // 系統2: OOM能動ガード（主防御、Gemini G1 CRITICAL #3）
    // ------------------------------------------------------------------

    // T-GW-5: 異常系 - 事前ガード: 空きメモリが必要ピークRAM＋安全マージンを下回る →
    // ロード/推論を一切開始せずFallback(OUT_OF_MEMORY_PREVENTED)。fakeモデルのgeneratePlanが
    // 1回も呼ばれないことを検証
    @Test
    fun tGw5_insufficientAvailableMemory_returnsFallbackOutOfMemoryPrevented_modelNeverInvoked() = runTest {
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(availMemBytes = 200L * 1024 * 1024), // 200MB空き
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw5")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.OUT_OF_MEMORY_PREVENTED, (result as AiResult.Fallback).reason)
        assertEquals(
            "OOM事前ガード発動時はロード・推論を一切実行せずfakeモデルのgeneratePlanは" +
                "0回のはずです(Gemini G1 CRITICAL #3)",
            0,
            model.generatePlanCallCount
        )
    }

    // T-GW-17: 異常系 - 二次防御: 事前ガードは通過したがfakeモデルがOutOfMemoryErrorを投げる
    // （事前ガードをすり抜けた残余ケース）→ Fallback(OUT_OF_MEMORY)。
    // 【部分カバレッジ】「アンロードが呼ばれる」検証は§16凍結interfaceにunload相当のメソッドが
    // 存在せずGateway境界から観測不能なため対象外（クラスKDoc参照）。
    @Test
    fun tGw17_modelThrowsOutOfMemoryError_returnsFallbackOutOfMemory() = runTest {
        val model = FakeLocalLanguageModel(
            listOf(PlanCallOutcome.ThrowError(OutOfMemoryError("fake native alloc failure")))
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw17")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.OUT_OF_MEMORY, (result as AiResult.Fallback).reason)
    }

    // ------------------------------------------------------------------
    // 系統3: タイムアウト
    // ------------------------------------------------------------------

    // T-GW-6: 異常系 - fakeモデルが閾値を超えて応答しない → Fallback(TIMEOUT)（runTestの仮想時間で検証）
    @Test
    fun tGw6_modelExceedsTimeout_returnsFallbackTimeout() = runTest {
        val model = FakeLocalLanguageModel(
            outcomes = listOf(PlanCallOutcome.Respond(validSingleStepResponse())),
            delayMillisPerCall = LocalAiGateway.DEFAULT_TIMEOUT_MILLIS + 5_000L
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw6")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.TIMEOUT, (result as AiResult.Fallback).reason)
    }

    // ------------------------------------------------------------------
    // 系統4: スキーマ検証失敗
    // ------------------------------------------------------------------

    // T-GW-7: 異常系 - 1回目がスキーマ不合格・2回目が合格 → Success かつ metrics.retried==true
    // （§20 retry1回）。
    // 【Fable 5確認事項・統合ギャップ→裁定3で解決】旧版はLocalLanguageModel.generatePlan()が
    // AIPlanResponseを返す契約とSchemaValidator.validate()がrawJson: Stringを受け取る契約との
    // 橋渡し方法が未確定だったが、裁定3（ADR-0045）でgeneratePlan()自体がString（生JSON）を
    // 返す契約に変更されたためこの統合ギャップは解消された。本テストは変更後の契約下でも
    // 「1回目maxItems=8超過（9件）で不合格相当・2回目1件で合格相当」という観測可能な入力の差
    // だけでGateway全体の振る舞いを検証する設計を維持する。
    @Test
    fun tGw7_firstSchemaInvalidSecondValid_returnsSuccessWithRetriedTrue() = runTest {
        val model = FakeLocalLanguageModel(
            listOf(
                PlanCallOutcome.Respond(schemaInvalidNineStepResponse()),
                PlanCallOutcome.Respond(validSingleStepResponse())
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw7")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(
            "1回目不合格・2回目合格ならSuccessになるべきです(§20 retry1回): $result",
            result is AiResult.Success
        )
        assertTrue(
            "retry発生時はmetrics.retried==trueになるべきです",
            (result as AiResult.Success).metrics.retried
        )
        assertEquals(2, model.generatePlanCallCount)
    }

    // T-GW-8: 異常系 - 1回目も2回目も不合格 → Fallback(SCHEMA_INVALID)。呼び出し回数がちょうど2回
    // （T-GW-7と同じ前提に立つ。裁定3でgeneratePlan()がString契約になったため統合ギャップは解消）
    @Test
    fun tGw8_bothAttemptsSchemaInvalid_returnsFallbackSchemaInvalid_calledExactlyTwice() = runTest {
        val model = FakeLocalLanguageModel(
            listOf(
                PlanCallOutcome.Respond(schemaInvalidNineStepResponse()),
                PlanCallOutcome.Respond(schemaInvalidNineStepResponse())
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw8")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.SCHEMA_INVALID, (result as AiResult.Fallback).reason)
        assertEquals("3回目を呼んではいけません(retryは1回のみ、§20)", 2, model.generatePlanCallCount)
    }

    // 【未実装・Fable 5確認事項・裁定8で確定】T-GW-18: ロード前検証(SHA-256再検証)不一致→Fallback
    // (MODEL_CORRUPTED)＋ファイル削除＋2回目以降は再計算しない(Gemini G1 CRITICAL #2)。
    // 「導入済みだがModelCatalogEntry.sha256と一致しない」状態を組み立てるには
    // installedModelStorage()と同じ理由（ModelStorageのファイル配置規約・検証対象エントリの
    // 選択方法がいずれも未確定）で自己判断による組み立てを避けた。Fable 5裁定8（ADR-0049）は
    // P7-C4（ModelStorageファイルレイアウト規約確定時）まで骨格を書かないことを確定した。

    // ------------------------------------------------------------------
    // 系統5: 端末非対応
    // ------------------------------------------------------------------

    // T-GW-9: 異常系 - 端末非対応（RAM不足）→ Fallback(UNSUPPORTED_DEVICE)
    @Test
    fun tGw9_unsupportedRam_returnsFallbackUnsupportedDevice() = runTest {
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = unsupportedRamDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw9")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.UNSUPPORTED_DEVICE, (result as AiResult.Fallback).reason)
    }

    // T-GW-10: 異常系 - 端末非対応（ABI）→ Fallback(UNSUPPORTED_ABI)
    @Test
    fun tGw10_unsupportedAbi_returnsFallbackUnsupportedAbi() = runTest {
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = unsupportedAbiDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw10")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.UNSUPPORTED_ABI, (result as AiResult.Fallback).reason)
    }

    // ------------------------------------------------------------------
    // その他の確定済みFallback系統・横断的関心事
    // ------------------------------------------------------------------

    // T-GW-1: 正常系 - AI ON＋モデル導入済＋fakeモデルが正しいJSONを返す → AiResult.Success
    @Test
    fun tGw1_aiEnabledModelInstalledFakeModelSucceeds_returnsSuccess() = runTest {
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw1")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue("AI ON・導入済み・fakeモデル成功時はSuccessになるべきです: $result", result is AiResult.Success)
    }

    // T-GW-2: 正常系 - AI OFF（既定）→ Fallback(AI_DISABLED)。fakeモデルのgeneratePlanが
    // 1回も呼ばれない（§19）
    @Test
    fun tGw2_aiDisabled_returnsFallbackAiDisabled_modelNeverInvoked() = runTest {
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = false, prefsFileName = "test_ai_prefs_tGw2")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.AI_DISABLED, (result as AiResult.Fallback).reason)
        assertEquals("AI OFF時はfakeモデルのgeneratePlanが1回も呼ばれてはいけません", 0, model.generatePlanCallCount)
    }

    // T-GW-3: 正常系 - モデル未DL → Fallback(MODEL_NOT_INSTALLED)、推論を開始しない
    @Test
    fun tGw3_modelNotInstalled_returnsFallbackModelNotInstalled_modelNeverInvoked() = runTest {
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = notInstalledModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw3")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.MODEL_NOT_INSTALLED, (result as AiResult.Fallback).reason)
        assertEquals(0, model.generatePlanCallCount)
    }

    // T-GW-12: 異常系 - fakeモデルが未定義のRuntimeExceptionを投げる → Fallback(UNKNOWN)かつ
    // detailに例外クラス名が入る（サイレント握り潰しの禁止）
    @Test
    fun tGw12_modelThrowsUnclassifiedRuntimeException_returnsFallbackUnknownWithClassNameInDetail() = runTest {
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.ThrowError(RuntimeException("unexpected failure"))))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw12")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        val fallback = result as AiResult.Fallback
        assertEquals(AiFallbackReason.UNKNOWN, fallback.reason)
        assertTrue(
            "detailに例外クラス名が含まれサイレント握り潰しを避けるべきです(T-GW-12): ${fallback.detail}",
            fallback.detail?.contains("RuntimeException") == true
        )
    }

    // T-GW-13: エッジ - 呼び出し側のコルーチンがキャンセルされた → CancellationExceptionを
    // 握り潰さず再送出する（構造化並行性を壊さない）。Fallbackに化けさせない
    @Test
    fun tGw13_modelThrowsCancellationException_isRethrownNotConvertedToFallback() = runTest {
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.ThrowError(CancellationException("cancelled"))))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw13")
        )

        var thrown: Throwable? = null
        try {
            gateway.generatePlan(planningContext())
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue(
            "CancellationExceptionは握り潰されずそのまま再送出されFallbackに化けさせては" +
                "いけません（実際: $thrown）",
            thrown is CancellationException
        )
    }

    // T-GW-15: 正常系 - 同時に2回呼ばれても推論が直列化される（Mutex）。ネイティブコンテキストの
    // 同時使用を起こさない
    @Test
    fun tGw15_concurrentCalls_neverOverlapInsideModel() = runTest {
        val model = ConcurrencyTrackingFakeModel(delayMillis = 50)
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw15")
        )

        val first = async { gateway.generatePlan(planningContext()) }
        val second = async { gateway.generatePlan(planningContext()) }
        first.await()
        second.await()

        assertEquals(
            "Mutexによる直列化下ではfakeモデル内で同時アクティブ呼び出しが1を超えては" +
                "いけません(T-GW-15)",
            1,
            model.maxObservedConcurrency
        )
    }

    // ------------------------------------------------------------------
    // 系統6: samplingPolicy呼び分け（Fable 5裁定9・ADR-0050、P7-C2c新設）
    // ------------------------------------------------------------------

    // T-GW-19: 正常系 - 1回目で合格するときはPrimary方針のみで1回呼ばれる（Retry方針は
    // 一度も使われない）
    @Test
    fun tGw19_singleSuccessfulCall_usesPrimaryPolicyOnly() = runTest {
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw19")
        )

        gateway.generatePlan(planningContext())

        assertEquals(
            "1回で成功する場合はPrimary方針のみが使われるべきです(Fable 5裁定9・ADR-0050)",
            listOf(SamplingPolicy.Primary),
            model.recordedSamplingPolicies
        )
    }

    // T-GW-20: 異常系 - 1回目不合格・2回目合格 → 1回目はPrimary・2回目はRetry方針で
    // generatePlanが呼ばれる（この呼び分けはGatewayの責務。adapterは渡された方針に
    // 従うだけで検証の成否を知らない、Fable 5裁定9・ADR-0050・S-2是正）
    @Test
    fun tGw20_retrySequence_firstCallUsesPrimarySecondCallUsesRetryPolicy() = runTest {
        val model = FakeLocalLanguageModel(
            listOf(
                PlanCallOutcome.Respond(schemaInvalidNineStepResponse()),
                PlanCallOutcome.Respond(validSingleStepResponse())
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw20")
        )

        gateway.generatePlan(planningContext())

        assertEquals(
            "1回目はPrimary・2回目はRetry方針で呼ばれるべきです(Fable 5裁定9・ADR-0050・S-2是正)",
            listOf(SamplingPolicy.Primary, SamplingPolicy.Retry),
            model.recordedSamplingPolicies
        )
    }

    // ------------------------------------------------------------------
    // 系統7: ロード前再検証（§8.6 #12・Gemini G1 CRITICAL #2・T-GW-18、P7-C4でGreen化）
    // ------------------------------------------------------------------

    /** [ModelVerifier.verify]の呼び出し回数を数える薄いラッパー（T-GW-18bのキャッシュ検証用）。 */
    private class CountingModelVerifier(private val delegate: ModelVerifier) : ModelVerifier {
        var verifyCallCount: Int = 0
            private set

        override fun verify(file: File, expected: ModelCatalogEntry): ModelVerificationResult {
            verifyCallCount += 1
            return delegate.verify(file, expected)
        }
    }

    // T-GW-18a: 異常系 - ロード前検証(SHA-256再検証)不一致 → Fallback(MODEL_CORRUPTED)・
    // ファイル削除・fakeモデルは1回も呼ばれない(Gemini G1 CRITICAL #2、ADR-0049裁定8・ADR-0054)
    @Test
    fun tGw18a_installedModelFailsPreLoadReverification_returnsFallbackModelCorrupted_deletesFile() = runTest {
        val entry = fakeInstalledEntry()
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))
        // FAKE_INSTALLED_MODEL_BYTESと同じ長さ・異なる内容 → SIZE_MISMATCHではなく
        // HASH_MISMATCHで不合格になる（改竄想定、ModelVerifierTestのtamperedContentと同型）。
        val tamperedBytes = ByteArray(FAKE_INSTALLED_MODEL_BYTES.size) { 0x00 }
        storage.finalFile(entry).apply {
            parentFile?.mkdirs()
            writeBytes(tamperedBytes)
        }
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw18a")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.MODEL_CORRUPTED, (result as AiResult.Fallback).reason)
        assertEquals(
            "ロード前検証が不合格の場合はfakeモデルのgeneratePlanが1回も呼ばれてはいけません",
            0,
            model.generatePlanCallCount
        )
        assertTrue(
            "検証失敗時は破損ファイルを削除するべきです(§8.6 #12)",
            !storage.finalFile(entry).exists()
        )
    }

    // T-GW-18b: 正常系 - 検証成功後、同一Gatewayインスタンスへの2回目の呼び出しではSHA-256を
    // 再計算しない(プロセス内キャッシュ、§8.6 #12「以後の呼び出しでは再計算しない」、
    // Gemini G1 CRITICAL #2)
    @Test
    fun tGw18b_secondCallWithinSameProcess_doesNotRecomputeSha256() = runTest {
        val countingVerifier = CountingModelVerifier(ModelVerifierImpl())
        val model = FakeLocalLanguageModel(
            listOf(
                PlanCallOutcome.Respond(validSingleStepResponse()),
                PlanCallOutcome.Respond(validSingleStepResponse())
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = countingVerifier,
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw18b")
        )

        gateway.generatePlan(planningContext())
        gateway.generatePlan(planningContext())

        assertEquals(
            "同一プロセス内(同一Gatewayインスタンス)の2回目呼び出しではSHA-256を再計算しない" +
                "はずです(§8.6 #12、Gemini G1 CRITICAL #2)",
            1,
            countingVerifier.verifyCallCount
        )
    }
}
