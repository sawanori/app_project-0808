package com.actionstarter.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.actionstarter.MainActivity
import com.actionstarter.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F17/F20 — 権限拒否E2E（計画書§10.2 T-E2E2-2、§12.1 Step 6、裁定B14）。
 *
 * **P2-C8fix改訂（Fable 5分析、2026-08-09）**: 初回のP2-C8実測で本テストがFAILした
 * （`manual_event_title_field`が表示されない）。原因は本テストの旧バージョンが
 * `adb shell pm revoke`直後に手動入力フォームへ直接到達すると誤って前提していたこと。
 * 実際には計画書§7.4改訂（Fable 5裁定2026-08-09、`EventSelectionViewModel`の
 * `permissionRequested`フラグ導入）により、`pm revoke`後の**新規プロセスでの初回起動**は
 * `permissionRequested`が既定値`false`のままであるため
 * [com.actionstarter.features.eventselection.EventSelectionUiState.PermissionRequired]
 * （事前説明カード）に帰着するのが**正しい仕様どおりの挙動**である（ホスト側の`pm revoke`は
 * アプリ内の`permissionRequested`フラグを一切変更しない）。[PermissionDenied]
 * （手動入力フォールバック）へ到達するには、`ActionStarterNavHost`が結線する実際の
 * `ActivityResultContracts.RequestPermission`launcher経由で拒否結果を受け取り
 * `onPermissionDenied()`が呼ばれる必要がある。したがって本テストは
 * ①事前説明カードの許可ボタン（testTag "event_selection_grant_permission_button"）を
 * Compose経由でタップ→②実際に起動するOS権限ダイアログ（`com.google.android.permissioncontroller`、
 * Composeのアクセシビリティツリー外＝`ComposeTestRule`では操作不可）を`UiDevice`（UiAutomator）
 * 経由で「Don't allow」相当のボタンをタップして拒否→③その結果[EventSelectionViewModel.onPermissionDenied]
 * が呼ばれ[PermissionDenied]へ遷移し手動入力フォームが表示される、という3段階の状態遷移を
 * 実際に踏む形へ改めた（`EventSelectionViewModel.kt`・`ActionStarterNavHost.kt`該当KDoc、
 * および計画書§7.4「確定裁定」参照）。
 *
 * **OS権限ダイアログの実文言（2026-08-09、emulator実機実測。`actionstarter_test`AVD・API 35）**:
 * メッセージ「Allow Action Starter to access your calendar?」、ボタン「Allow」
 * （resource-id `com.android.permissioncontroller:id/permission_allow_button`）／
 * 「Don't allow」（resource-id `com.android.permissioncontroller:id/permission_deny_button`）。
 * 「Don't allow」の実文字列はタイポグラフィックアポストロフィ（U+2019）を含むため、
 * テキスト一致ではなくresource-id一致で選択する（ロケール・グリフ差異に対して頑健）。
 *
 * **作成のみ・実行しない、の制約は解除済み**（P2-C8で実行フェーズに入った。従来の
 * [CalendarE2ETest]と同じ制約下にあったが、本ファイルはP2-C8fixで実行対象になった）。
 *
 * **裁定B14による分離実行の必須要件**: 実行時権限の`revoke`は対象アプリのプロセスを
 * killするため、拒否状態を作る操作を本テストクラス内（`@Before`等）で行うことは禁止する。
 * quality-runnerが**テストプロセス起動前にホスト側で**
 * `adb shell pm revoke com.actionstarter android.permission.READ_CALENDAR`を実行し
 * 未許可状態を確定させたうえで、新規プロセスとして本クラスのみを他のE2Eケース
 * （[CalendarE2ETest]）とは別の実行（別の`am instrument`／Gradle`--tests`指定呼び出し）として
 * 起動する。同一テストプロセス内で[CalendarE2ETest]と連続実行しない。
 *
 * **`pm revoke`の成否確認（M-10・§12.1 Step 6・エラーマップ#20）**: `pm revoke`は終了コード0・
 * 無出力でも実際には権限が剥奪されていない場合があるサイレント障害を持つ
 * （対象権限が未宣言の場合等）。quality-runnerは本テスト実行前に必ず
 * `adb shell dumpsys package com.actionstarter | grep "android.permission.READ_CALENDAR: granted="`
 * を実行し`granted=false`であることを確認してから本テストを実行する。終了コードのみでの
 * 成功判定は禁止する（誤って権限が残ったままテストが「成功」と誤認されることを防ぐ）。
 *
 * §12.1 Step 6の完全な手順: ①ホスト側で`pm revoke`実行 ②`dumpsys package`で`granted=false`
 * 確認 ③本クラスを独立実行 ④`pm grant`で権限を戻す ⑤`granted=true`を確認
 * ⑥[CalendarE2ETest]等の残りのE2Eケースを実行する。
 *
 * testTag／string契約は[EventSelectionPermissionTest]（T-PERM-4）・[ManualEventEntryTest]と
 * 共通（"manual_event_title_field"、[R.string.calendar_open_settings_button]）。
 * 事前説明カードのtestTag（"event_selection_grant_permission_button"）は
 * [EventSelectionScreen]のtestTag規約（KDoc参照）と共通。
 */
