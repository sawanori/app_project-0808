package com.actionstarter.features.recovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.actionstarter.R

/**
 * 仕様§31-34・§35 Screen5準拠（Plan updated画面）。最大の独自価値。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。[onNavigateToExecution]は「Use this plan」タップ相当の遷移。
 * 候補選択自体は自動適用されず（仕様§34、T-REC-5）、[selectedId]は画面内の「仮選択中」の
 * ローカル状態にとどめる。
 *
 * testTag規約: 各候補行を "recovery_option_item_<id>" 形式で付与する（T-REC-2/4/5）。
 * 行はテキスト検証のため`mergeDescendants = true`で子Textのセマンティクスをマージする。
 */
@Composable
fun RecoveryScreen(
    uiState: RecoveryUiState,
    onNavigateToExecution: () -> Unit
) {
    var selectedId by rememberSaveable(uiState.selectedOptionId) {
        mutableStateOf(uiState.selectedOptionId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.recovery_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(R.string.recovery_subtitle),
            style = MaterialTheme.typography.bodyMedium
        )

        if (uiState.options.isEmpty()) {
            // 候補0件（エラー＆レスキューマップ#11）：案内文言のみを手動導線として提示する
            // （§34の確認操作前提を守るため、Use this plan／See alternativesは表示しない）。
            Text(
                text = stringResource(R.string.recovery_no_options_message),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Column {
                uiState.options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .testTag("recovery_option_item_${option.id}")
                            .semantics(mergeDescendants = true) {}
                            .clickable { selectedId = option.id }
                            .padding(vertical = 8.dp)
                    ) {
                        Column {
                            Text(text = option.title, style = MaterialTheme.typography.titleMedium)
                            Text(text = option.explanation, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNavigateToExecution) {
                    Text(text = stringResource(R.string.recovery_use_this_plan_button))
                }
                Button(onClick = { /* Phase 1: 候補切替は表示のみ（画面内で完結） */ }) {
                    Text(text = stringResource(R.string.recovery_see_alternatives_button))
                }
            }
        }
    }
}
