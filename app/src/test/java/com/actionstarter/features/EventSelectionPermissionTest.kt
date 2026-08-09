package com.actionstarter.features

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.features.eventselection.EventSelectionScreen
import com.actionstarter.features.eventselection.EventSelectionUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * F16/F17 — 権限UI（計画書§7.3／§7.4／§10.2、T-PERM-1／T-PERM-2／T-PERM-4）。
 *
 * Fable 5裁定によりP2-C3後半（本ファイル）の担当範囲はT-PERM-1／T-PERM-2／T-PERM-4に
 * 限定される（T-PERM-3／5／6／7はP2-C4冒頭へ移動済みのため本ファイルの対象外。
 * それらはCalendarService／ON_RESUME再チェック等、実際のCalendar結線を伴うためRed化に
 * C4のGreen実装着手が前提となるという判断）。
 *
 * [EventSelectionScreen]の[EventSelectionUiState.PermissionRequired]／
 * [EventSelectionUiState.PermissionDenied]分岐は現時点（P2-C3前段scaffold）で
 * Exhaustive when要件を満たすためだけの空ブランチであるため、以下は全件
 * 「期待する要素が見つからない」ことでRedになるのが正しい（単なるコンパイルエラーでは
 * なくアサーション不一致による失敗。`docs/TEAMS.md`§6 G2節）。
 *
 * testTag規約（本テストが要求するC4/C5実装契約）:
 * - "event_selection_grant_permission_button" … 事前説明カードの許可ボタン（T-PERM-1/2）。
 *   タップで[EventSelectionScreen]の`onRequestCalendarPermission`ラムダを呼ぶ
 *   （§7.3：本Composableは`ActivityResultLauncher`を直接持たず、呼び出し側へ委譲する）。
 * - "event_selection_open_settings_button" … PermissionDenied状態のSettings誘導ボタン
 *   （T-PERM-4）。タップで`onOpenAppSettings`ラムダを呼ぶ。
 * - "manual_event_title_field" … [com.actionstarter.features.eventselection.ManualEventEntry]の
 *   testTag契約（[ManualEventEntryTest]参照）。PermissionDenied状態はこのフォームを
 *   埋め込んで表示する（§7.4「手動入力フォーム＋Settings導線を常に併記」）。
 */
@RunWith(AndroidJUnit4::class)
class EventSelectionPermissionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // T-PERM-1: 正常系 - PermissionRequired状態の初回表示でsystem dialog相当の
    // onRequestCalendarPermissionが自動起動されない（呼び出し回数0。§95.4「アプリ起動時に
    // 一括要求しない」）。あわせて事前説明カード（タイトル・本文・許可ボタン）が
    // 表示されることを検証する（エラーマップ#1）。
    @Test
    fun tPerm1_permissionRequiredState_doesNotAutoRequestAndShowsRationaleCard() {
        var requestCount = 0
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.PermissionRequired,
                onNavigateToPlanReview = {},
                onRequestCalendarPermission = { requestCount++ }
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.calendar_permission_rationale_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.calendar_permission_rationale_message))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.calendar_permission_grant_button))
            .assertIsDisplayed()
        // system dialogに相当するラムダは、タップ等の明示操作なしには一切呼ばれていない
        assertEquals(0, requestCount)
    }

    // T-PERM-2: 正常系 - 事前説明カードの許可ボタンをタップするとonRequestCalendarPermission
    // が1回だけ呼ばれる（明示タップでのみ要求する。§95.4）。
    @Test
    fun tPerm2_tapGrantButton_invokesOnRequestCalendarPermissionExactlyOnce() {
        var requestCount = 0
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.PermissionRequired,
                onNavigateToPlanReview = {},
                onRequestCalendarPermission = { requestCount++ }
            )
        }

        composeTestRule.onNodeWithTag("event_selection_grant_permission_button").performClick()

        assertEquals(1, requestCount)
    }

    // T-PERM-4: 異常系 - PermissionDenied状態で手動入力フォーム（ManualEventEntryの入力欄）と
    // Settings導線ボタンの両方が同時に表示される（エラーマップ#2／#3、§95.6第1行）。
    // Settingsボタンのタップでは`onOpenAppSettings`が1回呼ばれる。
    @Test
    fun tPerm4_permissionDeniedState_showsManualEntryAndSettingsLinkTogether() {
        var settingsOpenCount = 0
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.PermissionDenied,
                onNavigateToPlanReview = {},
                onOpenAppSettings = { settingsOpenCount++ }
            )
        }
        val context = RuntimeEnvironment.getApplication()

        // 手動入力導線（ManualEventEntryの入力欄。ManualEventEntryTestと共通のtestTag契約）
        composeTestRule.onNodeWithTag("manual_event_title_field").assertIsDisplayed()
        // Settings導線が常時併記される（§7.4「今後表示しない」永久拒否対応・エラーマップ#3）
        composeTestRule.onNodeWithText(context.getString(R.string.calendar_open_settings_button))
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("event_selection_open_settings_button").performClick()

        assertEquals(1, settingsOpenCount)
    }
}