@RunWith(AndroidJUnit4::class)
class CalendarPermissionDeniedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    // T-E2E2-2: 異常系 - READ_CALENDAR未許可状態で起動すると、まず事前説明カードが表示され
    // （§7.4改訂・Fable 5裁定2026-08-09、起動前拒否は`permissionRequested=false`のため
    // PermissionRequiredに帰着する）、そこから実際にOS権限ダイアログを経由して拒否すると
    // 手動入力フォームとSettings導線が表示され、アプリが継続動作する（GOAL.md D(3)/F、
    // エラーマップ#2）。クラッシュしないこと自体が主要な検証対象であり、Basic Engineの
    // 全機能が手動入力経由で継続利用できることを示す（§95.4冒頭「アプリ全体が停止しない
    // ことを必須とする」）。
    @Test
    fun tE2e2_2_readCalendarDenied_showsManualEntryAndAppKeepsRunning() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // ①事前説明カード（PermissionRequired）が表示されていることを確認する
        // （§7.4改訂の状態機械どおり、起動前拒否＋permissionRequested=falseの帰着先）。
        composeTestRule.onNodeWithTag("event_selection_grant_permission_button").assertIsDisplayed()

        // ②説明カードの許可ボタンをComposeテストフレームワーク経由でタップする。
        // これにより`ActionStarterNavHost`が`onPermissionRequested()`を呼んだうえで
        // 実際の`ActivityResultContracts.RequestPermission`launcherを起動する。
        composeTestRule.onNodeWithTag("event_selection_grant_permission_button").performClick()

        // ③起動したOS権限ダイアログ（com.google.android.permissioncontroller）は
        // Composeのセマンティクスツリー外＝ComposeTestRuleでは操作できないため、
        // UiAutomator（UiDevice）で操作する。「Don't allow」ボタンをresource-idで待機・
        // 特定してタップする（実文言のアポストロフィ差異に頑健。KDoc冒頭の実測結果参照）。
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val denyButtonFound = device.wait(
            Until.hasObject(By.res(PERMISSION_DENY_BUTTON_RES_ID)),
            OS_DIALOG_WAIT_TIMEOUT_MS
        )
        check(denyButtonFound) {
            "OS permission dialog's deny button ($PERMISSION_DENY_BUTTON_RES_ID) did not appear " +
                "within ${OS_DIALOG_WAIT_TIMEOUT_MS}ms after tapping the grant button. " +
                "If the app has already been denied this permission twice via real UI interaction " +
                "in this install, Android may auto-deny without showing the dialog; a full " +
                "uninstall+reinstall (not just `adb install -r`) resets that history."
        }
        device.findObject(By.res(PERMISSION_DENY_BUTTON_RES_ID)).click()

        // OS側ダイアログのウィンドウが完全に消えてからComposeの再照会に戻る
        // （ダイアログ消滅とCompose側再コンポーズのタイミングずれによるflaky回避）。
        device.wait(Until.gone(By.pkg(PERMISSION_CONTROLLER_ACTIVITY_PACKAGE)), OS_DIALOG_WAIT_TIMEOUT_MS)
        composeTestRule.waitForIdle()

        // ④拒否結果を受けて`onPermissionDenied()`経由でPermissionDenied（手動入力
        // フォールバック）へ遷移していることを確認する（既存アサーション、変更なし）。
        composeTestRule.onNodeWithTag("manual_event_title_field").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.calendar_open_settings_button))
            .assertIsDisplayed()
    }

    private companion object {
        // P2-C8fix実測（2026-08-09、logcat実測で確認）: 実行中のAPK/プロセスのpackage名
        // （`By.pkg`が照合する対象、ActivityTaskManagerのログにも現れる）は
        // "com.google.android.permissioncontroller"だが、resource-idのprefix
        // （`By.res`が照合する対象）はビルド時のリソースパッケージ名の都合で異なり
        // "com.android.permissioncontroller"（"google."を含まない）となる。実機の
        // `uiautomator dump`で両属性を直接確認済み。初回実装でこの2つを同一の定数に
        // 統一してしまい、`By.res`が終始マッチせず10秒フルタイムアウトしてFAILする
        // 回帰を作り込んだため（本コメント自体がその修正記録）、意図的に別定数へ分離する。
        private const val PERMISSION_CONTROLLER_ACTIVITY_PACKAGE = "com.google.android.permissioncontroller"
        private const val PERMISSION_CONTROLLER_RESOURCE_PACKAGE = "com.android.permissioncontroller"
        private const val PERMISSION_DENY_BUTTON_RES_ID =
            "$PERMISSION_CONTROLLER_RESOURCE_PACKAGE:id/permission_deny_button"
        private const val OS_DIALOG_WAIT_TIMEOUT_MS = 10_000L
    }
}
