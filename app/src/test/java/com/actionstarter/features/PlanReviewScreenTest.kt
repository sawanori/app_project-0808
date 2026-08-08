package com.actionstarter.features

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.features.planreview.PlanReviewScreen
import com.actionstarter.features.planreview.PlanReviewUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * F5 — Plan Review画面（計画書§11.2、T-PLAN-1〜6）。
 *
 * PlanReviewScreenは現時点（C2契約scaffold）で本文が空実装のため、以下のテストは
 * すべて「期待する要素が見つからない」ことによりRedになるのが正しい。
 *
 * testTag規約: 各ステップ行を "plan_review_step_item" タグで統一し（T-PLAN-1の昇順検証で
 * onAllNodesWithTagにより収集する）、行内テキストにstep.titleを含める。
 */
@RunWith(AndroidJUnit4::class)
class PlanReviewScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-08T00:00:00Z")

    private fun sampleEvent(): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = "Product Shoot",
        notes = null,
        startDate = fixedNow.plus(2, ChronoUnit.HOURS),
        locationName = "Shibuya",
        coordinates = null,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun sampleStep(
        title: String,
        scheduledStart: Instant?,
        priority: StepPriority = StepPriority.IMPORTANT
    ): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = title.lowercase().replace(" ", "_"),
        type = ExecutionStepType.PREPARATION,
        title = title,
        estimatedDuration = Duration.ofMinutes(10),
        priority = priority,
        skippable = true,
        scheduledStart = scheduledStart,
        completedAt = null
    )

    private fun samplePlan(steps: List<ExecutionStep>, isBehind: Boolean = false): ExecutionPlan {
        val event = sampleEvent()
        return ExecutionPlan(
            event = event,
            steps = steps,
            transitionStart = if (isBehind) fixedNow.minus(5, ChronoUnit.MINUTES) else fixedNow.plus(5, ChronoUnit.MINUTES),
            departureTime = fixedNow.plus(70, ChronoUnit.MINUTES),
            estimatedArrival = fixedNow.plus(112, ChronoUnit.MINUTES),
            arrivalBuffer = Duration.ofMinutes(8)
        )
    }

    // T-PLAN-1: 正常系 - ステップが昇順で表示され、Start／Editボタンが揃っている
    @Test
    fun tPlan1_stepsDisplayedInAscendingOrder_withStartAndEditButtons() {
        val step1 = sampleStep("Stop working", fixedNow.plus(5, ChronoUnit.MINUTES))
        val step2 = sampleStep("Get dressed", fixedNow.plus(15, ChronoUnit.MINUTES))
        val step3 = sampleStep("Check equipment", fixedNow.plus(25, ChronoUnit.MINUTES))
        val plan = samplePlan(listOf(step1, step2, step3))

        composeTestRule.setContent {
            PlanReviewScreen(
                uiState = PlanReviewUiState(plan = plan, isBehindSchedule = false, isEditEnabled = false),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_edit_button)).assertIsDisplayed()

        val stepNodes = composeTestRule.onAllNodesWithTag("plan_review_step_item").fetchSemanticsNodes()
        assertEquals(3, stepNodes.size)
        val texts = stepNodes.map { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = " ") { it.text }.orEmpty()
        }
        assertTrue(texts[0].contains(step1.title))
        assertTrue(texts[1].contains(step2.title))
        assertTrue(texts[2].contains(step3.title))
    }

    // T-PLAN-2: 異常系 - 画面表示だけでは自動的にexecutionへ遷移しない（§26）
    @Test
    fun tPlan2_displayOnly_doesNotAutoNavigateToExecution() {
        val plan = samplePlan(listOf(sampleStep("Get dressed", fixedNow.plus(15, ChronoUnit.MINUTES))))
        var navigated = false

        composeTestRule.setContent {
            PlanReviewScreen(
                uiState = PlanReviewUiState(plan = plan, isBehindSchedule = false, isEditEnabled = false),
                onNavigateToExecution = { navigated = true }
            )
        }
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.waitForIdle()

        // 表示確認（現状は未実装のためここで失敗しRedとなる）
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).assertIsDisplayed()
        // 自動遷移していないこと（恒常的に成立すべき不変条件）
        assertFalse(navigated)
    }

    // T-PLAN-3: 正常系 - Startタップでexecutionへ遷移する
    @Test
    fun tPlan3_tapStart_navigatesToExecution() {
        val plan = samplePlan(listOf(sampleStep("Get dressed", fixedNow.plus(15, ChronoUnit.MINUTES))))
        var navigated = false

        composeTestRule.setContent {
            PlanReviewScreen(
                uiState = PlanReviewUiState(plan = plan, isBehindSchedule = false, isEditEnabled = false),
                onNavigateToExecution = { navigated = true }
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()

        assertTrue(navigated)
    }

    // T-PLAN-4: エッジケース - 準備ステップが0件でも「準備ステップなし」を表示しStart可能（U5）
    @Test
    fun tPlan4_zeroSteps_showsNoStepsMessageAndStartRemainsEnabled() {
        val plan = samplePlan(emptyList())

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

    // T-PLAN-5: エッジケース - isBehindSchedule時は警告を色とテキストの両方で表示する（§63）
    // 本Robolectricテストではテキストによる明示を検証する（色の検証はスクリーンショット/
    // 視覚回帰の領域でありG4-Eスコープ外）。
    @Test
    fun tPlan5_behindSchedule_showsWarningText() {
        val plan = samplePlan(listOf(sampleStep("Get dressed", fixedNow.plus(15, ChronoUnit.MINUTES))), isBehind = true)

        composeTestRule.setContent {
            PlanReviewScreen(
                uiState = PlanReviewUiState(plan = plan, isBehindSchedule = true, isEditEnabled = false),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_behind_schedule_warning)).assertIsDisplayed()
    }

    // T-PLAN-6: エッジケース - Editボタンは無効化され、理由文言が表示される（Phase 1では未実装）
    @Test
    fun tPlan6_editButton_disabledWithReason() {
        val plan = samplePlan(listOf(sampleStep("Get dressed", fixedNow.plus(15, ChronoUnit.MINUTES))))

        composeTestRule.setContent {
            PlanReviewScreen(
                uiState = PlanReviewUiState(plan = plan, isBehindSchedule = false, isEditEnabled = false),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_edit_button)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_edit_disabled_reason)).assertIsDisplayed()
    }
}
