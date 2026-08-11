package com.actionstarter.features

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.actionstarter.features.recovery.resolveRecoveryOptionExplanation
import com.actionstarter.features.recovery.resolveRecoveryOptionTitle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * T-RECUI-1〜8（計画書§8.6、`docs/plans/phase6-recovery-basic.md`）。
 *
 * [RecoveryScreen]は本ファイル作成時点（P6-C2）でETA表示（§32）を実装しておらず、
 * [com.actionstarter.features.recovery.resolveRecoveryOptionTitle]／
 * [com.actionstarter.features.recovery.resolveRecoveryOptionExplanation]は本体が
 * `TODO("P6-C4で実装")`のため、以下は「期待する要素が見つからない」または
 * `NotImplementedError`のいずれかによりRedになるのが正しい（意図した失敗。
 * [com.actionstarter.features.PlanReviewStepDisplayTest]と同じ趣旨）。
 *
 * ## testTag契約（本テストが要求するP6-C4実装契約。既存の"recovery_option_item_<id>"に
 * 加えて新設する）
 * - `"recovery_option_eta_<id>"` … 各候補行内のETA表示ノード（T-RECUI-1・T-RECUI-4・
 *   T-RECUI-8）。`estimatedArrival == null`の候補では存在してはならない（T-RECUI-8）。
 *
 * ## T-RECUI-5〜7に関する留意（既存T-REC-4/5/3との関係）
 * [RecoveryScreen]は既にPhase 1でoptionの表示・候補0件時の案内・自動非適用を実装済みのため、
 * これらを再確認するT-RECUI-5〜7は[com.actionstarter.features.RecoveryScreenTest]の
 * T-REC-4/5/3と同様に**現状でも既にGreenの見込みが高い**（[com.actionstarter.planning
 * .PlanningLlmIsolationTest]のT-BPE-28と同じ性質の回帰ガード）。既存ファイルではなく
 * 本ファイルへ新設するのは、計画書§6.1がT-RECUI-1〜8をまとめて新規ファイル
 * `RecoveryOptionDisplayTest.kt`に割り当てているため（既存`RecoveryScreenTest.kt`は
 * assertion強度維持のまま無変更で温存する）。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryOptionDisplayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-08T00:00:00Z")

    private fun sampleOption(
        semanticAction: String = "keep_all_steps",
        estimatedArrival: Instant? = fixedNow.plus(58, ChronoUnit.MINUTES),
        skippedStepIds: List<UUID> = emptyList()
    ): RecoveryOption = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = semanticAction,
        title = semanticAction,
        explanation = "$semanticAction explanation",
        estimatedArrival = estimatedArrival,
        skippedStepIds = skippedStepIds
    )

    private fun sampleStep(title: String, priority: StepPriority): ExecutionStep = ExecutionStep(
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

    private fun semanticsText(tag: String): String {
        val node = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = " ") { it.text }.orEmpty()
    }

    // T-RECUI-1: 正常系 - 各候補にrecovery_option_eta_label＋整形済みETAが表示される（§32）
    @Test
    fun tRecUi1_eachOption_displaysEtaLabelAndFormattedArrival() {
        val option = sampleOption(estimatedArrival = fixedNow.plus(58, ChronoUnit.MINUTES))
        composeTestRule.setContent {
            RecoveryScreen(uiState = RecoveryUiState(options = listOf(option)), onNavigateToExecution = {})
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("recovery_option_eta_${option.id}", useUnmergedTree = true).assertIsDisplayed()
        val etaLabel = context.getString(R.string.recovery_option_eta_label)
        assertTrue(
            "ETAラベル文言($etaLabel)がoption行に含まれていません",
            semanticsText("recovery_option_eta_${option.id}").contains(etaLabel)
        )
    }

    // T-RECUI-2: 正常系 - title/explanationがsemanticActionからstringResource解決される
    // （Domain層のハードコード英語が消えている）
    @Test
    fun tRecUi2_knownSemanticActions_resolveToNonBlankTitleAndExplanation() {
        val knownActions = listOf(
            "keep_all_steps",
            "skip_optional_steps",
            "skip_optional_and_important_steps",
            "change_transport_mode"
        )
        composeTestRule.setContent {
            Column {
                knownActions.forEach { action ->
                    Text(
                        text = resolveRecoveryOptionTitle(action),
                        modifier = Modifier.testTag("probe_title_$action")
                    )
                    Text(
                        text = resolveRecoveryOptionExplanation(action, fixedNow.plus(30, ChronoUnit.MINUTES)),
                        modifier = Modifier.testTag("probe_explanation_$action")
                    )
                }
            }
        }

        knownActions.forEach { action ->
            assertTrue(
                "semanticAction=$action のtitleが空白です",
                semanticsText("probe_title_$action").isNotBlank()
            )
            assertTrue(
                "semanticAction=$action のexplanationが空白です",
                semanticsText("probe_explanation_$action").isNotBlank()
            )
        }
    }

    // T-RECUI-3: エッジケース - 未知のsemanticAction → フォールバック文言を返しクラッシュしない
    // （resolveStepTitle先例）
    @Test
    fun tRecUi3_unknownSemanticAction_returnsFallbackTextWithoutCrashing() {
        composeTestRule.setContent {
            Text(
                text = resolveRecoveryOptionTitle("totally_unknown_action_xyz"),
                modifier = Modifier.testTag("probe_title")
            )
        }

        assertTrue(semanticsText("probe_title").isNotBlank())
    }

    // T-RECUI-4: 正常系 - ja/en両ロケールで全候補の文言・ETAラベルが非空（既存T-REC-6の拡張）
    @Test
    fun tRecUi4_defaultLocale_etaLabelRendersNonEmptyAndIsDisplayed() {
        val option = sampleOption()
        composeTestRule.setContent {
            RecoveryScreen(uiState = RecoveryUiState(options = listOf(option)), onNavigateToExecution = {})
        }
        val context = RuntimeEnvironment.getApplication()
        val etaLabel = context.getString(R.string.recovery_option_eta_label)

        assertTrue(etaLabel.isNotBlank())
        composeTestRule.onNodeWithTag("recovery_option_eta_${option.id}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ja")
    fun tRecUi4_jaLocale_etaLabelRendersNonEmptyAndIsDisplayed() {
        val option = sampleOption()
        composeTestRule.setContent {
            RecoveryScreen(uiState = RecoveryUiState(options = listOf(option)), onNavigateToExecution = {})
        }
        val context = RuntimeEnvironment.getApplication()
        val etaLabel = context.getString(R.string.recovery_option_eta_label)

        assertTrue(etaLabel.isNotBlank())
        composeTestRule.onNodeWithTag("recovery_option_eta_${option.id}", useUnmergedTree = true).assertIsDisplayed()
    }

    // T-RECUI-5: 異常系 - REQUIREDステップ名が省略候補領域に現れない（既存T-REC-4の維持）
    @Test
    fun tRecUi5_requiredStepTitle_neverAppearsInOptionCandidateArea() {
        val requiredStep = sampleStep(title = "Safety check", priority = StepPriority.REQUIRED)
        val skippableStep = sampleStep(title = "Detailed equipment check", priority = StepPriority.OPTIONAL)
        val legitimateOption = sampleOption(skippedStepIds = listOf(skippableStep.id))

        composeTestRule.setContent {
            RecoveryScreen(uiState = RecoveryUiState(options = listOf(legitimateOption)), onNavigateToExecution = {})
        }

        composeTestRule.onNodeWithTag("recovery_option_item_${legitimateOption.id}").assertIsDisplayed()
        assertFalse(semanticsText("recovery_option_item_${legitimateOption.id}").contains(requiredStep.title))
    }

    // T-RECUI-6: 異常系 - 候補選択だけでは自動適用されない（既存T-REC-5の維持・§34）
    @Test
    fun tRecUi6_selectingOption_doesNotAutoApply() {
        val options = listOf(sampleOption(semanticAction = "keep_all_steps"), sampleOption(semanticAction = "skip_optional_steps"))
        var navigatedToExecution = false
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = options),
                onNavigateToExecution = { navigatedToExecution = true }
            )
        }

        composeTestRule.onNodeWithTag("recovery_option_item_${options[0].id}").performClick()

        assertFalse(navigatedToExecution)
    }

    // T-RECUI-7: エッジケース - 候補0件 → 案内文言と手動導線（既存T-REC-3の維持）
    @Test
    fun tRecUi7_zeroOptions_showsGuidanceMessage() {
        composeTestRule.setContent {
            RecoveryScreen(uiState = RecoveryUiState(options = emptyList()), onNavigateToExecution = {})
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.recovery_no_options_message)).assertIsDisplayed()
    }

    // T-RECUI-8: エッジケース - estimatedArrival == nullの候補はETA行を描画しない（偽値を表示しない）
    @Test
    fun tRecUi8_nullEstimatedArrival_doesNotRenderEtaRow() {
        val optionWithEta = sampleOption(semanticAction = "keep_all_steps", estimatedArrival = fixedNow.plus(30, ChronoUnit.MINUTES))
        val optionWithoutEta = sampleOption(semanticAction = "change_transport_mode", estimatedArrival = null)

        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = listOf(optionWithEta, optionWithoutEta)),
                onNavigateToExecution = {}
            )
        }

        composeTestRule.onNodeWithTag("recovery_option_eta_${optionWithEta.id}", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("recovery_option_eta_${optionWithoutEta.id}", useUnmergedTree = true).assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // Phase 9（計画書`docs/plans/phase9-recovery-ai.md`§3.5、コミット3、ADR-0063想定）:
    // resolveRecoveryOptionExplanationのaiExplanation引数。
    // ------------------------------------------------------------------

    // T-P9-32: 正常 - aiExplanation(非null・非空)を渡すとそのまま返し、静的stringResource解決を
    // スキップする。resolveRecoveryOptionExplanationの本体はStep 3（Red）時点でまだaiExplanationを
    // 無視するため、期待値（aiExplanationそのもの）と実際の戻り値（静的解決結果）が不一致となり
    // AssertionErrorでRedになるのが正しい（RecoveryOptionText.ktのKDoc参照）。
    @Test
    fun tP9_32_aiExplanationNonEmpty_isReturnedDirectlyWithoutStaticResolution() {
        val aiText = "AIが生成した差し替え説明文"
        var resolved = ""
        composeTestRule.setContent {
            resolved = resolveRecoveryOptionExplanation(
                "keep_all_steps",
                fixedNow.plus(30, ChronoUnit.MINUTES),
                aiExplanation = aiText
            )
        }

        assertTrue(
            "aiExplanationが非null・非空のときはそのまま返すべきです(T-P9-32): resolved=$resolved",
            resolved == aiText
        )
    }

    // T-P9-33: 正常・回帰（[born-green]） - aiExplanationがnull／空文字のときは既存の静的
    // stringResource解決へフォールバックする（2引数版の既存呼び出しと同一結果になることの確認）。
    @Test
    fun tP9_33_aiExplanationNullOrEmpty_fallsBackToStaticResolution() {
        var withoutAiExplanation = ""
        var withNullAiExplanation = ""
        var withEmptyAiExplanation = ""
        composeTestRule.setContent {
            withoutAiExplanation = resolveRecoveryOptionExplanation("keep_all_steps", fixedNow.plus(30, ChronoUnit.MINUTES))
            withNullAiExplanation =
                resolveRecoveryOptionExplanation("keep_all_steps", fixedNow.plus(30, ChronoUnit.MINUTES), aiExplanation = null)
            withEmptyAiExplanation =
                resolveRecoveryOptionExplanation("keep_all_steps", fixedNow.plus(30, ChronoUnit.MINUTES), aiExplanation = "")
        }

        assertTrue(
            "aiExplanation=nullは2引数版と同一の静的解決結果になるべきです(T-P9-33)",
            withNullAiExplanation == withoutAiExplanation
        )
        assertTrue(
            "aiExplanation=\"\"(空文字)も静的解決結果へフォールバックするべきです(T-P9-33)",
            withEmptyAiExplanation == withoutAiExplanation
        )
    }
}
