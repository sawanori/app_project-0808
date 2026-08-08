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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.actionstarter.R

/**
 * 仕様§26・§35 Screen2準拠（Your plan画面）。AIが勝手に確定しない（画面表示だけでは
 * 自動的にexecutionへ遷移しない。T-PLAN-2）。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。[onNavigateToExecution]は「Start」タップ相当の遷移。
 *
 * testTag規約: 各ステップ行を "plan_review_step_item" タグで統一する（T-PLAN-1、
 * `onAllNodesWithTag`で収集）。行はテキスト検証のため`mergeDescendants = true`で
 * 子Textのセマンティクスを行自体へマージする。
 *
 * 準備ステップ0件でも[PlanReviewUiState.plan]は生成され、Start可能とする（U5、T-PLAN-4）。
 * Editボタンは[PlanReviewUiState.isEditEnabled]がPhase 1で常にfalseのため無効化され、
 * 理由文言を併記する（T-PLAN-6）。
 */
@Composable
fun PlanReviewScreen(
    uiState: PlanReviewUiState,
    onNavigateToExecution: () -> Unit
) {
    val plan = uiState.plan ?: return // 未取得状態（Mock未接続）。C5でSharedPlanViewModel結線後に解消。

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

        if (plan.steps.isEmpty()) {
            Text(
                text = stringResource(R.string.plan_review_no_steps_message),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Column {
                plan.steps.forEach { step ->
                    Row(
                        modifier = Modifier
                            .testTag("plan_review_step_item")
                            .semantics(mergeDescendants = true) {}
                            .padding(vertical = 4.dp)
                    ) {
                        Text(text = step.title, style = MaterialTheme.typography.bodyLarge)
                    }
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
