package com.actionstarter.features

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.features.recovery.RecoveryScreen
import com.actionstarter.features.recovery.RecoveryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * F8 — Recovery画面（計画書§11.2、T-REC-1〜6）。
 *
 * RecoveryScreenは現時点（C2契約scaffold）で本文が空実装のため、以下のテストは
 * すべて「期待する要素が見つからない」ことによりRedになるのが正しい。
 *
 * testTag規約: 各候補行を "recovery_option_item_<id>" 形式で付与する。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-08T00:00:00Z")

    private fun sampleOption(
        title: String,
        skippedStepIds: List<UUID> = emptyList(),
        semanticAction: String = title.lowercase().replace(" ", "_")
    ): RecoveryOption = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = semanticAction,
        title = title,
        explanation = "$title explanation",
        estimatedArrival = fixedNow.plus(58, ChronoUnit.MINUTES),
        skippedStepIds = skippedStepIds
    )

    private fun sampleStep(
        title: String,
        priority: StepPriority
    ): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = title.lowercase().replace(" ", "_"),
        type = ExecutionStepType.PREPARATION,
        title = title,
        estimatedDuration = Duration.ofMinutes(10),
        priority = priority,
        skippable = priority != StepPriority.REQUIRED,
        scheduledStart = fixedNow,
        completedAt = null
    )

    // T-REC-1: 正常系 - Screen5（Recovery）の表示要素が揃っている
    @Test
    fun tRec1_populatedState_displaysAllRequiredElements() {
        val options = listOf(sampleOption("Leave now"))
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = options, selectedOptionId = null),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.recovery_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_subtitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_use_this_plan_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_see_alternatives_button)).assertIsDisplayed()
    }

    // T-REC-2: 正常系 - Recovery候補が3件のとき全件表示される
    //
    // 【P6-C5 fixture更新（§4.2 U-6で承認済み、assertion強度は維持）】RecoveryScreenがP6-C5で
    // option.title直接表示からresolveRecoveryOptionTitle(option.semanticAction)経由へ
    // 切り替わった（RecoveryScreen.ktのKDoc参照）。BasicRecoveryEngine（本番のRecoveryEngine）は
    // title/explanationを常に空文字で生成するため、option.titleを直接アサートする旧来の
    // 検証はもはや画面の実際の表示内容を検証しない（title自体は表示に使われなくなった）。
    // 3件の候補がそれぞれ異なる既知semanticActionキーを持つ構成にしたうえで、各候補行に
    // 「そのキーに対応する解決済みstringResourceの実テキスト」が表示されることを検証する形へ
    // 更新した（「3件が個別に識別可能な文言で全件表示される」という検証意図自体は不変）。
    @Test
    fun tRec2_threeOptions_allDisplayed() {
        val options = listOf(
            sampleOption("Leave now", semanticAction = "keep_all_steps"),
            sampleOption("Change transport", semanticAction = "change_transport_mode"),
            sampleOption("Prepare a delay message", semanticAction = "skip_optional_steps")
        )
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = options, selectedOptionId = null),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()
        val expectedTitleBySemanticAction = mapOf(
            "keep_all_steps" to context.getString(R.string.recovery_option_title_keep_all_steps),
            "change_transport_mode" to context.getString(R.string.recovery_option_title_change_transport_mode),
            "skip_optional_steps" to context.getString(R.string.recovery_option_title_skip_optional_steps)
        )

        options.forEach { option ->
            composeTestRule.onNodeWithTag("recovery_option_item_${option.id}").assertIsDisplayed()
            composeTestRule.onNodeWithText(expectedTitleBySemanticAction.getValue(option.semanticAction))
                .assertIsDisplayed()
        }
    }

    // T-REC-3: エッジケース - 候補0件のとき案内文言と手動導線が表示される
    @Test
    fun tRec3_zeroOptions_showsGuidanceMessage() {
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = emptyList(), selectedOptionId = null),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.recovery_no_options_message)).assertIsDisplayed()
    }

    // T-REC-4: 異常系 - StepPriority.REQUIREDのステップは省略候補として提示されない（§33）
    //
    // 生成時点での禁止（仕様§33、エラー＆レスキューマップ#13）はT-DM-9
    // （MockRecoveryFactoryTest・ファクトリ層契約）が担保する。RecoveryOption自体は
    // skippedStepIdsのみを保持しPriority情報を持たないため、構築時例外による検証は
    // 仕様§51のフィールド変更なしには実装不能である（T-DM-9と同じ構造上の理由）。
    // 本テストはUI表示層の防衛線として、REQUIREDステップを一切スキップしない正当な
    // RecoveryOption群が渡された場合に、(a) 省略候補領域
    // （recovery_option_item_<id> testTag配下）にREQUIREDステップのタイトルが一切
    // 現れないこと、(b) 正当なOPTIONAL/IMPORTANT省略候補は表示されることを検証する。
    @Test
    fun tRec4_requiredStepTitle_neverAppearsInOptionCandidateArea() {
        val requiredStep = sampleStep(title = "Safety check", priority = StepPriority.REQUIRED)
        val skippableStep = sampleStep(title = "Detailed equipment check", priority = StepPriority.OPTIONAL)
        val legitimateOption = sampleOption(
            title = "Leave now",
            skippedStepIds = listOf(skippableStep.id)
        )

        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = listOf(legitimateOption), selectedOptionId = null),
                onNavigateToExecution = {}
            )
        }

        // (b) 正当な省略候補は表示される（現状は未実装のためここで失敗しRedとなる）
        composeTestRule.onNodeWithTag("recovery_option_item_${legitimateOption.id}").assertIsDisplayed()

        // (a) 省略候補領域にREQUIREDステップのタイトルは一切現れない（恒常的に成立すべき不変条件）
        val optionNode = composeTestRule.onNodeWithTag("recovery_option_item_${legitimateOption.id}")
            .fetchSemanticsNode()
        val optionText = optionNode.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = " ") { it.text }
            .orEmpty()
        assertFalse(optionText.contains(requiredStep.title))
    }

    // T-REC-5: 異常系 - 候補選択だけでは自動適用されず、確認操作後にのみ更新される（§34）
    @Test
    fun tRec5_selectingOption_doesNotAutoApply() {
        val options = listOf(sampleOption("Leave now"), sampleOption("Change transport"))
        var applied = false
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = options, selectedOptionId = null),
                onNavigateToExecution = { applied = true }
            )
        }
        val context = RuntimeEnvironment.getApplication()

        // 表示確認（現状は未実装のためここで失敗しRedとなる）
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_title)).assertIsDisplayed()
        // 「Use this plan」ではなく候補自体をタップする
        composeTestRule.onNodeWithTag("recovery_option_item_${options[0].id}").performClick()

        // 確認操作（Use this plan）を経ていないため自動適用されない
        assertFalse(applied)
    }

    // T-REC-6: 正常系 - ja/en双方で文言が非空
    @Test
    fun tRec6_defaultLocale_labelsRenderNonEmpty() {
        val options = listOf(sampleOption("Leave now"))
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = options, selectedOptionId = null),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()
        val enTitle = context.getString(R.string.recovery_title)
        assertEquals(true, enTitle.isNotBlank())

        composeTestRule.onNodeWithText(enTitle).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ja")
    fun tRec6_jaLocale_labelsRenderNonEmpty() {
        val options = listOf(sampleOption("Leave now"))
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = options, selectedOptionId = null),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()
        val jaTitle = context.getString(R.string.recovery_title)
        assertEquals(true, jaTitle.isNotBlank())

        composeTestRule.onNodeWithText(jaTitle).assertIsDisplayed()
    }
}
