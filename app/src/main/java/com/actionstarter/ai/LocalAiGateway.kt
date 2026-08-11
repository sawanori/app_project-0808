package com.actionstarter.ai

import com.actionstarter.ai.model.DeviceCapability
import com.actionstarter.ai.model.DeviceTier
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelSelector
import com.actionstarter.ai.model.ModelSelectorImpl
import com.actionstarter.ai.model.ModelStorage
import com.actionstarter.ai.model.ModelVerificationResult
import com.actionstarter.ai.model.ModelVerifier
import com.actionstarter.ai.prompt.PlanPromptBuilder
import com.actionstarter.ai.prompt.RecoveryPromptBuilder
import com.actionstarter.ai.schema.ContentSanityChecker
import com.actionstarter.ai.schema.ContentSanityResult
import com.actionstarter.ai.schema.RecoverySchemaValidationResult
import com.actionstarter.ai.schema.RecoverySchemaValidator
import com.actionstarter.ai.schema.SchemaValidationResult
import com.actionstarter.ai.schema.SchemaValidator
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.Locale

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
 * **P7-C5実装済み（ADR-0055）**: [AiMetrics]の`modelLoadMs`／`firstTokenMs`／`outputTokens`／
 * `tokensPerSecond`／`peakNativeHeapBytes`は、[model]が新設[BenchmarkMetricsSource]を追加実装
 * している場合に限りそこから得た[InferenceBenchmarkSnapshot]の実測値を使う（[invokeModel]・
 * [buildMetrics]参照）。[model]がこれを実装しない場合（`LocalAiGatewayTest`の
 * `FakeLocalLanguageModel`等）は従来どおり`0`のプレースホルダのままであり、既存テストの
 * 期待値に影響しない（後方互換）。§16の凍結`LocalLanguageModel`interfaceには一切手を加えて
 * いない（[BenchmarkMetricsSource]のKDoc参照）。`totalMs`は引き続きGateway境界での実測
 * （`System.nanoTime()`差分）を使う。
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
 * @param modelSelector Phase 8.5新設（計画書`docs/plans/phase8.5-adaptive-model-selection.md`
 *   §3設計4、ADR-0062）。`selectedModelId`が[AiPreferences.AUTO_SELECT_MODEL_ID]のとき、
 *   [resolveInstalledEntry]が導入済みかつ空きメモリに収まる最高品質のモデルを選ぶために使う。
 *   末尾・既定値付きパラメータとして追加しており、既存呼び出し元（名前付き引数で構築する
 *   `SettingsAiSafetyTest`等）との後方互換を保つ——ADR-0053の`ModelStorageImpl(context,
 *   catalog=..., preferences=null)`と同型のパターン。
 *
 *   **既定値へ`engineLoadStateSource`を自動配線（Phase 9.5新設、計画書§3.10 F-5b、
 *   実機A/B実測で発見した統合ギャップの修正）**: 既定値は`ModelSelectorImpl(deviceCapability,
 *   modelStorage, engineLoadStateSource = model as? EngineLoadStateSource)`——先行パラメータ
 *   [model]をKotlinの既定値式から参照する（Kotlinの既定値式は同一コンストラクタ内の先行
 *   パラメータを参照可能。ADR-0053の`ModelStorageImpl`と同型の既定値パターン）。**旧既定値
 *   `ModelSelectorImpl(deviceCapability, modelStorage)`（`engineLoadStateSource`省略＝`null`）
 *   のままだと、`modelSelector`を明示的に渡さない全ての呼び出し元でF-5（§3.10）のロード済み
 *   免除が機能しない**——`AppContainer`は明示配線済みのため無関係だが、`PerformanceBaselineProbeTest`
 *   （`modelSelector`引数を渡さず本既定値に委ねている）はこの経路を踏んでおり、実機A/B実測で
 *   warm試行が依然`auto: no candidate fits`で即Fallbackすることが判明した（T-P95-49で回帰
 *   ロック）。既定値を本来あるべき自動配線へ変更することで、`modelSelector`を明示的に渡さない
 *   将来の呼び出し元でもF-5が同様に機能する。
 */
