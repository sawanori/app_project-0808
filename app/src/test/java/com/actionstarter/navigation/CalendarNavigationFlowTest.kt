package com.actionstarter.navigation

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * F16/F17/F18統合フロー — T-NAV2-1（計画書§10.2、対象「NavHost（統合フロー）」）。
 *
 * 「権限拒否→手動入力→PlanReview→Executionの通しフローが成立する」を検証する統合テスト。
 * 既存[NavigationFlowTest]の手法（Robolectric＋`createAndroidComposeRule<ComponentActivity>`＋
 * 実[ActionStarterNavHost]をそのままホストし、UI操作と画面文言の出現／消失のみで検証する）を
 * 踏襲する。[ActionStarterNavHost]はNavControllerを外部へ公開しないため、本テストも
 * [NavigationFlowTest]と同様にUI経由でのみ状態を観測する（例外として、
 * `SharedPlanViewModel.selectedEvent`への反映はNavHostが内部で保持するactivity-scoped
 * ViewModelの値そのものであり、testTagやstring resourceでは表現できないため、
 * [ViewModelProvider]（`androidx.lifecycle`のコア型。ADR-0014下のNavHostがまさに
 * `viewModel()`のデフォルトスコープ＝ホストActivityで取得している同一インスタンスを
 * テスト側からも取得する）経由で直接検証する）。
 *
 * ## §15(d)解消（Fable 5裁定2026-08-09、統合修正サイクル）
 * 本テスト作成時点で本テストを失敗させていた複合要因のうち、NavHostの結線未了
 * （`onRequestCalendarPermission`／`onOpenAppSettings`／[com.actionstarter.features.eventselection.ManualEventEntry]
 * 未参照）と`EventSelectionScreen`の`PermissionDenied`空ブランチは、C5／C6統合サイクルで
 * 解消済み（[ActionStarterNavHost]・`EventSelectionScreen`の現行実装参照）。
 *
 * 残った1点（[com.actionstarter.features.eventselection.EventSelectionViewModel]の権限状態
 * 遷移が「起動前からの拒否」を`PermissionDenied`ではなく`PermissionRequired`として扱う
 * ギャップ）は§15(d)として発見・報告され、Fable 5裁定2026-08-09（§7.4改訂）により
 * `permissionRequested`フラグの導入で解消した（[EventSelectionViewModel]該当KDoc参照）。
 * このフラグは「要求UIを一度でも経由したか」で状態を分岐するため、本テストも
 * `shadowOf(application).denyPermissions(...)`（起動前拒否）に加えて、事前説明カードの
 * 許可ボタンをUI経由でタップし、その結果としてlauncherが拒否（`false`）を返す状況を
 * 明示的に作り出す必要がある。実システム許可ダイアログはRobolectric上で表示できないため、
 * `LocalActivityResultRegistryOwner`（compose）へ「起動されたら即座に`false`を返す」fake
 * `ActivityResultRegistry`を提供する方式を採用した（[ActionStarterNavHost]の
 * `rememberLauncherForActivityResult`はこのCompositionLocalからregistryを解決するため、
 * ViewModelの`onPermissionDenied()`を直接呼ぶ迂回はせず、必ずUI操作＝ボタンタップを経由して
 * 到達させる）。既存の5アサーション（手動フォーム表示→入力→submit→selectedEvent反映→
 * planReview遷移）自体は無変更。
 *
 * ## 追加発見と対応（本サイクルで判明。§15(d)の対象外だが§7.4改訂後の実測で新たに顕在化）
 * 上記の権限state machine修正後に実測したところ、[ManualEventEntry]の確定ボタンは
 * titleに加え開始日時（`startDateTime`）も揃わないと有効化されない契約（裁定B16、
 * [com.actionstarter.features.ManualEventEntryTest] T-MANUAL-7と同じ契約）であるため、
 * 開始日時入力を省略したまま`manual_event_submit_button`をタップしても（クラッシュはしない
 * ものの）ボタンのonClick内部ガードにより送信は無視され、`SharedPlanViewModel.selectedEvent`
 * が反映されないことが判明した（実測：`assertEquals`が`expected:<Studio walkthrough>
 * but was:<null>`で失敗）。これは§15(d)（権限state machineのギャップ）とは独立した別要因であり、
 * 従来この経路まで到達したことがなかったため今回初めて顕在化した。対応として、
 * `manual_event_start_date_field`ボタンでMaterial3 `DatePickerDialog`を開き、日付セルを
 * タップしてから確定（`manual_event_picker_confirm_button`のstring resource文言）をタップする
 * 手順をUI経由（実際のダイアログ操作、ViewModelやStateの直接書き換えなし）で追加した。
 * 日付セルは`Role = Button`・`Text = "[Today, ]EEEE, MMMM d, yyyy"`形式であることを
 * printToStringによるセマンティクスツリー調査で実測確認済み（調査用コード自体は
 * 成果物に残していない）。実行日に依存せず一意にヒットするよう「今日」のセルを
 * 実行時に動的に組み立てて特定する（固定文字列をハードコードしない）。時刻は指定せず
 * [ManualEventEntry]の既定値（9:00）に委ねる（本テストは時刻の妥当性を検証しないため
 * 影響しない）。開始時刻入力欄（`manual_event_start_time_field`）自体は操作しない（既定値で
 * 十分なため）。
 */
