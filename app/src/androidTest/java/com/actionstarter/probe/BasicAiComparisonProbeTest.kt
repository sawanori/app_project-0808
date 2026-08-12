package com.actionstarter.probe

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.actionstarter.R
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

/**
 * probe専用・正式テストではない（既存probe群——[GpuBackendProbeTest]／[PerformanceBaselineProbeTest]
 * ——と同型: `@Ignore`既定・Log出力・目的と測定対象をKDocに明記）。
 *
 * **Phase 12計画書（`docs/plans/phase12-basic-ai-experiment.md`§3.1、3系統レビュー
 * 〔オーケストレーター＋Gemini 3.5-flash＋独立検証役〕反映後の中核実験）**。「Basic固定文言 vs
 * AI文脈化文言」の準備ステップ`displayText`を、固定30イベント（[PROBE_EVENTS]、計画書§3.2、
 * 事前登録・恣意選択防止）全件について比較し、機械副指標（reject理由内訳・エコー率・
 * 差別化率・TTFT・tok/s・Applied率）をLogcatへ構造化出力する。
 *
 * **`AnalyticsStore`／Room／ViewModelを一切経由しない設計（計画書§2、独立検証役確認済み）**:
 * [LocalAiGateway.generatePlan]の戻り値[AiResult]は`Success`／`Fallback`いずれも[com.actionstarter.
 * ai.AiMetrics]（`sanityRejectCount`／`lastSanityRejectReason`／`firstTokenMs`／`tokensPerSecond`を
 * 含む）を保持しており、[PerformanceBaselineProbeTest]が既に同じパターンで実運用している
 * （`result.metrics.sanityRejectCount`等をその場で読む）。本プローブもこれを踏襲し、
 * 永続化層を新設しない。
 *
 * **Basic側の静的文言**: `features/common/StepTitle.kt`の`resolveStepTitle("preparation")`は
 * `@Composable`のため本プローブ（非Compose）からは直接呼べない。同関数の実体は
 * `stringResource(R.string.step_title_preparation)`のみであるため、[Context.getString]で
 * 同じ値を取得する（計画書§2で確認済み）。
 *
 * **AI側の「準備ステップ」の特定**: [com.actionstarter.ai.AIPlanStepResponse.actionType]が
 * `"prepare_items"`／`"get_ready"`／`"gather_belongings"`のいずれかのステップが
 * `ExecutionStepType.PREPARATION`に対応する（`ai/LocalAiPlanContextualizer.kt`の
 * `PlanActionType.toExecutionStepTypeOrNull`と意味的に同一、`internal`関数への依存を避けるため
 * 本ファイルで同じ判定を独立して行う。計画書§2参照）。
 *
 * **測定統制（計画書§3.4）**: 出力テキストは[LocalAiGateway]のPrimary attemptが決定的
 * サンプリング（topK=1/temperature=0/seed=0）を使うため、テキスト系指標は再実行しても安定する。
 * 時間系指標（TTFT・tok/s）の統制（給電統一・item間クールダウン・実行前force-stop/GC）は
 * コード上の対策ではなく実機実行（C2）の運用手順として扱う（[PerformanceBaselineProbeTest]の
 * `PSS_FINAL_PEAK`等と同型の「手順で担保する」設計）。
 *
 * **人手評価との関係（計画書§3.3、ユーザー確定のハイブリッド主指標）**: 本プローブはBasic/AI
 * それぞれの生のdisplayTextをログへ出すだけであり、盲検シートの作成（Basic/AIの甲乙シャッフル）
 * はオーケストレーターが別途行う（実装者が盲検マッピングに関与しないことでの盲検の完全性担保、
 * 検証役#1・Gemini H-3）。本ファイルにシャッフル・ラベル秘匿ロジックは一切含まない。
 *
 * 実行方法（`-e class`で個別指定、既存probe群と同じ理由——
 * `connectedDebugAndroidTest`一括実行の対象に含めない）:
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionstarter.probe.BasicAiComparisonProbeTest#probeBasicVsAiComparison`
 *
 * 結果はLogcat（TAG=P12_COMPARISON_PROBE）へ出力する。全30イベントはPII非含有の合成タイトル
 * （計画書§3.2、実カレンダーデータは一切使用しない）。
 *
 * 前提: 実際に導入済みの本番`ModelStorage`（[PerformanceBaselineProbeTest]と同じ前提、
 * probe専用のpush/copyは行わない）。
 */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore(
    "probe専用（Phase 12、docs/plans/phase12-basic-ai-experiment.md §3.1）。30イベント×AI推論の" +
        "実行に数分〜十数分を要し実機依存（モデル未導入時はAI_DISABLED/MODEL_NOT_INSTALLEDで" +
        "全件Fallback記録となる）のため、connectedDebugAndroidTest一括実行の対象に含めない。" +
        "再実行する場合は既存probe群と同じ罠——discoveryの時点で@Ignoreクラスごと除外されるため、" +
        "再実行時は本クラスの@Ignoreを一時的にコメントアウトするか`-e class`で直接指定する。"
)
class BasicAiComparisonProbeTest {

