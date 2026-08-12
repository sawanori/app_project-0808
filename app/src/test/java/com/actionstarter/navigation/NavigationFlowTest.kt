package com.actionstarter.navigation

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.services.calendar.CalendarQuerySpec
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * F9 — Navigation（計画書§11.2、T-NAV-1〜5）。
 *
 * ActionStarterNavHostは現在フル配線済み（NavController生成・composable{}によるルート配線・
 * DI結線を含む、C5／C6統合サイクルで完了）。以下5テストはUI操作と画面文言の出現／消失のみで
 * 各シナリオを検証する（本体・アサーションはC2b契約scaffold時点から不変更）。
 *
 * **§15(e)解消（Fable 5裁定2026-08-09、統合修正サイクル）**: `AppContainer`が一時ブリッジから
 * 実装（[com.actionstarter.services.calendar.CalendarProviderCalendarService]／
 * [com.actionstarter.services.permission.AndroidPermissionGate]）へ結線されたことで、
 * 本ファイルの5テストは新規に環境前提のRedへ転じていた：Robolectricの`READ_CALENDAR`権限
 * shadowは既定で未許可であり、かつRobolectric JVM環境にはCalendarProvider相当の
 * ContentProviderが登録されていないため、`EventSelectionUiState.Content`（テストが前提とする
 * 「イベントが一覧表示される」状態）へ到達できなかった（実測は§15(e)参照）。[setUpCalendarEnvironment]
 * （環境整備のみ・各テスト本体やアサーションは無変更）で(a)権限shadowを明示的に許可し、
 * (b)[FakeCalendarContentProvider]を`CalendarContract.AUTHORITY`へ登録して実クエリ経路
 * （`ContentResolverCursorSource`→実`ContentResolver`）が有効な1件のInstances行を返す状態を
 * 作ることで、既存アサーションが期待する`Content`到達を成立させる。
 *
 * **既知の間欠事象と安定化（P2-C7〔旧P2-C6〕で解消）**: `AppContainer`／`CalendarProviderCalendarService`
 * の既定`Dispatchers.IO`（実バックグラウンドスレッド。本サイクルの変更対象外）とRobolectricの
 * Compose test同期機構の間の競合と見られる要因により、5テストを連続実行すると`setContent`
 * 直後の最初のUI操作がまれに（数回に1回程度）対象ノード未検出で失敗する間欠事象を統合修正
 * サイクルで確認した（単体実行では再現しない）。当時は各`@Test`の`setContent`直後へ
 * `composeTestRule.waitForIdle()`を防御的に追加していたが完全解消には至っていなかった
 * （`waitForIdle()`はComposeのアイドル到達のみを保証し、対象状態への到達そのものは保証しない
 * ため）。P2-C7（旧P2-C6、Refactor＋G4-JVMサイクル）で[waitForEventSelectionContent]
 * （対象ノードの出現を条件とする`waitUntil`）へ置換して安定化し、`--rerun`による全スイート
 * 3回連続実行で3回とも122/122 Greenを実測した（既存アサーション本体は無変更。
 * `build/agent-logs/p2c6-stability-run{1,2,3}.log`。計画書§15(e)参照）。
 *
 * ActionStarterNavHost()はNavControllerを外部へ公開しないため、本テストは実際の
 * ComponentActivityへホストして（戻る操作の検証のためcreateAndroidComposeRuleを使用）、
 * 画面遷移に伴って表示されるはずのUI文言の出現／消失のみで各シナリオを検証する。
 */
