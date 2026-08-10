package com.actionstarter.ai

import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceTier
import com.actionstarter.ai.model.ModelCatalog
import com.actionstarter.ai.model.ModelStorage
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
 * 3. **[modelStorage]導入済みチェック・[modelVerifier]ロード前再検証は本サイクルでは未配線
 *    （P7-C4延期・判断事項、下記「modelStorage/modelVerifierチェックの延期」参照）**
 * 4. [deviceCapability]の現在の空きメモリ確認（不足 → ロード・推論を実行せず即座に
 *    [AiFallbackReason.OUT_OF_MEMORY_PREVENTED]、§8.6 #7、Gemini G1 CRITICAL #3・主防御）
 * 5. [model]呼び出し（`model.generatePlan(context, SamplingPolicy.Primary)`。
 *    [requestTimeoutMillis]で`withTimeout`。タイムアウト → [AiFallbackReason.TIMEOUT]、§8.6 #8）
 * 6. 上記「検証パイプライン」（①形式・②内容sanity）。不合格→`model.generatePlan(context,
 *    SamplingPolicy.Retry)`で**新規single-turnセッションでの微小摂動再生成＋静的制約追加**で
 *    [model]をもう一度呼び出す（retry 1回、S-2是正・品質ハーネス§4/§6・Fable 5裁定9・
 *    ADR-0050）→再度不合格なら[AiFallbackReason.SCHEMA_INVALID]、§8.6 #9・§20
 *
 * **modelStorage/modelVerifierチェックの延期（P7-C3・判断事項）**: `LocalAiGatewayTest`の
 * `installedModelStorage()`ヘルパーは`notInstalledModelStorage()`と**同一の未初期化
 * `ModelStorageImpl`インスタンスを返す**（同テストのKDoc「本ヘルパーは...同じ未初期化の
 * ModelStorageImplを返す（意図表明のみのプレースホルダ）...P7-C4・P7-C5が上記2点の内部規約を
 * 確定させた時点で、本ヘルパーを実際にファイルを配置する形へ更新する必要がある」と明記済み）。
 * `ModelStorageImpl.installedModelPath()`はP7-C4スコープでファイル配置規約が未確定のため、
 * これを呼び出すと（"installed"想定のフィクスチャも含め）全T-GW-*ケースで無条件に
 * `NotImplementedError`となり、5系統フォールバック（T-GW-1・4〜10・12・13・15・17・19・20、
 * 14件）が1件もGreen化できなくなる。**したがって本サイクルは§8.6 #11（モデル未導入）・
 * #12（ロード前再検証）のチェックをGatewayの実行パスから意図的に除外し、P7-C4で
 * `ModelStorage`のファイル配置規約が確定した時点で配線する。** この結果:
 * - **T-GW-3**（モデル未導入→`MODEL_NOT_INSTALLED`）は`notInstalledModelStorage()`と
 *   `installedModelStorage()`が区別不能なため本サイクルでは対象外（Red継続、失敗形態が
 *   `NotImplementedError`から`AssertionError`〔Success期待に反しFallback等が返らない〕へ変わる）。
 * - **T-GW-18**（ロード前再検証失敗→`MODEL_CORRUPTED`）はADR-0049裁定8により元々P7-C4まで
 *   据え置き確定済み（テストメソッド自体が未作成のため対象外）。
 * [modelStorage]／[modelVerifier]はコンストラクタ引数として維持する（テストが構築時に渡すため
 * シグネチャ変更は不可、かつP7-C4がファイル配置規約を確定した時点でP7-C5がこの2ステップを
 * 実装で埋める設計上の受け皿として残す）。
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
 * @param modelStorage F90。導入済みモデルの有無・パス確認に用いる（P7-C3では未使用、上記
 *   「modelStorage/modelVerifierチェックの延期」参照）。
 * @param modelVerifier F89。ロード前の破損・改竄再検証に用いる（P7-C3では未使用、同上）。
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
        if (!deviceCapability.hasAvailableMemory(REQUIRED_PEAK_MEMORY_BYTES)) {
            return AiResult.Fallback(
                AiFallbackReason.OUT_OF_MEMORY_PREVENTED,
                "availMem is below required peak RAM ($REQUIRED_PEAK_MEMORY_BYTES bytes incl. safety margin, §8.6 #7)"
            )
        }

        return inferenceMutex.withLock { runValidationPipeline(context) }
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
         */
        private const val MEMORY_SAFETY_MARGIN_BYTES: Long = 512L * 1024 * 1024

        /**
         * 現状カタログ唯一のエントリ（[ModelCatalog.ALL]）を基準にした必要ピークRAM。
         * 複数モデルが選択可能になった時点（P7-C4/C6、[AiPreferences.selectedModelId]配線後）で
         * `selectedModelId`に対応する[com.actionstarter.ai.model.ModelCatalogEntry]を参照する形へ
         * 差し替えること。
         */
        private val REQUIRED_PEAK_MEMORY_BYTES: Long =
            ModelCatalog.QWEN3_0_6B_INT4_BLOCK32.peakRamBytes + MEMORY_SAFETY_MARGIN_BYTES
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
