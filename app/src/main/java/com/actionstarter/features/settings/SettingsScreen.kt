package com.actionstarter.features.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import com.actionstarter.ai.model.ModelDownloadFailureReason

/**
 * F97実装（計画書§7.1・§12.6・§13.5 S-6範囲裁定、P7-C6）。Settings画面（AIトグル・モデル状態・
 * DL/削除・容量表示のみ、S-6裁定によりフル機能Settingsは対象外）。
 *
 * §10.6の疎結合規約により、画面遷移・ViewModelアクションはラムダ引数として受け取り、
 * NavController／ViewModelを直接参照しない（他画面と同型）。刷新済みテーマ
 * （`ui/theme`のティール配色・カード・余白）に合わせ、Card区画（AIトグル区画／モデル区画）で
 * 構成する。`ActionStarterNavHost`のScaffoldが既にsafe-drawing insetsを適用済みのため、
 * 本Composable自身はstatusBars等のpaddingを追加しない（[com.actionstarter.features.
 * eventselection.EventSelectionScreen]のKDoc「edge-to-edge insetsについての設計判断」と同型）。
 *
 * testTag規約: "settings_back_button" / "settings_ai_toggle" /
 * "settings_ai_unsupported_reason" / "settings_model_status_text" /
 * "settings_download_progress" / "settings_download_button"（初回DL・retry兼用） /
 * "settings_delete_button" / "settings_capacity_required_text" /
 * "settings_capacity_available_text" / "settings_capacity_insufficient_warning"。
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onNavigateBack: () -> Unit = {},
    onAiEnabledToggled: (Boolean) -> Unit = {},
    onDownloadRequested: () -> Unit = {},
    onDeleteRequested: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        TextButton(
            onClick = onNavigateBack,
            modifier = Modifier.testTag("settings_back_button")
        ) {
            Text(stringResource(R.string.settings_back_button_label))
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(20.dp))
        AiToggleSection(uiState = uiState, onAiEnabledToggled = onAiEnabledToggled)
        Spacer(Modifier.height(16.dp))
        ModelSection(
            uiState = uiState,
            onDownloadRequested = onDownloadRequested,
            onDeleteRequested = onDeleteRequested
        )
    }
}

/** AIトグル区画（T-SET-1〜3）。 */
@Composable
private fun AiToggleSection(uiState: SettingsUiState, onAiEnabledToggled: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_ai_toggle_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = uiState.aiEnabled,
                    onCheckedChange = onAiEnabledToggled,
                    enabled = uiState.isDeviceSupported,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("settings_ai_toggle")
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_ai_toggle_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!uiState.isDeviceSupported) {
                Spacer(Modifier.height(8.dp))
                val reasonTextRes = when (uiState.deviceUnsupportedReason) {
                    DeviceUnsupportedReason.UNSUPPORTED_ABI -> R.string.settings_ai_unsupported_abi_reason
                    DeviceUnsupportedReason.INSUFFICIENT_RAM, null -> R.string.settings_ai_unsupported_ram_reason
                }
                Text(
                    text = stringResource(reasonTextRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("settings_ai_unsupported_reason")
                )
            }
        }
    }
}

/** モデル区画（T-SET-4〜6。状態表示・DL/削除導線・容量表示）。 */
@Composable
private fun ModelSection(
    uiState: SettingsUiState,
    onDownloadRequested: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_model_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    text = stringResource(R.string.settings_model_name_label) + ": ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = uiState.selectedModel.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(4.dp))
            ModelStatusContent(
                status = uiState.modelStatus,
                onDownloadRequested = onDownloadRequested,
                onDeleteRequested = onDeleteRequested
            )
            Spacer(Modifier.height(12.dp))
            CapacitySection(uiState)
        }
    }
}

@Composable
private fun ModelStatusContent(
    status: ModelDownloadStatus,
    onDownloadRequested: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    when (status) {
        is ModelDownloadStatus.NotInstalled -> {
            Text(
                text = stringResource(R.string.settings_model_status_not_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_model_status_text")
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDownloadRequested,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.testTag("settings_download_button")
            ) {
                Text(stringResource(R.string.settings_download_button))
            }
        }

        is ModelDownloadStatus.Downloading -> {
            Text(
                text = stringResource(R.string.settings_model_status_downloading_format, status.percent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_model_status_text")
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { status.percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_download_progress")
            )
        }

        is ModelDownloadStatus.Installed -> {
            Text(
                text = stringResource(R.string.settings_model_status_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("settings_model_status_text")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDeleteRequested,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.testTag("settings_delete_button")
            ) {
                Text(stringResource(R.string.settings_delete_button))
            }
        }

        is ModelDownloadStatus.Failed -> {
            Text(
                text = stringResource(failureReasonTextRes(status.reason)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("settings_model_status_text")
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDownloadRequested,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.testTag("settings_download_button")
            ) {
                Text(stringResource(R.string.settings_retry_button))
            }
        }
    }
}

/** T-SET-6。[ModelDownloadFailureReason]ごとの表示文言（検証失敗・容量不足は専用文言）。 */
private fun failureReasonTextRes(reason: ModelDownloadFailureReason): Int = when (reason) {
    ModelDownloadFailureReason.INSUFFICIENT_STORAGE -> R.string.settings_model_status_failed_insufficient_storage
    ModelDownloadFailureReason.VERIFICATION_FAILED -> R.string.settings_model_status_failed_verification
    ModelDownloadFailureReason.INSECURE_URL,
    ModelDownloadFailureReason.NETWORK_ERROR,
    ModelDownloadFailureReason.HTTP_ERROR,
    ModelDownloadFailureReason.SIZE_EXCEEDED,
    ModelDownloadFailureReason.STORAGE_ERROR -> R.string.settings_model_status_failed_generic
}

/** T-SET-5。必要容量・空き容量の常時表示、不足時は追加で警告を出す。 */
@Composable
private fun CapacitySection(uiState: SettingsUiState) {
    Column {
        Text(
            text = stringResource(R.string.settings_capacity_required_format, uiState.requiredBytesForDownload.toGigabytes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_capacity_required_text")
        )
        Text(
            text = stringResource(R.string.settings_capacity_available_format, uiState.availableBytes.toGigabytes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_capacity_available_text")
        )
        if (uiState.hasInsufficientStorage) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_capacity_insufficient_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("settings_capacity_insufficient_warning")
            )
        }
    }
}

/** バイト数を10進GB（1e9基準、計画書§0の「2.59GB」表記と同じ単位系）へ変換する。 */
private fun Long.toGigabytes(): Double = this / 1_000_000_000.0
