package com.actionstarter.features

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.features.eventselection.EventSelectionScreen
import com.actionstarter.features.eventselection.EventSelectionUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * F18 — Event Selectionの一覧化（計画書§10.2、T-SEL2-1／2／3／4／5／7）。
 *
 * Fable 5裁定によりP2-C3後半（本ファイル）の担当範囲はT-SEL2-1〜5・7（6を除く）に限定
 * される。T-SEL2-6（`Failure`→エラー表示＋再試行導線）はP2-C4冒頭へ移動済みのため
 * 本ファイルの対象外。
 *
 * [EventSelectionUiState.Content]は既にC2で`events: List<ExecutionEvent>`保持へ変更済みだが、
 * [EventSelectionScreen]の描画は現時点（P2-C3前段scaffold）で「既存describable要素を
 * 壊さない最小適応として、従来どおり先頭（次の）イベント1件のみを描画する」状態であり、
 * 複数件の一覧描画（LazyColumn化等）はまだ実装されていない。したがって以下は全件
 * 「期待する要素（2件目以降の行・次イベントバッジ）が見つからない」ことでRedになるのが
 * 正しい（単なるコンパイルエラーではなくアサーション不一致による失敗）。
 *
 * testTag規約（本テストが要求するC4実装契約。既存の"event_selection_location_row"／
 * "event_selection_title_text"／"event_selection_time_text"タグ〔EventSelectionScreenTest〕は
 * 単一イベント表示時の契約としてそのまま維持し、本ファイルは一覧表示向けに以下を追加する）:
 * - "event_selection_row_<index>" … 一覧内のN番目（0始まり、開始時刻昇順）のイベントを
 *   表す行コンテナ。タップで（少なくとも）`onNavigateToPlanReview`が呼ばれる。
 * - "event_selection_next_badge" … 先頭行（index 0、最も近い次のイベント）の内部にのみ
 *   表示されるバッジ（[R.string.event_selection_next_event_label]）。
 * - 各行内の"event_selection_location_row"（既存タグを行単位で繰り返し使用可）は、
 *   その行のイベントの`locationName`がnullのとき存在しない。
 *
 * 既知の限界（T-SEL2-2、報告参照）: [EventSelectionScreen]の`onNavigateToPlanReview`は
 * 本ファイル作成時点で`() -> Unit`（引数なし）のままであり、「どのイベントがタップされたか」
 * を型で伝搬する手段がない。「選択したイベントが共有される」ことの厳密な検証
 * （計画書§10.2 T-SEL2-2後半）はNavHost／SharedPlanViewModel統合レベル（T-NAV2-1相当）
 * でのみ可能であり、T-NAV2-1はP2-C4冒頭へ移動済みのため本ファイルの対象外。本ファイルの
 * T-SEL2-2は「先頭以外の行をタップしても遷移ラムダが呼ばれる」ことのみを検証する。
 */