    @Test
    fun probeBasicVsAiComparison() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        logLine("===== P12 Basic/AI comparison probe start (30 events) =====")

        val storage = ModelStorageImpl(context, catalog = ModelCatalog.ALL)
        val preferences = AiPreferencesImpl(
            context.getSharedPreferences(AiPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE)
        )
        val originalAiEnabled = preferences.aiEnabled
        val basicPreparationText = context.getString(R.string.step_title_preparation)
        logLine("BASIC_PREPARATION_TEXT value=\"$basicPreparationText\"")

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

            val zoneId = ZoneId.of("Asia/Tokyo")
            val now = Instant.now()
            var appliedCount = 0
            var echoCount = 0
            var differentiatedCount = 0
            val rejectReasonCounts = mutableMapOf<String, Int>()

            PROBE_EVENTS.forEachIndexed { index, probeEvent ->
                val itemNo = index + 1
                val startInstant = resolveEventStartInstant(now, zoneId, probeEvent.offsetDays, probeEvent.localHour, probeEvent.localMinute)
                val planningContext = buildPlanningContext(probeEvent, startInstant, now, zoneId)

                logLine(
                    "ITEM no=$itemNo layer=${probeEvent.layer} title=\"${probeEvent.title}\" " +
                        "category=\"${probeEvent.categoryLabel}\" startInstant=$startInstant basicText=\"$basicPreparationText\""
                )

                val result = runBlocking { gateway.generatePlan(planningContext) }
                when (result) {
                    is AiResult.Success -> {
                        val metrics = result.metrics
                        val aiText = extractPreparationDisplayText(result.value.steps)
                        val isEcho = metrics.lastSanityRejectReason?.name == "FEW_SHOT_ECHO"
                        val isDifferentiated = aiText != null && aiText != basicPreparationText
                        if (isEcho) echoCount++
                        if (isDifferentiated) differentiatedCount++
                        appliedCount++
                        metrics.lastSanityRejectReason?.let {
                            rejectReasonCounts[it.name] = (rejectReasonCounts[it.name] ?: 0) + 1
                        }
                        logLine(
                            "RESULT no=$itemNo kind=Success aiText=\"$aiText\" " +
                                "sanityRejectCount=${metrics.sanityRejectCount} lastSanityRejectReason=${metrics.lastSanityRejectReason} " +
                                "firstTokenMs=${metrics.firstTokenMs} tokensPerSecond=${metrics.tokensPerSecond} " +
                                "echoFlag=$isEcho differentiationFlag=$isDifferentiated"
                        )
                    }

                    is AiResult.Fallback -> {
                        val metrics = result.metrics
                        val isEcho = metrics?.lastSanityRejectReason?.name == "FEW_SHOT_ECHO"
                        if (isEcho) echoCount++
                        metrics?.lastSanityRejectReason?.let {
                            rejectReasonCounts[it.name] = (rejectReasonCounts[it.name] ?: 0) + 1
                        } ?: run {
                            rejectReasonCounts[result.reason.name] = (rejectReasonCounts[result.reason.name] ?: 0) + 1
                        }
                        logLine(
                            "RESULT no=$itemNo kind=Fallback aiText=null reason=${result.reason} detail=${result.detail} " +
                                "metricsPresent=${metrics != null} sanityRejectCount=${metrics?.sanityRejectCount} " +
                                "lastSanityRejectReason=${metrics?.lastSanityRejectReason} " +
                                "firstTokenMs=${metrics?.firstTokenMs} tokensPerSecond=${metrics?.tokensPerSecond} " +
                                "echoFlag=$isEcho differentiationFlag=false"
                        )
                    }
                }
            }

