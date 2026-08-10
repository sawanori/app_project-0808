package com.actionstarter.features.planreview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.features.common.resolveStepTitle
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 仕様§26・§35 Screen2準拠（Your plan画面）。AIが勝手に確定しない（画面表示だけでは
 * 自動的にexecutionへ遷移しない。T-PLAN-2）。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。[onNavigateToExecution]は「Start」タップ相当の遷移。
 *
 * testTag規約: 各ステップ行を "plan_review_step_item" タグで統一する（T-PLAN-1、
 * `onAllNodesWithTag`で収集する）。行はテキスト検証のため`mergeDescendants = true`で
 * 子Textのセマンティクスを行自体へマージする。行内の時刻テキストは
 * "plan_review_step_time_text" タグを持つ（F47、T-P4UI-1）。親が`mergeDescendants = true`
 * のため、このタグへのクエリは`useUnmergedTree = true`で行う必要がある。
 *
 * ステップ行のtitleは[ExecutionStep.title][com.actionstarter.domain.model.ExecutionStep.title]が
 * 非空ならそのまま描画し、空文字（`BasicPlanningEngine`が常に生成する。G-4）のときは
 * [resolveStepTitle]で[ExecutionStep.semanticId][com.actionstarter.domain.model.ExecutionStep.semanticId]から
 * 解決する（F47、T-P4UI-2/3/5。既存の[com.actionstarter.features.execution.ExecutionScreen]と
 * 同じフォールバック規約）。
 *
 * 準備ステップ0件でも[PlanReviewUiState.plan]は生成され、Start可能とする（U5、T-PLAN-4）。
 * 「準備ステップなし」の判定はTRANSITION/PREPARATION双方が0件かどうかで行う（F44により
 * DEPARTUREが常に1件生成されるため`plan.steps.isEmpty()`が構造的に成立しなくなったことへの
 * 対応。§9エラーマップ#13、T-P4UI-4）。
 * Editボタンは[PlanReviewUiState.isEditEnabled]がPhase 1で常にfalseのため無効化され、
 * 理由文言を併記する（T-PLAN-6）。
 *
 * **再デザインサイクル2（目的・UX合致サイクル）**: 前サイクルはExecution／EventSelection
 * のみテーマ・カードを適用済みで、本画面は素の`Column`・無地`Text`・無地`Button`のまま
 * だった。本サイクルで①`background(colorScheme.background)`の明示＋`verticalScroll`追加
 * （ステップ数が多い実データでもボタンまで到達可能にする。RecoveryScreen・DepartureScreen
 * の既存パターンを踏襲）、②各ステップ行を[OutlinedCard]（EventSelectionScreenの2件目以降の
 * 行と同一トークン）へ統合、③「予定より遅れています」警告を`errorContainer`チップ
 * （Execution/Departureの警告チップと同一視覚言語）へ統一、④[PlanReviewUiState.plan]が
 * 既に保持する`plan.event.title`を見出し直下の控えめなキャプションとして表示（§10「UXの
 * 繋がり」。新規のViewModel／UiStateフィールドは不要——`plan`経由で既に取得可能なため）、
 * ⑤Start／Editボタンをshapes.large・height 52dpへ統一——を行った。可視テキストの内容・
 * testTag・`Modifier.semantics{contentDescription=...}`はいずれも既存のまま1つも変更して
 * おらず、`PlanReviewScreenTest`・`PlanReviewStepDisplayTest`・`AccessibilitySemanticsTest`・
 * `FontScaleResilienceTest`の既存回帰ガードへの影響はない。
 *
 * **edge-to-edge insetsについての設計判断**: [com.actionstarter.features.execution.
 * ExecutionScreen]と同じ理由により、本画面もstatusBars／navigationBarsのpaddingを自前で
 * 追加しない（`ActionStarterNavHost`側の外側`Scaffold`が既にsafe-drawing insetsをNavHost
 * 全体へ一度だけ適用済み）。
 */