@RunWith(AndroidJUnit4::class)
class NavigationFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * §15(e)解消（Fable 5裁定2026-08-09）の環境整備：(a)`READ_CALENDAR`権限shadowを明示的に
     * 許可し、(b)`CalendarContract.AUTHORITY`へ[FakeCalendarContentProvider]を登録する。
     * 5テストいずれも`EventSelectionUiState.Content`（＝`event_selection_prepare_button`が
     * 表示される状態）への到達を前提とするため、全テスト共通の`@Before`とする。
     */
    @Before
    fun setUpCalendarEnvironment() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CALENDAR)

        val providerInfo = ProviderInfo().apply { authority = CalendarContract.AUTHORITY }
        Robolectric.buildContentProvider(FakeCalendarContentProvider::class.java).create(providerInfo)
    }

    /**
     * P2-C7（旧P2-C6、安定化対応）: 5テスト共通の`setContent`直後待機ヘルパー。
     *
     * §15(e)「既知の残存事象」で記録した間欠事象（`setContent`直後の最初のUI操作がまれに
     * 対象ノード未検出で失敗する）への対処。`waitForIdle()`はComposeのアイドル到達のみを
     * 保証し、[EventSelectionUiState.Content]（実`Dispatchers.IO`経由の非同期
     * `refresh()`完了後に到達する状態。`AppContainer`／`CalendarProviderCalendarService`側の
     * dispatcher・実装は本サイクルの変更対象外）へ実際に到達したことは保証しないため、
     * 対象ノード（[R.string.event_selection_prepare_button]。5テストいずれも初期表示が
     * この状態へ到達することを前提とする）の出現そのものを条件とする`waitUntil`へ置換した。
     * 各`@Test`本体・アサーションは無変更（待機ヘルパーの追加・置換のみ）。
     */
    private fun waitForEventSelectionContent() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(context.getString(R.string.event_selection_prepare_button))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    // T-NAV-1: 正常系 - Selection→Review→Execution→Departureの一連の遷移が通しで成立する
    //
    // **P5-C6更新（ADR-0028・計画書§10.6申し送り）**: execution routeがExecutionViewModel
    // 経由の本番結線に切り替わったことで、「Doneタップ1回でExecutionから離脱する」という旧前提
    // （M5-14の既知の制限。旧NavHostが`onDone=null`固定で渡していたことによる
    // ExecutionScreenのフォールバック挙動、T-EXEC-4）が解消された。PlanReviewViewModel
    // （本サイクルの変更対象外）が組み立てるPlanningContextは`travelEstimate=null`固定のため、
    // BasicPlanningEngineはTRANSITION／PREPARATION／DEPARTUREの3ステップを生成する
    // （BasicPlanningDefaults.TRANSITION=5分・PREPARATION=15分がいずれも0分超のため生成され、
    // TRAVELはtravelEstimate=nullのため生成されない。ADR-0016のstep構築順）。したがって
    // departureへ到達するにはステップ数と同数の3回「Done」をタップする必要がある。
    //
    // **P11-C3追補（F79、計画書§7.1）**: PlanReviewの「Start」タップがAPI 33+で
    // POST_NOTIFICATIONS実行時権限launcher（`ActivityResultContracts.RequestPermission()`）を
    // 経由するようになり、遷移がlauncherの非同期コールバック内へ移った（[ActionStarterNavHost]
    // 参照）。本テストはナビゲーション構造の検証が目的であり権限フロー自体の検証対象では
    // ないため（権限フロー自体は`NotificationPermissionRequestTest`が担当）、`@Config(sdk =
    // [26])`でPOST_NOTIFICATIONSが概念上存在しないAPIへ固定し、launcherを介さない直接遷移
    // 分岐（§7.1）を通すことで、Phase 11以前と同じ同期的な遷移挙動を維持する。
    @Config(sdk = [26])
    @Test
    fun tNav1_selectionToReviewToExecutionToDeparture_fullFlowSucceeds() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        // TRANSITION → PREPARATION → DEPARTURE の3ステップぶん「Done」をタップし、
        // One Actionの多段階前進（F58）を通しで検証する。
        repeat(3) {
            composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()
        }
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
    }

    // T-DEPFIX-8（`docs/plans/departure-screen-fixes.md`§3.3・§4、Step 3 Red、欠陥③）: 正常系 -
    // DepartureScreenの戻るボタン操作がEventSelection（popUpTo inclusive）へ遷移し、完了済みの
    // Execution画面が再表示されない（tNav1と同じ経路でDepartureまで到達したうえで検証する）。
    // 現時点ではDepartureScreenに戻るボタン自体が描画されない（欠陥③、Green段階でActionStarterNavHost
    // の`DepartureRoute`へ配線する）ため、"departure_back_button"のノードが見つからずRedになるのが
    // 正しい。
    @Config(sdk = [26])
    @Test
    fun tDepFix8_departureBackButton_navigatesToEventSelection_doesNotResurrectExecution() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        repeat(3) {
            composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).performClick()
        }
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()

        composeTestRule.onNodeWithTag("departure_back_button").performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertDoesNotExist()
    }

    // T-NAV-2: 正常系 - 戻る操作（back）が各画面で妥当に動作する
    @Test
    fun tNav2_backPress_returnsToPreviousScreen() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()

        composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).assertIsDisplayed()
    }

    // T-NAV-3: 正常系 - recoveryから「Use this plan」でexecutionへ戻る
    //
    // **P11-C3追補（F79）**: tNav1と同じ理由で`@Config(sdk = [26])`を付与する（本テストも
    // PlanReviewの「Start」タップを経由してExecutionへ到達するため）。
    @Config(sdk = [26])
    @Test
    fun tNav3_recoveryUseThisPlan_returnsToExecution() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        // BuildConfig.DEBUGガード付き「Simulate delay (debug)」ボタン（計画書§10.4、U4）でRecovery割込を発生させる
        composeTestRule.onNodeWithText(context.getString(R.string.execution_simulate_delay_debug_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_title)).assertIsDisplayed()

        composeTestRule.onNodeWithText(context.getString(R.string.recovery_use_this_plan_button)).performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()
    }

    // T-NAV-4: 異常系（前提検証） - Planが未確定のままexecutionへ到達しようとした場合、
    // popUpToでeventSelectionへ戻しSnackbarで通知する。
    // ActionStarterNavHost()はNavControllerを外部へ公開しないため、本テストは
    // 「起動直後はeventSelection画面の要素が表示され、Execution画面固有の要素（NOWラベル）
    // は現れない」というガード結果の整合性を検証する（前提検証）。
    @Test
    fun tNav4_unconfirmedPlan_guardKeepsUserOnEventSelection() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertDoesNotExist()
    }

    // T-NAV-5: エッジケース - プロセス再生成後もdestinationが復元される
    // recreate()は素のComponentActivityホストでは再setContent経路がなく構造的に失敗するため、
    // Fable 5裁定（2026-08-08）によりStateRestorationTester方式へ修繕。
    // ケース意図は計画書§11.2 T-NAV-5のまま（プロセス再生成後もdestinationが復元されることの検証）。
    @Test
    fun tNav5_processRecreation_restoresDestination() {
        val context = RuntimeEnvironment.getApplication()
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()

        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()
    }

    /**
     * §15(e)解消（Fable 5裁定2026-08-09）用の最小限fake ContentProvider。実`ContentResolver`
     * 経由（[com.actionstarter.services.calendar.ContentResolverCursorSource]）で問い合わせが
     * 届いた際に、`CalendarContract.Instances`へは[CalendarQuerySpec.PROJECTION]の列を持つ
     * 有効な1件の今後の予定を、`CalendarContract.Calendars`へは可視カレンダー1件を、それぞれ
     * [MatrixCursor]で返す。呼び出し側が要求した`projection`をそのまま反映する
     * （`CalendarProviderCalendarServiceTest.buildCursorEchoingProjection`と同様の方針）。
     *
     * `begin`は呼び出し時点の実時刻＋1時間（[CalendarInstanceMapper.isExcluded]の
     * 「未来のみ」判定を確実に満たすため実行時に計算する。固定値にすると将来のテスト実行時刻に
     * 対して過去になり除外され得る）とし、[CalendarQuerySpec.UPCOMING_WINDOW]（7日）の
     * 範囲内に収める。
     *
     * `private`にしない理由：[Robolectric.buildContentProvider]がリフレクションで零引数
     * コンストラクタを呼び出すため、可視性による解決失敗リスクを避ける（テストファイル外へは
     * 実質露出しない＝`NavigationFlowTest.FakeCalendarContentProvider`経由でのみ参照可能）。
     */
    class FakeCalendarContentProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor {
            return if (uri.pathSegments.firstOrNull() == "instances") {
                instancesCursor(projection)
            } else {
                calendarsCursor(projection)
            }
        }

        private fun instancesCursor(projection: Array<String>?): MatrixCursor {
            val columns = projection ?: CalendarQuerySpec.PROJECTION
            val begin = System.currentTimeMillis() + Duration.ofHours(1).toMillis()
            val row: Map<String, Any?> = mapOf(
                CalendarContract.Instances.EVENT_ID to 1L,
                CalendarContract.Instances.BEGIN to begin,
                CalendarContract.Instances.END to begin + Duration.ofMinutes(30).toMillis(),
                CalendarContract.Instances.TITLE to "Fake Robolectric Event",
                CalendarContract.Instances.EVENT_LOCATION to null,
                CalendarContract.Instances.CALENDAR_ID to 1L,
                CalendarContract.Instances.ALL_DAY to 0,
                CalendarContract.Instances.STATUS to CalendarContract.Instances.STATUS_CONFIRMED,
                CalendarContract.Events.DELETED to 0,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS to
                    CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
                CalendarContract.Instances.DESCRIPTION to null,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME to "Work",
                CalendarContract.Instances.EVENT_TIMEZONE to "UTC",
                CalendarContract.Instances.AVAILABILITY to CalendarContract.Instances.AVAILABILITY_BUSY
            )
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> row.getValue(column) }.toTypedArray())
            }
        }

        private fun calendarsCursor(projection: Array<String>?): MatrixCursor {
            val columns = projection ?: arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.VISIBLE
            )
            val row: Map<String, Any?> = mapOf(
                CalendarContract.Calendars._ID to 1L,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to "Work",
                CalendarContract.Calendars.VISIBLE to 1
            )
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> row.getValue(column) }.toTypedArray())
            }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
        ): Int = 0
    }
}
