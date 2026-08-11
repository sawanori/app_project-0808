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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelDownloadFailureReason

/**
 * F97実装（計画書§7.1・§12.6・§13.5 S-6範囲裁定、P7-C6／Phase 8.5 F-B）。Settings画面
 * （AIトグル・モデル一覧「自動」＋各モデルのDL/削除・容量表示。S-6裁定によりフル機能Settingsは
 * 対象外）。
 *
 * §10.6の疎結合規約により、画面遷移・ViewModelアクションはラムダ引数として受け取り、
 * NavController／ViewModelを直接参照しない。
 *
 * **Phase 8.5 F-Bでの変更（計画書§3設計F-B-4、ADR-0062）**: モデル区画が単一モデルの状態表示から
 * [SettingsUiState.models]をループする複数行表示へ変わった。「DL中は他行のDL/削除ボタンを
 * 無効化」（T-P85-21・§7「DL中に別モデルのダウンロードが要求される」）は
 * [ModelListSection]が`uiState.models.any { it.status is Downloading }`から導出し、
 * [ModelOptionRow]／[ModelStatusContent]へ`isDownloadOrDeleteEnabled`として配線する
 * （DL中の行自体はボタンを描画しないため、自行を除外する特別扱いは不要）。§11確認事項1
 * （ADR-0062）の「行内注記」は[ModelOptionUiState.isMemoryInsufficient]が`true`の行に
 * `settings_model_insufficient_memory_warning`を表示する（DL状態と独立、削除提案はしない）。
 *
 * testTag規約（行ごとに`ModelOption`のid相当のsuffixを付与、[modelOptionTagId]参照）:
 * "settings_back_button" / "settings_ai_toggle" / "settings_ai_unsupported_reason" /
 * "settings_model_row_&lt;id&gt;" / "settings_model_select_&lt;id&gt;" /
 * "settings_model_recommended_badge_&lt;id&gt;" / "settings_model_insufficient_memory_warning_&lt;id&gt;" /
 * "settings_model_status_text_&lt;id&gt;" / "settings_download_progress_&lt;id&gt;" /
 * "settings_download_button_&lt;id&gt;" / "settings_delete_button_&lt;id&gt;" /
 * "settings_capacity_required_text_&lt;id&gt;" / "settings_capacity_available_text" /
 * "settings_capacity_insufficient_warning_&lt;id&gt;"。
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onNavigateBack: () -> Unit = {},
    onAiEnabledToggled: (Boolean) -> Unit = {},
    onModelSelected: (ModelOption) -> Unit = {},
    onDownloadRequested: (ModelCatalogEntry) -> Unit = {},
    onDeleteRequested: (ModelCatalogEntry) -> Unit = {}
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
        ModelListSection(
            uiState = uiState,
            onModelSelected = onModelSelected,
            onDownloadRequested = onDownloadRequested,
            onDeleteRequested = onDeleteRequested
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_capacity_available_format, uiState.availableBytes.toGigabytes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_capacity_available_text")
        )
    }
}

/** AIトグル区画（T-SET-1〜3・T-P85-20）。多モデル化と無関係、無変更。 */
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

/** [ModelOption]をtestTag／状態管理のキーへ変換する（[ModelOption.Auto]は固定文字列）。 */
private fun modelOptionTagId(option: ModelOption): String = when (option) {
    ModelOption.Auto -> "auto"
    is ModelOption.Specific -> option.entry.id
}

/**
 * モデル区画（T-SET-4〜6・T-P85-16〜22。Phase 8.5 F-Bで単一モデル表示から複数行表示へ変更）。
 * [SettingsUiState.models]を行ごとにループし、非対応端末（[SettingsUiState.isDeviceSupported]
 * が`false`）では一覧全体を無効化する（T-P85-20）。
 *
 * [anyRowDownloading]（T-P85-21）: いずれかの行が`Downloading`中なら、他行のDL/削除ボタンを
 * 無効化する（§7「DL中に別モデルのダウンロードが要求される」、二重起動防止をUI層で担う設計）。
 * DL中の行自体は[ModelStatusContent]の`Downloading`分岐がボタンを描画しないため、自行を除く
 * 特別扱いは不要。
 */
@Composable
private fun ModelListSection(
    uiState: SettingsUiState,
    onModelSelected: (ModelOption) -> Unit,
    onDownloadRequested: (ModelCatalogEntry) -> Unit,
    onDeleteRequested: (ModelCatalogEntry) -> Unit
) {
    val anyRowDownloading = uiState.models.any { it.status is ModelDownloadStatus.Downloading }
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
            uiState.models.forEach { row ->
                Spacer(Modifier.height(12.dp))
                ModelOptionRow(
                    uiState = uiState,
                    row = row,
                    isDownloadOrDeleteEnabled = !anyRowDownloading,
                    onModelSelected = onModelSelected,
                    onDownloadRequested = onDownloadRequested,
                    onDeleteRequested = onDeleteRequested
                )
            }
        }
    }
}

