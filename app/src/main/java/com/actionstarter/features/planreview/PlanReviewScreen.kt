package com.actionstarter.features.planreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * `onAllNodesWithTag`で収集）。行はテキスト検証のため`mergeDescendants = true`で
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
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.plan_review_title),
            style = MaterialTheme.typography.headlineSmall
        )

        if (uiState.isBehindSchedule) {
            Text(
                text = stringResource(R.string.plan_review_behind_schedule_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        val hasNoPreparationSteps = plan.steps.none {
            it.type == ExecutionStepType.TRANSITION || it.type == ExecutionStepType.PREPARATION
        }

        if (hasNoPreparationSteps) {
            Text(
                text = stringResource(R.string.plan_review_no_steps_message),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Column {
                plan.steps.forEach { step ->
                    PlanReviewStepRow(step = step, timeFormatter = timeFormatter)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onNavigateToExecution) {
                Text(text = stringResource(R.string.plan_review_start_button))
            }
            Button(onClick = { /* Phase 1未実装（T-PLAN-6） */ }, enabled = uiState.isEditEnabled) {
                Text(text = stringResource(R.string.plan_review_edit_button))
            }
        }
        if (!uiState.isEditEnabled) {
            Text(
                text = stringResource(R.string.plan_review_edit_disabled_reason),
                style = MaterialTheme.typography.bodySmall
            )
        }
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
 */
@Composable
private fun PlanReviewStepRow(step: ExecutionStep, timeFormatter: DateTimeFormatter) {
    val scheduledStart = step.scheduledStart
    val formattedTime = scheduledStart?.let {
        timeFormatter.format(ZonedDateTime.ofInstant(it, ZoneId.systemDefault()))
    }
    val title = step.title.ifBlank { resolveStepTitle(step.semanticId) }
    val rowContentDescription = listOfNotNull(formattedTime, title).joinToString(" ")

    Row(
        modifier = Modifier
            .testTag("plan_review_step_item")
            .semantics(mergeDescendants = true) { contentDescription = rowContentDescription }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (formattedTime != null) {
            Text(
                text = formattedTime,
                modifier = Modifier.testTag("plan_review_step_time_text"),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
