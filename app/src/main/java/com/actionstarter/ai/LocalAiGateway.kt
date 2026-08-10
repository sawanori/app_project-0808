package com.actionstarter.ai

import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceTier
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelStorage
import com.actionstarter.ai.model.ModelVerificationResult
import com.actionstarter.ai.model.ModelVerifier
import com.actionstarter.ai.schema.ContentSanityChecker
import com.actionstarter.ai.schema.ContentSanityResult
import com.actionstarter.ai.schema.SchemaValidationResult
import com.actionstarter.ai.schema.SchemaValidator
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * F96実装（計画書§7.1・§8.5・§8.6・§14 P7-C1／C5／P7契約確定）。`ai/`パッケージの唯一の外部
 * 公開点（S-3の解決）。[LocalLanguageModel]（§16、Fable 5裁定3で戻り値のみ契約変更済み）は
 * 失敗を例外でしか表現できないため、本クラスがその上位で[AiResult.Success]／
 * [AiResult.Fallback]の封じ込み型へ変換する。
 *
 * **契約（§8.5）**: 例外を外へ出さない。`Throwable`は全て[AiResult.Fallback]へ写像し、
 * 必ず`reason`と`detail`を埋めAnalyticsへ記録する（§95.6「サイレントに握り潰さない」）。
 * ただし`kotlinx.coroutines.CancellationException`は握り潰さず再送出する
 * （§8.7原則3・T-GW-13。構造化並行性を壊さない）。
 *
 * **検証パイプライン（Fable 5裁定3・9、2026-08-10、ADR-0047・ADR-0050。[model]呼び出し後の
 * 内部処理）**:
 * ```text
 * model.generatePlan(context, SamplingPolicy.Primary) の戻り値 rawJson: String   … 1回目
 *   → SchemaValidator.validate(rawJson)  … ①形式検証（enum/件数/長さ/additionalProperties）
 *   → ContentSanityChecker.check(response, context) … ②内容sanity（捏造・コピー・locale・重複）
 *   → ①②通過 → AiResult.Success(AIPlanResponse, metrics)
 *   → ①または②不合格 → retry 1回:
 *       model.generatePlan(context, SamplingPolicy.Retry) … 2回目（新規single-turnセッション＋
 *       微小摂動＋静的制約。品質ハーネス§4/§6・S-2是正）
 *     → 再度①② → なお不合格 → AiResult.Fallback(SCHEMA_INVALID)
 * ```
 * **`samplingPolicy`の呼び分けはGatewayの責務（Fable 5裁定9、ADR-0050）**: 1回目は
 * [SamplingPolicy.Primary]、①または②の不合格による2回目は[SamplingPolicy.Retry]を
 * [model]へ明示的に渡す。[model]（[com.actionstarter.ai.adapter.LiteRtLmLocalLanguageModel]、
 * P7-C5）は検証の成否を知らず、渡された[SamplingPolicy]に従って生成するだけである
 * （[SamplingPolicy]のKDoc「責務分担」参照）。**P7-C3でこの呼び分け自体を実装済み**
 * （T-GW-19・T-GW-20が回帰ロックする。[model]側の実際の`SamplerConfig`マッピングはP7-C5）。
 *
 * **ContentSanityCheckerの実配線（P7-C3・判断事項、下記「配線の判断」参照）**: 当初ADR-0047は
 * [com.actionstarter.ai.schema.ContentSanityChecker]の実配線をP7-C5としていたが、Gatewayの
 * 3段検証パイプラインをGreenにするには本サイクルで配線が必要だった。[SchemaValidator]・
 * [ContentSanityChecker]は共にコンストラクタ引数を持たない状態レス（純粋関数的）クラスであり、
 * **コンストラクタへ注入するのではなく本クラスのprivateフィールドとして直接インスタンス化した**
 * （fake差し替えの必要がないため。テストはいずれも本物の検証結果を要求しており、
 * fakeにする理由がない）。この設計により**`AppContainer.kt`は一切変更不要**（凍結ファイルへの
 * 波及なし）。
 *
 * **[generatePlan]が実行時に確認する順序（§8.6の発動条件表・§12.5 T-GW-1〜18が規定）**:
 * 1. [preferences].aiEnabled（false → [AiFallbackReason.AI_DISABLED]、§8.6 #10）
 * 2. [deviceCapability]のRAM／ABI判定（不適合 → [AiFallbackReason.UNSUPPORTED_DEVICE]／
 *    [AiFallbackReason.UNSUPPORTED_ABI]、§8.6 #1・#2）
 * 3. **[modelStorage]導入済みチェック（§8.6 #11）・[modelVerifier]ロード前再検証（§8.6 #12）**。
 *    P7-C4（ADR-0053・ADR-0054）で配線済み。[checkInstalledModel]参照
 * 4. [deviceCapability]の現在の空きメモリ確認（不足 → ロード・推論を実行せず即座に
 *    [AiFallbackReason.OUT_OF_MEMORY_PREVENTED]、§8.6 #7、Gemini G1 CRITICAL #3・主防御）
 * 5. [model]呼び出し（`model.generatePlan(context, SamplingPolicy.Primary)`。
 *    [requestTimeoutMillis]で`withTimeout`。タイムアウト → [AiFallbackReason.TIMEOUT]、§8.6 #8）
 * 6. 上記「検証パイプライン」（①形式・②内容sanity）。不合格→`model.generatePlan(context,
 *    SamplingPolicy.Retry)`で**新規single-turnセッションでの微小摂動再生成＋静的制約追加**で
 *    [model]をもう一度呼び出す（retry 1回、S-2是正・品質ハーネス§4/§6・Fable 5裁定9・
 *    ADR-0050）→再度不合格なら[AiFallbackReason.SCHEMA_INVALID]、§8.6 #9・§20
 *
 * **modelStorage/modelVerifierチェックの配線（P7-C4、ADR-0053・ADR-0054、ADR-0051の再検討
 * トリガーへの回答）**: P7-C3は`ModelStorage`のファイル配置規約が未確定だったため、この2ステップを
 * `generatePlan`の実行パスから意図的に除外していた（ADR-0051）。P7-C4で配置規約（`ModelStorage`の
 * `installedEntry`／`installedModelPath`が`catalog`〔既定[com.actionstarter.ai.model.
 * ModelCatalog.ALL]〕を走査し[com.actionstarter.ai.model.ModelStorage.finalFile]の実在で判定する
 * 方式）を確定させたことに伴い、本サイクルで配線した。[checkInstalledModel]が3〜4の間（[inferenceMutex]
 * 内、2回目以降の同時呼び出しからも直列化される）で次を行う:
 * - [modelStorage.installedEntry]／[modelStorage.installedModelPath]のいずれかが`null`
 *   → [AiFallbackReason.MODEL_NOT_INSTALLED]（§8.6 #11、T-GW-3）
 * - 毎回: 実ファイルサイズと[ModelCatalogEntry.sizeBytes]の照合。不一致 → 削除して
 *   [AiFallbackReason.MODEL_CORRUPTED]
 * - プロセス内（＝本[LocalAiGateway]インスタンス）で当該エントリを未検証の場合のみ
 *   [modelVerifier.verify]でSHA-256を再検証し、結果を[sha256VerifiedEntryId]へキャッシュする
 *   （§8.6 #12「以後の呼び出しでは再計算しない」、Gemini G1 CRITICAL #2、T-GW-18）。不一致
 *   → 削除して[AiFallbackReason.MODEL_CORRUPTED]
 * [modelStorage]／[modelVerifier]はP7-C1からのコンストラクタ引数をそのまま使う（シグネチャ変更なし）。
 *
 * **Analytics記録の設計は未確定（P7-C2への申し送り、本サイクルでも未解消）**: T-GW-14（全
 * Fallback経路でAnalytics記録が1回呼ばれる）を満たすための注入可能な収集口が必要だが、
 * 本プロジェクトにAnalytics基盤がまだ存在しない（`AnalyticsStore`はPhase 10、Analytics実装は
 * Phase 12、`ARCHITECTURE.md`§1）。**Fable 5裁定7（2026-08-10、ADR-0049）**:
 * Analytics収集用コラボレータは本コンストラクタへ追加しない。Phase 10（`AnalyticsStore`
 * 導入）／Phase 12（Analytics実装）でAnalytics基盤そのものと一体で設計し直す
 * （T-GW-14は既存P7-C2完了記録のとおり据え置き、新規のワークアラウンドを導入しない）。
 *
 * **P7-C3実装済み（Green）**: [Mutex]による直列化（T-GW-15）、`withTimeout`による打ち切り
 * （T-GW-6）、retry 1回（T-GW-7・8・19・20）、例外→[AiResult.Fallback]写像
 * （T-GW-4・5・9・10・12・13・17）を実装した（T-GW-1〜10・12・13・15・17・19・20・T-AIMET-1。
 * 対象外ID・帰属確定は`LocalAiGatewayTest`のクラスKDoc、および裁定6・8〔ADR-0049〕参照）。
 * [AiMetrics]の`modelLoadMs`／`firstTokenMs`／`outputTokens`／`tokensPerSecond`／
 * `peakNativeHeapBytes`は、実測用の`BenchmarkInfo`を持つ[com.actionstarter.ai.adapter.
 * LiteRtLmLocalLanguageModel]（P7-C5）が未実装のため`0`のプレースホルダとした
 * （`totalMs`のみGateway境界で実測可能なためKotlin側で計測する）。いずれのフィールドも
 * `LocalAiGatewayTest`で具体値を検証されておらず、後方互換に問題はない。
 *
 * @param model モデル実装。[LocalLanguageModel]型で受け取ることで§16「モデルは技術検証で
 *   交換可能にする」を維持する（本クラス自身は`com.google.ai.edge.litertlm`を一切importしない。
 *   T-AIISO-9）。
 * @param modelStorage F90。導入済みモデルの有無・パス確認に用いる（P7-C4で配線、上記
 *   「modelStorage/modelVerifierチェックの配線」参照）。
 * @param modelVerifier F89。ロード前の破損・改竄再検証に用いる（P7-C4で配線、同上）。
 * @param deviceCapability F91。静的な端末対応可否判定と、動的な空きメモリ確認の両方に使う
 *   （[com.actionstarter.ai.model.DeviceCapability]のKDoc参照）。
 * @param preferences F92。AI ON/OFF判定に用いる。
 * @param requestTimeoutMillis 推論タイムアウト（§8.6 #8「仮20,000ms」。G4-D実測〔§11.3〕で
 *   確定するまでの暫定値）。
 */