@Composable
private fun ModelOptionRow(
    uiState: SettingsUiState,
    row: ModelOptionUiState,
    isDownloadOrDeleteEnabled: Boolean,
    onModelSelected: (ModelOption) -> Unit,
    onDownloadRequested: (ModelCatalogEntry) -> Unit,
    onDeleteRequested: (ModelCatalogEntry) -> Unit
) {
    val tagId = modelOptionTagId(row.option)
    Column(modifier = Modifier.testTag("settings_model_row_$tagId")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = row.isSelected,
                onClick = { onModelSelected(row.option) },
                enabled = uiState.isDeviceSupported,
                modifier = Modifier.testTag("settings_model_select_$tagId")
            )
            val labelRes = when (row.option) {
                ModelOption.Auto -> R.string.settings_model_option_auto
                is ModelOption.Specific -> null
            }
            Text(
                text = labelRes?.let { stringResource(it) } ?: (row.option as ModelOption.Specific).entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (row.isRecommended) {
                Text(
                    text = stringResource(R.string.settings_model_recommended_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("settings_model_recommended_badge_$tagId")
                )
            }
        }
        if (row.option == ModelOption.Auto) {
            Text(
                text = stringResource(R.string.settings_model_option_auto_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // §11確認事項1（ADR-0062）: 現在の空きメモリでは動作しない可能性がある行への注記。
        // DL状態とは独立（未DLでも表示する）。削除提案はしない（確定回答どおり注記のみ）。
        if (row.isMemoryInsufficient) {
            Text(
                text = stringResource(R.string.settings_model_insufficient_memory_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("settings_model_insufficient_memory_warning_$tagId")
            )
        }
        val entry = (row.option as? ModelOption.Specific)?.entry
        val status = row.status
        if (entry != null && status != null) {
            ModelStatusContent(
                tagId = tagId,
                status = status,
                enabled = isDownloadOrDeleteEnabled,
                onDownloadRequested = { onDownloadRequested(entry) },
                onDeleteRequested = { onDeleteRequested(entry) }
            )
            Text(
                text = stringResource(R.string.settings_capacity_required_format, row.requiredBytesForDownload.toGigabytes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_capacity_required_text_$tagId")
            )
            if (uiState.availableBytes < row.requiredBytesForDownload) {
                Text(
                    text = stringResource(R.string.settings_capacity_insufficient_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("settings_capacity_insufficient_warning_$tagId")
                )
            }
        }
    }
}

/**
 * [enabled]（T-P85-21）: `false`のときDL/再試行/削除ボタンを無効化する（他行がDL中の場合、
 * §7「UI層で他行のDL/削除ボタンを無効化」）。`Downloading`分岐自体はボタンを持たないため
 * 影響を受けない。
 */
@Composable
private fun ModelStatusContent(
    tagId: String,
    status: ModelDownloadStatus,
    enabled: Boolean,
    onDownloadRequested: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    when (status) {
        is ModelDownloadStatus.NotInstalled -> {
            Text(
                text = stringResource(R.string.settings_model_status_not_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_model_status_text_$tagId")
            )
            Button(
                onClick = onDownloadRequested,
                enabled = enabled,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.testTag("settings_download_button_$tagId")
            ) {
                Text(stringResource(R.string.settings_download_button))
            }
        }

        is ModelDownloadStatus.Downloading -> {
            Text(
                text = stringResource(R.string.settings_model_status_downloading_format, status.percent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_model_status_text_$tagId")
            )
            LinearProgressIndicator(
                progress = { status.percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_download_progress_$tagId")
            )
        }

        is ModelDownloadStatus.Installed -> {
            Text(
                text = stringResource(R.string.settings_model_status_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("settings_model_status_text_$tagId")
            )
            OutlinedButton(
                onClick = onDeleteRequested,
                enabled = enabled,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.testTag("settings_delete_button_$tagId")
            ) {
                Text(stringResource(R.string.settings_delete_button))
            }
        }

        is ModelDownloadStatus.Failed -> {
            Text(
                text = stringResource(failureReasonTextRes(status.reason)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("settings_model_status_text_$tagId")
            )
            Button(
                onClick = onDownloadRequested,
                enabled = enabled,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.testTag("settings_download_button_$tagId")
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

/** バイト数を10進GB（1e9基準、計画書§0の「2.59GB」表記と同じ単位系）へ変換する。 */
private fun Long.toGigabytes(): Double = this / 1_000_000_000.0
