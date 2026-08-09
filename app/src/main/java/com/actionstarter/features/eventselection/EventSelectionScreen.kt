package com.actionstarter.features.eventselection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 仕様§24 Phase A・§35 Screen1準拠（Next Event画面）。F18（Upcoming Events一覧化）を含む
 * 実装（計画書§7.3〜§7.5、T-SEL2-1〜7・T-PERM-1/2/4・T-MANUAL-1〜7）。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。
 * - [onNavigateToPlanReview] … 一覧の行タップ（Prepare this eventタップを含む）相当の遷移。
 *   引数なし（`() -> Unit`）のため「どのイベントが選ばれたか」はこの層では伝搬しない
 *   （呼び出し側＝NavHostが直前の[EventSelectionUiState.Content.events]から解決する）。
 * - [onRequestCalendarPermission] … [EventSelectionUiState.PermissionRequired]の説明カードの
 *   許可ボタンタップで1回だけ呼ばれる（T-PERM-1/2）。本Composableは`ActivityResultLauncher`を
 *   直接持たず、呼び出し側（NavHost）へ委譲する（計画書§7.3）。system dialog相当の自動起動は
 *   行わない。
 * - [onOpenAppSettings] … [EventSelectionUiState.PermissionDenied]のSettings導線タップで呼ばれる
 *   （T-PERM-4、エラー＆レスキューマップ#3）。
 * - [onRetry] … [EventSelectionUiState.Error]の再試行導線タップで呼ばれる（T-SEL2-6）。
 * - [onManualEventConfirmed] … [EventSelectionUiState.PermissionDenied]に埋め込まれる
 *   [ManualEventEntry]の確定操作で生成された[ExecutionEvent]を通知する。本Composableは
 *   このイベントをどこにも保持・送信しない（NavHost側でSharedPlanViewModel等へ受け渡す
 *   想定。CalendarNavigationFlowTestのKDoc「結線が存在しない」を参照。当該結線自体は
 *   NavHost所有のためC5スコープ）。
 *
 * testTag規約:
 * - "event_selection_row_<index>" … 一覧内のN番目（0始まり、[EventSelectionUiState.Content.events]の
 *   順序どおり。並び替え規則自体は計画書§9によりCalendarService/ViewModel側の責務）の行。
 *   タップで[onNavigateToPlanReview]を呼ぶ（T-SEL2-2）。
 * - "event_selection_next_badge" … 先頭行（index 0）にのみ存在するバッジ（T-SEL2-1）。
 * - "event_selection_title_text" / "event_selection_time_text" / "event_selection_location_row" …
 *   既存契約（T-SEL-4/5/7）を行単位で維持する（場所情報なし時は非表示。T-SEL2-4）。
 * - "event_selection_grant_permission_button" / "event_selection_open_settings_button" /
 *   "event_selection_retry_button" / "event_selection_error_message" … 各状態の主要導線。
 *
 * 先頭行（index 0）のみ仕様§35 Screen 1のワイヤーフレームどおり「次の予定」バッジと
 * "Prepare this event"ボタンを表示する（T-SEL2-1「先頭が強調される」）。2件目以降は
 * ボタンを重ねず、行全体をタップ対象にする（T-SEL2-2）。無題（title空白）の行は破棄せず
 * [R.string.event_untitled]を表示する（エラー＆レスキューマップ#13、T-SEL2-5）。
 */
@Composable
fun EventSelectionScreen(
    uiState: EventSelectionUiState,
    onNavigateToPlanReview: () -> Unit,
    onRequestCalendarPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onRetry: () -> Unit = {},
    onManualEventConfirmed: (ExecutionEvent) -> Unit = {}
) {
    // P2-C7（旧P2-C6、リファクタサイクル。§89「No giant Composable」）: 状態ごとの分岐本体を
    // private Composableへ分割し、本関数は状態のディスパッチのみを担う薄い分岐に留める。
    // 各分岐のUIツリー・Modifier・testTag・文字列リソースは分割前と完全に同一（挙動変更なし）。
    when (uiState) {
        is EventSelectionUiState.Loading -> {
            // Mock未接続の初期状態（契約scaffold時点の名残）。実行時はCalendarService結線後、
            // 初回のrefresh()完了までの瞬間的な状態としてのみ通過する。
        }

        is EventSelectionUiState.Empty -> EventSelectionEmptyContent()

        is EventSelectionUiState.Content -> EventSelectionContentList(
            uiState = uiState,
            onNavigateToPlanReview = onNavigateToPlanReview
        )

        is EventSelectionUiState.PermissionRequired -> EventSelectionPermissionRequiredContent(
            onRequestCalendarPermission = onRequestCalendarPermission
        )

        is EventSelectionUiState.PermissionDenied -> EventSelectionPermissionDeniedContent(
            onOpenAppSettings = onOpenAppSettings,
            onManualEventConfirmed = onManualEventConfirmed
        )

        is EventSelectionUiState.Error -> EventSelectionErrorContent(onRetry = onRetry)
    }
}