class LocalAiGateway(
    private val model: LocalLanguageModel,
    private val modelStorage: ModelStorage,
    private val modelVerifier: ModelVerifier,
    private val deviceCapability: DeviceCapability,
    private val preferences: AiPreferences,
    private val requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val modelSelector: ModelSelector =
        ModelSelectorImpl(deviceCapability, modelStorage, engineLoadStateSource = model as? EngineLoadStateSource)
) {
    private val schemaValidator = SchemaValidator()
    private val contentSanityChecker = ContentSanityChecker()

    /** Phase 9（計画書§3.4）。[generateRecovery]の①形式＋pairing検証に使う（[schemaValidator]のRecovery版）。 */
    private val recoverySchemaValidator = RecoverySchemaValidator()

    /** T-GW-15。同一インスタンスへの同時呼び出しでも[model]呼び出しが重ならないよう直列化する。 */
    private val inferenceMutex = Mutex()

    /**
     * §8.6 #12「プロセス初回ロード前のみSHA-256再計算・以後の呼び出しでは再計算しない」の
     * プロセス内キャッシュ（Gemini G1 CRITICAL #2、T-GW-18）。[com.actionstarter.di.AppContainer]が
     * `by lazy`で1インスタンスのみ生成する設計（R-7）のため、本フィールドの寿命＝プロセスの
     * 寿命と一致する。[checkInstalledModel]は常に[inferenceMutex]内から呼ばれるため、この
     * `var`への書き込みはMutexで直列化されており追加の同期は不要。値は検証済みの
     * [ModelCatalogEntry.id]の集合（検証対象から外れたエントリのみ個別に[MutableSet.remove]する）。
     *
     * **Phase 8.5でSetへ変更（計画書§3設計7、ADR-0062）**: 単一スロット（`String?`）のままだと、
     * F-Bのモデル切替UIでユーザーがGemma4⇄Qwen0.6Bを行き来するたびに、既に検証済みのモデルの
     * SHA-256（最大2.59GB級ファイル）を毎回再計算してしまう。Setへ変更しモデルごとに独立して
     * 検証済み状態を保持することでこれを解消する。
     */
    private val sha256VerifiedEntryIds: MutableSet<String> = mutableSetOf()

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

            // ADR-0057: peakRamBytes（フルコンテキスト実測・プロファイル非依存の単一値）ではなく
            // defaultProfilePeakRamBytes（実際に使う既定プロファイルでの実効ピーク）を使う。
            // P7-C5実機実測（ADR-0056決定6b）がpeakRamBytesのまま判定すると小コンテキスト・
            // 本番プロファイルの実要求量に対してガードが過大判定することを発見したため
            // （ModelCatalogEntryのKDoc「defaultProfilePeakRamBytes」参照）。
            // Phase 8.5: マージン定数はDeviceCapability.MEMORY_SAFETY_MARGIN_BYTESへ昇格した
            // （ModelSelectorImplと単一情報源を共有するため、計画書§3設計2）。
            val requiredPeakMemoryBytes = installedEntry.defaultProfilePeakRamBytes + DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES
            if (!isEntryAlreadyLoaded(installedEntry) && !deviceCapability.hasAvailableMemory(requiredPeakMemoryBytes)) {
                return@withLock AiResult.Fallback(
                    AiFallbackReason.OUT_OF_MEMORY_PREVENTED,
                    "availMem is below required peak RAM ($requiredPeakMemoryBytes bytes incl. safety margin, §8.6 #7)"
                )
            }

            runValidationPipeline(context, installedEntry)
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
        val entry = resolveInstalledEntry() ?: return InstalledModelCheck.Failed(unresolvedEntryFallback())
        // installedEntry()と同一の解決結果（finalFile）を使う。installedModelPath()を別途
        // 呼ぶと2回目のcatalog走査になり、理論上は間にファイルが変化するTOCTOUの窓も生まれる
        // ため、同じentryから直接導出する。
        val file = modelStorage.finalFile(entry)

        // 毎回: サイズ照合（軽量）。
        if (file.length() != entry.sizeBytes) {
            modelStorage.delete(entry)
            sha256VerifiedEntryIds.remove(entry.id)
            return InstalledModelCheck.Failed(
                AiResult.Fallback(
                    AiFallbackReason.MODEL_CORRUPTED,
                    "Installed file size ${file.length()} != catalog ${entry.sizeBytes} (§8.6 #12)"
                )
            )
        }

        // プロセス内で当該エントリが未検証のときのみSHA-256を再検証する（§8.6 #12、T-GW-18）。
        if (entry.id !in sha256VerifiedEntryIds) {
            when (val verification = modelVerifier.verify(file, entry)) {
                is ModelVerificationResult.Invalid -> {
                    modelStorage.delete(entry)
                    sha256VerifiedEntryIds.remove(entry.id)
                    return InstalledModelCheck.Failed(
                        AiResult.Fallback(
                            AiFallbackReason.MODEL_CORRUPTED,
                            "SHA-256 re-verification failed: ${verification.reason} " +
                                "(§8.6 #12, Gemini G1 CRITICAL #2)"
                        )
                    )
                }

                ModelVerificationResult.Valid -> {
                    sha256VerifiedEntryIds.add(entry.id)
                }
            }
        }

        return InstalledModelCheck.Ready(entry)
    }

    /**
     * Phase 9.5新設（計画書`docs/plans/phase9.5-performance-quality.md`§3.10 F-5、§14発見②、
     * Red検収での差し戻し訂正）。[generatePlan]／[generateRecovery]の post-selection OOMガードが
     * [entry]をスキップ対象にできるかどうかを判定する共有ヘルパー。[model]が任意interface
     * [EngineLoadStateSource]を実装している場合のみ`(model as? EngineLoadStateSource)?.
     * loadedModelPath()`を読み、[modelStorage.finalFile]で解決した[entry]の絶対パスと一致すれば
     * `true`（[BenchmarkMetricsSource]の`as?`パターンと同型、[model]が実装しない場合は
     * 常に`false`＝ガードは無変更で従来どおり動く、後方互換）。
     *
     * **[ModelSelectorImpl]側の同種ガード（選定時点、計画書§3.10）とは独立した層**: auto選択時は
     * [modelSelector]自身が既にロード済み候補をavailMemガードから除外している可能性があるが、
     * 明示選択（[AiPreferences.selectedModelId]が具体的なモデルIDの場合、[ModelSelector]を
     * 一切経由しない）ではこのGateway側ガードのみが唯一の防御となるため、二層とも必要
     * （T-P95-42〜45が明示選択経路、T-P95-48がauto経路を回帰ロックする）。
     */
    private fun isEntryAlreadyLoaded(entry: ModelCatalogEntry): Boolean {
        val loadedPath = (model as? EngineLoadStateSource)?.loadedModelPath() ?: return false
        return loadedPath == modelStorage.finalFile(entry).absolutePath
    }

    /**
     * Phase 8.5（計画書§3設計4、ADR-0062）。`selectedModelId`が[AiPreferences.
     * AUTO_SELECT_MODEL_ID]なら[modelSelector]（導入済み×availMem適合の自動選択）、
     * そうでなければ既存の[ModelStorage.installedEntry]（明示選択、無変更）を使う。
     */
    private fun resolveInstalledEntry(): ModelCatalogEntry? =
        if (preferences.selectedModelId == AiPreferences.AUTO_SELECT_MODEL_ID) {
            modelSelector.select()
        } else {
            modelStorage.installedEntry()
        }

    /**
     * Phase 8.5（計画書§3設計4、ADR-0062）。[resolveInstalledEntry]が`null`のときのFallback理由を
     * 組み立てる。明示選択は既存どおり[AiFallbackReason.MODEL_NOT_INSTALLED]のみ。auto選択は
     * [modelSelector]の`select()`が`null`を返した理由（候補が1件も導入されていないのか、導入済み
     * だが全滅なのか）を[ModelSelector.candidates]で判別し、[AiFallbackReason.MODEL_NOT_INSTALLED]
     * ／[AiFallbackReason.OUT_OF_MEMORY_PREVENTED]を区別する（`select()`の`null`一値だけでは
     * この情報が失われるため）。`detail`は常に「auto: 」を明記し非サイレントとする。
     */
    private fun unresolvedEntryFallback(): AiResult.Fallback {
        if (preferences.selectedModelId != AiPreferences.AUTO_SELECT_MODEL_ID) {
            return AiResult.Fallback(AiFallbackReason.MODEL_NOT_INSTALLED, "ModelStorage.installedEntry() is null (§8.6 #11)")
        }
        val candidateIds = modelSelector.candidates.map { it.id }
        val anyCandidateInstalled = modelSelector.candidates.any { modelStorage.finalFile(it).isFile }
        return if (anyCandidateInstalled) {
            AiResult.Fallback(
                AiFallbackReason.OUT_OF_MEMORY_PREVENTED,
                "auto: no candidate fits available memory (§8.6 #7, candidates=$candidateIds)"
            )
        } else {
            AiResult.Fallback(
                AiFallbackReason.MODEL_NOT_INSTALLED,
                "auto: no candidate is installed (§8.6 #11, candidates=$candidateIds)"
            )
        }
    }

    /**
     * @param entry [checkInstalledModel]が確定した導入済みエントリ（Phase 8.5で追加。
     *   [invokeModel]へのモデルパス引き回しと[buildMetrics]の`selectedModelId`記録に使う。
     *   計画書§3設計5・8参照）。
     */
    private suspend fun runValidationPipeline(context: PlanningContext, entry: ModelCatalogEntry): AiResult<AIPlanResponse> {
        val startedAtNanos = System.nanoTime()

        val primaryAttempt = invokeModel(context, entry, SamplingPolicy.Primary)
        if (primaryAttempt is ModelAttempt.Failed) return primaryAttempt.fallback
        val primaryRaw = primaryAttempt as ModelAttempt.RawJson
        val primaryValidation = validate(primaryRaw.text, context)
        if (primaryValidation is ValidationOutcome.Valid) {
            return AiResult.Success(
                primaryValidation.response,
                buildMetrics(
                    retried = false,
                    elapsedNanos = System.nanoTime() - startedAtNanos,
                    benchmark = primaryRaw.benchmark,
                    selectedModelId = entry.id
                )
            )
        }
        val primaryInvalid = primaryValidation as ValidationOutcome.Invalid
        val sanityRejectCountAfterPrimary = if (primaryInvalid.sanityRejectReason != null) 1 else 0

        // ①または②不合格 → retry 1回（新規single-turnセッション相当の SamplingPolicy.Retry で
        // 呼び直す。会話履歴の破棄自体は[model]実装〔P7-C5〕の内部関心事、S-2是正・ADR-0050）。
        val retryAttempt = invokeModel(context, entry, SamplingPolicy.Retry)
        if (retryAttempt is ModelAttempt.Failed) return retryAttempt.fallback
        val retryRaw = retryAttempt as ModelAttempt.RawJson
        val retryValidation = validate(retryRaw.text, context)
        if (retryValidation is ValidationOutcome.Valid) {
            return AiResult.Success(
                retryValidation.response,
                buildMetrics(
                    retried = true,
                    elapsedNanos = System.nanoTime() - startedAtNanos,
                    benchmark = retryRaw.benchmark,
                    selectedModelId = entry.id,
                    sanityRejectCount = sanityRejectCountAfterPrimary,
                    lastSanityRejectReason = primaryInvalid.sanityRejectReason
                )
            )
        }

        val retryInvalid = retryValidation as ValidationOutcome.Invalid
        val finalSanityRejectCount = sanityRejectCountAfterPrimary + if (retryInvalid.sanityRejectReason != null) 1 else 0
        val finalSanityRejectReason = retryInvalid.sanityRejectReason ?: primaryInvalid.sanityRejectReason
        return AiResult.Fallback(
            AiFallbackReason.SCHEMA_INVALID,
            retryInvalid.reason,
            metrics = buildRejectMetricsOrNull(
                sanityRejectCount = finalSanityRejectCount,
                lastSanityRejectReason = finalSanityRejectReason,
                elapsedNanos = System.nanoTime() - startedAtNanos,
                benchmark = retryRaw.benchmark,
                selectedModelId = entry.id
            )
        )
    }

    private sealed interface ModelAttempt {
        /**
         * @param benchmark [model]が[BenchmarkMetricsSource]を追加実装している場合のみ非null
         *   （P7-C5・ADR-0055。[invokeModel]参照）。
         */
        data class RawJson(val text: String, val benchmark: InferenceBenchmarkSnapshot?) : ModelAttempt
        data class Failed(val fallback: AiResult.Fallback) : ModelAttempt
    }

    /**
     * [model]を[policy]で1回呼び出す。[requestTimeoutMillis]で打ち切り、発生し得る例外を
     * §8.6の該当[AiFallbackReason]へ写像する。[CancellationException]（[TimeoutCancellationException]
     * を除く）は握り潰さず再送出する（T-GW-13）。
     *
     * **ベンチマーク値の取得（P7-C5・ADR-0055）**: `model.generatePlan`成功直後に
     * `(model as? BenchmarkMetricsSource)?.lastInferenceMetrics()`を読む。[model]がこの任意
     * interfaceを実装しない場合は`null`のままであり、[buildMetrics]がプレースホルダへ縮退する。
     *
     * **[entry]引数追加（Phase 8.5、計画書§3設計5、ADR-0062）**: `modelStorage.
     * finalFile(entry).absolutePath`を都度計算し[model.generatePlan]へ明示的に渡す
     * （[LocalLanguageModel.generatePlan]のKDoc「[modelPath]引数追加」参照）。
     */
    private suspend fun invokeModel(context: PlanningContext, entry: ModelCatalogEntry, policy: SamplingPolicy): ModelAttempt =
        invokeModelCall(entry) { modelPath -> model.generatePlan(context, modelPath, policy) }

    /**
     * [model]を[policy]で1回呼び出す（[invokeModel]のRecovery版、[invokeModelCall]共有）。
     * [modelPath]は[entry]から都度計算し[model.generateRecovery]へ明示的に渡す
     * （ADR-0062決定5と同型）。
     */
    private suspend fun invokeRecoveryModel(
        context: RecoveryContext,
        options: List<RecoveryOption>,
        entry: ModelCatalogEntry,
        policy: SamplingPolicy
    ): ModelAttempt =
        invokeModelCall(entry) { modelPath -> model.generateRecovery(context, options, modelPath, policy) }

    /**
     * [invokeModel]・[invokeRecoveryModel]共有（Phase 9コミット2リファクタ）。両者はPlan/Recovery
     * どちらも[requestTimeoutMillis]での打ち切り・例外→[AiFallbackReason]写像ロジックが完全に
     * 同一だったため、実際に呼び出す関数（`generatePlan`／`generateRecovery`、[modelPath]以外の
     * 引数の型・個数が異なる）だけを[call]として渡す形に統一した。[CancellationException]
     * （[TimeoutCancellationException]を除く）は握り潰さず再送出する（T-GW-13）。
     */
    private suspend fun invokeModelCall(entry: ModelCatalogEntry, call: suspend (modelPath: String) -> String): ModelAttempt {
        return try {
            val modelPath = modelStorage.finalFile(entry).absolutePath
            val rawJson = withTimeout(requestTimeoutMillis) { call(modelPath) }
            val benchmark = (model as? BenchmarkMetricsSource)?.lastInferenceMetrics()
            ModelAttempt.RawJson(rawJson, benchmark)
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

        /**
         * @param sanityRejectReason Phase 9 L5（計画書§4.5）。①[SchemaValidator]によるrejectなら
         *   `null`、②[ContentSanityChecker]によるrejectなら非null（[runValidationPipeline]が
         *   [AiMetrics.sanityRejectCount]／[AiMetrics.lastSanityRejectReason]の集計に使う）。
         */
        data class Invalid(val reason: String, val sanityRejectReason: SanityRejectReason?) : ValidationOutcome
    }

    /**
     * ①[SchemaValidator]（形式）→②[ContentSanityChecker]（内容、L2）の順で検証する（ADR-0047）。
     * [ContentSanityChecker.check]へ渡す`knownFewShotTitles`（R1a）は[PlanPromptBuilder]・
     * [RecoveryPromptBuilder]両方のfew-shotタイトルの和集合とする（Plan/Recoveryが同一の
     * モデルエンジンを共有する以上、エコー混線はどちらの模範例からも起こり得るため、
     * [ContentSanityChecker]のKDoc「responsibility 4」参照）。
     */
    private fun validate(rawJson: String, context: PlanningContext): ValidationOutcome {
        val schemaResult = schemaValidator.validate(rawJson)
        val response = when (schemaResult) {
            is SchemaValidationResult.Invalid -> return ValidationOutcome.Invalid(schemaResult.reason, sanityRejectReason = null)
            is SchemaValidationResult.Valid -> schemaResult.response
        }

        return when (val sanityResult = contentSanityChecker.check(response, context, knownFewShotTitles())) {
            is ContentSanityResult.Invalid -> ValidationOutcome.Invalid(sanityResult.reason, sanityResult.rejectReason)
            ContentSanityResult.Valid -> ValidationOutcome.Valid(response)
        }
    }

    /**
     * [PlanPromptBuilder.fewShotEventTitles]・[RecoveryPromptBuilder.fewShotEventTitles]の
     * ja/en両ロケールぶんの和集合（[validate]・[validateRecovery]が共有、Phase 9コミット2）。
     *
     * **`locale`引数を取らずja/en両方を常に合算する理由（実装時の実測発見）**: 当初は
     * [validate]は`context.locale`（[PlanningContext]から取得可能）を使う設計を検討したが、
     * [validateRecovery]は[RecoveryContext]が`locale`を持たないため`Locale.getDefault()`を
     * 使わざるを得ず、これがJVMテスト実行環境の既定localeに依存してテストを不安定化させる
     * ことが実測で判明した（[com.actionstarter.ai.LocalAiGatewayTest]のT-P9-22が、テスト実行
     * 環境の既定localeがjaでない場合に日本語few-shotタイトル集合を取得できず、R1a
     * （[SanityRejectReason.FEW_SHOT_ECHO]）ではなくR2(a)（[SanityRejectReason.
     * LENGTH_OUT_OF_RANGE]）でrejectされてしまい失敗した）。R1a（few-shotエコー検出）は
     * 本来「その呼び出しのlocaleと一致する模範例だけ」に限定する必要がなく（LLMがどの言語の
     * 模範例を暗記していたとしても、どちらの言語で出力してもエコーはエコーであるため）、
     * [validate]側もja/en両方の和集合へ統一することで再現性のある決定的判定にした
     * （QH-11「同一入力には常に同一の判定」の精神を、locale解決手段の違いによる非決定性からも
     * 守る）。
     */
    private fun knownFewShotTitles(): Set<String> =
        PlanPromptBuilder.fewShotEventTitles(Locale.JAPAN) + PlanPromptBuilder.fewShotEventTitles(Locale.US) +
            RecoveryPromptBuilder.fewShotEventTitles(Locale.JAPAN) + RecoveryPromptBuilder.fewShotEventTitles(Locale.US)

    /**
     * Phase 9 L5（計画書§4.5）。[sanityRejectCount]が0より大きい（＝Primary/Retryの少なくとも
     * 一方が②[ContentSanityChecker]でrejectされた）場合のみ[AiResult.Fallback.metrics]を構築し、
     * 純粋に①[SchemaValidator]レベルの不合格だった場合は`null`のまま（後方互換、コミット1までの
     * 既存挙動を維持）とする。
     */
    private fun buildRejectMetricsOrNull(
        sanityRejectCount: Int,
        lastSanityRejectReason: SanityRejectReason?,
        elapsedNanos: Long,
        benchmark: InferenceBenchmarkSnapshot?,
        selectedModelId: String
    ): AiMetrics? {
        if (sanityRejectCount <= 0) return null
        return buildMetrics(
            retried = true,
            elapsedNanos = elapsedNanos,
            benchmark = benchmark,
            selectedModelId = selectedModelId,
            sanityRejectCount = sanityRejectCount,
            lastSanityRejectReason = lastSanityRejectReason,
            sanityPassed = false
        )
    }

    /**
     * [AiMetrics.modelLoadMs]／[AiMetrics.firstTokenMs]／[AiMetrics.outputTokens]／
     * [AiMetrics.tokensPerSecond]／[AiMetrics.peakNativeHeapBytes]は[benchmark]
     * （検証を通過した試行の[BenchmarkMetricsSource]実測値、[runValidationPipeline]参照）が
     * 非nullならそこから採用し、`null`（[model]が[BenchmarkMetricsSource]を実装しない場合）
     * なら従来どおりプレースホルダ（`0`）とする（P7-C5・ADR-0055、クラスKDoc参照）。
     * [AiMetrics.totalMs]は引き続きGateway境界で実測する。
     *
     * @param selectedModelId Phase 8.5追加（計画書§3設計8、ADR-0062）。実際にロード対象と
     *   なった[ModelCatalogEntry.id]をそのまま記録する（PIIではない、`T-AIMET-1`参照）。
     * @param sanityRejectCount Phase 9追加（計画書§4.5）。**コミット2 Greenで実配線済み**——
     *   [runValidationPipeline]・[runRecoveryValidationPipeline]がPrimary/Retryの各attemptで
     *   ②[ContentSanityChecker]がrejectした回数（0〜2）を実測して渡す。
     * @param lastSanityRejectReason 同上。直近のreject理由（非nullは②由来のrejectがあったことを
     *   意味する）。
     * @param sanityPassed Phase 9追加（計画書§4.5）。既定`true`（成功時の呼び出しは全て②を
     *   通過済みのため）。[buildRejectMetricsOrNull]がPrimary/Retryとも②でrejectされた
     *   [AiResult.Fallback.metrics]を構築する際のみ`false`を明示的に渡す。
     */
    private fun buildMetrics(
        retried: Boolean,
        elapsedNanos: Long,
        benchmark: InferenceBenchmarkSnapshot?,
        selectedModelId: String,
        sanityRejectCount: Int = 0,
        lastSanityRejectReason: SanityRejectReason? = null,
        sanityPassed: Boolean = true
    ): AiMetrics = AiMetrics(
        modelLoadMs = benchmark?.modelLoadMs ?: 0L,
        firstTokenMs = benchmark?.firstTokenMs ?: 0L,
        totalMs = elapsedNanos / NANOS_PER_MILLI,
        outputTokens = benchmark?.outputTokens ?: 0,
        tokensPerSecond = benchmark?.tokensPerSecond ?: 0.0,
        peakNativeHeapBytes = benchmark?.peakNativeHeapBytes ?: 0L,
        retried = retried,
        schemaValid = true,
        sanityPassed = sanityPassed,
        selectedModelId = selectedModelId,
        sanityRejectCount = sanityRejectCount,
        lastSanityRejectReason = lastSanityRejectReason
    )

    /**
     * Phase 9 コミット1実装（計画書`docs/plans/phase9-recovery-ai.md`§3.4、ADR-0063想定）。
     * [options]（`BasicRecoveryEngine`が既に決定した候補集合）の各`semanticAction`に対する
     * `explanation`を生成し、[AiResult]で封じ込めて返す。[generatePlan]と同一のパイプライン構造
     * （[inferenceMutex]で実推論呼び出しを含め直列化——計画書§8「Plan/Recovery AI呼び出しの
     * 同時発生」・§13 A-6訂正・T-GW-15、`resolveInstalledEntry`／`checkInstalledModel`の共通化、
     * Primary→[RecoverySchemaValidator]検証（①形式＋pairing）→[ContentSanityChecker.
     * checkRecovery]検証（②内容sanity、L2）→不合格ならRetry→再検証→なお不合格なら
     * Fallback(SCHEMA_INVALID)）を踏襲する。例外は一切外へ送出しない（[CancellationException]
     * を除く）。
     *
     * **②[ContentSanityChecker]内容sanity（L2）配線済み（コミット2 Green）**: 計画書§11コミット
     * 粒度（コミット1=「AIが生成できる」基盤、コミット2=「生成された内容の安全性」）どおり、
     * コミット1では[runRecoveryValidationPipeline]は①（[RecoverySchemaValidator]の形式＋pairing
     * 検証）のみを行っていたが、本コミットで②（[validateRecovery]経由の[ContentSanityChecker.
     * checkRecovery]、R1a/R2）を追加配線した（[validate]のRecovery版、L3retry・L5メトリクスの
     * 集計方法も[runValidationPipeline]と同型）。
     */
    suspend fun generateRecovery(context: RecoveryContext, options: List<RecoveryOption>): AiResult<AIRecoveryResponse> {
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

            val requiredPeakMemoryBytes = installedEntry.defaultProfilePeakRamBytes + DeviceCapability.MEMORY_SAFETY_MARGIN_BYTES
            if (!isEntryAlreadyLoaded(installedEntry) && !deviceCapability.hasAvailableMemory(requiredPeakMemoryBytes)) {
                return@withLock AiResult.Fallback(
                    AiFallbackReason.OUT_OF_MEMORY_PREVENTED,
                    "availMem is below required peak RAM ($requiredPeakMemoryBytes bytes incl. safety margin, §8.6 #7)"
                )
            }

            runRecoveryValidationPipeline(context, options, installedEntry)
        }
    }

    /**
     * @param entry [checkInstalledModel]が確定した導入済みエントリ（[runValidationPipeline]と
     *   同型のPlan/Recovery共通パターン）。
     */
    private suspend fun runRecoveryValidationPipeline(
        context: RecoveryContext,
        options: List<RecoveryOption>,
        entry: ModelCatalogEntry
    ): AiResult<AIRecoveryResponse> {
        val startedAtNanos = System.nanoTime()
        val expectedSemanticActions = options.map { it.semanticAction }.toSet()

        val primaryAttempt = invokeRecoveryModel(context, options, entry, SamplingPolicy.Primary)
        if (primaryAttempt is ModelAttempt.Failed) return primaryAttempt.fallback
        val primaryRaw = primaryAttempt as ModelAttempt.RawJson
        val primaryValidation = validateRecovery(primaryRaw.text, context, expectedSemanticActions)
        if (primaryValidation is RecoveryValidationOutcome.Valid) {
            return AiResult.Success(
                primaryValidation.response,
                buildMetrics(
                    retried = false,
                    elapsedNanos = System.nanoTime() - startedAtNanos,
                    benchmark = primaryRaw.benchmark,
                    selectedModelId = entry.id
                )
            )
        }
        val primaryInvalid = primaryValidation as RecoveryValidationOutcome.Invalid
        val sanityRejectCountAfterPrimary = if (primaryInvalid.sanityRejectReason != null) 1 else 0

        // ①または②不合格 → retry 1回（generatePlanと同じSamplingPolicy.Retryで呼び直す。会話履歴の
        // 破棄自体はmodel実装〔ai/adapter/〕の内部関心事）。
        val retryAttempt = invokeRecoveryModel(context, options, entry, SamplingPolicy.Retry)
        if (retryAttempt is ModelAttempt.Failed) return retryAttempt.fallback
        val retryRaw = retryAttempt as ModelAttempt.RawJson
        val retryValidation = validateRecovery(retryRaw.text, context, expectedSemanticActions)
        if (retryValidation is RecoveryValidationOutcome.Valid) {
            return AiResult.Success(
                retryValidation.response,
                buildMetrics(
                    retried = true,
                    elapsedNanos = System.nanoTime() - startedAtNanos,
                    benchmark = retryRaw.benchmark,
                    selectedModelId = entry.id,
                    sanityRejectCount = sanityRejectCountAfterPrimary,
                    lastSanityRejectReason = primaryInvalid.sanityRejectReason
                )
            )
        }

        val retryInvalid = retryValidation as RecoveryValidationOutcome.Invalid
        val finalSanityRejectCount = sanityRejectCountAfterPrimary + if (retryInvalid.sanityRejectReason != null) 1 else 0
        val finalSanityRejectReason = retryInvalid.sanityRejectReason ?: primaryInvalid.sanityRejectReason
        return AiResult.Fallback(
            AiFallbackReason.SCHEMA_INVALID,
            retryInvalid.reason,
            metrics = buildRejectMetricsOrNull(
                sanityRejectCount = finalSanityRejectCount,
                lastSanityRejectReason = finalSanityRejectReason,
                elapsedNanos = System.nanoTime() - startedAtNanos,
                benchmark = retryRaw.benchmark,
                selectedModelId = entry.id
            )
        )
    }

    private sealed interface RecoveryValidationOutcome {
        data class Valid(val response: AIRecoveryResponse) : RecoveryValidationOutcome

        /** [ValidationOutcome.Invalid]のRecovery版。意味は同一（[sanityRejectReason]が非nullなら
         * ②[ContentSanityChecker.checkRecovery]由来のreject）。 */
        data class Invalid(val reason: String, val sanityRejectReason: SanityRejectReason?) : RecoveryValidationOutcome
    }

    /**
     * ①[RecoverySchemaValidator]（形式＋pairing）→②[ContentSanityChecker.checkRecovery]
     * （内容、L2）の順で検証する（[validate]のRecovery版）。`knownFewShotTitles`（R1a）は
     * [knownFewShotTitles]（ja/en和集合、locale非依存）を[validate]と共有する——理由は
     * [knownFewShotTitles]のKDoc参照。
     */
    private fun validateRecovery(
        rawJson: String,
        context: RecoveryContext,
        expectedSemanticActions: Set<String>
    ): RecoveryValidationOutcome {
        val schemaResult = recoverySchemaValidator.validate(rawJson, expectedSemanticActions)
        val response = when (schemaResult) {
            is RecoverySchemaValidationResult.Invalid ->
                return RecoveryValidationOutcome.Invalid(schemaResult.reason, sanityRejectReason = null)
            is RecoverySchemaValidationResult.Valid -> schemaResult.response
        }

        return when (val sanityResult = contentSanityChecker.checkRecovery(response, context, knownFewShotTitles())) {
            is ContentSanityResult.Invalid -> RecoveryValidationOutcome.Invalid(sanityResult.reason, sanityResult.rejectReason)
            ContentSanityResult.Valid -> RecoveryValidationOutcome.Valid(response)
        }
    }

    companion object {
        /** §8.6 #8「仮20,000ms」。G4-D実測（§11.3）で確定するまでの暫定値。 */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 20_000L

        private const val NANOS_PER_MILLI: Long = 1_000_000L

        // Phase 8.5（計画書§3設計2、ADR-0062）: 旧`MEMORY_SAFETY_MARGIN_BYTES`（512MB）はここに
        // privateで定義されていたが、ModelSelectorImplと単一情報源を共有するため
        // DeviceCapability.MEMORY_SAFETY_MARGIN_BYTESへ昇格した（値・意味とも無変更）。
        // 参照箇所はgeneratePlan()参照。
    }
}

