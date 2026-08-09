package com.actionstarter.features

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.features.common.resolveStepTitle
import com.actionstarter.features.planreview.PlanReviewScreen
import com.actionstarter.features.planreview.PlanReviewUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * T-P4UI-1〜5（計画書§8.2 F47、`docs/plans/phase4-basic-engine.md`）。
 *
 * [PlanReviewScreen]は現時点（P4-C2）で時刻表示・`semanticId`解決を実装しておらず
 * （`step.title`をそのまま描画する旧Phase 1実装のまま）、[resolveStepTitle]
 * （`features/common/StepTitle.kt`）は本体が`TODO("P4-C4で実装")`のため、以下は
 * 「期待する要素が見つからない」または`NotImplementedError`のいずれかによりRedになるのが
 * 正しい（意図した失敗。既存の[PlanReviewScreenTest]と同じ趣旨）。
 *
 * ## testTag契約（本テストが要求するP4-C4実装契約。既存の"plan_review_step_item"に加えて
 * 新設する）
 * - "plan_review_step_time_text" … 各ステップ行内の時刻表示テキスト（T-P4UI-1）。
 *   `mergeDescendants = true`の親（"plan_review_step_item"）の子として存在するため、
 *   クエリは`useUnmergedTree = true`で行う。
 *
 * ## T-P4UI-2・T-P4UI-5の設計メモ
 * `resolveStepTitle`は`stringResource`をComposable内部で呼ぶため、en/ja両ロケールの
 * 解決結果を「同一テスト内で」比較するには、Compose Testの`setContent`は1テストにつき
 * 1回しか呼べない制約を回避する必要がある。本ファイルは
 * `LocalContext.current.createConfigurationContext(Configuration)`で日本語ロケール専用の
 * `Context`を作り、`CompositionLocalProvider(LocalContext provides …, LocalConfiguration
 * provides …)`でja部分木を同一コンポジション内に併存させる方式を用いる（Android標準の
 * 「特定configurationでリソースを解決する」ためのAPIの組み合わせであり、Robolectricの
 * グローバル設定切り替え（`RuntimeEnvironment.setQualifiers`）には依存しない）。
 * これにより、対象は`PlanReviewScreen`が実際にtitle解決へ委譲する[resolveStepTitle]
 * そのものとする（画面全体を2ロケール分二重レンダリングして"plan_review_step_item"タグの
 * 重複を扱うより単純かつ確実なため）。
 */