@Composable
fun PlanReviewScreen(
    uiState: PlanReviewUiState,
    onNavigateToExecution: () -> Unit
) {
    val plan = uiState.plan ?: return // 未取得状態（Mock未接続）。C5でSharedPlanViewModel結線後に解消。

    val locale = LocalConfiguration.current.locales[0]
    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Text(
            text = stringResource(R.string.plan_review_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        // §10「UXの繋がり」: 「どの予定のためのプランか」の控えめな文脈。plan.event.titleは
        // PlanReviewUiState.planに既に含まれるため新規フィールドは不要（無い情報を捏造しない、
        // 空白タイトルなら非表示）。
        if (plan.event.title.isNotBlank()) {
            Text(
                text = plan.event.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (uiState.isBehindSchedule) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.plan_review_behind_schedule_warning),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        val hasNoPreparationSteps = plan.steps.none {
            it.type == ExecutionStepType.TRANSITION || it.type == ExecutionStepType.PREPARATION
        }

        if (hasNoPreparationSteps) {
            Text(
                text = stringResource(R.string.plan_review_no_steps_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                plan.steps.forEach { step ->
                    PlanReviewStepRow(step = step, timeFormatter = timeFormatter)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onNavigateToExecution,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Text(text = stringResource(R.string.plan_review_start_button), style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
                onClick = { /* Phase 1未実装（T-PLAN-6） */ },
                enabled = uiState.isEditEnabled,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Text(text = stringResource(R.string.plan_review_edit_button))
            }
        }
        if (!uiState.isEditEnabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.plan_review_edit_disabled_reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * P4-C6軽リファクタ（§89「No giant Composable」、`docs/plans/phase4-basic-engine.md`§10
 * P4-C6）: [PlanReviewScreen]のステップ行描画（C4でF47の時刻表示・title解決を追加した箇所）を
 * 単一責務の private Composable へ抽出したもの（`EventSelectionScreen.kt`の`EventRow`と同じ
 * 抽出パターン、P2-C6先例踏襲）。挙動・testTag・文字列リソースは抽出前と不変。
 *
 * **F81実装（P11-C3、T-P11A-2）**: P4-C6時点でscaffoldされていた空`semantics(mergeDescendants
 * = true) {}`ブロックへcontentDescription（時刻＋タイトル）を実装した。行を1つの読み上げ単位
 * として文脈のある文（例:「09:15 準備を始める」）に統合する（§7.2）。
 *
 * **再デザインサイクル2**: 素の`Row`から[OutlinedCard]（EventSelectionScreenの2件目以降の
 * 行と同一トークン: `surface`背景・`outlineVariant`ボーダー・`shapes.large`）へ変更した。
 * `rowModifier`（testTag・semantics）はカードの`modifier`引数へそのまま渡すのみで内容・意味は
 * 変更していない（[EventSelectionScreen]の`EventRow`と同じ「既存modifierチェーンをCardの
 * modifierへ渡す」パターン）。時刻テキスト（testTag "plan_review_step_time_text"）の
 * `style`は`bodyLarge`のまま不変（[com.actionstarter.features.PlanReviewStepDisplayTest]の
 * `tP4Ui1`が`SemanticsProperties.Text`の内容のみを検証するため、`style`変更は無関係だが
 * 念のため据え置いた）。
 */
@Composable
private fun PlanReviewStepRow(step: ExecutionStep, timeFormatter: DateTimeFormatter) {
    val scheduledStart = step.scheduledStart
    val formattedTime = scheduledStart?.let {
        timeFormatter.format(ZonedDateTime.ofInstant(it, ZoneId.systemDefault()))
    }
    val title = step.title.ifBlank { resolveStepTitle(step.semanticId) }
    val rowContentDescription = listOfNotNull(formattedTime, title).joinToString(" ")

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plan_review_step_item")
            .semantics(mergeDescendants = true) { contentDescription = rowContentDescription },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (formattedTime != null) {
                Text(
                    text = formattedTime,
                    modifier = Modifier.testTag("plan_review_step_time_text"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
