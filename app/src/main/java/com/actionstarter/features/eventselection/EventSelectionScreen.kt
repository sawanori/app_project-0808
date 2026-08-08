package com.actionstarter.features.eventselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 仕様§24 Phase A・§35 Screen1準拠（Next Event画面）。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。[onNavigateToPlanReview]は「Prepare this event」タップ相当の遷移。
 *
 * testTag規約（T-SEL-4/5/7で使用）:
 * - "event_selection_title_text" … タイトル表示テキスト（T-SEL-5の省略表示セマンティクス検証対象）
 * - "event_selection_time_text"  … 開始時刻表示テキスト（T-SEL-7のAM/PM性質検証対象）
 * - "event_selection_location_row" … 場所情報の表示行（場所情報なし時は非表示。T-SEL-4）
 *
 * 時刻表示は仕様§7.5に従い`DateTimeFormatter.ofLocalizedTime(SHORT)`を用い、
 * `"HH:mm"`等のハードコードを行わない（ロケール依存のAM/PM表記差異はこれにより担保される）。
 */
@Composable
fun EventSelectionScreen(
    uiState: EventSelectionUiState,
    onNavigateToPlanReview: () -> Unit
) {
    when (uiState) {
        is EventSelectionUiState.Loading -> {
            // Mock未接続の初期状態（契約scaffold時点の名残）。C5でMockEventSource結線後は
            // Content/Emptyへ遷移するため、Loading中は何も描画しない。
        }

        is EventSelectionUiState.Empty -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.event_selection_empty_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = stringResource(R.string.event_selection_empty_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        is EventSelectionUiState.Content -> {
            val event = uiState.nextEvent
            val locale = LocalConfiguration.current.locales[0]
            val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            val formattedTime = timeFormatter.format(ZonedDateTime.ofInstant(event.startDate, ZoneId.systemDefault()))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.event_selection_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("event_selection_title_text")
                )
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("event_selection_time_text")
                )
                if (event.locationName != null) {
                    Row(modifier = Modifier.testTag("event_selection_location_row")) {
                        Text(text = event.locationName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(onClick = onNavigateToPlanReview) {
                    Text(text = stringResource(R.string.event_selection_prepare_button))
                }
            }
        }
    }
}
