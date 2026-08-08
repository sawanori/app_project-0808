package com.actionstarter.features

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.features.execution.ExecutionScreen
import com.actionstarter.features.execution.ExecutionUiState
import com.actionstarter.features.execution.ExecutionViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * F6 — Execution One Action画面（計画書§11.2、T-EXEC-1〜6。T-EXEC-7〜9は
 * [ExecutionViewModelTest] を参照）。
 *
 * ExecutionScreenは現時点（C2契約scaffold）で本文が空実装のため、以下のテストは
 * すべて「期待する要素が見つからない」ことによりRedになるのが正しい。
 *
 * testTag規約: 各ステップComposableに "step_item_<id>" 形式のtestTagを付与する
 * （T-EXEC-2実装注記、A4・計画書§11）。
 *
 * 既知の制約（T-EXEC-3／T-EXEC-6、報告参照）: 現行のExecutionScreenシグネチャは
 * ナビゲーション用の3ラムダのみを持ち、「Done（非最終ステップ）」「5 min later」に相当する
 * コールバックを持たない。ExecutionViewModelのコンストラクタもSavedStateHandleのみで
 * Plan全体を注入する経路がない（SharedPlanViewModel結線はC5）。そのためT-EXEC-3／
 * T-EXEC-6はSavedStateHandleへcurrentStepIndexのみを事前投入したExecutionViewModelを
 * 画面へcollectAsStateで結線し、実クリックでcurrentStepIndexの変化を検証する最小限の
 * 形にとどめる（completedAt個別記録／scheduledStart後倒しの値そのものの検証は、
 * Plan注入経路がC4/C5で確定してから追補が必要）。
 */
@RunWith(AndroidJUnit4::class)
class ExecutionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-08T00:00:00Z")

    private fun sampleStep(
        id: UUID = UUID.randomUUID(),
        title: String = "Get dressed",
        scheduledStart: Instant? = fixedNow
    ): ExecutionStep = ExecutionStep(
        id = id,
        semanticId = title.lowercase().replace(" ", "_"),
        type = ExecutionStepType.PREPARATION,
        title = title,
        estimatedDuration = Duration.ofMinutes(10),
        priority = StepPriority.IMPORTANT,
        skippable = true,
        scheduledStart = scheduledStart,
        completedAt = null
    )

    // T-EXEC-1: 正常系 - NOW表示＋現在ステップ＋Doneボタン＋「5 min later」導線が揃っている
    @Test
    fun tExec1_currentStep_displaysNowLabelStepDoneAndPostponeAffordances() {
        val step = sampleStep(title = "Get dressed")
        composeTestRule.setContent {
            ExecutionScreen(
                uiState = ExecutionUiState(currentStep = step, currentStepIndex = 0),
                onNavigateToDeparture = {},
                onNavigateToRecovery = {},
                onNavigateToEventSelection = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(step.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_five_min_later_button)).assertIsDisplayed()
    }

    // T-EXEC-2: 異常系 - 現在ステップ以外のステップタイトルはassertDoesNotExist（§28 One Action）
    // 実装注記（A4・計画書§11）: 各ステップComposableに step_item_<id> 形式のtestTagを付与し、
    // 非アクティブな全ステップのtestTagをassertDoesNotExist()で検証する。
    @Test
    fun tExec2_onlyCurrentStepPresent_othersAbsentFromComposeTree() {
        val currentStep = sampleStep(title = "Get dressed")
        val otherStep = sampleStep(title = "Check equipment")
        composeTestRule.setContent {
            ExecutionScreen(
                uiState = ExecutionUiState(currentStep = currentStep, currentStepIndex = 1),
                onNavigateToDeparture = {},
                onNavigateToRecovery = {},
                onNavigateToEventSelection = {}
            )
        }

        // 現在ステップは表示される（現状は未実装のためここで失敗しRedとなる）
        composeTestRule.onNodeWithTag("step_item_${currentStep.id}").assertIsDisplayed()
        // 非アクティブなステップのtestTagは存在しない（One Action制約。恒常的に成立すべき不変条件）
        composeTestRule.onNodeWithTag("step_item_${otherStep.id}").assertDoesNotExist()
    }

    // T-EXEC-3: 正常系 - Doneタップで次ステップへ進み、completedAtが記録される
    @Test
    fun tExec3_doneTap_onNonFinalStep_advancesCurrentStepIndex() {
        val savedStateHandle = SavedStateHandle(mapOf(KEY_CURRENT_STEP_INDEX to 0))
        val viewModel = ExecutionViewModel(savedStateHandle)
        var navigatedAway = false

        composeTestRule.setContent {
            val uiState by viewModel.uiState.collectAsState()
            ExecutionScreen(
                uiState = uiState,
                onNavigateToDeparture = { navigatedAway = true },
                onNavigateToRecovery = { navigatedAway = true },
                onNavigateToEventSelection = { navigatedAway = true }
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()

        assertEquals(1, viewModel.uiState.value.currentStepIndex)
        assertFalse(navigatedAway)
    }

    // T-EXEC-4: エッジケース - 最終ステップのDoneでdepartureへ遷移し、範囲外アクセスが発生しない
    @Test
    fun tExec4_doneOnFinalStep_navigatesToDeparture_noOutOfBoundsAccess() {
        val finalStep = sampleStep(title = "Leave")
        var navigatedToDeparture = false

        composeTestRule.setContent {
            ExecutionScreen(
                uiState = ExecutionUiState(currentStep = finalStep, currentStepIndex = 2),
                onNavigateToDeparture = { navigatedToDeparture = true },
                onNavigateToRecovery = {},
                onNavigateToEventSelection = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()

        assertTrue(navigatedToDeparture)
    }

    // T-EXEC-5: エッジケース - 準備ステップ0件で入場した場合、departureへ直行する
    @Test
    fun tExec5_zeroSteps_navigatesDirectlyToDeparture() {
        var navigatedToDeparture = false

        composeTestRule.setContent {
            ExecutionScreen(
                uiState = ExecutionUiState(currentStep = null, currentStepIndex = 0),
                onNavigateToDeparture = { navigatedToDeparture = true },
                onNavigateToRecovery = {},
                onNavigateToEventSelection = {}
            )
        }
        composeTestRule.waitForIdle()

        assertTrue(navigatedToDeparture)
    }

    // T-EXEC-6: 正常系 - 「5 min later」タップでscheduledStartが後倒しされる（ステップを省略しない）
    @Test
    fun tExec6_fiveMinLaterTap_postponesWithoutSkippingStep() {
        val savedStateHandle = SavedStateHandle(mapOf(KEY_CURRENT_STEP_INDEX to 0))
        val viewModel = ExecutionViewModel(savedStateHandle)

        composeTestRule.setContent {
            val uiState by viewModel.uiState.collectAsState()
            ExecutionScreen(
                uiState = uiState,
                onNavigateToDeparture = {},
                onNavigateToRecovery = {},
                onNavigateToEventSelection = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_five_min_later_button)).performClick()

        // ステップは省略されない（indexは進まない。§27の5 min later導線）
        assertEquals(0, viewModel.uiState.value.currentStepIndex)
    }

    private companion object {
        const val KEY_CURRENT_STEP_INDEX = "currentStepIndex"
    }
}
