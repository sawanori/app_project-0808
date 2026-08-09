package com.actionstarter.features.eventselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.getSelectedDate
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import java.io.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * F17（権限拒否時フォールバック）手動入力フォームの入力状態（計画書§6.1／§7.5）。
 *
 * [startDateTime]はユーザーのローカル時刻（端末表示のカレンダー・時刻ピッカー入力値）を
 * そのまま保持する。`Instant`への変換（`ZoneId.systemDefault()`使用、裁定B12・T-MANUAL-1）は
 * 確定（[ManualEventEntry]の`onSubmit`）時に行う。
 *
 * [java.io.Serializable]を実装するのは、[T-MANUAL-6]（画面回転での入力保持）が呼び出し側の
 * `rememberSaveable { mutableStateOf(ManualEventEntryState()) }`（追加のカスタムSaverなし）に
 * 依存しているため。Compose の既定Saver（`autoSaver()`）は`Serializable`実装クラスを
 * そのまま保存対象と認識する。`title: String`／`locationName: String?`はもとよりSerializableで
 * あり、`startDateTime: LocalDateTime?`（`java.time.LocalDateTime`）も`Serializable`を実装する
 * ため、本クラス全体がSerializable契約を満たせる。
 */
data class ManualEventEntryState(
    val title: String = "",
    val startDateTime: LocalDateTime? = null,
    val locationName: String? = null
) : Serializable

/**
 * F17 手動入力フォームのComposable（計画書§6.1／§7.5、T-MANUAL-1〜7）。
 * [EventSelectionUiState.PermissionDenied]状態での表示先（§7.4）。
 *
 * Composeの単方向データフロー（State Hoisting）規約に従い、入力状態は[state]として
 * 受け取り[onStateChange]で変更を通知する。呼び出し側が`rememberSaveable`で状態を
 * 保持することで画面回転を跨いだ入力保持が成立する（T-MANUAL-6。[ManualEventEntryState]の
 * `Serializable`実装を参照）。
 *
 * [onSubmit]は確定操作で生成された[ExecutionEvent]を1回通知する。生成規則（計画書§7.5）：
 * - `externalCalendarId` / `notes` / `coordinates` は常に`null`（手動入力に対応する概念がない）
 * - `startDate` は[state.startDateTime]を`ZoneId.systemDefault()`で`Instant`変換した値
 *   （裁定B12、T-MANUAL-1）
 * - `locationName` は[state.locationName]をtrimし、空文字/空白のみなら`null`に正規化する
 *   （場所は任意項目）
 * - `sourceCalendar` は`CalendarSource(id = "manual", displayName = "")`固定
 *   （displayNameへ日本語/英語のハードコード文言を入れない。§7。UI側の表示解決は
 *   本Composableの責務外）
 *
 * 確定ボタン（testTag "manual_event_submit_button"）はtitleが空白、または開始日時が
 * 未入力の間は無効にする（裁定B16、T-MANUAL-2／7）。開始日時が過去の場合は確定を妨げず
 * （自動補正しない、§34ユーザー最終決定）、警告文言のみ表示する（T-MANUAL-3）。
 *
 * 画面遷移（planReviewへの遷移）は本Composableの責務外であり、呼び出し側が[onSubmit]内で
 * 行う（§10.6疎結合規約の継承）。
 *
 * testTag規約（[ManualEventEntryTest]契約）:
 * - "manual_event_title_field" … titleのテキスト入力欄
 * - "manual_event_submit_button" … 確定ボタン
 *
 * 開始日時の入力ウィジェットの具体的な形はテスト契約上未確定（[ManualEventEntryTest]
 * クラスKDoc参照）。本実装ではMaterial3の`DatePicker`/`TimePicker`をダイアログとして提示する
 * （testTag "manual_event_start_date_field" / "manual_event_start_time_field"。いずれも
 * テスト契約外の付随実装であり、[ManualEventEntryTest]は[state]を直接注入して検証するため
 * ダイアログ操作自体はテスト対象に含まれない）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEventEntry(
    state: ManualEventEntryState,
    onStateChange: (ManualEventEntryState) -> Unit,
    onSubmit: (ExecutionEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = remember { ZoneId.systemDefault() }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val titleIsBlank = state.title.isBlank()
    val startIsMissing = state.startDateTime == null
    val startIsPast = state.startDateTime?.isBefore(LocalDateTime.now(zone)) == true
    val canSubmit = !titleIsBlank && !startIsMissing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = { onStateChange(state.copy(title = it)) },
            label = { Text(text = stringResource(R.string.manual_event_title_label)) },
            isError = titleIsBlank,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_event_title_field")
        )
        if (titleIsBlank) {
            Text(
                text = stringResource(R.string.manual_event_title_required_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))

        val dateButtonLabel = state.startDateTime
            ?.toLocalDate()
            ?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it) }
            ?: stringResource(R.string.manual_event_start_date_button)
        val timeButtonLabel = state.startDateTime
            ?.toLocalTime()
            ?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(it) }
            ?: stringResource(R.string.manual_event_start_time_button)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.testTag("manual_event_start_date_field")
            ) {
                Text(text = dateButtonLabel)
            }
            OutlinedButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.testTag("manual_event_start_time_field")
            ) {
                Text(text = timeButtonLabel)
            }
        }
        if (startIsMissing) {
            Text(
                text = stringResource(R.string.manual_event_start_required_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        } else if (startIsPast) {
            Text(
                text = stringResource(R.string.manual_event_past_start_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.locationName.orEmpty(),
            onValueChange = { onStateChange(state.copy(locationName = it)) },
            label = { Text(text = stringResource(R.string.manual_event_location_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_event_location_field")
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val startDateTime = state.startDateTime
                if (state.title.isNotBlank() && startDateTime != null) {
                    onSubmit(
                        ExecutionEvent(
                            id = UUID.randomUUID(),
                            externalCalendarId = null,
                            title = state.title,
                            notes = null,
                            startDate = startDateTime.atZone(zone).toInstant(),
                            locationName = state.locationName?.trim()?.ifEmpty { null },
                            coordinates = null,
                            sourceCalendar = CalendarSource(id = "manual", displayName = "")
                        )
                    )
                }
            },
            enabled = canSubmit,
            modifier = Modifier.testTag("manual_event_submit_button")
        ) {
            Text(text = stringResource(R.string.manual_event_submit_button))
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val pickedDate = datePickerState.getSelectedDate()
                    if (pickedDate != null) {
                        val time = state.startDateTime?.toLocalTime() ?: DEFAULT_START_TIME
                        onStateChange(state.copy(startDateTime = LocalDateTime.of(pickedDate, time)))
                    }
                    showDatePicker = false
                }) {
                    Text(text = stringResource(R.string.manual_event_picker_confirm_button))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val initialTime = state.startDateTime?.toLocalTime() ?: DEFAULT_START_TIME
        val timePickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = state.startDateTime?.toLocalDate() ?: LocalDate.now(zone)
                    val pickedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                    onStateChange(state.copy(startDateTime = LocalDateTime.of(date, pickedTime)))
                    showTimePicker = false
                }) {
                    Text(text = stringResource(R.string.manual_event_picker_confirm_button))
                }
            },
            text = { TimePicker(state = timePickerState) }
        )
    }
}

/** 日付のみ選択済み・時刻未選択の状態から`LocalDateTime`を組み立てる際の既定時刻。 */
private val DEFAULT_START_TIME: LocalTime = LocalTime.of(9, 0)
