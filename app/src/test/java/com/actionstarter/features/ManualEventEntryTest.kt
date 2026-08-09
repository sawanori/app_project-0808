package com.actionstarter.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.features.eventselection.ManualEventEntry
import com.actionstarter.features.eventselection.ManualEventEntryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * F17 — 手動入力フォーム（計画書§7.5／§10.2、T-MANUAL-1〜7）。
 *
 * [ManualEventEntry]の本文は現時点（P2-C3前段scaffold）で意図的に空実装
 * （「期待する入力欄／確定ボタンが見つからない」というアサーション不一致でRedにする」と
 * KDocに明記済み）のため、以下は全件Redになるのが正しい（単なるコンパイルエラーではない）。
 *
 * 本テストは[ManualEventEntry]がstate-hoisted（[ManualEventEntryState]を外部から受け取る）
 * であることを利用し、「入力途中の状態」を実際のUI操作（ピッカー操作等）で再現するのでは
 * なく、あらかじめ組み立てた[ManualEventEntryState]を直接渡すことで検証する。開始日時の
 * 入力ウィジェット（DatePicker/TimePicker等）の具体的な形は計画書上もP2-C4以降の実装判断
 * （未確定）であるため、本テストはその実装詳細に依存しない。
 *
 * testTag規約（本テストが要求するC4実装契約）:
 * - "manual_event_title_field" … titleのテキスト入力欄（[performTextInput]対象）
 * - "manual_event_submit_button" … 確定ボタン。titleが空白、または開始日時が未入力の間は
 *   無効（disabled）にする（裁定B16、T-MANUAL-2／7）
 *
 * strings契約: [R.string.manual_event_title_required_error]／
 * [R.string.manual_event_start_required_error]／[R.string.manual_event_past_start_warning]／
 * [R.string.manual_event_submit_button]（本Redサイクルでen/ja同時追加済み）。
 */