/**
 * [LocalAiGateway]の戻り値（S-3の解決、計画書§8.5）。例外の代わりにこの封じ込み型で成功／
 * 失敗を表現する。
 */
sealed interface AiResult<out T> {
    /** 成功。[metrics]は§57指標に対応する非PII計測値のみ（[AiMetrics]のKDoc参照）。 */
    data class Success<T>(val value: T, val metrics: AiMetrics) : AiResult<T>

    /**
     * 失敗。[reason]は必ず設定する（サイレント握り潰し禁止、§95.6）。
     *
     * **[metrics]追加（Phase 9、計画書§4.5・§9決定5、ADR-0063想定・既定`null`で後方互換）**:
     * 両attempt（Primary/Retry）ともreject・失敗した場合でも、L5（品質防御ハーネスのfallback率
     * 定量化・Phase 12計測基盤）向けに部分的なメトリクス（[AiMetrics.sanityRejectCount]等）を
     * 残せるようにする拡張点。**コミット2 Redの本コミットではまだどの呼び出し元も本フィールドへ
     * 実値を書き込んでいない**（全既存`Fallback(...)`呼び出しは無変更のまま既定`null`でコンパイル
     * を維持する）。実配線はGreenで行う。
     */
    data class Fallback(val reason: AiFallbackReason, val detail: String?, val metrics: AiMetrics? = null) : AiResult<Nothing>
}