class LocalAiGateway(
    private val model: LocalLanguageModel,
    private val modelStorage: ModelStorage,
    private val modelVerifier: ModelVerifier,
    private val deviceCapability: DeviceCapability,
    private val preferences: AiPreferences,
    private val requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {
    private val schemaValidator = SchemaValidator()
    private val contentSanityChecker = ContentSanityChecker()

    /** T-GW-15。同一インスタンスへの同時呼び出しでも[model]呼び出しが重ならないよう直列化する。 */
    private val inferenceMutex = Mutex()

    /**
     * §8.6 #12「プロセス初回ロード前のみSHA-256再計算・以後の呼び出しでは再計算しない」の
     * プロセス内キャッシュ（Gemini G1 CRITICAL #2、T-GW-18）。[com.actionstarter.di.AppContainer]が
     * `by lazy`で1インスタンスのみ生成する設計（R-7）のため、本フィールドの寿命＝プロセスの
     * 寿命と一致する。[checkInstalledModel]は常に[inferenceMutex]内から呼ばれるため、この
     * `var`への書き込みはMutexで直列化されており追加の同期は不要。値は検証済みの
     * [ModelCatalogEntry.id]（検証対象が変わったら再検証する）。
     */
    private var sha256VerifiedEntryId: String? = null

    /**
     * §71完成条件の中核。[PlanningContext]から[AIPlanResponse]を生成し、[AiResult]で
     * 封じ込めて返す。内部の検証パイプラインはクラスKDoc「検証パイプライン」参照。
     * 例外は一切外へ送出しない（[CancellationException]を除く。§8.5・T-GW-13）。
     */
    suspend fun generatePlan(context: PlanningContext): AiResult<AIPlanResponse> {
        if (!preferences.aiEnabled) {
            return AiResult.Fallback(AiFallbackReason.AI_DISABLED, "AiPreferences.aiEnabled is false (§19既定OFF)")
        }
        if (deviceCapability.classify() == DeviceTier.TIER_0_UNSUPPORTED) {
            return AiResult.Fallback(
                AiFallbackReason.UNSUPPORTED_DEVICE,
                "Device totalMem is below the Local AI tier threshold (§5.3段0)"
            )
        }
        if (!deviceCapability.isAbiSupported()) {
            return AiResult.Fallback(
                AiFallbackReason.UNSUPPORTED_ABI,
                "Device SUPPORTED_ABIS does not include arm64-v8a (§8.2)"
            )
        }

        return inferenceMutex.withLock {
            val modelCheck = checkInstalledModel()
            if (modelCheck is InstalledModelCheck.Failed) return@withLock modelCheck.fallback
            val installedEntry = (modelCheck as InstalledModelCheck.Ready).entry

            val requiredPeakMemoryBytes = installedEntry.peakRamBytes + MEMORY_SAFETY_MARGIN_BYTES
            if (!deviceCapability.hasAvailableMemory(requiredPeakMemoryBytes)) {
                return@withLock AiResult.Fallback(
                    AiFallbackReason.OUT_OF_MEMORY_PREVENTED,
                    "availMem is below required peak RAM ($requiredPeakMemoryBytes bytes incl. safety margin, §8.6 #7)"
                )
            }

            runValidationPipeline(context)
        }
    }

    private sealed interface InstalledModelCheck {
        data class Ready(val entry: ModelCatalogEntry) : InstalledModelCheck
        data class Failed(val fallback: AiResult.Fallback) : InstalledModelCheck
    }

    /**
     * §8.6 #11（モデル未導入）・#12（ロード前再検証）。[inferenceMutex]内からのみ呼ばれる
     * （クラスKDoc「modelStorage/modelVerifierチェックの配線」参照）。
     */
    private fun checkInstalledModel(): InstalledModelCheck {
        val entry = modelStorage.installedEntry()
            ?: return InstalledModelCheck.Failed(
                AiResult.Fallback(AiFallbackReason.MODEL_NOT_INSTALLED, "ModelStorage.installedEntry() is null (§8.6 #11)")
            )
        // installedEntry()と同一の解決結果（finalFile）を使う。installedModelPath()を別途
        // 呼ぶと2回目のcatalog走査になり、理論上は間にファイルが変化するTOCTOUの窓も生まれる
        // ため、同じentryから直接導出する（installedModelPath()自体はLiteRtLmLocalLanguageModel
        // のmodelPathProvider経由で引き続き使われる、AppContainerのKDoc参照）。
        val file = modelStorage.finalFile(entry)

        // 毎回: サイズ照合（軽量）。
        if (file.length() != entry.sizeBytes) {
            modelStorage.delete(entry)
            sha256VerifiedEntryId = null
            return InstalledModelCheck.Failed(
                AiResult.Fallback(
                    AiFallbackReason.MODEL_CORRUPTED,
                    "Installed file size ${file.length()} != catalog ${entry.sizeBytes} (§8.6 #12)"
                )
            )
        }

        // プロセス内で当該エントリが未検証のときのみSHA-256を再検証する（§8.6 #12、T-GW-18）。
        if (sha256VerifiedEntryId != entry.id) {
            when (val verification = modelVerifier.verify(file, entry)) {
                is ModelVerificationResult.Invalid -> {
                    modelStorage.delete(entry)
                    sha256VerifiedEntryId = null
                    return InstalledModelCheck.Failed(
                        AiResult.Fallback(
                            AiFallbackReason.MODEL_CORRUPTED,
                            "SHA-256 re-verification failed: ${verification.reason} " +
                                "(§8.6 #12, Gemini G1 CRITICAL #2)"
                        )
                    )
                }

                ModelVerificationResult.Valid -> {
                    sha256VerifiedEntryId = entry.id
                }
            }
        }

        return InstalledModelCheck.Ready(entry)
    }

    private suspend fun runValidationPipeline(context: PlanningContext): AiResult<AIPlanResponse> {
        val startedAtNanos = System.nanoTime()

        val primaryAttempt = invokeModel(context, SamplingPolicy.Primary)
        if (primaryAttempt is ModelAttempt.Failed) return primaryAttempt.fallback
        val primaryValidation = validate((primaryAttempt as ModelAttempt.RawJson).text, context)
        if (primaryValidation is ValidationOutcome.Valid) {
            return AiResult.Success(
                primaryValidation.response,
                buildMetrics(retried = false, elapsedNanos = System.nanoTime() - startedAtNanos)
            )
        }

        // ①または②不合格 → retry 1回（新規single-turnセッション相当の SamplingPolicy.Retry で
        // 呼び直す。会話履歴の破棄自体は[model]実装〔P7-C5〕の内部関心事、S-2是正・ADR-0050）。
        val retryAttempt = invokeModel(context, SamplingPolicy.Retry)
        if (retryAttempt is ModelAttempt.Failed) return retryAttempt.fallback
        val retryValidation = validate((retryAttempt as ModelAttempt.RawJson).text, context)
        if (retryValidation is ValidationOutcome.Valid) {
            return AiResult.Success(
                retryValidation.response,
                buildMetrics(retried = true, elapsedNanos = System.nanoTime() - startedAtNanos)
            )
        }

        return AiResult.Fallback(AiFallbackReason.SCHEMA_INVALID, (retryValidation as ValidationOutcome.Invalid).reason)
    }

    private sealed interface ModelAttempt {
        data class RawJson(val text: String) : ModelAttempt
        data class Failed(val fallback: AiResult.Fallback) : ModelAttempt
    }

    /**
     * [model]を[policy]で1回呼び出す。[requestTimeoutMillis]で打ち切り、発生し得る例外を
     * §8.6の該当[AiFallbackReason]へ写像する。[CancellationException]（[TimeoutCancellationException]
     * を除く）は握り潰さず再送出する（T-GW-13）。
     */
    private suspend fun invokeModel(context: PlanningContext, policy: SamplingPolicy): ModelAttempt {
        return try {
            val rawJson = withTimeout(requestTimeoutMillis) { model.generatePlan(context, policy) }
            ModelAttempt.RawJson(rawJson)
        } catch (e: TimeoutCancellationException) {
            ModelAttempt.Failed(
                AiResult.Fallback(AiFallbackReason.TIMEOUT, "No response within ${requestTimeoutMillis}ms (§8.6 #8)")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsatisfiedLinkError) {
            ModelAttempt.Failed(AiResult.Fallback(AiFallbackReason.MODEL_LOAD_FAILED, e.message ?: e.toString()))
        } catch (e: OutOfMemoryError) {
            // 二次防御（事前ガードをすり抜けた残余ケース）。§8.6 #13・T-GW-17。
            ModelAttempt.Failed(AiResult.Fallback(AiFallbackReason.OUT_OF_MEMORY, e.message ?: e.toString()))
        } catch (e: Throwable) {
            // 未分類の失敗はサイレントに握り潰さず、例外クラス名をdetailへ残す（T-GW-12）。
            ModelAttempt.Failed(
                AiResult.Fallback(AiFallbackReason.UNKNOWN, "${e::class.qualifiedName}: ${e.message}")
            )
        }
    }

    private sealed interface ValidationOutcome {
        data class Valid(val response: AIPlanResponse) : ValidationOutcome
        data class Invalid(val reason: String) : ValidationOutcome
    }

    /** ①[SchemaValidator]（形式）→②[ContentSanityChecker]（内容）の順で検証する（ADR-0047）。 */
    private fun validate(rawJson: String, context: PlanningContext): ValidationOutcome {
        val schemaResult = schemaValidator.validate(rawJson)
        val response = when (schemaResult) {
            is SchemaValidationResult.Invalid -> return ValidationOutcome.Invalid(schemaResult.reason)
            is SchemaValidationResult.Valid -> schemaResult.response
        }

        return when (val sanityResult = contentSanityChecker.check(response, context)) {
            is ContentSanityResult.Invalid -> ValidationOutcome.Invalid(sanityResult.reason)
            ContentSanityResult.Valid -> ValidationOutcome.Valid(response)
        }
    }

    /**
     * [modelLoadMs]／[AiMetrics.firstTokenMs]／[AiMetrics.outputTokens]／
     * [AiMetrics.tokensPerSecond]／[AiMetrics.peakNativeHeapBytes]はP7-C5の`BenchmarkInfo`配線
     * 待ちのプレースホルダ（`0`）とする（クラスKDoc参照）。[AiMetrics.totalMs]のみGateway境界で
     * 実測する。
     */
    private fun buildMetrics(retried: Boolean, elapsedNanos: Long): AiMetrics = AiMetrics(
        modelLoadMs = 0L,
        firstTokenMs = 0L,
        totalMs = elapsedNanos / NANOS_PER_MILLI,
        outputTokens = 0,
        tokensPerSecond = 0.0,
        peakNativeHeapBytes = 0L,
        retried = retried,
        schemaValid = true,
        sanityPassed = true
    )

    /**
     * Phase 7時点では常に[AiResult.Fallback]（[AiFallbackReason.NOT_IMPLEMENTED_IN_PHASE7]）を
     * 返す契約（U-8・§13 #18「`TODO()`で落とさない」）。[model]のgenerateRecoveryを呼ばずに
     * 早期リターンする設計とし、Phase 7が未対応の経路を安全側で閉じる。実際の対応はPhase 9
     * （§18申し送り5）。
     */
    suspend fun generateRecovery(context: RecoveryContext): AiResult<AIRecoveryResponse> {
        return AiResult.Fallback(
            AiFallbackReason.NOT_IMPLEMENTED_IN_PHASE7,
            "generateRecovery is not implemented in Phase 7 (U-8); model.generateRecovery is not invoked"
        )
    }

    companion object {
        /** §8.6 #8「仮20,000ms」。G4-D実測（§11.3）で確定するまでの暫定値。 */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 20_000L

        private const val NANOS_PER_MILLI: Long = 1_000_000L

        /**
         * §8.6 #7「必要ピークRAM＋安全マージン」の暫定マージン（512MB）。G4-D実測（§11.3）で
         * 確定するまでの仮値（§8.6冒頭「タイムアウト閾値の数値は...仮置き」と同じ扱い）。
         *
         * **P7-C4での変更**: 必要ピークRAMは`ModelCatalog`の特定エントリを本クラスへ
         * ハードコードせず、[checkInstalledModel]が解決した導入済み[ModelCatalogEntry.
         * peakRamBytes]（実際にロード対象となるモデル）へ本マージンを加える形へ改めた
         * （[generatePlan]参照）。§17「モデル名を製品仕様として固定しない」との整合、および
         * `ModelStorage`のテストfixtureエントリ（本番カタログ非依存）でもOOMガードが正しい値で
         * 動作するようにするための変更。
         */
        private const val MEMORY_SAFETY_MARGIN_BYTES: Long = 512L * 1024 * 1024
    }
}

/**
 * [LocalAiGateway]の戻り値（S-3の解決、計画書§8.5）。例外の代わりにこの封じ込み型で成功／
 * 失敗を表現する。
 */
sealed interface AiResult<out T> {
    /** 成功。[metrics]は§57指標に対応する非PII計測値のみ（[AiMetrics]のKDoc参照）。 */
    data class Success<T>(val value: T, val metrics: AiMetrics) : AiResult<T>

    /** 失敗。[reason]は必ず設定する（サイレント握り潰し禁止、§95.6）。 */
    data class Fallback(val reason: AiFallbackReason, val detail: String?) : AiResult<Nothing>
}

/**
 * 計画書§8.5・§57・§60。Local AI推論の非PIIメトリクス。**カレンダー本文・イベントタイトル・
 * 住所・座標は一切含めない**（T-AIMET-1がフィールド集合を許可リストとして回帰ロックする）。
 * P7-C0実測（計画書§14 P7-C0行）で発見した`Conversation.getBenchmarkInfo()`
 * （`ExperimentalFlags.enableBenchmark = true`が必要）が本データクラスの直接の情報源になる
 * 見込み。
 *
 * **[sanityPassed]追加（Fable 5裁定・retry契約確定、2026-08-10、ADR-0049。品質ハーネスUQ-5）**:
 * ②内容sanity（[com.actionstarter.ai.schema.ContentSanityChecker]）を通過したかどうかの
 * 非PII bool値。[schemaValid]（①形式）とは独立した指標として扱う——①のみ通過・②で不合格に
 * なるケースを判別できるようにするため（品質ハーネス§8「sanity通過率」指標に対応）。
 * §60許可リストの範囲内（真偽値のみでPII/自由文を保持し得ない、[AiMetricsTest]の
 * `noFieldHasStringType_cannotHoldFreeTextOrPii`が回帰ロックする）。
 */
data class AiMetrics(
    val modelLoadMs: Long,
    val firstTokenMs: Long,
    val totalMs: Long,
    val outputTokens: Int,
    val tokensPerSecond: Double,
    val peakNativeHeapBytes: Long,
    val retried: Boolean,
    val schemaValid: Boolean,
    val sanityPassed: Boolean
)