@RunWith(AndroidJUnit4::class)
class ManualEventEntryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // T-MANUAL-1: 正常系 - title＋開始日時を入力して確定するとExecutionEventが生成される。
    // 生成されるstartDateは入力値をZoneId.systemDefault()でInstant変換した値と一致する
    // （裁定B12）。§7.5の構築規則どおりexternalCalendarId/notes/coordinatesはnullになる。
    @Test
    fun tManual1_validTitleAndStart_submitProducesExecutionEventWithZoneConvertedInstant() {
        val startLocal = LocalDateTime.of(2026, 8, 10, 9, 30)
        var submitted: ExecutionEvent? = null
        composeTestRule.setContent {
            ManualEventEntry(
                state = ManualEventEntryState(title = "Team sync", startDateTime = startLocal, locationName = null),
                onStateChange = {},
                onSubmit = { submitted = it }
            )
        }

        composeTestRule.onNodeWithTag("manual_event_submit_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("manual_event_submit_button").performClick()

        val expectedInstant = startLocal.atZone(ZoneId.systemDefault()).toInstant()
        assertEquals("Team sync", submitted?.title)
        assertEquals(expectedInstant, submitted?.startDate)
        assertNull(submitted?.externalCalendarId)
        assertNull(submitted?.notes)
        assertNull(submitted?.coordinates)
    }

    // T-MANUAL-2: 異常系 - titleが空白のみのとき確定ボタンが無効になり、理由が表示される
    // （裁定B16）。
    @Test
    fun tManual2_blankTitle_submitDisabledWithReasonShown() {
        composeTestRule.setContent {
            ManualEventEntry(
                state = ManualEventEntryState(
                    title = "   ",
                    startDateTime = LocalDateTime.of(2026, 8, 10, 9, 30),
                    locationName = null
                ),
                onStateChange = {},
                onSubmit = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("manual_event_submit_button").assertIsNotEnabled()
        composeTestRule.onNodeWithText(context.getString(R.string.manual_event_title_required_error))
            .assertIsDisplayed()
    }

    // T-MANUAL-3: 異常系 - 開始日時が過去でも確定は可能（自動補正しない。§34ユーザー最終
    // 決定）だが、「すでに開始時刻を過ぎています」相当の警告が表示される。
    @Test
    fun tManual3_pastStartDateTime_submitAllowedButShowsPastWarning() {
        val pastLocal = LocalDateTime.now(ZoneId.systemDefault()).minusDays(1)
        var submitted: ExecutionEvent? = null
        composeTestRule.setContent {
            ManualEventEntry(
                state = ManualEventEntryState(title = "Team sync", startDateTime = pastLocal, locationName = null),
                onStateChange = {},
                onSubmit = { submitted = it }
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.manual_event_past_start_warning))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("manual_event_submit_button").assertIsEnabled()

        composeTestRule.onNodeWithTag("manual_event_submit_button").performClick()

        // 自動補正しない：入力した過去日時がそのままInstant変換されて渡る
        assertEquals(pastLocal.atZone(ZoneId.systemDefault()).toInstant(), submitted?.startDate)
    }

    // T-MANUAL-4: エッジケース - 場所未入力で確定すると、locationName == nullのイベントが
    // 生成される（場所は任意項目）。
    @Test
    fun tManual4_blankLocation_producesEventWithNullLocationName() {
        var submitted: ExecutionEvent? = null
        composeTestRule.setContent {
            ManualEventEntry(
                state = ManualEventEntryState(
                    title = "Team sync",
                    startDateTime = LocalDateTime.of(2026, 8, 10, 9, 30),
                    locationName = null
                ),
                onStateChange = {},
                onSubmit = { submitted = it }
            )
        }

        composeTestRule.onNodeWithTag("manual_event_submit_button").performClick()

        assertNull(submitted?.locationName)
    }

    // T-MANUAL-5: エッジケース - 生成イベントのsourceCalendar.id == "manual"であり、
    // displayNameに日本語/英語のハードコード文言が入っていない（空文字。§7）。
    @Test
    fun tManual5_submittedEvent_hasManualSourceCalendarWithoutHardcodedDisplayName() {
        var submitted: ExecutionEvent? = null
        composeTestRule.setContent {
            ManualEventEntry(
                state = ManualEventEntryState(
                    title = "Team sync",
                    startDateTime = LocalDateTime.of(2026, 8, 10, 9, 30),
                    locationName = "Shibuya"
                ),
                onStateChange = {},
                onSubmit = { submitted = it }
            )
        }

        composeTestRule.onNodeWithTag("manual_event_submit_button").performClick()

        assertEquals(CalendarSource(id = "manual", displayName = ""), submitted?.sourceCalendar)
    }

    // T-MANUAL-6: エッジケース - 入力途中で画面回転（プロセス構成変更相当）しても入力内容が
    // 失われない。ManualEventEntryはstate-hoistedであるため、呼び出し側がrememberSaveableで
    // 状態を保持する自然な利用パターンをこのテスト内のハーネスComposableで再現し、
    // ManualEventEntryStateがrememberSaveableの既定の保存機構と両立することを検証する。
    @Test
    fun tManual6_rotationDuringInput_preservesEnteredTitle() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            var state by rememberSaveable { mutableStateOf(ManualEventEntryState()) }
            ManualEventEntry(
                state = state,
                onStateChange = { state = it },
                onSubmit = {}
            )
        }

        composeTestRule.onNodeWithTag("manual_event_title_field").performTextInput("Team sync")

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Team sync").assertIsDisplayed()
    }

    // T-MANUAL-7: エッジケース - 開始日時が未入力のとき確定ボタンが無効になる（裁定B16）。
    @Test
    fun tManual7_missingStartDateTime_submitDisabledWithReasonShown() {
        composeTestRule.setContent {
            ManualEventEntry(
                state = ManualEventEntryState(title = "Team sync", startDateTime = null, locationName = null),
                onStateChange = {},
                onSubmit = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("manual_event_submit_button").assertIsNotEnabled()
        composeTestRule.onNodeWithText(context.getString(R.string.manual_event_start_required_error))
            .assertIsDisplayed()
    }
}