/** [EventSelectionUiState.Empty]の表示（一覧が0件）。分割前と同一のUIツリー。 */
@Composable
private fun EventSelectionEmptyContent() {
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

/** [EventSelectionUiState.Content]の表示（Upcoming Events一覧、F18）。分割前と同一のUIツリー。 */
@Composable
private fun EventSelectionContentList(
    uiState: EventSelectionUiState.Content,
    onNavigateToPlanReview: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.event_selection_title),
                style = MaterialTheme.typography.headlineSmall
            )
        }
        itemsIndexed(
            items = uiState.events,
            key = { _, event -> event.id }
        ) { index, event ->
            EventRow(
                index = index,
                event = event,
                timeFormatter = timeFormatter,
                onNavigateToPlanReview = onNavigateToPlanReview
            )
        }
    }
}

/** [EventSelectionUiState.PermissionRequired]の表示（事前説明カード、T-PERM-1/2）。分割前と同一のUIツリー。 */
@Composable
private fun EventSelectionPermissionRequiredContent(
    onRequestCalendarPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.calendar_permission_rationale_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(R.string.calendar_permission_rationale_message),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = onRequestCalendarPermission,
            modifier = Modifier.testTag("event_selection_grant_permission_button")
        ) {
            Text(text = stringResource(R.string.calendar_permission_grant_button))
        }
    }
}

/** [EventSelectionUiState.PermissionDenied]の表示（手動入力フォールバック、F17）。分割前と同一のUIツリー。 */
@Composable
private fun EventSelectionPermissionDeniedContent(
    onOpenAppSettings: () -> Unit,
    onManualEventConfirmed: (ExecutionEvent) -> Unit
) {
    var manualEntryState by rememberSaveable { mutableStateOf(ManualEventEntryState()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // ManualEventEntryはweight(fill=false)で上限のみ拘束する：フォームが長くなっても
        // Settingsボタンの表示領域を必ず確保する（フォームは自身のverticalScrollで
        // 内部スクロールする）。フォーム側のほうが短い場合はボタンをすぐ下に詰めて表示する
        // （画面全体を埋めるほどには引き伸ばさない）。
        ManualEventEntry(
            state = manualEntryState,
            onStateChange = { manualEntryState = it },
            onSubmit = onManualEventConfirmed,
            modifier = Modifier.weight(weight = 1f, fill = false)
        )
        Button(
            onClick = onOpenAppSettings,
            modifier = Modifier.testTag("event_selection_open_settings_button")
        ) {
            Text(text = stringResource(R.string.calendar_open_settings_button))
        }
    }
}

/** [EventSelectionUiState.Error]の表示（再試行導線、T-SEL2-6）。分割前と同一のUIツリー。 */
@Composable
private fun EventSelectionErrorContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.event_selection_error_message),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("event_selection_error_message")
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.testTag("event_selection_retry_button")
        ) {
            Text(text = stringResource(R.string.event_selection_retry_button))
        }
    }
}

/**
 * 一覧内の1行（計画書§7.3〜§7.5、T-SEL2-1〜5）。[index] 0（次の予定）のみ次バッジと
 * "Prepare this event"ボタンを表示し、行自体はクリック対象にしない（ボタンのみ）。
 * 1件以上の一覧の場合、2件目以降は行全体がクリック対象になる（T-SEL2-2）。
 *
 * 行コンテナ自体は`mergeDescendants`によるセマンティクス統合を意図的に行わない。
 * "event_selection_next_badge" 等の子testTagが[hasAnyDescendant]（[EventSelectionListTest]）で
 * 個別に検出可能であることを要件とするため（親へマージし単一ノード化すると子のtestTagが
 * 失われる。`SemanticsProperties.TestTag`のマージポリシーは祖先の値を優先し子の値を捨てる）。
 */
@Composable
private fun EventRow(
    index: Int,
    event: ExecutionEvent,
    timeFormatter: DateTimeFormatter,
    onNavigateToPlanReview: () -> Unit
) {
    val isNext = index == 0
    val formattedTime = timeFormatter.format(ZonedDateTime.ofInstant(event.startDate, ZoneId.systemDefault()))
    val displayTitle = event.title.ifBlank { stringResource(R.string.event_untitled) }

    var rowModifier = Modifier
        .testTag("event_selection_row_$index")
        .fillMaxWidth()
        .padding(vertical = 12.dp)
    if (!isNext) {
        rowModifier = rowModifier.clickable(onClick = onNavigateToPlanReview)
    }

    Column(modifier = rowModifier) {
        if (isNext) {
            Text(
                text = stringResource(R.string.event_selection_next_event_label),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("event_selection_next_badge")
            )
        }
        Text(
            text = displayTitle,
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
        if (isNext) {
            Button(onClick = onNavigateToPlanReview) {
                Text(text = stringResource(R.string.event_selection_prepare_button))
            }
        }
    }
}
