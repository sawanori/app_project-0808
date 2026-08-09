package com.actionstarter.features.execution

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.actionstarter.BuildConfig
import com.actionstarter.R
import com.actionstarter.features.common.resolveStepTitle

/**
 * 仕様§27-28・§35 Screen3準拠（NOW画面）。プロダクト最重要UI。
 * ONE ACTION ONLY原則（仕様§28）：同時に1ステップ（[ExecutionUiState.currentStep]）
 * のみをComposeツリーに存在させ、畳んだリスト・進捗プレビューは描画しない。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。
 * - [onNavigateToDeparture]：最終ステップDone相当の遷移（T-EXEC-4）。
 *   [ExecutionUiState.currentStep]が`null`かつ[ExecutionUiState.snackbarMessageResId]も
 *   `null`のとき（＝ステップが尽きた正常終了。準備ステップ0件のケースを含む、T-EXEC-5）に
 *   自動的に呼ばれる。
 * - [onNavigateToRecovery]：Recovery割込相当の遷移。Phase 1ではU4（`docs/plans/
 *   phase1-ui-skeleton-domain.md`§10.4）に基づく「Simulate delay (debug)」ボタンから
 *   呼ばれる。ボタンの表示可否は`BuildConfig.DEBUG`（`app/build.gradle.kts`で
 *   `buildFeatures.buildConfig = true`が有効化済み・C5対応）でガードし、releaseビルドには
 *   非搭載とする。
 * - [onNavigateToEventSelection]：プロセス再生成後の状態復元不能時の遷移
 *   （エラー＆レスキューマップ#8、T-EXEC-9）。[ExecutionUiState.snackbarMessageResId]が
 *   非nullのとき自動的に呼ばれる。
 *
 * testTag規約: 現在ステップComposableに"step_item_<id>"形式のtestTagを付与する
 * （T-EXEC-2実装注記）。
 */
@Composable
fun ExecutionScreen(
    uiState: ExecutionUiState,
    onNavigateToDeparture: () -> Unit,
    onNavigateToRecovery: () -> Unit,
    onNavigateToEventSelection: () -> Unit
) {
    val currentStep = uiState.currentStep

    if (currentStep == null) {
        LaunchedEffect(uiState.snackbarMessageResId) {
            if (uiState.snackbarMessageResId != null) {
                onNavigateToEventSelection()
            } else {
                onNavigateToDeparture()
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.execution_now_label),
            style = MaterialTheme.typography.labelLarge
        )

        Column(
            modifier = Modifier
                .testTag("step_item_${currentStep.id}")
                .semantics(mergeDescendants = true) {}
        ) {
            Text(
                text = currentStep.title.ifBlank { resolveStepTitle(currentStep.semanticId) },
                style = MaterialTheme.typography.headlineMedium
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val onDone = uiState.onDone
                if (onDone != null) {
                    onDone()
                } else {
                    onNavigateToDeparture()
                }
            }) {
                Text(text = stringResource(R.string.execution_done_button))
            }
            Button(onClick = { uiState.onPostpone?.invoke() }) {
                Text(text = stringResource(R.string.execution_five_min_later_button))
            }
        }

        if (BuildConfig.DEBUG) {
            Button(onClick = onNavigateToRecovery) {
                Text(text = stringResource(R.string.execution_simulate_delay_debug_button))
            }
        }
    }
}