            logLine(
                "SUMMARY totalEvents=${PROBE_EVENTS.size} appliedCount=$appliedCount " +
                    "appliedRate=${appliedCount.toDouble() / PROBE_EVENTS.size} echoCount=$echoCount " +
                    "differentiatedCount=$differentiatedCount rejectReasonCounts=$rejectReasonCounts"
            )
        } finally {
            preferences.aiEnabled = originalAiEnabled
            logLine("CLEANUP aiEnabledRestoredTo=$originalAiEnabled")
            logLine("===== P12 Basic/AI comparison probe end =====")
        }
    }

    /**
     * [PlanActionType.toExecutionStepTypeOrNull]と意味的に同一の判定を独立実装する
     * （`internal`関数への依存を避けるため、クラスKDoc参照）。3つの`actionType`は
     * [ExecutionStepType.PREPARATION]に対応する唯一の集合。
     */
    private fun extractPreparationDisplayText(steps: List<com.actionstarter.ai.AIPlanStepResponse>): String? =
        steps.firstOrNull { it.actionType.lowercase(Locale.ROOT) in PREPARATION_ACTION_TYPES }?.displayText

    private fun resolveEventStartInstant(now: Instant, zoneId: ZoneId, offsetDays: Long, localHour: Int, localMinute: Int): Instant =
        now.atZone(zoneId).toLocalDate().plusDays(offsetDays).atTime(localHour, localMinute).atZone(zoneId).toInstant()

    private fun buildPlanningContext(probeEvent: ProbeEvent, startInstant: Instant, now: Instant, zoneId: ZoneId): PlanningContext {
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = probeEvent.title,
            notes = null,
            startDate = startInstant,
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "p12-comparison-probe", displayName = "P12 Comparison Probe Calendar")
        )
        return PlanningContext(
            event = event,
            now = now,
            zoneId = zoneId,
            locale = Locale.JAPAN,
            transportMode = TransportMode.WALKING,
            travelEstimate = null,
            arrivalBuffer = Duration.ofMinutes(10),
            profile = null
        )
    }

    private fun logLine(message: String) {
        Log.e(TAG, message)
    }

    /**
     * 1件の固定プローブイベント（計画書§3.2の表と1:1対応）。[layer]は`"L1"`／`"L2"`／`"L3"`、
     * [offsetDays]・[localHour]・[localMinute]はプローブ実行時刻からの相対オフセット
     * （`Asia/Tokyo`基準の現地時刻へ変換、[resolveEventStartInstant]参照）。
     */
    private data class ProbeEvent(
        val title: String,
        val layer: String,
        val categoryLabel: String,
        val offsetDays: Long,
        val localHour: Int,
        val localMinute: Int = 0
    )

    private companion object {
        const val TAG = "P12_COMPARISON_PROBE"

        /** [com.actionstarter.ai.schema.PlanActionType]のうち[ExecutionStepType.PREPARATION]へ写像される3値。 */
        val PREPARATION_ACTION_TYPES = setOf("prepare_items", "get_ready", "gather_belongings")

        /**
         * 計画書§3.2の固定30イベント（事前登録、Gemini H-4「恣意選択防止」）。L1（seed非依存の
         * 日常）10件・L2（seedと近縁だが別語、16件のfew-shot seedタイトルと逐語重複なし）10件・
         * L3（AIが苦手な不規則）10件、計30件。構造検証（件数・重複なし・layer内訳）は
         * `app/src/test/java/com/actionstarter/probe/BasicAiComparisonDatasetTest.kt`が
         * ソーススキャン型pinningテストとして担う（本ファイルへの変更を検出する回帰ガード）。
         */
        val PROBE_EVENTS = listOf(
            // L1: seed非依存の日常（10件）
            ProbeEvent(title = "美容院", layer = "L1", categoryLabel = "日常", offsetDays = 1, localHour = 10),
            ProbeEvent(title = "ジムでの筋トレ", layer = "L1", categoryLabel = "日常", offsetDays = 1, localHour = 18),
            ProbeEvent(title = "車検の予約", layer = "L1", categoryLabel = "日常", offsetDays = 2, localHour = 9),
            ProbeEvent(title = "PTA役員会", layer = "L1", categoryLabel = "日常", offsetDays = 2, localHour = 14),
            ProbeEvent(title = "確定申告の相談", layer = "L1", categoryLabel = "日常", offsetDays = 3, localHour = 10),
            ProbeEvent(title = "銀行での住宅ローン相談", layer = "L1", categoryLabel = "日常", offsetDays = 3, localHour = 15),
            ProbeEvent(title = "図書館での本の返却", layer = "L1", categoryLabel = "日常", offsetDays = 4, localHour = 11),
            ProbeEvent(title = "粗大ごみの回収申込", layer = "L1", categoryLabel = "日常", offsetDays = 4, localHour = 9),
            ProbeEvent(title = "期日前投票", layer = "L1", categoryLabel = "日常", offsetDays = 5, localHour = 12),
            ProbeEvent(title = "インフルエンザ予防接種", layer = "L1", categoryLabel = "日常", offsetDays = 5, localHour = 16),
            // L2: seedと近縁だが別語（10件、逐語重複なし）
            ProbeEvent(title = "眼科検診", layer = "L2", categoryLabel = "医療（歯科検診近縁）", offsetDays = 6, localHour = 10),
            ProbeEvent(title = "送別会", layer = "L2", categoryLabel = "社交（結婚式近縁）", offsetDays = 6, localHour = 19),
            ProbeEvent(title = "工場見学", layer = "L2", categoryLabel = "出張（出張近縁）", offsetDays = 7, localHour = 9),
            ProbeEvent(title = "社内勉強会", layer = "L2", categoryLabel = "会議（打ち合わせ近縁）", offsetDays = 7, localHour = 13),
            ProbeEvent(title = "還暦祝い", layer = "L2", categoryLabel = "社交（誕生日会近縁）", offsetDays = 8, localHour = 18),
            ProbeEvent(title = "人間ドック", layer = "L2", categoryLabel = "医療（健康診断近縁）", offsetDays = 8, localHour = 9),
            ProbeEvent(title = "帰省", layer = "L2", categoryLabel = "旅行（旅行近縁）", offsetDays = 9, localHour = 8),
            ProbeEvent(title = "取引先との会食", layer = "L2", categoryLabel = "会議（商談近縁）", offsetDays = 9, localHour = 19),
            ProbeEvent(title = "忘年会", layer = "L2", categoryLabel = "社交（結婚式／誕生日会近縁）", offsetDays = 10, localHour = 19),
            ProbeEvent(title = "株主総会", layer = "L2", categoryLabel = "会議（打ち合わせ近縁）", offsetDays = 10, localHour = 10),
            // L3: AIが苦手な不規則（10件）
            ProbeEvent(title = "深夜のオンラインゲーム大会", layer = "L3", categoryLabel = "不規則", offsetDays = 11, localHour = 23, localMinute = 30),
            ProbeEvent(title = "推し活（ライブ参戦）", layer = "L3", categoryLabel = "不規則", offsetDays = 11, localHour = 17),
            ProbeEvent(title = "断捨離（クローゼット整理）", layer = "L3", categoryLabel = "不規則", offsetDays = 12, localHour = 10),
            ProbeEvent(title = "早朝の釣り", layer = "L3", categoryLabel = "不規則", offsetDays = 12, localHour = 5),
            ProbeEvent(title = "自宅での瞑想会", layer = "L3", categoryLabel = "不規則", offsetDays = 13, localHour = 7),
            ProbeEvent(title = "深夜のコンビニスイーツ食べ比べ", layer = "L3", categoryLabel = "不規則", offsetDays = 13, localHour = 23),
            ProbeEvent(title = "推しの誕生日カフェ巡り", layer = "L3", categoryLabel = "不規則", offsetDays = 14, localHour = 11),
            ProbeEvent(title = "早朝ランニングの計測会", layer = "L3", categoryLabel = "不規則", offsetDays = 14, localHour = 6),
            ProbeEvent(title = "家庭菜園の収穫", layer = "L3", categoryLabel = "不規則", offsetDays = 15, localHour = 8),
            ProbeEvent(title = "深夜のコードレビュー会", layer = "L3", categoryLabel = "不規則", offsetDays = 15, localHour = 1)
        )
    }
}
