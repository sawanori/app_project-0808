package com.actionstarter.ai

import android.app.ActivityManager
import android.content.Context
import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceCapabilityImpl
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelLicense
import com.actionstarter.ai.model.ModelStorage
import com.actionstarter.ai.model.ModelStorageImpl
import com.actionstarter.ai.model.ModelVerifier
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import java.security.MessageDigest

/**
 * Phase 8（計画書§8ヘッダ「JVM層はLocalAiGatewayをfake化しAIPlanResponseを注入」）用の共有
 * テストfixture。[LocalAiGatewayTest]が確立した「fakeは[LocalLanguageModel]の境界のみ・
 * [LocalAiGateway]／`ModelStorageImpl`／`DeviceCapabilityImpl`／`AiPreferencesImpl`は実物を
 * Robolectricで駆動する」という既存方式（同ファイルKDoc「fake注入の設計」参照）を、
 * [LocalAiPlanContextualizerTest]と`features.PlanReviewViewModelTest`の両方から再利用する。
 * `internal`可視性はモジュール全体（テストソースセット含む）に及ぶため、別パッケージ
 * （`com.actionstarter.features`）からも参照できる。
 *
 * **なぜ`com.google.ai.edge.litertlm`実装（[com.actionstarter.ai.adapter.
 * LiteRtLmLocalLanguageModel]）を使わないか（domain-implementer実装時の実測発見）**: 当該AARの
 * クラスファイルバージョン（class file version 65=Java 21相当）が本機のJDK（17.0.19、
 * 認識上限61）を上回るため、本クラスをJVM（Robolectric）上で参照するだけで
 * `UnsupportedClassVersionError`が発生する（実測、`AppContainer.kt`のKDoc「LinkageError防御」
 * 参照）。既存の[LocalAiGatewayTest]／`SettingsAiSafetyTest`がいずれも実装を一切参照せず
 * [FakeLocalLanguageModel]のみで駆動している既存規約は、この制約とも整合する。
 */
internal object AiGatewayTestFixtures {
    private const val GB = 1024L * 1024 * 1024
    private val FAKE_MODEL_BYTES = "phase8-fake-installed-model-fixture-bytes".toByteArray()

    fun context(): Context = RuntimeEnvironment.getApplication()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

    /** [installedModelStorage]／[corruptedModelStorage]／[notInstalledModelStorage]が共有するfixtureエントリ。 */
    fun fakeInstalledEntry(): ModelCatalogEntry = ModelCatalogEntry(
        id = "p8-test-installed-model",
        displayName = "P8 Test Installed Model",
        downloadUrl = "https://example.invalid/p8-test-installed-model.litertlm",
        sha256 = sha256Hex(FAKE_MODEL_BYTES),
        sizeBytes = FAKE_MODEL_BYTES.size.toLong(),
        peakRamBytes = 1L * 1024 * 1024,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false
    )

