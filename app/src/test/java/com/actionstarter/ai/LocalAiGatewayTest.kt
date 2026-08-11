package com.actionstarter.ai

import android.app.ActivityManager
import android.content.Context
import com.actionstarter.ai.adapter.requiresEngineReload
import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceCapabilityImpl
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelLicense
import com.actionstarter.ai.model.ModelSelectorImpl
import com.actionstarter.ai.model.ModelStorage
import com.actionstarter.ai.model.ModelStorageImpl
import com.actionstarter.ai.model.ModelVerificationResult
import com.actionstarter.ai.model.ModelVerifier
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
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

    /**
     * Phase 9追加（計画書`docs/plans/phase9-recovery-ai.md`§3.2）。[FakeLocalLanguageModel]の
     * `generateRecovery`版[Outcome]（[PlanCallOutcome]と同型）。
     */
    private sealed interface RecoveryCallOutcome {
        data class Respond(val rawJson: String) : RecoveryCallOutcome
        data class ThrowError(val error: Throwable) : RecoveryCallOutcome
    }

    private class FakeLocalLanguageModel(
        private val outcomes: List<PlanCallOutcome>,
        private val delayMillisPerCall: Long = 0L,
        private val recoveryOutcomes: List<RecoveryCallOutcome> = emptyList()
    ) : LocalLanguageModel {
        override val modelIdentifier: String = "fake-model"

        var generatePlanCallCount: Int = 0
            private set

        /** Phase 9追加（計画書§3.2、T-P9-26〜28）。[generateRecovery]の呼び出し回数。 */
        var generateRecoveryCallCount: Int = 0
            private set

        /**
         * Fable 5裁定9（ADR-0050、T-GW-19・T-GW-20）: 呼び出しごとに使われた[SamplingPolicy]を
         * 記録する。「1回目はPrimary・検証不合格による2回目はRetry」という呼び分けは
         * [LocalAiGateway]の責務であり、本fakeは渡された値をそのまま記録するだけ
         * （[SamplingPolicy]のKDoc「責務分担」参照）。
         */
        val recordedSamplingPolicies: MutableList<SamplingPolicy> = mutableListOf()

        /**
         * Phase 8.5追加（計画書`docs/plans/phase8.5-adaptive-model-selection.md`§3設計5・
         * §6 T-P85-28/29、ADR-0062、アーキテクトレビューPass 1 CRITICAL対応）。呼び出しごとに
         * 受け取った`modelPath`を記録し、「解決済みモデルと実際にロードへ渡されるパスが一致するか」
         * をテストから検証できるようにする（[recordedSamplingPolicies]と同型のパターン）。
         */
        val recordedModelPaths: MutableList<String> = mutableListOf()

        /** Phase 9追加（計画書§3.2、T-P9-26）。[generateRecovery]呼び出しごとの`modelPath`記録。 */
        val recordedRecoveryModelPaths: MutableList<String> = mutableListOf()

        override suspend fun generatePlan(context: PlanningContext, modelPath: String, samplingPolicy: SamplingPolicy): String {
            recordedSamplingPolicies.add(samplingPolicy)
            recordedModelPaths.add(modelPath)
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

        /**
         * Phase 9追加（計画書§3.2）。[recoveryOutcomes]が空のままなら（既定）Plan専用fixture
         * としての従来動作（`UnsupportedOperationException`）を維持する。非空の場合のみ
         * [generatePlan]と同型の消費ロジックで応答する（T-P9-26〜28が使う）。
         */
        override suspend fun generateRecovery(
            context: RecoveryContext,
            options: List<RecoveryOption>,
            modelPath: String,
            samplingPolicy: SamplingPolicy
        ): String {
            if (recoveryOutcomes.isEmpty()) {
                throw UnsupportedOperationException("本fixtureはPlan経路専用のため未使用（recoveryOutcomesが空のまま）")
            }
            recordedRecoveryModelPaths.add(modelPath)
            val outcome = recoveryOutcomes.getOrElse(generateRecoveryCallCount) { recoveryOutcomes.last() }
            generateRecoveryCallCount += 1
            return when (outcome) {
                is RecoveryCallOutcome.Respond -> outcome.rawJson
                is RecoveryCallOutcome.ThrowError -> throw outcome.error
            }
        }
    }

    /** T-GW-15専用: fakeモデル内での同時アクティブ呼び出し数を観測する。 */
    private class ConcurrencyTrackingFakeModel(private val delayMillis: Long) : LocalLanguageModel {
        override val modelIdentifier: String = "concurrency-tracking-fake-model"

        private val activeCalls = AtomicInteger(0)

        var maxObservedConcurrency: Int = 0
            private set

        override suspend fun generatePlan(context: PlanningContext, modelPath: String, samplingPolicy: SamplingPolicy): String {
            val current = activeCalls.incrementAndGet()
            maxObservedConcurrency = maxOf(maxObservedConcurrency, current)
            delay(delayMillis)
            activeCalls.decrementAndGet()
            return singleStepPlanJson(eventType = "business_meeting", actionType = "prepare_items")
        }

        // Phase 9（計画書`docs/plans/phase9-recovery-ai.md`§3.2）: シグネチャの機械的追随のみ。
        override suspend fun generateRecovery(
            context: RecoveryContext,
            options: List<RecoveryOption>,
            modelPath: String,
            samplingPolicy: SamplingPolicy
        ): String = throw UnsupportedOperationException("T-GW-15では未使用")
    }

    // ------------------------------------------------------------------
    // 実コラボレータのフィクスチャヘルパー（Robolectric実Context/実SharedPreferences/実shadow）
    // ------------------------------------------------------------------

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun activityManager(): ActivityManager =
        context().getSystemService(ActivityManager::class.java)

    /**
     * **Phase 8.5での変更（ADR-0062、既存テストへの波及調査）**: `selectedModelId`の既定値が
     * `AiPreferences.AUTO_SELECT_MODEL_ID`へ変わったため（[AiPreferences.
     * DEFAULT_SELECTED_MODEL_ID]参照）、本ヘルパーが返す`preferences`をそのまま使う既存の
     * 系統1〜7テスト（timeout/retry/OOM/SHA検証/並行性等、モデル選択そのものはテスト対象外）は
     * 何もしなければauto経路（[ModelSelector]）へ誤って迂回され、`fakeInstalledEntry()`という
     * 実カタログに存在しない汎用fixtureが「候補外」として扱われてしまう（本番コードのバグではなく、
     * これらのテストが元々「明示選択」を暗黙の前提にしていたことによる契約変更の波及）。
     * 既定で[fakeInstalledEntry]を明示選択しておくことで、既存テスト群の意図（明示選択された
     * 任意の1モデルでのGateway機構検証）を変えずに済ませる。auto経路自体を検証する
     * T-P85-10〜13・28はこの既定値を個別に`AiPreferences.AUTO_SELECT_MODEL_ID`へ上書きする。
     */
    private fun preferences(aiEnabled: Boolean, prefsFileName: String): AiPreferences {
        val prefs = context().getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean(AiPreferencesImpl.KEY_AI_ENABLED, aiEnabled).commit()
        return AiPreferencesImpl(prefs).apply { selectedModelId = fakeInstalledEntry().id }
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
    private fun installedModelStorage(): ModelStorage = installedModelStorageWithEntry(fakeInstalledEntry())

    /**
     * [installedModelStorage]の一般化版（ADR-0057・T-GW-21/22用）。[fakeInstalledEntry]固定ではなく
     * 任意の[entry]（`.copy(peakRamBytes = ..., defaultProfilePeakRamBytes = ...)`等でOOM関連の値を
     * 差し替えたもの）を「導入済み」状態にする。[FAKE_INSTALLED_MODEL_BYTES]は[entry].sizeBytes
     * と一致させる必要があるため、[entry]は[fakeInstalledEntry]から`.copy`したものを渡すこと
     * （`sizeBytes`／`sha256`を変えないまま他のフィールドのみ差し替える）。
     */
    private fun installedModelStorageWithEntry(entry: ModelCatalogEntry): ModelStorage {
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))
        storage.finalFile(entry).apply {
            parentFile?.mkdirs()
            writeBytes(FAKE_INSTALLED_MODEL_BYTES)
        }
        return storage
    }

    /**
     * Phase 8.5追加（計画書`docs/plans/phase8.5-adaptive-model-selection.md`§6
     * T-P85-10〜15・28・29、ADR-0062）。[installedModelStorageWithEntry]の複数エントリ版。
     * [entries]の並び順をそのまま`catalog`へ渡すため、実カタログ（`ModelCatalog.ALL`、
     * Qwen3-0.6B先頭）と同じ順序で導入済み状態を表現する場合は呼び出し側で順序を揃えること
     * （[autoTestCatalog]参照）。
     */
    private fun installedModelStorageWithEntries(
        entries: List<ModelCatalogEntry>,
        preferences: AiPreferences? = null
    ): ModelStorage {
        val storage = ModelStorageImpl(context(), catalog = entries, preferences = preferences)
        entries.forEach { entry ->
            storage.finalFile(entry).apply {
                parentFile?.mkdirs()
                writeBytes(FAKE_INSTALLED_MODEL_BYTES)
            }
        }
        return storage
    }

    /**
     * Phase 8.5追加（T-P85-10〜15・28・29用）。実カタログ`ModelCatalog.GEMMA_4_E2B_IT`と同じ
     * `defaultProfilePeakRamBytes`（2GiB）を持つfixture（実モデルファイルは使わない）。
     */
    private fun fakeGemma4LikeEntry(): ModelCatalogEntry = fakeInstalledEntry().copy(
        id = "test-gemma4-like",
        displayName = "Test Gemma4-like Model",
        peakRamBytes = 2L * GB,
        defaultProfilePeakRamBytes = 2L * GB
    )

    /**
     * Phase 8.5追加（T-P85-10〜15・28・29用）。実カタログ`ModelCatalog.QWEN3_0_6B_INT4_BLOCK32`と
     * 同じ`defaultProfilePeakRamBytes`（1.25GiB）を持つfixture。
     */
    private fun fakeQwen06bLikeEntry(): ModelCatalogEntry = fakeInstalledEntry().copy(
        id = "test-qwen06b-like",
        displayName = "Test Qwen3-0.6B-like Model",
        peakRamBytes = GB + GB / 4,
        defaultProfilePeakRamBytes = GB + GB / 4
    )

    /** 実カタログ順（Qwen3-0.6B先頭）を模した2件のcatalog（T-P85-10〜15・28・29用）。 */
    private fun autoTestCatalog(): List<ModelCatalogEntry> = listOf(fakeQwen06bLikeEntry(), fakeGemma4LikeEntry())

    /**
     * Phase 8.5 Step 4（Green）修正: `ModelSelectorImpl`へ渡す`candidates`は品質順
     * （Gemma4-like > Qwen06b-like）でなければならない。[autoTestCatalog]（Qwen先頭）を
     * そのまま流用していたためT-P85-10・28が「両方適合時はGemma4-likeを選ぶ」を検証できず
     * 誤ってQwen06b-likeが選ばれていた（テスト実装のミス、本番`ModelSelectorImpl.select`は
     * candidatesを渡された順に評価するのみで正しい）。`ModelStorageImpl`の`catalog`（実ファイル
     * 解決順、[autoTestCatalog]のまま）とは別概念であることに注意。
     */
    private fun qualityOrderedCandidates(): List<ModelCatalogEntry> = listOf(fakeGemma4LikeEntry(), fakeQwen06bLikeEntry())

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

    // T-GW-21: 正常系（ADR-0057・回帰ロック） - OOM事前ガードはModelCatalogEntry.peakRamBytes
    // （フルコンテキスト参考値）ではなくdefaultProfilePeakRamBytes（実際に使う既定プロファイルの
    // 実効ピーク）を基準に判定する。peakRamBytesを故意に巨大値にし、defaultProfilePeakRamBytesは
    // 空きメモリ内に収まる小さい値にした場合、Fallbackにならず推論へ進むことを検証する
    // （修正前の実装〔installedEntry.peakRamBytesを直接参照〕ではこのテストはFallbackになり失敗する）
    @Test
    fun tGw21_hugePeakRamBytesButSmallDefaultProfilePeakRamBytes_doesNotTriggerOomGuard() = runTest {
        val entry = fakeInstalledEntry().copy(
            peakRamBytes = 100L * GB,
            defaultProfilePeakRamBytes = 1L * 1024 * 1024
        )
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorageWithEntry(entry),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw21")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(
            "peakRamBytesが巨大でもdefaultProfilePeakRamBytesが空きメモリ内ならOOMガードは" +
                "発動せず推論へ進むべきです(ADR-0057): $result",
            result is AiResult.Success
        )
    }

    // T-GW-22: 異常系（ADR-0057・回帰ロック） - 逆に、peakRamBytesが小さくても
    // defaultProfilePeakRamBytesが空きメモリを超える巨大値ならOOM事前ガードが発動し、
    // 推論を一切開始しない（Gatewayが新フィールドを実際に参照していることの確認）
    @Test
    fun tGw22_smallPeakRamBytesButHugeDefaultProfilePeakRamBytes_triggersOomGuard_modelNeverInvoked() = runTest {
        val entry = fakeInstalledEntry().copy(
            peakRamBytes = 1L * 1024 * 1024,
            defaultProfilePeakRamBytes = 100L * GB
        )
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorageWithEntry(entry),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tGw22")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.OUT_OF_MEMORY_PREVENTED, (result as AiResult.Fallback).reason)
        assertEquals(
            "defaultProfilePeakRamBytesが巨大でOOMガードが発動した場合はfakeモデルの" +
                "generatePlanが1回も呼ばれてはいけません(ADR-0057)",
            0,
            model.generatePlanCallCount
        )
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

    // ------------------------------------------------------------------
    // 系統8: Phase 8.5 auto選択の配線プラミング（計画書§6 T-P85-10〜15・28・29、ADR-0062、
    // アーキテクトレビューPass 1 CRITICAL対応）。
    // Step 3(Red)時点: checkInstalledModel()のauto分岐本体は未実装のまま（既存
    // installedEntry()のcatalog順fallbackを素通りするだけ）。T-P85-10・28はこの理由でRedと
    // なることを狙うが、T-P85-11〜13・29はcatalog順fallback（Qwen06b-like先頭）が偶然
    // 期待値と一致しborn-greenになる可能性がある（実行結果をそのまま報告する）。T-P85-14・15は
    // 明示選択経路が無変更なため確定でborn-green。
    // ------------------------------------------------------------------

    // T-P85-10: 正常 - auto・両方導入・availMem=3GiB(両方適合) → 品質最上位のGemma4-likeへ
    // 進むべきだが、auto分岐が未実装のため実際にはcatalog順(Qwen06b-like)へ進む
    @Test
    fun tP85_10_auto_bothInstalledSufficientForBoth_selectsGemma4Like() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP85_10")
            .apply { selectedModelId = AiPreferences.AUTO_SELECT_MODEL_ID }
        val storage = installedModelStorageWithEntries(entries, preferences)
        val deviceCapability = supportedDeviceCapability(availMemBytes = 3L * GB)
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapability,
            preferences = preferences,
            modelSelector = ModelSelectorImpl(deviceCapability, storage, candidates = qualityOrderedCandidates())
        )

        gateway.generatePlan(planningContext())

        assertEquals(
            "autoは両方適合時に品質最上位のGemma4-likeを選ぶべきです(計画書§3決定表)",
            storage.finalFile(fakeGemma4LikeEntry()).absolutePath,
            model.recordedModelPaths.single()
        )
    }

    // T-P85-11: 正常 - auto・両方導入・availMem=2.0GiB(Gemma4-like不適合/Qwen06b-like適合) →
    // Qwen06b-likeへ進むべき
    @Test
    fun tP85_11_auto_bothInstalledOnlyQwenFits_selectsQwen06bLike() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP85_11")
            .apply { selectedModelId = AiPreferences.AUTO_SELECT_MODEL_ID }
        val storage = installedModelStorageWithEntries(entries, preferences)
        val deviceCapability = supportedDeviceCapability(availMemBytes = 2L * GB)
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapability,
            preferences = preferences,
            modelSelector = ModelSelectorImpl(deviceCapability, storage, candidates = qualityOrderedCandidates())
        )

        gateway.generatePlan(planningContext())

        assertEquals(
            storage.finalFile(fakeQwen06bLikeEntry()).absolutePath,
            model.recordedModelPaths.single()
        )
    }

    // T-P85-12: 異常 - auto・両方未導入 → Fallback(MODEL_NOT_INSTALLED)
    @Test
    fun tP85_12_auto_neitherInstalled_returnsFallbackModelNotInstalled() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP85_12")
            .apply { selectedModelId = AiPreferences.AUTO_SELECT_MODEL_ID }
        val storage = ModelStorageImpl(context(), catalog = entries, preferences = preferences) // ファイル未配置=未導入
        val deviceCapability = supportedDeviceCapability(availMemBytes = 3L * GB)
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapability,
            preferences = preferences,
            modelSelector = ModelSelectorImpl(deviceCapability, storage, candidates = qualityOrderedCandidates())
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.MODEL_NOT_INSTALLED, (result as AiResult.Fallback).reason)
        assertEquals(0, model.generatePlanCallCount)
    }

    // T-P85-13: 異常 - auto・両方導入だがavailMem=1.0GiB(いずれも不適合) →
    // Fallback(OUT_OF_MEMORY_PREVENTED)
    @Test
    fun tP85_13_auto_bothInstalledInsufficientForBoth_returnsFallbackOutOfMemoryPrevented() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP85_13")
            .apply { selectedModelId = AiPreferences.AUTO_SELECT_MODEL_ID }
        val storage = installedModelStorageWithEntries(entries, preferences)
        val deviceCapability = supportedDeviceCapability(availMemBytes = 1L * GB)
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapability,
            preferences = preferences,
            modelSelector = ModelSelectorImpl(deviceCapability, storage, candidates = qualityOrderedCandidates())
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.OUT_OF_MEMORY_PREVENTED, (result as AiResult.Fallback).reason)
        assertEquals(0, model.generatePlanCallCount)
    }

    // T-P85-14: 正常・既存回帰の固定化 - 明示選択(Gemma4-like)・両方導入・availMem=2.0GiB
    // (Gemma4-likeは不適合・Qwen06b-likeなら適合する状況) → Qwenへの無音差し替えは起きず
    // Fallback(OUT_OF_MEMORY_PREVENTED)・fakeモデルは1回も呼ばれない
    // （A54実測phase8-a54-ram-tier-fix.md§10.3のGreen回帰ロック）
    @Test
    fun tP85_14_explicitGemma4Like_insufficientForGemma4ButQwenWouldFit_noSilentSubstitution() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP85_14")
            .apply { selectedModelId = fakeGemma4LikeEntry().id }
        val storage = installedModelStorageWithEntries(entries, preferences)
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(availMemBytes = 2L * GB),
            preferences = preferences
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.OUT_OF_MEMORY_PREVENTED, (result as AiResult.Fallback).reason)
        assertEquals(
            "明示選択したGemma4-likeが不適合でも、導入済みのQwen06b-likeへ無音で差し替えては" +
                "いけません(F-A原則)",
            0,
            model.generatePlanCallCount
        )
    }

    // T-P85-15: 正常 - 明示選択(Qwen06b-like)・両方導入・availMem=2.0GiB(Qwen06b-likeは適合) →
    // ModelSelectorを経由せずQwen06b-likeへ進む
    @Test
    fun tP85_15_explicitQwen06bLike_installedAndSufficient_proceedsWithQwen06bLike() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP85_15")
            .apply { selectedModelId = fakeQwen06bLikeEntry().id }
        val storage = installedModelStorageWithEntries(entries, preferences)
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(availMemBytes = 2L * GB),
            preferences = preferences
        )

        gateway.generatePlan(planningContext())

        assertEquals(
            storage.finalFile(fakeQwen06bLikeEntry()).absolutePath,
            model.recordedModelPaths.single()
        )
    }

    // T-P85-28: 正常 - auto解決がQwen06b-likeのfinalFile → (availMemを回復させ再呼び出し) →
    // Gemma4-likeのfinalFileへ切り替わるべき。auto分岐が未実装のためcatalog順fallbackのまま
    // 変化しない
    @Test
    fun tP85_28_auto_modelPathSwitchesWhenAvailMemRecovers() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP85_28")
            .apply { selectedModelId = AiPreferences.AUTO_SELECT_MODEL_ID }
        val storage = installedModelStorageWithEntries(entries, preferences)
        val model = FakeLocalLanguageModel(
            listOf(
                PlanCallOutcome.Respond(validSingleStepResponse()),
                PlanCallOutcome.Respond(validSingleStepResponse())
            )
        )
        val deviceCapability = supportedDeviceCapability(availMemBytes = 2L * GB) // Qwen06b-likeのみ適合
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapability,
            preferences = preferences,
            modelSelector = ModelSelectorImpl(deviceCapability, storage, candidates = qualityOrderedCandidates())
        )

        gateway.generatePlan(planningContext())

        // availMemを回復させる(同一ActivityManagerのshadowを再設定、両方適合する量へ)
        val recoveredInfo = ActivityManager.MemoryInfo().apply {
            totalMem = 8L * GB
            availMem = 3L * GB
            threshold = 0L
            lowMemory = false
        }
        shadowOf(activityManager()).setMemoryInfo(recoveredInfo)

        gateway.generatePlan(planningContext())

        assertEquals(2, model.recordedModelPaths.size)
        assertEquals(
            "1回目(availMem=2GiB)はQwen06b-likeが選ばれるべきです",
            storage.finalFile(fakeQwen06bLikeEntry()).absolutePath,
            model.recordedModelPaths[0]
        )
        assertEquals(
            "2回目(availMem回復後=3GiB)はGemma4-likeへ切り替わるべきです(auto選択の再評価)",
            storage.finalFile(fakeGemma4LikeEntry()).absolutePath,
            model.recordedModelPaths[1]
        )
    }

    // T-P85-29: 回帰 - 明示選択(Gemma4-like)・導入済み・availMem十分 → model.generatePlanへ
    // 渡るmodelPathがmodelStorage.finalFile(gemma4Entry).absolutePathと一致する
    @Test
    fun tP85_29_explicitSelection_modelPathMatchesResolvedEntry() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP85_29")
            .apply { selectedModelId = fakeGemma4LikeEntry().id }
        val storage = installedModelStorageWithEntries(entries, preferences)
        val model = FakeLocalLanguageModel(listOf(PlanCallOutcome.Respond(validSingleStepResponse())))
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(availMemBytes = 3L * GB),
            preferences = preferences
        )

        gateway.generatePlan(planningContext())

        assertEquals(
            storage.finalFile(fakeGemma4LikeEntry()).absolutePath,
            model.recordedModelPaths.single()
        )
    }

    // ------------------------------------------------------------------
    // Phase 9（計画書`docs/plans/phase9-recovery-ai.md`§3.2・§7、ADR-0063想定）: generateRecoveryの
    // modelPath配線・auto選択の共用（T-P9-26〜28）。generateRecovery本体（LocalAiGateway・
    // LiteRtLmLocalLanguageModelとも）が`TODO()`のため、全件`NotImplementedError`により
    // Redになるのが正しい。
    // ------------------------------------------------------------------

    private fun recoveryContext(): RecoveryContext = RecoveryContext(
        currentTime = Instant.parse("2026-08-10T09:15:00Z"),
        currentLocation = null,
        event = sampleEvent(),
        unfinishedSteps = emptyList(),
        latestTravelEstimate = Duration.ofMinutes(20),
        plannedDepartureTime = Instant.parse("2026-08-10T09:00:00Z")
    )

    private fun sampleRecoveryOptions(): List<RecoveryOption> = listOf(
        RecoveryOption(
            id = java.util.UUID.randomUUID(),
            semanticAction = "keep_all_steps",
            title = "",
            explanation = "",
            estimatedArrival = Instant.parse("2026-08-10T09:30:00Z"),
            skippedStepIds = emptyList()
        )
    )

    // T-P9-26: 正常 - generateRecoveryがLocalAiGatewayの選択したmodelPathをそのままadapterへ渡す
    @Test
    fun tP9_26_generateRecovery_passesResolvedModelPathToAdapter() = runTest {
        val model = FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                RecoveryCallOutcome.Respond(
                    JSONObject().put(
                        "options",
                        JSONArray().put(
                            JSONObject().put("semantic_action", "keep_all_steps").put("explanation", "Finish getting ready.")
                        )
                    ).toString()
                )
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP9_26")
        )

        gateway.generateRecovery(recoveryContext(), sampleRecoveryOptions())

        assertEquals(
            "generateRecoveryはgeneratePlanと同じ解決済みmodelPathをそのままadapterへ渡すべきです(T-P9-26)",
            installedModelStorage().finalFile(fakeInstalledEntry()).absolutePath,
            model.recordedRecoveryModelPaths.single()
        )
    }

    // T-P9-27: 正常（回帰） - generateRecoveryもModelSelector/auto既定を経由する
    // （Recovery専用の解決経路が存在しないことの回帰確認）
    @Test
    fun tP9_27_generateRecovery_alsoRoutesThroughAutoSelection_noRecoverySpecificResolutionPath() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP9_27")
            .apply { selectedModelId = AiPreferences.AUTO_SELECT_MODEL_ID }
        val storage = installedModelStorageWithEntries(entries, preferences)
        val model = FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                RecoveryCallOutcome.Respond(
                    JSONObject().put(
                        "options",
                        JSONArray().put(
                            JSONObject().put("semantic_action", "keep_all_steps").put("explanation", "Finish getting ready.")
                        )
                    ).toString()
                )
            )
        )
        val deviceCapability = supportedDeviceCapability(availMemBytes = 2L * GB) // Qwen06b-likeのみ適合
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapability,
            preferences = preferences,
            modelSelector = ModelSelectorImpl(deviceCapability, storage, candidates = qualityOrderedCandidates())
        )

        gateway.generateRecovery(recoveryContext(), sampleRecoveryOptions())

        assertEquals(
            "generateRecoveryもModelSelectorのauto解決結果(この条件ではQwen06b-like)を使うべきです(T-P9-27)",
            storage.finalFile(fakeQwen06bLikeEntry()).absolutePath,
            model.recordedRecoveryModelPaths.single()
        )
    }

    // T-P9-28（born-green・限定的な回帰確認）: EngineLoadPolicy.requiresEngineReloadは呼び出し元
    // （Plan/Recovery）を区別しない純粋関数であるため、generateRecoveryが同じ関数を再利用しさえ
    // すれば同一パス時のEngine再利用は構造的に成立する。実際のEngine生成回数の実機検証は
    // LiteRtLmLocalLanguageModel.generateRecoveryの実装完了後（コミット3以降）にandroidTest
    // プローブで別途行う（本テストのスコープ外、計画書§6「検証境界の明記」と同型の限定）。
    @Test
    fun tP9_28_engineLoadPolicy_isCallerAgnostic_reusableForRecoveryPathToo() {
        val planPath = "/data/models/qwen3-0.6b.litertlm"

        assertTrue(
            "同一パスへの2回目の要求はPlan/Recoveryを問わず再ロード不要と判定するべきです(T-P9-28)",
            !requiresEngineReload(loadedModelPath = planPath, requestedModelPath = planPath)
        )
        assertTrue(
            "異なるパスへの要求はPlan/Recoveryを問わず再ロード必要と判定するべきです(T-P9-28)",
            requiresEngineReload(loadedModelPath = planPath, requestedModelPath = "/data/models/gemma-4-e2b-it.litertlm")
        )
    }

    // ------------------------------------------------------------------
    // Phase 9（計画書§4.2〜4.5、コミット2、ADR-0063想定）: L2/L3/L5のGateway統合レベル確認
    // （T-P9-19〜23）。**L2（ContentSanityChecker.checkRecovery）はコミット2 Redの本コミット時点
    // ではGatewayパイプラインへまだ配線されていない**（コミット1完了時点で701件Greenだった
    // パイプラインを壊さないため）。したがって以下は`NotImplementedError`ではなく**振る舞いの
    // 期待値との不一致（assertion failure）によるRed**が正しい——Primaryの"歯科検診"エコー応答が
    // 現状は内容検査なしにそのまま受理されてしまう、というL2未配線の実態を可視化する。
    // ------------------------------------------------------------------

    private fun dentalCheckupEchoRecoveryJson(): String = JSONObject().put(
        "options",
        JSONArray().put(JSONObject().put("semantic_action", "keep_all_steps").put("explanation", "歯科検診"))
    ).toString()

    private fun cleanRecoveryJson(): String = JSONObject().put(
        "options",
        JSONArray().put(
            JSONObject().put("semantic_action", "keep_all_steps").put("explanation", "そのまま準備を続けて出発しましょう")
        )
    ).toString()

    // T-P9-19: 正常・実例接地 - Primary出力が"歯科検診"エコー→L2 reject→Retry(topK5/temp0.15)
    // 呼び出し→Retry出力が正常→Success
    @Test
    fun tP9_19_primaryEchoesFewShotTitle_l2RejectsAndRetrySucceeds() = runTest {
        val model = FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                RecoveryCallOutcome.Respond(dentalCheckupEchoRecoveryJson()),
                RecoveryCallOutcome.Respond(cleanRecoveryJson())
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP9_19")
        )

        val result = gateway.generateRecovery(recoveryContext(), sampleRecoveryOptions())

        assertEquals(
            "L2がPrimaryの'歯科検診'エコーをrejectしRetryへ昇格するべきです(T-P9-19、コミット2 GreenでL2配線済み)",
            2,
            model.generateRecoveryCallCount
        )
        assertTrue(
            "Retryが正常な文言を返せばSuccessになるべきです(T-P9-19)",
            result is AiResult.Success
        )
    }

    // T-P9-20: 異常・実例接地 - Primary/Retryとも"歯科検診"エコーを再現（決定的サンプリングの
    // 再現性、§12.5「2回目生成でも同一出力」を模した二重fakeを使用）→Fallback(SCHEMA_INVALID)、
    // 無加工Basicへ縮退
    @Test
    fun tP9_20_bothAttemptsEchoFewShotTitle_fallsBackWithSchemaInvalid() = runTest {
        val model = FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                RecoveryCallOutcome.Respond(dentalCheckupEchoRecoveryJson()),
                RecoveryCallOutcome.Respond(dentalCheckupEchoRecoveryJson())
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP9_20")
        )

        val result = gateway.generateRecovery(recoveryContext(), sampleRecoveryOptions())

        assertTrue(
            "Primary/Retryとも内容不良ならFallback(SCHEMA_INVALID)になるべきです" +
                "(T-P9-20、コミット2 GreenでL2配線済み): $result",
            result is AiResult.Fallback && result.reason == AiFallbackReason.SCHEMA_INVALID
        )
    }

    // T-P9-21: 正常 - Primary成功（reject無し）→sanityRejectCount=0・lastSanityRejectReason=null
    @Test
    fun tP9_21_primarySucceedsWithoutReject_metricsShowZeroRejects() = runTest {
        val model = FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(RecoveryCallOutcome.Respond(cleanRecoveryJson()))
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP9_21")
        )

        val result = gateway.generateRecovery(recoveryContext(), sampleRecoveryOptions())

        assertTrue(result is AiResult.Success)
        val metrics = (result as AiResult.Success).metrics
        assertEquals(0, metrics.sanityRejectCount)
        assertEquals(null, metrics.lastSanityRejectReason)
    }

    // T-P9-22: 正常 - PrimaryがR1でreject後Retry成功→sanityRejectCount=1・
    // lastSanityRejectReason=FEW_SHOT_ECHO
    @Test
    fun tP9_22_primaryRejectedByR1_retrySucceeds_metricsRecordOneReject() = runTest {
        val model = FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                RecoveryCallOutcome.Respond(dentalCheckupEchoRecoveryJson()),
                RecoveryCallOutcome.Respond(cleanRecoveryJson())
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP9_22")
        )

        val result = gateway.generateRecovery(recoveryContext(), sampleRecoveryOptions())

        assertTrue(
            "PrimaryがR1a(FEW_SHOT_ECHO)でrejectされRetry成功後のmetricsに反映されるべきです(T-P9-22): $result",
            result is AiResult.Success &&
                (result as AiResult.Success).metrics.sanityRejectCount == 1 &&
                result.metrics.lastSanityRejectReason == SanityRejectReason.FEW_SHOT_ECHO
        )
    }

    // T-P9-23: 異常 - 両attemptともreject→Fallback.metricsにsanityRejectCount=2が記録される
    // （AiResult.Fallback.metrics新設フィールドの検証）
    @Test
    fun tP9_23_bothAttemptsRejected_fallbackMetricsRecordTwoRejects() = runTest {
        val model = FakeLocalLanguageModel(
            outcomes = emptyList(),
            recoveryOutcomes = listOf(
                RecoveryCallOutcome.Respond(dentalCheckupEchoRecoveryJson()),
                RecoveryCallOutcome.Respond(dentalCheckupEchoRecoveryJson())
            )
        )
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = installedModelStorage(),
            modelVerifier = verifier(),
            deviceCapability = supportedDeviceCapability(),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP9_23")
        )

        val result = gateway.generateRecovery(recoveryContext(), sampleRecoveryOptions())

        assertTrue(
            "両attemptがrejectされたFallback.metricsにsanityRejectCount=2が記録されるべきです(T-P9-23): $result",
            result is AiResult.Fallback && (result as AiResult.Fallback).metrics?.sanityRejectCount == 2
        )
    }

    // ------------------------------------------------------------------
    // Phase 9.5（計画書`docs/plans/phase9.5-performance-quality.md`§3.10 F-5・§14発見②、
    // 優先繰り上げ）: ロード済みモデルパスに対するOOM事前ガードのスキップ判定。
    // LocalAiGatewayの現行実装はまだEngineLoadStateSourceを一切問い合わせないため、
    // 「ロード済み+availMem低」のケースは従来どおりOUT_OF_MEMORY_PREVENTEDとなり、期待
    // （ガードスキップ→モデル呼び出しを試行）とAssertionErrorで不一致になるのが正しい
    // （commit 2のT-P9-19〜23と同型のbehavioral-gap Red）。
    // ------------------------------------------------------------------

    /** F-5専用fake。[EngineLoadStateSource]を実装し「指定パスが既にロード済み」を任意に模す。 */
    private class LoadAwareFakeModel(
        private val loadedPath: String?,
        private val planRawJson: String? = null,
        private val recoveryRawJson: String? = null
    ) : LocalLanguageModel, EngineLoadStateSource {
        override val modelIdentifier: String = "load-aware-fake-model"

        var generatePlanCallCount: Int = 0
            private set
        var generateRecoveryCallCount: Int = 0
            private set

        override fun loadedModelPath(): String? = loadedPath

        override suspend fun generatePlan(context: PlanningContext, modelPath: String, samplingPolicy: SamplingPolicy): String {
            generatePlanCallCount += 1
            return planRawJson ?: throw UnsupportedOperationException("F-5 Recovery専用テストのため未使用")
        }

        override suspend fun generateRecovery(
            context: RecoveryContext,
            options: List<RecoveryOption>,
            modelPath: String,
            samplingPolicy: SamplingPolicy
        ): String {
            generateRecoveryCallCount += 1
            return recoveryRawJson ?: throw UnsupportedOperationException("F-5 Plan専用テストのため未使用")
        }
    }

    // T-P95-42（F-5）: 異常→正常 - 解決済みモデルパスが既にロード済み（EngineLoadStateSource経由）の
    // 場合、Plan生成はavailMemが通常ガードの閾値を下回っていてもOOM事前ガードをスキップし
    // モデル呼び出しを試行する。
    @Test
    fun tP95_42_generatePlan_engineAlreadyLoadedForResolvedPath_skipsOomGuardEvenWithLowAvailMem() = runTest {
        val entry = fakeInstalledEntry()
        val storage = installedModelStorage()
        val resolvedModelPath = storage.finalFile(entry).absolutePath
        val model = LoadAwareFakeModel(loadedPath = resolvedModelPath, planRawJson = validSingleStepResponse())
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = 100L * 1024 * 1024, "arm64-v8a"),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP95_42")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(
            "ロード済みモデルパスであればavailMemが低くてもOOM事前ガードをスキップしモデル呼び出しを" +
                "試行するべきです(T-P95-42、F-5): $result",
            result !is AiResult.Fallback || (result as AiResult.Fallback).reason != AiFallbackReason.OUT_OF_MEMORY_PREVENTED
        )
        assertEquals(1, model.generatePlanCallCount)
    }

    // T-P95-43（F-5回帰）: 未ロード（EngineLoadStateSource.loadedModelPath()がnull）+availMem低の
    // 場合は従来どおりOUT_OF_MEMORY_PREVENTEDを維持する。
    @Test
    fun tP95_43_generatePlan_engineNotLoaded_stillAppliesOomGuardWithLowAvailMem() = runTest {
        val storage = installedModelStorage()
        val model = LoadAwareFakeModel(loadedPath = null, planRawJson = validSingleStepResponse())
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = 100L * 1024 * 1024, "arm64-v8a"),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP95_43")
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(
            "未ロードならavailMemが低い場合は従来どおりOUT_OF_MEMORY_PREVENTEDになるべきです" +
                "(T-P95-43、F-5回帰): $result",
            result is AiResult.Fallback && (result as AiResult.Fallback).reason == AiFallbackReason.OUT_OF_MEMORY_PREVENTED
        )
        assertEquals(0, model.generatePlanCallCount)
    }

    // T-P95-44（F-5、Recovery版）: 異常→正常 - generateRecoveryもgeneratePlanと同一構造のガードを
    // 個別に持つため、同一の修正・同一の検証を行う。
    @Test
    fun tP95_44_generateRecovery_engineAlreadyLoadedForResolvedPath_skipsOomGuardEvenWithLowAvailMem() = runTest {
        val entry = fakeInstalledEntry()
        val storage = installedModelStorage()
        val resolvedModelPath = storage.finalFile(entry).absolutePath
        val model = LoadAwareFakeModel(loadedPath = resolvedModelPath, recoveryRawJson = cleanRecoveryJson())
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = 100L * 1024 * 1024, "arm64-v8a"),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP95_44")
        )

        val result = gateway.generateRecovery(recoveryContext(), sampleRecoveryOptions())

        assertTrue(
            "ロード済みモデルパスであればavailMemが低くてもOOM事前ガードをスキップしモデル呼び出しを" +
                "試行するべきです(T-P95-44、F-5): $result",
            result !is AiResult.Fallback || (result as AiResult.Fallback).reason != AiFallbackReason.OUT_OF_MEMORY_PREVENTED
        )
        assertEquals(1, model.generateRecoveryCallCount)
    }

    // T-P95-45（F-5回帰、Recovery版）: 未ロード+availMem低の場合は従来どおり
    // OUT_OF_MEMORY_PREVENTEDを維持する。
    @Test
    fun tP95_45_generateRecovery_engineNotLoaded_stillAppliesOomGuardWithLowAvailMem() = runTest {
        val storage = installedModelStorage()
        val model = LoadAwareFakeModel(loadedPath = null, recoveryRawJson = cleanRecoveryJson())
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = 100L * 1024 * 1024, "arm64-v8a"),
            preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP95_45")
        )

        val result = gateway.generateRecovery(recoveryContext(), sampleRecoveryOptions())

        assertTrue(
            "未ロードならavailMemが低い場合は従来どおりOUT_OF_MEMORY_PREVENTEDになるべきです" +
                "(T-P95-45、F-5回帰): $result",
            result is AiResult.Fallback && (result as AiResult.Fallback).reason == AiFallbackReason.OUT_OF_MEMORY_PREVENTED
        )
        assertEquals(0, model.generateRecoveryCallCount)
    }

    // T-P95-48（F-5、auto経路・§14実測ケース接地、Red検収での差し戻し訂正）: 異常→正常 -
    // M実測（§14発見①・②）で実際に観測した経路そのもの: auto選択でロード済みQwenがavailMem
    // 不足によりModelSelector.select()の段階で候補から弾かれ（unresolvedEntryFallback()の
    // OUT_OF_MEMORY_PREVENTED「auto: no candidate fits」で再現）、post-selectionガードへ
    // 到達すらしなかった欠陥が、ModelSelectorImplへのengineLoadStateSource注入により解消し
    // Successへ至る。T-P95-42はexplicit選択経路のみを検証しておりこの経路をカバーしていなかった
    // ため、Red検収の指摘どおりauto経路版を追加する。
    @Test
    fun tP95_48_autoSelection_qwenAlreadyLoadedWithLowAvailMem_selectorNoLongerExcludesIt_generatePlanSucceeds() = runTest {
        val entries = autoTestCatalog()
        val preferences = preferences(aiEnabled = true, prefsFileName = "test_ai_prefs_tP95_48")
            .apply { selectedModelId = AiPreferences.AUTO_SELECT_MODEL_ID }
        val storage = installedModelStorageWithEntries(entries, preferences)
        val resolvedModelPath = storage.finalFile(fakeQwen06bLikeEntry()).absolutePath
        val model = LoadAwareFakeModel(loadedPath = resolvedModelPath, planRawJson = validSingleStepResponse())
        val deviceCapability = deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = 100L * 1024 * 1024, "arm64-v8a")
        val gateway = LocalAiGateway(
            model = model,
            modelStorage = storage,
            modelVerifier = verifier(),
            deviceCapability = deviceCapability,
            preferences = preferences,
            modelSelector = ModelSelectorImpl(
                deviceCapability,
                storage,
                candidates = qualityOrderedCandidates(),
                engineLoadStateSource = model
            )
        )

        val result = gateway.generatePlan(planningContext())

        assertTrue(
            "auto経路でロード済みQwenがavailMem不足で弾かれず選ばれ、モデル呼び出しを試行するべきです" +
                "(T-P95-48、F-5、§14発見①②実例接地): $result",
            result !is AiResult.Fallback || (result as AiResult.Fallback).reason != AiFallbackReason.OUT_OF_MEMORY_PREVENTED
        )
        assertEquals(1, model.generatePlanCallCount)
    }
}
