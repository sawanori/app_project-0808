package com.actionstarter.features

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.features.eventselection.EventSelectionScreen
import com.actionstarter.features.eventselection.EventSelectionUiState
import org.junit.Assert.assertFalse
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
 * F4 — Event Selection画面（計画書§11.2、T-SEL-1〜7）。
 *
 * EventSelectionScreenは現時点（C2契約scaffold）で本文が空実装のため、以下のテストは
 * すべて「期待する要素が見つからない」ことによりRedになるのが正しい（計画書§9.4／
 * `docs/TEAMS.md`§6 G2節。単なるコンパイルエラーではなくアサーション不一致による失敗）。
 *
 * testTag規約（本テストが要求するC4実装契約。§11注記に明示のないものはここで定義する）:
 * - "event_selection_title_text" … Next Eventのタイトル表示テキスト（T-SEL-5のセマンティクス検証対象）
 * - "event_selection_time_text"  … イベント開始時刻の表示テキスト（T-SEL-7のAM/PM性質検証対象）
 * - "event_selection_location_row" … 場所情報の表示行（T-SEL-4でnull時のassertDoesNotExist対象）
 */
@RunWith(AndroidJUnit4::class)
class EventSelectionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-08T00:00:00Z")

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

    // T-SEL-1: 正常系 - Screen1（Next Event）の表示要素が揃っている
    @Test
    fun tSel1_contentState_displaysAllRequiredElements() {
        val event = sampleEvent()
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(nextEvent = event),
                onNavigateToPlanReview = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(event.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(event.locationName!!).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).assertIsDisplayed()
    }

    // T-SEL-2: 正常系 - イベントをタップすると（＝Prepare this eventタップで）planReviewへ遷移する
    @Test
    fun tSel2_tapPrepareButton_navigatesToPlanReview() {
        val event = sampleEvent()
        var navigated = false
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(nextEvent = event),
                onNavigateToPlanReview = { navigated = true }
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()

        assertTrue(navigated)
    }

    // T-SEL-3: エッジケース - 空リストのとき空状態文言が表示され、クラッシュしない
    @Test
    fun tSel3_emptyState_showsEmptyMessageWithoutCrash() {
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

    // T-SEL-4: エッジケース - 場所情報がないイベントは場所行が非表示になる
    @Test
    fun tSel4_eventWithoutLocation_hidesLocationRow() {
        val event = sampleEvent(locationName = null, coordinates = null)
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(nextEvent = event),
                onNavigateToPlanReview = {}
            )
        }
        // まず基本要素が表示されること（現状は未実装のためここで失敗しRedとなる）
        composeTestRule.onNodeWithText(event.title).assertIsDisplayed()
        // 場所行のtestTagは存在しない（恒常的に成立すべき不変条件）
        composeTestRule.onNodeWithTag("event_selection_location_row").assertDoesNotExist()
    }

    // T-SEL-5: エッジケース - 100字を超えるタイトルは省略表示される
    // 実装注記（A4・計画書§11）: 省略表示の検証はセマンティクスツリー上のmaxLines／
    // TextOverflow.Ellipsisプロパティで行う。"..."文字列の検出による判定は行わない。
    @Test
    fun tSel5_longTitle_truncatesWithEllipsisSemantics() {
        val longTitle = "A very long product shoot title that keeps going on and on ".repeat(3).trim()
        require(longTitle.length > 100)
        val event = sampleEvent(title = longTitle)
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(nextEvent = event),
                onNavigateToPlanReview = {}
            )
        }

        val layoutResults = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithTag("event_selection_title_text")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { capture -> capture(layoutResults) }

        val layoutInput = layoutResults.first().layoutInput
        assertTrue(layoutInput.maxLines in 1..2)
        assertTrue(layoutInput.overflow == TextOverflow.Ellipsis)
    }

    // T-SEL-6: 正常系 - ja/en双方でタイトル等の文言が非空かつ内容が異なる
    @Test
    fun tSel6_defaultLocale_labelsRenderNonEmpty() {
        val event = sampleEvent()
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(nextEvent = event),
                onNavigateToPlanReview = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()
        val enPrepareLabel = context.getString(R.string.event_selection_prepare_button)
        assertTrue(enPrepareLabel.isNotBlank())

        composeTestRule.onNodeWithText(enPrepareLabel).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ja")
    fun tSel6_jaLocale_labelsRenderNonEmptyAndDifferFromEnglish() {
        val event = sampleEvent()
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(nextEvent = event),
                onNavigateToPlanReview = {}
            )
        }
        val jaContext = RuntimeEnvironment.getApplication()
        val jaPrepareLabel = jaContext.getString(R.string.event_selection_prepare_button)
        assertTrue(jaPrepareLabel.isNotBlank())

        // en文言を直接resources経由で取得し、ja文言と異なることを確認する
        val enPrepareLabel = "Prepare this event"
        assertTrue(jaPrepareLabel != enPrepareLabel)

        composeTestRule.onNodeWithText(jaPrepareLabel).assertIsDisplayed()
    }

    // T-SEL-7: 正常系 - 時刻表示はen-USでAM/PM表記を含み、jaでは含まない（性質検証）
    @Test
    @Config(qualifiers = "en-rUS")
    fun tSel7_enUsLocale_timeDisplay_includesAmPm() {
        val event = sampleEvent(startDate = fixedNow.plus(10, ChronoUnit.HOURS))
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(nextEvent = event),
                onNavigateToPlanReview = {}
            )
        }

        val node = composeTestRule.onNodeWithTag("event_selection_time_text").fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = " ") { it.text }.orEmpty()

        assertTrue(text.contains("AM", ignoreCase = true) || text.contains("PM", ignoreCase = true))
    }

    @Test
    @Config(qualifiers = "ja-rJP")
    fun tSel7_jaLocale_timeDisplay_excludesAmPm() {
        val event = sampleEvent(startDate = fixedNow.plus(10, ChronoUnit.HOURS))
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(nextEvent = event),
                onNavigateToPlanReview = {}
            )
        }

        val node = composeTestRule.onNodeWithTag("event_selection_time_text").fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = " ") { it.text }.orEmpty()

        assertFalse(text.contains("AM", ignoreCase = true) || text.contains("PM", ignoreCase = true))
    }
}