    /** 「導入済み・検証合格」状態のModelStorage（§8.6 #11・#12を本物のロジックで通過させる）。 */
    fun installedModelStorage(): ModelStorage {
        val entry = fakeInstalledEntry()
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))
        storage.finalFile(entry).apply {
            parentFile?.mkdirs()
            writeBytes(FAKE_MODEL_BYTES)
        }
        return storage
    }

    /** 「未導入」状態（T-P8-7・MODEL_NOT_INSTALLED用）。 */
    fun notInstalledModelStorage(): ModelStorage =
        ModelStorageImpl(context(), catalog = listOf(fakeInstalledEntry()))

    /** 「導入済みだがファイルサイズが破損」状態（T-P8-11・MODEL_CORRUPTED用）。 */
    fun corruptedModelStorage(): ModelStorage {
        val entry = fakeInstalledEntry()
        val storage = ModelStorageImpl(context(), catalog = listOf(entry))
        storage.finalFile(entry).apply {
            parentFile?.mkdirs()
            writeBytes(FAKE_MODEL_BYTES + byteArrayOf(0)) // entry.sizeBytesと不一致にする
        }
        return storage
    }

    private fun deviceCapabilityWith(totalMemBytes: Long, availMemBytes: Long, abis: Array<String>): DeviceCapability {
        ShadowBuild.setSupportedAbis(abis)
        val activityManager = context().getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().apply {
            totalMem = totalMemBytes
            availMem = availMemBytes
            threshold = 0L
            lowMemory = false
        }
        shadowOf(activityManager).setMemoryInfo(info)
        return DeviceCapabilityImpl(context())
    }

    fun supportedDeviceCapability(availMemBytes: Long = 4L * GB): DeviceCapability =
        deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = availMemBytes, abis = arrayOf("arm64-v8a"))

    /** T-P8-10（前半）・OOM_PREVENTED用: 空きメモリを極小にする。 */
    fun lowMemoryDeviceCapability(): DeviceCapability =
        deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = 100L * 1024 * 1024, abis = arrayOf("arm64-v8a"))

    /** T-P8-12（前半）・UNSUPPORTED_DEVICE用。 */
    fun unsupportedRamDeviceCapability(): DeviceCapability =
        deviceCapabilityWith(totalMemBytes = 2L * GB, availMemBytes = 2L * GB, abis = arrayOf("arm64-v8a"))

    /** T-P8-12（後半）・UNSUPPORTED_ABI用。 */
    fun unsupportedAbiDeviceCapability(): DeviceCapability =
        deviceCapabilityWith(totalMemBytes = 8L * GB, availMemBytes = 8L * GB, abis = arrayOf("armeabi-v7a"))

    /**
     * [prefsFileName]はテストごとに一意にすること（SharedPreferencesファイルの衝突回避）。
     *
     * **Phase 8.5での変更（ADR-0062）**: `selectedModelId`の既定値が`AiPreferences.
     * AUTO_SELECT_MODEL_ID`へ変わったため、本fixtureを使う既存テスト（[LocalAiPlanContextualizerTest]
     * 等、モデル選択そのものはテスト対象外）がauto経路へ誤って迂回されないよう、既定で
     * [fakeInstalledEntry]を明示選択しておく（[LocalAiGatewayTest]の同名ヘルパーと同じ対応）。
     */
    fun preferences(aiEnabled: Boolean, prefsFileName: String): AiPreferences {
        val prefs = context().getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean(AiPreferencesImpl.KEY_AI_ENABLED, aiEnabled).commit()
        return AiPreferencesImpl(prefs).apply { selectedModelId = fakeInstalledEntry().id }
    }

    /**
     * 既定はAI呼び出しが成功する状態に揃えたgateway（[model]のみ差し替え可能）。異常系テストは
     * [modelStorage]／[deviceCapability]／[aiEnabled]を個別に上書きする。
     */
    fun readyGateway(
        model: LocalLanguageModel,
        prefsFileName: String,
        aiEnabled: Boolean = true,
        modelStorage: ModelStorage = installedModelStorage(),
        deviceCapability: DeviceCapability = supportedDeviceCapability(),
        modelVerifier: ModelVerifier = ModelVerifierImpl()
    ): LocalAiGateway = LocalAiGateway(
        model = model,
        modelStorage = modelStorage,
        modelVerifier = modelVerifier,
        deviceCapability = deviceCapability,
        preferences = preferences(aiEnabled = aiEnabled, prefsFileName = prefsFileName)
    )

    /** §20縮小スキーマ（event_type + steps[action_type, display_text]）の1step版。 */
    fun singleStepPlanJson(actionType: String, displayText: String, eventType: String = "business_meeting"): String =
        multiStepPlanJson(eventType, listOf(actionType to displayText))

    /** 複数step版（`Pair(actionType, displayText)`をAI出力順に並べる）。 */
    fun multiStepPlanJson(eventType: String, steps: List<Pair<String, String>>): String =
        JSONObject().apply {
            put("event_type", eventType)
            put(
                "steps",
                JSONArray().apply {
                    steps.forEach { (actionType, displayText) ->
                        put(
                            JSONObject().apply {
                                put("action_type", actionType)
                                put("display_text", displayText)
                            }
                        )
                    }
                }
            )
        }.toString()

    /** maxItems=8超過（9件）。SchemaValidator観点での不合格を意図する（T-P8-8用）。 */
    fun schemaInvalidNineStepResponse(): String {
        val steps = (0 until 9).map { "action_$it" to "Step $it" }
        return multiStepPlanJson("business_meeting", steps)
    }

    /**
     * Phase 9（計画書`docs/plans/phase9-recovery-ai.md`§3.3）。`RecoveryJsonSchema`の縮小契約
     * （`options[{semantic_action, explanation}]`）に沿った生JSON文字列を組み立てる
     * （[multiStepPlanJson]のRecovery版）。
     */
    fun recoveryOptionsJson(vararg options: Pair<String, String>): String =
        JSONObject().apply {
            put(
                "options",
                JSONArray().apply {
                    options.forEach { (semanticAction, explanation) ->
                        put(
                            JSONObject().apply {
                                put("semantic_action", semanticAction)
                                put("explanation", explanation)
                            }
                        )
                    }
                }
            )
        }.toString()

    /**
     * [LocalAiGatewayTest.FakeLocalLanguageModel]と同型のfake。[cancelledCount]はT-P8-22
     * （collectLatestによる構造化キャンセル）の直接検証に使う——[delayMillisPerCall]中に
     * `CancellationException`を受けたら記録してから再送出する（§4.3・T-GW-13と同じく
     * 握り潰さない）。
     *
     * **Phase 9追加（計画書`docs/plans/phase9-recovery-ai.md`§3.2、既定値付き・後方互換）**:
     * [recoveryOutcomes]（既定空リスト）を追加した。空のままなら[generateRecovery]は
     * 従来どおり`UnsupportedOperationException`を投げる（Plan専用のfixtureとして使う既存呼び出し
     * 元は無変更のまま成立する）。非空の場合のみ[outcomes]と同型の消費ロジックで応答する
     * （[LocalAiRecoveryContextualizerTest]・T-P9-26〜28が使う）。
     */
    class FakeLocalLanguageModel(
        private val outcomes: List<Outcome>,
        private val delayMillisPerCall: Long = 0L,
        private val recoveryOutcomes: List<Outcome> = emptyList()
    ) : LocalLanguageModel {
        override val modelIdentifier: String = "phase8-fake-model"

        var callCount: Int = 0
            private set

        var cancelledCount: Int = 0
            private set

        /** Phase 9追加。[generateRecovery]の呼び出し回数（[callCount]のRecovery版）。 */
        var recoveryCallCount: Int = 0
            private set

        /**
         * Phase 8.5追加（計画書`docs/plans/phase8.5-adaptive-model-selection.md`§3設計5、
         * ADR-0062、アーキテクトレビューPass 1 CRITICAL対応）。呼び出しごとに受け取った
         * `modelPath`を記録する（T-P85-28/29が「解決済みモデルと実際に渡されるパスが一致するか」
         * を検証するために使う、[recordedSamplingPolicies]と同型のパターン）。
         */
        val recordedModelPaths: MutableList<String> = mutableListOf()

        /** Phase 9追加（計画書§3.2、T-P9-26）。[generateRecovery]呼び出しごとの`modelPath`記録。 */
        val recordedRecoveryModelPaths: MutableList<String> = mutableListOf()

        sealed interface Outcome {
            data class Respond(val rawJson: String) : Outcome
            data class ThrowError(val error: Throwable) : Outcome
        }

        override suspend fun generatePlan(context: PlanningContext, modelPath: String, samplingPolicy: SamplingPolicy): String {
            recordedModelPaths.add(modelPath)
            val outcome = outcomes.getOrElse(callCount) { outcomes.last() }
            callCount += 1
            if (delayMillisPerCall > 0) {
                try {
                    delay(delayMillisPerCall)
                } catch (e: CancellationException) {
                    cancelledCount += 1
                    throw e
                }
            }
            return when (outcome) {
                is Outcome.Respond -> outcome.rawJson
                is Outcome.ThrowError -> throw outcome.error
            }
        }

        /**
         * Phase 9追加（計画書§3.2）。[recoveryOutcomes]が空のままなら（既定）Plan専用fixture
         * としての従来動作（`UnsupportedOperationException`）を維持する。非空の場合のみ
         * [generatePlan]と同型の消費ロジックで応答する。
         */
        override suspend fun generateRecovery(
            context: RecoveryContext,
            options: List<RecoveryOption>,
            modelPath: String,
            samplingPolicy: SamplingPolicy
        ): String {
            if (recoveryOutcomes.isEmpty()) {
                throw UnsupportedOperationException("Phase 8 fixtureでは未使用（recoveryOutcomesが空のまま）")
            }
            recordedRecoveryModelPaths.add(modelPath)
            val outcome = recoveryOutcomes.getOrElse(recoveryCallCount) { recoveryOutcomes.last() }
            recoveryCallCount += 1
            return when (outcome) {
                is Outcome.Respond -> outcome.rawJson
                is Outcome.ThrowError -> throw outcome.error
            }
        }
    }
}