/**
 * Phase 9新設（計画書§4.5、ADR-0063想定）。[ContentSanityChecker]（②内容sanity、L2）が
 * `Invalid`と判定した理由の分類。[AiMetrics.lastSanityRejectReason]の型。
 * PII非出力（enum定数名のみ、自由文を保持しない）。
 */
enum class SanityRejectReason {
    FEW_SHOT_ECHO,
    MIN_QUALITY,
    TITLE_COPY,
    FABRICATED_CONTENT,
    LOCALE_MISMATCH,
    DUPLICATE_ACTION_TYPE,
    LENGTH_OUT_OF_RANGE,
    BANNED_WORD
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
 *
 * **[selectedModelId]追加（Phase 8.5、計画書`docs/plans/phase8.5-adaptive-model-selection.md`
 * §3設計8、ADR-0062）**: 実際にロード対象となった[com.actionstarter.ai.model.
 * ModelCatalogEntry.id]（例: `"gemma-4-e2b-it"`）。カレンダー本文・イベントタイトル等の自由文
 * ではなく、開発者が管理する固定カタログの識別子であるためPIIではない。`AiMetricsTest`の
 * `noFieldHasStringType_cannotHoldFreeTextOrPii`は本フィールドのみを許可された唯一のString型
 * フィールドとして明示的に除外する（それ以外のString型フィールドが追加された場合は引き続き
 * 検知する）。
 *
 * **[sanityRejectCount]／[lastSanityRejectReason]追加（Phase 9、計画書§4.5、ADR-0063想定）**:
 * L2（[com.actionstarter.ai.schema.ContentSanityChecker]）がPrimary/Retryの各attemptで
 * rejectした回数（0〜2）と、直近のreject理由（[SanityRejectReason]、非PII enum）。
 * **本コミット（コミット2 Red）時点では[buildMetrics]がまだ常に`0`/`null`を渡す**
 * （L2がGatewayパイプラインへ配線されていないため、実際にrejectを検知できない。実配線は
 * コミット2のGreenで行う）。
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
    val sanityPassed: Boolean,
    val selectedModelId: String,
    val sanityRejectCount: Int,
    val lastSanityRejectReason: SanityRejectReason?
)