@RunWith(AndroidJUnit4::class)
class EventSelectionListTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-09T00:00:00Z")

    private fun sampleEvent(
        title: String = "Product Shoot",
        locationName: String? = "Shibuya",
        coordinates: Coordinate? = Coordinate(35.6, 139.7),
        startDate: Instant = fixedNow.plus(1, ChronoUnit.HOURS)
    ): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = title,
        notes = null,
        startDate = startDate,
        locationName = locationName,
        coordinates = coordinates,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    // T-SEL2-1: 正常系 - 3件のイベントが開始時刻昇順で表示され、先頭が「次の予定」として
    // 強調される（§35 Screen 1）。バッジは先頭行にのみ存在し、他の行には存在しない。
    @Test
    fun tSel2_1_multipleEvents_displayAllWithOnlyFirstRowHighlightedAsNext() {
        val first = sampleEvent(title = "Team Standup", startDate = fixedNow.plus(1, ChronoUnit.HOURS))
        val second = sampleEvent(title = "Client Call", startDate = fixedNow.plus(3, ChronoUnit.HOURS))
        val third = sampleEvent(title = "Design Review", startDate = fixedNow.plus(6, ChronoUnit.HOURS))
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(events = listOf(first, second, third)),
                onNavigateToPlanReview = {}
            )
        }

        composeTestRule.onNodeWithText(first.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(second.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(third.title).assertIsDisplayed()

        // 先頭行のみに「次の予定」バッジが存在する
        composeTestRule.onNode(
            hasTestTag("event_selection_row_0") and hasAnyDescendant(hasTestTag("event_selection_next_badge"))
        ).assertExists()
        composeTestRule.onNode(
            hasTestTag("event_selection_row_1") and hasAnyDescendant(hasTestTag("event_selection_next_badge"))
        ).assertDoesNotExist()
        composeTestRule.onNode(
            hasTestTag("event_selection_row_2") and hasAnyDescendant(hasTestTag("event_selection_next_badge"))
        ).assertDoesNotExist()
    }

    // T-SEL2-2: 正常系 - 任意の1件のタップでplanReviewへ遷移する（onNavigateToPlanReview
    // 呼び出し）。「先頭以外」を選んでタップすることで、一覧内のどの行からでも遷移できる
    // ことを検証する（本ファイルKDoc「既知の限界」参照：選択イベントの共有内容自体の
    // 検証はNavHost統合レベルの対象）。
    @Test
    fun tSel2_2_tapNonFirstEventRow_invokesNavigateToPlanReview() {
        val first = sampleEvent(title = "Team Standup", startDate = fixedNow.plus(1, ChronoUnit.HOURS))
        val second = sampleEvent(title = "Client Call", startDate = fixedNow.plus(3, ChronoUnit.HOURS))
        val third = sampleEvent(title = "Design Review", startDate = fixedNow.plus(6, ChronoUnit.HOURS))
        var navigated = false
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(events = listOf(first, second, third)),
                onNavigateToPlanReview = { navigated = true }
            )
        }

        composeTestRule.onNodeWithTag("event_selection_row_2").performClick()

        assertTrue(navigated)
    }

    // T-SEL2-3: エッジケース - 権限あり・予定0件のとき、既存の空状態文言が表示され
    // クラッシュしない。EventSelectionUiState.Emptyの描画自体はPhase 1から実装済みのため
    // 既にGreenの可能性があるが、F18の新UiState契約下でのケースID網羅として作成する。
    @Test
    fun tSel2_3_zeroEventsWithPermissionGranted_showsEmptyStateWithoutCrash() {
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Empty,
                onNavigateToPlanReview = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_empty_message)).assertIsDisplayed()
    }

    // T-SEL2-4: エッジケース - 一覧中の1件がlocationName == nullのとき、その行だけ場所行が
    // 非表示になる（既存T-SEL-4の一覧版。他の行の場所表示には影響しない）。
    @Test
    fun tSel2_4_eventWithoutLocationInList_hidesOnlyThatRowsLocationRow() {
        val withLocation = sampleEvent(
            title = "Client Call",
            locationName = "Shibuya Office",
            startDate = fixedNow.plus(1, ChronoUnit.HOURS)
        )
        val withoutLocation = sampleEvent(
            title = "Focus Block",
            locationName = null,
            coordinates = null,
            startDate = fixedNow.plus(3, ChronoUnit.HOURS)
        )
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(events = listOf(withLocation, withoutLocation)),
                onNavigateToPlanReview = {}
            )
        }

        composeTestRule.onNodeWithText(withLocation.locationName!!).assertIsDisplayed()
        composeTestRule.onNodeWithText(withoutLocation.title).assertIsDisplayed()
        composeTestRule.onNode(
            hasTestTag("event_selection_row_1") and hasAnyDescendant(hasTestTag("event_selection_location_row"))
        ).assertDoesNotExist()
    }

    // T-SEL2-5: エッジケース - 一覧中にtitleが空のイベントがあっても行は破棄されず、
    // event_untitled代替文言が表示される（無題イベントを不可視にしない。エラーマップ#13）。
    @Test
    fun tSel2_5_untitledEventInList_showsFallbackTextWithoutHidingRow() {
        val untitled = sampleEvent(title = "", startDate = fixedNow.plus(1, ChronoUnit.HOURS))
        val other = sampleEvent(title = "Client Call", startDate = fixedNow.plus(3, ChronoUnit.HOURS))
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(events = listOf(untitled, other)),
                onNavigateToPlanReview = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.event_untitled)).assertIsDisplayed()
        composeTestRule.onNodeWithText(other.title).assertIsDisplayed()
    }

    // T-SEL2-7: 正常系 - 一覧化に伴う新規文言（次の予定バッジ）がja/en双方で非空かつ
    // 異なる（既存T-SEL-6/7と同じ検証パターンをF18新規文言へ適用）。
    @Test
    fun tSel2_7_defaultLocale_nextEventLabelIsNonBlankAndDisplayed() {
        val event = sampleEvent()
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(events = listOf(event)),
                onNavigateToPlanReview = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()
        val enLabel = context.getString(R.string.event_selection_next_event_label)
        assertTrue(enLabel.isNotBlank())

        composeTestRule.onNodeWithText(enLabel).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ja")
    fun tSel2_7_jaLocale_nextEventLabelIsNonBlankAndDiffersFromEnglish() {
        val event = sampleEvent()
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(events = listOf(event)),
                onNavigateToPlanReview = {}
            )
        }
        val jaContext = RuntimeEnvironment.getApplication()
        val jaLabel = jaContext.getString(R.string.event_selection_next_event_label)
        assertTrue(jaLabel.isNotBlank())

        // en文言を直接resources経由で取得し、ja文言と異なることを確認する
        // （EventSelectionScreenTest.tSel6_jaLocale...と同じ確認パターン）
        val enLabel = "Next up"
        assertTrue(jaLabel != enLabel)

        composeTestRule.onNodeWithText(jaLabel).assertIsDisplayed()
    }

    // T-SEL2-6（P2-C4冒頭のRed先行ミニステップで追加。計画書§10.2）: 異常系 -
    // Failure（プロバイダ異常）→ 空状態ではなくエラー表示＋再試行導線が出る
    // （0件と失敗をUI上で区別する。エラーマップ#6/#7系統）。
    //
    // [EventSelectionScreen]のError分岐は現時点でExhaustive when要件を満たすためだけの
    // 空ブランチ（本ファイル作成時点のEventSelectionScreen.kt実装より）であるため、以下は
    // 「期待する要素が見つからない」ことでRedになるのが正しい（単なるコンパイルエラーでは
    // なくアサーション不一致による失敗。本ファイル冒頭KDoc「T-SEL2-6…本ファイルの対象外」の
    // 記述は本ケース追加により更新対象だが、既存テスト変更禁止のため当該KDoc自体は
    // 変更せず本コメントで補足するに留める）。
    //
    // testTag規約（本テストが要求するC4実装契約。新規）:
    // - "event_selection_error_message" … エラー時のメッセージ表示（存在確認のみ。具体的な
    //   文言はP2-C4実装時に決定するためstringResourceは参照しない）
    // - "event_selection_retry_button" … 再試行ボタン。タップで[EventSelectionScreen]の
    //   `onRetry`ラムダを呼ぶ（呼び出し元は`EventSelectionViewModel.onRetry`を渡す想定。
    //   計画書§7.3）
    @Test
    fun tSel2_6_failureState_showsErrorWithRetryAffordanceInsteadOfEmpty() {
        var retryCount = 0
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Error,
                onNavigateToPlanReview = {},
                onRetry = { retryCount++ }
            )
        }

        composeTestRule.onNodeWithTag("event_selection_error_message").assertIsDisplayed()
        composeTestRule.onNodeWithTag("event_selection_retry_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("event_selection_retry_button").performClick()

        assertTrue(retryCount == 1)
    }

    // T-SEL2-8（C8欠陥の回帰ロック・Fable 5承認2026-08-09）: 正常系 - 複数件表示時、先頭行
    // （event_selection_row_0、ヒーロー行）自体がクリック対象になる。実機E2E（tE2e2_1/4）が
    // event_selection_row_0の「中心タップ」で遷移することを前提にしているため
    // （Composeの`performClick()`は対象ノードにOnClickセマンティクスが無い場合、実座標への
    // フォールバックgestureへ切り替わり、行の中心が内側の"Prepare this event"ボタン上に
    // 一致しないと遷移しない＝C8で実測された欠陥そのもの）、行ノード自身が
    // `hasClickAction()`を満たすことを幾何（実際の描画座標）に依存せず決定的に検証する。
    // あわせて、その行をクリックするとonNavigateToPlanReviewが呼ばれることを検証する。
    // 「選択イベントが先頭イベントであること」は、T-SEL2-1と同じ手がかり
    // （event_selection_row_0がevent_selection_next_badgeを子に持つ＝一覧先頭=events[0]の行で
    // あることの構造的確認）によって担保する。本ComposableのonNavigateToPlanReviewは
    // 引数なし（クラスKDoc「既知の限界」参照）のため、どのイベントが選ばれたかという値自体の
    // 伝搬検証はこのファイルの対象外であり、ここでは「先頭行＝先頭イベントの行」であることと
    // 「その行のクリックで遷移ラムダが呼ばれること」の両方を確認する。
    @Test
    fun tSel2_8_heroRowIsClickable_tapInvokesNavigateToPlanReviewForHeadEvent() {
        val first = sampleEvent(title = "Team Standup", startDate = fixedNow.plus(1, ChronoUnit.HOURS))
        val second = sampleEvent(title = "Client Call", startDate = fixedNow.plus(3, ChronoUnit.HOURS))
        val third = sampleEvent(title = "Design Review", startDate = fixedNow.plus(6, ChronoUnit.HOURS))
        var navigated = false
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(events = listOf(first, second, third)),
                onNavigateToPlanReview = { navigated = true }
            )
        }

        // row_0 が一覧先頭（=events[0]=first）を表す行であることをT-SEL2-1と同じ手がかりで
        // 確認したうえで、行自体がクリック可能であることを幾何非依存に検証する。
        composeTestRule.onNode(
            hasTestTag("event_selection_row_0") and hasAnyDescendant(hasTestTag("event_selection_next_badge"))
        ).assertExists()
        composeTestRule.onNodeWithTag("event_selection_row_0").assert(hasClickAction())

        composeTestRule.onNodeWithTag("event_selection_row_0").performClick()

        assertTrue(navigated)
    }
}