@RunWith(AndroidJUnit4::class)
class CalendarNavigationFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // T-NAV2-1: 正常系 - 権限拒否状態からの一気通貫
    // （PermissionDenied表示→手動入力フォームへ→有効入力submit→
    // SharedPlanViewModel.selectedEventに反映→planReview遷移）。
    @Test
    fun tNav2_1_permissionDeniedThroughManualEntry_sharesSelectedEventAndReachesPlanReview() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CALENDAR)

        // §15(d)解消（Fable 5裁定2026-08-09）の環境整備：実システム許可ダイアログはRobolectric
        // 上で表示できないため、launcher起動と同時に拒否（false）を即時返すfake
        // ActivityResultRegistryをLocalActivityResultRegistryOwner経由で供給する。
        val fakeRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?
                    ) {
                        @Suppress("UNCHECKED_CAST")
                        dispatchResult(requestCode, false as O)
                    }
                }
        }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeRegistryOwner) {
                ActionStarterNavHost()
            }
        }

        // 権限拒否状態へ到達させる（環境整備）：事前説明カードの許可ボタンをUI経由でタップする。
        // ActionStarterNavHost.onRequestCalendarPermissionがviewModel.onPermissionRequested()と
        // launcher.launch(...)を呼び、上記fakeレジストリが即座にfalseを返す結果
        // viewModel.onPermissionDenied()が呼ばれてPermissionDenied（手動入力フォーム）へ遷移する
        // （§7.4改訂）。ここより後は元のアサーション本体（無変更）。
        composeTestRule.onNodeWithTag("event_selection_grant_permission_button").performClick()

        // 権限拒否状態: 手動入力フォームが表示される（§7.4／F17、ManualEventEntryTestと
        // 共通のtestTag契約）
        composeTestRule.onNodeWithTag("manual_event_title_field").assertIsDisplayed()

        composeTestRule.onNodeWithTag("manual_event_title_field").performTextInput("Studio walkthrough")

        // 環境整備の追加発見（本サイクルで判明。方式選定は完了報告で開示）：
        // ManualEventEntryの確定ボタンはtitleに加えstartDateTimeも必須（裁定B16、
        // ManualEventEntryTest T-MANUAL-7と同じ契約）であり、startDateTimeが未設定のままだと
        // 「確定」タップはガード付きonClick内部で無視され（クラッシュはしない）、
        // SharedPlanViewModel.selectedEventへ反映されない。そのため開始日時もUI経由（実際の
        // DatePickerダイアログ操作）で設定する。Material3 DatePickerの日付セルはRole=Buttonで
        // 「[Today, ]EEEE, MMMM d, yyyy」形式のTextを持つ（printToStringによるセマンティクス
        // ツリー調査で実測確認済み・調査用コードは残していない）ため、実行時刻に依存せず
        // 「今日」のセルを一意に特定できるよう動的に文言を組み立てる。時刻は指定せず
        // ManualEventEntryの既定値（9:00）に委ねる。
        composeTestRule.onNodeWithTag("manual_event_start_date_field").performClick()
        val todayDateCellLabel = "Today, " + DateTimeFormatter
            .ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
            .format(LocalDate.now(ZoneId.systemDefault()))
        composeTestRule.onNodeWithText(todayDateCellLabel).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.manual_event_picker_confirm_button)).performClick()

        composeTestRule.onNodeWithTag("manual_event_submit_button").performClick()

        // 選択されたイベントがactivity-scopedのSharedPlanViewModelへ反映される
        // （ActionStarterNavHostが`viewModel()`のデフォルトスコープ＝ホストActivityで
        // 生成する同一インスタンスをViewModelProvider経由で取得する）
        val sharedPlanViewModel = ViewModelProvider(composeTestRule.activity)
            .get(SharedPlanViewModel::class.java)
        assertEquals("Studio walkthrough", sharedPlanViewModel.selectedEvent.value?.title)

        // planReviewへ遷移する
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()
    }
}
