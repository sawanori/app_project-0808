package com.actionstarter.e2e

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.actionstarter.MainActivity
import com.actionstarter.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F21〜F30 — Routing/Location instrumented E2E（計画書§9.8 T-E2E3-1〜4、
 * `docs/plans/phase3-routing-location.md`§12 P3-C8）。Phase 3 C2後半（test-writer,
 * 2026-08-09）が新規作成。
 *
 * **作成のみ・実行しない**（担当プロンプトの明示指示。計画書§9.8「E2E群・opt-inは実行するまで
 * passとして報告することを禁止し、G2／G3の証拠に含めない（実行はG4-Eのみ）」。
 * [CalendarE2ETest]／[CalendarPermissionDeniedTest]と同じ制約下にある）。
 *
 * ## 前提（本クラスの実行前にquality-runnerがP3-C8／計画書§12.1相当の手順で用意する）
 * 1. Phase 2 §12.1 Step 0（裁定B10）のエミュレータ判定ガード（`ro.kernel.qemu`＝`1`かつ
 *    `ro.boot.qemu.avd_name`＝`actionstarter_test`）を通過していること。
 * 2. Phase 2のカレンダーseed手順（[CalendarE2ETest]のKDoc参照）により、Upcoming一覧に
 *    到達可能な予定が最低1件投入済みであること（`event_selection_row_0`が表示される状態）。
 * 3. [tE2e3_1_mockedLocationWithSeededEvent_showsEtaOnDepartureScreen]のみ:
 *    `adb shell pm grant com.actionstarter android.permission.ACCESS_FINE_LOCATION`により
 *    位置権限が許可済みであること、かつ`adb emu geo fix <lon> <lat>`（P3-P3実測待ち。
 *    計画書§6.1 `scripts/emu-geo-fix.sh`は本タスク時点では未作成）でモック位置を投入済みで
 *    あること。
 * 4. [tE2e3_2_locationPermissionDenied_continuesWithManualTravelTimeInput]のみ:
 *    `adb shell pm revoke com.actionstarter android.permission.ACCESS_FINE_LOCATION`実行後、
 *    `adb shell dumpsys package com.actionstarter | grep ACCESS_FINE_LOCATION`で
 *    `granted=false`を確認済みであること（[CalendarPermissionDeniedTest]のM-10教訓と同じ
 *    確認方法）。**裁定B14と同様、権限のrevokeはプロセスをkillするため、本ケースは
 *    quality-runnerが他の3ケースとは別のインスツルメンテーション実行（別の`am instrument`／
 *    Gradle`--tests`指定呼び出し）として単独起動すること**（同一テストプロセス内で
 *    連続実行しない）。
 * 5. BuildConfig.ROUTES_API_KEYが設定されているか否かでETAが実データ／縮退表示のいずれに
 *    なるかが変わる。いずれの分岐でもクラッシュしないことが本クラス全体の検証対象である。
 *
 * Departure画面への到達経路は
 * [CalendarE2ETest.tE2e2_1_seededCalendarEvents_selectionToDepartureFlowSucceeds]と同一の
 * testTag／文言契約（`event_selection_row_0` → `plan_review_start_button` →
 * `execution_done_button` → `departure_title`）を再利用する。
 */
@RunWith(AndroidJUnit4::class)
class RoutingLocationE2ETest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun navigateToDeparture(context: Context) {
        composeTestRule.onNodeWithTag("event_selection_row_0").performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()
    }

    // T-E2E3-1: 正常系 - `adb emu geo fix`で投入した現在地＋Phase 2のseed済みイベントで、
    // Departure画面にETAが表示される（§67完成条件の実測）。
    @Test
    fun tE2e3_1_mockedLocationWithSeededEvent_showsEtaOnDepartureScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        navigateToDeparture(context)

        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_estimated_arrival_label))
            .assertIsDisplayed()
        // §67完成条件「現在地→予定先の所要時間が取れる」の核心：ETA未取得メッセージが
        // 表示されないこと（取得できたことの間接的な証跡）。
        composeTestRule.onNodeWithText(context.getString(R.string.departure_eta_unavailable_message))
            .assertDoesNotExist()
    }

    // T-E2E3-2: 異常系 - 位置権限拒否状態で起動 → 手動Travel Time入力で継続動作する
    // （GOAL.md D(3)/F、エラー＆レスキューマップ#2）。`pm revoke`済みの状態（クラスKDoc参照）で
    // quality-runnerが単独起動する。
    @Test
    fun tE2e3_2_locationPermissionDenied_continuesWithManualTravelTimeInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        navigateToDeparture(context)

        // クラッシュせずDeparture画面へ到達すること自体が主要な検証対象（§95.4冒頭
        // 「アプリ全体が停止しないことを必須とする」）。
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.travel_time_manual_label))
            .assertIsDisplayed()
    }

    // T-E2E3-3: 異常系 - 機内モード（`svc wifi disable` / `svc data disable`）→
    // 経路取得失敗の縮退表示が出てクラッシュしない（§95.6）。
    @Test
    fun tE2e3_3_airplaneModeNetworkUnavailable_showsDegradedEtaWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("svc wifi disable")
        device.executeShellCommand("svc data disable")

        try {
            navigateToDeparture(context)

            // クラッシュしないこと自体が主要な検証対象（§95.6「Execution自体は停止しない」）。
            composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
        } finally {
            // 後続テストへ影響を残さないよう必ず復旧する。
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
        }
    }

    // T-E2E3-4: 正常系 - ja/en両ロケールでDeparture画面のスクリーンショットを取得
    // （GOAL.md D/E）。1回のインスツルメンテーション実行内でのロケール動的切替は困難なため
    // （[CalendarE2ETest]のT-E2E2-4と同じ制約）、quality-runnerがG4-Eでen/jaそれぞれ独立した
    // テストランとして実行しスクリーンショットを取得する（`adb exec-out screencap`等）。
    // 本テストは1ロケール分の到達可能性のみを検証する。
    @Test
    fun tE2e3_4_departureScreen_reachableForScreenshotCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        navigateToDeparture(context)

        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_estimated_arrival_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_event_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_buffer_label)).assertIsDisplayed()
    }
}
