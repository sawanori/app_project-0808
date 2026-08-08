package com.actionstarter.features.departure

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 仕様§29・§35 Screen4準拠（Leave now画面）。
 *
 * Phase 1のUXフロー（`docs/plans/phase1-ui-skeleton-domain.md`§10.2）ではDeparture画面が
 * 一連の遷移の終端であり、「Start navigation」は未実装のため無効化される（T-DEP-4）。
 * そのため本画面はPhase 1時点で画面遷移ラムダを持たない。
 *
 * [DepartureUiState.estimatedArrival]がnullのとき「移動時間未取得」と表示する（T-DEP-3、
 * エラー＆レスキューマップ#5）。[DepartureUiState.arrivalBuffer]が負値のとき、
 * テキストで明示する（T-DEP-2。色による明示はG4-Eスコープの視覚回帰領域）。
 */
@Composable
fun DepartureScreen(
    uiState: DepartureUiState
) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.departure_title),
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = stringResource(R.string.departure_estimated_arrival_label),
            style = MaterialTheme.typography.labelLarge
        )
        val estimatedArrival = uiState.estimatedArrival
        if (estimatedArrival != null) {
            Text(text = timeFormatter.format(ZonedDateTime.ofInstant(estimatedArrival, ZoneId.systemDefault())))
        } else {
            Text(
                text = stringResource(R.string.departure_eta_unavailable_message),
                color = MaterialTheme.colorScheme.error
            )
        }

        Text(
            text = stringResource(R.string.departure_event_label),
            style = MaterialTheme.typography.labelLarge
        )
        val eventStart = uiState.eventStart
        if (eventStart != null) {
            Text(text = timeFormatter.format(ZonedDateTime.ofInstant(eventStart, ZoneId.systemDefault())))
        }

        Text(
            text = stringResource(R.string.departure_buffer_label),
            style = MaterialTheme.typography.labelLarge
        )
        val buffer = uiState.arrivalBuffer
        if (buffer != null) {
            if (buffer.isNegative) {
                Text(
                    text = stringResource(R.string.departure_buffer_negative_warning),
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(text = stringResource(R.string.departure_buffer_minutes_format, buffer.toMinutes()))
            }
        }

        Button(onClick = { /* Phase 1未実装（T-DEP-4） */ }, enabled = uiState.isStartNavigationEnabled) {
            Text(text = stringResource(R.string.departure_start_navigation_button))
        }
        if (!uiState.isStartNavigationEnabled) {
            Text(
                text = stringResource(R.string.departure_start_navigation_disabled_reason),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
