package com.actionstarter.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.actionstarter.MainActivity
import com.actionstarter.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F17/F20 — 権限拒否E2E（計画書§10.2 T-E2E2-2、§12.1 Step 6、裁定B14）。
 *
 * **作成のみ・実行しない**（[CalendarE2ETest]と同じ制約。計画書§10.2末尾「E2E群は実行する
 * までpassとして報告することを禁止し、G2／G3の証拠には含めない」）。
 *
 * **裁定B14による分離実行の必須要件**: 実行時権限の`revoke`は対象アプリのプロセスを
 * killするため、拒否状態を作る操作を本テストクラス内（`@Before`等）で行うことは禁止する。
 * quality-runnerが**テストプロセス起動前にホスト側**で
 * `adb shell pm revoke com.actionstarter android.permission.READ_CALENDAR`を実行し拒否状態を
 * 確定させたうえで、新規プロセスとして本クラスのみを他のE2Eケース（[CalendarE2ETest]）とは
 * 別の実行（別の`am instrument`／Gradle`--tests`指定呼び出し）として起動する。同一テスト
 * プロセス内で[CalendarE2ETest]と連続実行しない。
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
 */
@RunWith(AndroidJUnit4::class)
class CalendarPermissionDeniedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    // T-E2E2-2: 異常系 - READ_CALENDAR拒否状態で起動すると手動入力フォームとSettings導線が
    // 表示され、アプリが継続動作する（GOAL.md D(3)/F、エラーマップ#2）。クラッシュしない
    // こと自体が主要な検証対象であり、Basic Engineの全機能が手動入力経由で継続利用できる
    // ことを示す（§95.4冒頭「アプリ全体が停止しないことを必須とする」）。
    @Test
    fun tE2e2_2_readCalendarDenied_showsManualEntryAndAppKeepsRunning() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeTestRule.onNodeWithTag("manual_event_title_field").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.calendar_open_settings_button))
            .assertIsDisplayed()
    }
}