@RunWith(AndroidJUnit4::class)
class PlanReviewStepDisplayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-08T00:00:00Z")

    private fun sampleEvent(startDate: Instant = fixedNow.plus(2, ChronoUnit.HOURS)): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = startDate,
        locationName = "Shibuya",
        coordinates = null,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    // BasicPlanningEngine(G-4)がtitleを常に空文字で生成する前提を再現する（title=""）。
    private fun templateStep(
        type: ExecutionStepType,
        semanticId: String,
        scheduledStart: Instant?,
        priority: StepPriority = StepPriority.IMPORTANT
    ): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = semanticId,
        type = type,
        title = "",
        estimatedDuration = if (type == ExecutionStepType.DEPARTURE) Duration.ZERO else Duration.ofMinutes(10),
        priority = priority,
        skippable = false,
        scheduledStart = scheduledStart,
        completedAt = null
    )

    private fun planWith(steps: List<ExecutionStep>, event: ExecutionEvent = sampleEvent()): ExecutionPlan = ExecutionPlan(
        event = event,
        steps = steps,
        transitionStart = fixedNow.plus(5, ChronoUnit.MINUTES),
        departureTime = fixedNow.plus(70, ChronoUnit.MINUTES),
        estimatedArrival = fixedNow.plus(112, ChronoUnit.MINUTES),
        arrivalBuffer = Duration.ofMinutes(8)
    )

    // T-P4UI-1: 正常系 - 各ステップ行に時刻(DateTimeFormatter.ofLocalizedTime(SHORT)書式)が
    // 表示される（仕様§25/§26）
    @Test
    fun tP4Ui1_stepRow_displaysScheduledStartTimeInShortLocalizedFormat() {
        val scheduledStart = fixedNow.plus(15, ChronoUnit.MINUTES)
        val step = templateStep(ExecutionStepType.PREPARATION, "preparation", scheduledStart)
        val plan = planWith(listOf(step))

        composeTestRule.setContent {
            PlanReviewScreen(
                uiState = PlanReviewUiState(plan = plan, isBehindSchedule = false, isEditEnabled = false),
                onNavigateToExecution = {}
            )
        }

        val expectedTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(ZonedDateTime.ofInstant(scheduledStart, ZoneId.systemDefault()))

        val timeNodes = composeTestRule
            .onAllNodesWithTag("plan_review_step_time_text", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, timeNodes.size)
        val text = timeNodes[0].config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = " ") { it.text }
            .orEmpty()
        assertEquals(expectedTime, text)
    }

    // T-P4UI-2: 正常系 - ja/en両ロケールでtitle表示(semanticId解決結果)が非空かつ言語ごとに異なる
    @Test
    fun tP4Ui2_stepTitle_resolvesNonBlankAndDiffersBetweenEnglishAndJapanese() {
        var enTitle = ""
        var jaTitle = ""

        composeTestRule.setContent {
            enTitle = resolveStepTitle("transition")

            val baseContext = LocalContext.current
            val jaConfiguration = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.JAPANESE)
            }
            val jaContext = baseContext.createConfigurationContext(jaConfiguration)

            CompositionLocalProvider(
                LocalContext provides jaContext,
                LocalConfiguration provides jaConfiguration
            ) {
                jaTitle = resolveStepTitle("transition")
            }
        }
        composeTestRule.waitForIdle()

        assertTrue("English-resolved step title must not be blank (was \"$enTitle\")", enTitle.isNotBlank())
        assertTrue("Japanese-resolved step title must not be blank (was \"$jaTitle\")", jaTitle.isNotBlank())
        assertTrue(
            "Step title resolution must differ between English and Japanese locales " +
                "(en=\"$enTitle\", ja=\"$jaTitle\")",
            enTitle != jaTitle
        )
    }

    // T-P4UI-3: エッジケース - 未知のsemanticIdが渡ってもフォールバック文言が表示されクラッシュしない
    @Test
    fun tP4Ui3_unknownSemanticId_resolvesNonBlankFallbackWithoutCrashing() {
        var resolvedTitle = ""

        composeTestRule.setContent {
            resolvedTitle = resolveStepTitle("some_future_template_not_yet_defined")
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "Unknown semanticId must resolve to a non-blank fallback string instead of crashing " +
                "(was \"$resolvedTitle\")",
            resolvedTitle.isNotBlank()
        )
    }

    // T-P4UI-4: エッジケース - DEPARTUREステップ1件のみのPlan(transition/preparation/travelすべて
    // 非生成)でも「準備ステップなし」相当の案内が表示され、Startボタンが有効なまま
    @Test
    fun tP4Ui4_departureOnlyPlan_showsNoStepsMessageAndStartRemainsEnabled() {
        val departureStep = templateStep(
            ExecutionStepType.DEPARTURE,
            "departure",
            fixedNow.plus(70, ChronoUnit.MINUTES),
            priority = StepPriority.REQUIRED
        )
        val plan = planWith(listOf(departureStep))

        composeTestRule.setContent {
            PlanReviewScreen(
                uiState = PlanReviewUiState(plan = plan, isBehindSchedule = false, isEditEnabled = false),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_no_steps_message)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).assertIsEnabled()
    }

    // T-P4UI-5: 正常系 - 新規追加文言(semanticId解決文言)がja/en間で文言パリティを保つ
    // (既存StringResourceParityTestパターンを踏襲：既知の4semanticId全件を走査し、
    // 不一致(非空でない、またはja==en)を収集してゼロであることを検証する)
    @Test
    fun tP4Ui5_allFourTemplateSemanticIds_maintainNonBlankAndDistinctLocalizationParity() {
        val semanticIds = listOf("transition", "preparation", "departure", "travel")
        val enTitles = mutableMapOf<String, String>()
        val jaTitles = mutableMapOf<String, String>()

        composeTestRule.setContent {
            semanticIds.forEach { semanticId ->
                enTitles[semanticId] = resolveStepTitle(semanticId)
            }

            val baseContext = LocalContext.current
            val jaConfiguration = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.JAPANESE)
            }
            val jaContext = baseContext.createConfigurationContext(jaConfiguration)

            CompositionLocalProvider(
                LocalContext provides jaContext,
                LocalConfiguration provides jaConfiguration
            ) {
                semanticIds.forEach { semanticId ->
                    jaTitles[semanticId] = resolveStepTitle(semanticId)
                }
            }
        }
        composeTestRule.waitForIdle()

        val mismatches = semanticIds.filter { id ->
            val en = enTitles[id].orEmpty()
            val ja = jaTitles[id].orEmpty()
            en.isBlank() || ja.isBlank() || en == ja
        }

        assertTrue(
            "semanticId解決文言のen/jaパリティ(非空・言語間で異なる)が崩れているsemanticId: $mismatches " +
                "(en=$enTitles, ja=$jaTitles)",
            mismatches.isEmpty()
        )
    }
}
