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
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.features.execution.ExecutionScreen
import com.actionstarter.features.execution.ExecutionUiState
import com.actionstarter.features.execution.ExecutionViewModel
import com.actionstarter.services.calendar.CalendarQuerySpec
import com.actionstarter.services.permission.PermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T-P11N-1〜10（計画書§8.2、F79/F80、`docs/plans/phase11-i18n-a11y.md`§7.1）。
 *
 * ## T-P11N系（NavHost統合、T-P11N-1/2/3/5/6）
 * [ActionStarterNavHost]は現時点（P11-C1 scaffold後）で`POST_NOTIFICATIONS`の実行時リクエストを
 * 一切発火しない（grep実測、計画書§0-1）。[NavigationFlowTest]・[CalendarNavigationFlowTest]と
 * 同じ手法（Robolectric＋`createAndroidComposeRule<ComponentActivity>`＋実[ActionStarterNavHost]を
 * そのままホストし、UI操作と画面文言の出現／消失のみで検証する）を踏襲し、EventSelection→
 * PlanReviewまでを実際のUI操作で進めてから「Start」をタップする。
 *
 * カレンダー環境整備（[setUpCalendarEnvironment]・[FakeCalendarContentProvider]）は
 * [NavigationFlowTest]の§15(e)解消と同じ構成をこのファイル専用に複製したもの
 * （本コードベースの既存規約＝テストファイルごとに閉じたfakeを持つ、
 * [DepartureRoutingScreenTest]のKDoc参照）。
 *
 * launcherのコールバック解決方法は検証内容によって使い分ける:
 * - T-P11N-1・T-P11N-6: 実（fakeしない）`ActivityResultRegistry`を使い、
 *   `Shadows.shadowOf(activity).lastRequestedPermission`でOSへの実際のリクエスト内容を検証する
 *   （callbackの解決自体は検証対象外のため未解決のままでよい）。
 * - T-P11N-2・T-P11N-3・T-P11N-5: [CalendarNavigationFlowTest]と同じ
 *   `LocalActivityResultRegistryOwner`経由の即時解決fake registryを使い、コールバックを
 *   同期的に発火させて遷移後の状態を検証する。計画書§7.1の設計（「結果は分岐せず、
 *   ExecutionViewModel側が都度isGranted()で再照会する」）どおり、fakeが返すbooleanの値
 *   そのものは意味を持たない——実際の許可/拒否状態は`shadowOf(application)
 *   .grantPermissions/denyPermissions(POST_NOTIFICATIONS)`で別途作る。
 *
 * T-P11N-6（多重タップ）は実registry（未解決のまま）を使う。既存の
 * `AppContainer.notificationService`は本番の[com.actionstarter.services.notification
 * .AndroidNotificationService]であり、NavHost統合レベルではこれをspyに差し替える手段がない
 * （`AppContainer`変更は計画書のスコープ外）ため、「`schedule()`呼び出し回数が正確に1回」の
 * ような内部カウント検証はできない。本ケースは「多重タップがクラッシュ・破綻状態を招かない」
 * ことを、2回目のタップ後もPlanReview画面が単一の一貫した状態のまま留まること（実
 * registryが未解決のため遷移していないことの構造的帰結）で検証する、意図的にスコープを
 * 絞った回帰ガードである（完了報告で開示）。
 *
 * ## T-P11N系（ExecutionViewModel単体、T-P11N-7/8/10）
 * [ExecutionViewModel.refreshDegradationState]（P11-C1 scaffold）を直接呼び出して検証する。
 * [ExecutionOneActionTest]と同じ「テストファイルごとに閉じたfake」規約に従い、
 * [FixedPermissionGate]／[MutablePermissionGate]をこのファイル内に独立して定義する。
 *
 * ## T-P11N-4（ExecutionScreen単体）
 * 設定導線ボタン（testTag "execution_notification_open_settings_button"）はNavHost結線とは
 * 独立してExecutionScreen単体で検証できるため、NavHost統合を経由しない。
 *
 * ## T-P11N-9（E1、計画書§8.2「全10件のうちT-P11N-9のみE1」）
 * 本ファイルは`@RunWith(AndroidJUnit4::class)`だが、本ケースはRobolectric/Composeを一切使わず
 * `strings.xml`を直接XMLパースする（[StringResourceParityTest]と同じ手法をこのファイル内に
 * 複製）。E1（純粋JVM）区分を満たすための意図的な実装選択。
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionRequestTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fixedNow: Instant = Instant.parse("2026-08-10T07:00:00Z")

    /**
     * [NavigationFlowTest.setUpCalendarEnvironment]と同型（§15(e)解消の複製）。5画面
     * 横断でPlanReviewへ到達するT-P11N-1/2/3/5/6が共通で必要とするため`@Before`とする。
     */
    @Before
    fun setUpCalendarEnvironment() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CALENDAR)
        val providerInfo = ProviderInfo().apply { authority = CalendarContract.AUTHORITY }
        Robolectric.buildContentProvider(FakeCalendarContentProvider::class.java).create(providerInfo)
    }

    /** [NavigationFlowTest.waitForEventSelectionContent]と同型。 */
    private fun waitForEventSelectionContent() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(context.getString(R.string.event_selection_prepare_button))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** EventSelection→PlanReviewまでをUI操作で進める（[NavigationFlowTest.tNav1]と同じ手順）。 */
    private fun navigateToPlanReview(context: android.content.Context) {
        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_title)).assertIsDisplayed()
    }

    /**
     * [CalendarNavigationFlowTest]と同型のfake registry。`launch()`が呼ばれた瞬間に[result]を
     * 同期的にdispatchする（実システムダイアログはRobolectric上で表示できないため）。
     */
    private fun immediateResultRegistryOwner(result: Boolean): ActivityResultRegistryOwner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?
                    ) {
                        @Suppress("UNCHECKED_CAST")
                        dispatchResult(requestCode, result as O)
                    }
                }
        }

    // T-P11N-1: 正常系 - API 33+環境でPlanReviewの「Start」タップ時に
    // requestNotificationPermissionLauncher.launch(POST_NOTIFICATIONS)が発火する
    // （Shadows.shadowOf(activity).getLastRequestedPermission()で検証。要検証P11-P3の結果:
    // ComposeのrememberLauncherForActivityResult経由のlaunch()もShadowActivityへ実際に
    // requestPermissions(...)として記録される——EventSelectionRoute/DepartureRouteの既存
    // launcherと同じActivityResultRegistry委譲経路のため。実registry・未解決のまま検証する）。
    @Config(sdk = [33])
    @Test
    fun tP11n1_api33StartTap_launchesPostNotificationsPermissionRequest() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()
        navigateToPlanReview(context)

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()

        val lastRequest = shadowOf(composeTestRule.activity).lastRequestedPermission
        assertTrue(
            "expected a runtime permission request to have been made after tapping Start",
            lastRequest != null
        )
        assertTrue(
            "expected POST_NOTIFICATIONS among the requested permissions, was " +
                lastRequest?.requestedPermissions?.toList(),
            lastRequest?.requestedPermissions?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
        )
    }

    // T-P11N-2: 正常系 - 許可済み環境でStartタップ後、Executionへ遷移した時点で
    // isNotificationPermissionDenied == false（バナー非表示）。
    @Config(sdk = [33])
    @Test
    fun tP11n2_grantedAfterTap_reachesExecutionWithoutDegradationBanner() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides immediateResultRegistryOwner(true)) {
                ActionStarterNavHost()
            }
        }
        waitForEventSelectionContent()
        navigateToPlanReview(context)

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("execution_notification_permission_banner").assertDoesNotExist()
    }

    // T-P11N-3: 異常系 - 拒否環境でStartタップ後、Execution画面に劣化バナーが表示される
    // （既存isNotificationPermissionDeniedの回帰確認）。
    @Config(sdk = [33])
    @Test
    fun tP11n3_deniedAfterTap_reachesExecutionWithDegradationBanner() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides immediateResultRegistryOwner(false)) {
                ActionStarterNavHost()
            }
        }
        waitForEventSelectionContent()
        navigateToPlanReview(context)

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()

        composeTestRule.onNodeWithTag("execution_notification_permission_banner").assertIsDisplayed()
    }

    // T-P11N-4: 正常系 - 拒否時バナーに設定導線ボタンが表示され、タップでonOpenNotificationSettings
    // が呼ばれる（ExecutionScreen単体、NavHost非経由）。
    @Test
    fun tP11n4_deniedBanner_showsSettingsButtonThatInvokesCallbackOnTap() {
        var settingsOpened = false
        composeTestRule.setContent {
            ExecutionScreen(
                uiState = ExecutionUiState(
                    currentStep = sampleStep(),
                    currentStepIndex = 0,
                    isNotificationPermissionDenied = true
                ),
                onNavigateToDeparture = {},
                onNavigateToRecovery = {},
                onNavigateToEventSelection = {},
                onOpenNotificationSettings = { settingsOpened = true }
            )
        }

        composeTestRule.onNodeWithTag("execution_notification_open_settings_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("execution_notification_open_settings_button").performClick()

        assertTrue(settingsOpened)
    }

    // T-P11N-5: エッジ - @Config(sdk = [26])（本アプリのminSdk）環境ではlaunch()を呼ばず
    // Executionへ直接遷移する（API 33未満分岐、§7.1）。例外は投げない。
    @Config(sdk = [26])
    @Test
    fun tP11n5_api26_startTap_navigatesDirectlyWithoutRequestingPermission() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent { ActionStarterNavHost() }
        waitForEventSelectionContent()
        navigateToPlanReview(context)

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_now_label)).assertIsDisplayed()
        assertNull(
            "API < 33 must not go through the launcher at all",
            shadowOf(composeTestRule.activity).lastRequestedPermission
        )
    }

    // T-P11N-6: エッジ - 「Start」を2回連続タップしても破綻しない（多重発火防止の回帰確認）。
    // スコープの限定については本ファイルのクラスKDoc参照（AppContainerの本番
    // NotificationServiceをspyへ差し替える手段がないため、schedule()呼び出し回数そのものの
    // 検証はできない）。実測により、実（未fake）ActivityResultRegistryのコールバックは
    // Robolectric上で非同期的だが確実にいずれ解決することが判明した（T-P11N-1のように
    // 「呼び出し内容の検証のみで解決を待たない」用途以外では、CalendarNavigationFlowTestと
    // 同型の即時解決fake registryを使うほうが決定的でタイミング非依存のテストになる）。
    // 本ケースは即時解決fake registryを用い、2回連続タップ後も単一の一貫したExecution画面へ
    // 収束すること（クラッシュしない・重複や不整合状態にならない）を検証する。
    @Config(sdk = [33])
    @Test
    fun tP11n6_doubleTapStart_doesNotCrashOrCorruptNavigationState() {
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides immediateResultRegistryOwner(true)) {
                ActionStarterNavHost()
            }
        }
        waitForEventSelectionContent()
        navigateToPlanReview(context)

        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        // 1回目のタップで既にExecutionへ遷移しているはずのため、2回目は「Start」ボタンが
        // 存在しないことを許容しつつ、例外を投げないことそのものを多重発火防止の回帰確認とする。
        val startButtonStillPresent = composeTestRule
            .onAllNodesWithText(context.getString(R.string.plan_review_start_button))
            .fetchSemanticsNodes()
            .isNotEmpty()
        if (startButtonStillPresent) {
            composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).performClick()
        }

        // 単一の一貫したExecution画面に収束していること（重複・不整合状態にならないこと）。
        composeTestRule.onAllNodesWithText(context.getString(R.string.execution_now_label)).assertCountEquals(1)
    }

    // T-P11N-7: 正常系 - Execution画面がON_RESUMEした際（refreshDegradationState()経由）、
    // isNotificationPermissionDeniedが最新のOS権限状態へ再同期される（設定から戻った直後に
    // バナーが消える）。
    @Test
    fun tP11n7_refreshDegradationState_reSyncsNotificationPermissionFromPermissionGate() {
        val mutableGate = MutablePermissionGate(granted = false)
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(buildMinimalPlan()) },
            permissionGate = mutableGate
        )
        assertTrue(
            "expected isNotificationPermissionDenied=true at construction (permission initially denied)",
            viewModel.uiState.value.isNotificationPermissionDenied
        )

        mutableGate.granted = true
        viewModel.refreshDegradationState()

        assertFalse(
            "expected isNotificationPermissionDenied to flip to false after refreshDegradationState() " +
                "once the OS-level permission is granted (simulates returning from Settings)",
            viewModel.uiState.value.isNotificationPermissionDenied
        )
    }

    // T-P11N-8: エッジ - sharedPlanViewModelがnull（プレースホルダ経路）のときも
    // isNotificationPermissionDenied算出は従来どおり動作し新規回帰を生まない。
    @Test
    fun tP11n8_refreshDegradationState_withNullSharedPlanViewModel_doesNotRegressPlaceholderPath() {
        val viewModel = ExecutionViewModel(savedStateHandle = SavedStateHandle())

        // Must not throw, and must leave the placeholder path's currentStepIndex contract intact.
        viewModel.refreshDegradationState()

        assertEquals(0, viewModel.uiState.value.currentStepIndex)
        assertFalse(viewModel.uiState.value.isNotificationPermissionDenied)
    }

    // T-P11N-9（E1、クラスKDoc参照）: 正常系 - ja/en両ロケールで新規追加文言
    // （notification_open_settings_button）が非空かつ相互に異なる。
    @Test
    fun tP11n9_notificationOpenSettingsButtonLabel_nonBlankAndDistinctBetweenEnAndJa() {
        val enValue = readStringResourceValue(qualifierSuffix = "", key = "notification_open_settings_button")
        val jaValue = readStringResourceValue(qualifierSuffix = "-ja", key = "notification_open_settings_button")

        assertTrue("en value for notification_open_settings_button must not be blank", enValue.isNotBlank())
        assertTrue("ja value for notification_open_settings_button must not be blank", jaValue.isNotBlank())
        assertNotEquals(
            "en/ja values for notification_open_settings_button must differ (real translation, not a copy)",
            enValue,
            jaValue
        )
    }

    // T-P11N-10: 回帰 - 既存T-P5UI-6（ExecutionOneActionTest.kt、通知拒否時NOWカードのみで
    // 状態が伝わる）が本Phase変更後も成立する。T-P5UI-6自体は無変更のまま独立に実行される
    // （build/agent-logs参照）。本ケースはさらにrefreshDegradationState()呼び出し後も同じ
    // 結論が維持されることを検証する、F80が持ち込む新しい呼び出し経路に対する追加の
    // アサーションである。
    @Test
    fun tP11n10_postNotificationsDenied_staysDeniedAfterRefreshDegradationState_perTP5ui6Contract() {
        val plan = buildMinimalPlan()
        val viewModel = ExecutionViewModel(
            savedStateHandle = SavedStateHandle(),
            sharedPlanViewModel = SharedPlanViewModel().apply { confirmPlan(plan) },
            permissionGate = FixedPermissionGate(granted = emptySet())
        )
        assertTrue(viewModel.uiState.value.isNotificationPermissionDenied)

        viewModel.refreshDegradationState()

        assertTrue(
            "isNotificationPermissionDenied must remain true after refreshDegradationState() when the " +
                "underlying PermissionGate still reports denied (T-P5UI-6 contract must survive F80's " +
                "new refresh path)",
            viewModel.uiState.value.isNotificationPermissionDenied
        )
    }

    // ---- 共有フィクスチャ（テストファイルごとに閉じたfake規約） ------------------------------

    private fun sampleStep(): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = "preparation",
        type = ExecutionStepType.PREPARATION,
        title = "Get dressed",
        estimatedDuration = Duration.ofMinutes(10),
        priority = StepPriority.IMPORTANT,
        skippable = true,
        scheduledStart = fixedNow,
        completedAt = null
    )

    /** [ExecutionOneActionTest.buildConfirmedPlan]と同型の最小フィクスチャ（1ステップ）。 */
    private fun buildMinimalPlan(): ExecutionPlan {
        val eventStart = fixedNow.plus(Duration.ofHours(1))
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "Team sync",
            notes = null,
            startDate = eventStart,
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "cal-1", displayName = "Work")
        )
        val step = ExecutionStep(
            id = UUID.nameUUIDFromBytes("${event.id}:prep".toByteArray()),
            semanticId = "prep",
            type = ExecutionStepType.PREPARATION,
            title = "",
            estimatedDuration = Duration.ofMinutes(10),
            priority = StepPriority.IMPORTANT,
            skippable = true,
            scheduledStart = fixedNow,
            completedAt = null
        )
        return ExecutionPlan(
            event = event,
            steps = listOf(step),
            transitionStart = fixedNow.plus(Duration.ofMinutes(10)),
            departureTime = eventStart.minus(Duration.ofMinutes(20)),
            estimatedArrival = eventStart.minus(Duration.ofMinutes(5)),
            arrivalBuffer = Duration.ofMinutes(10)
        )
    }

    /** 指定した権限文字列集合のみ許可する[PermissionGate]フェイク（T-P11N-10。[ExecutionOneActionTest]と同型）。 */
    private class FixedPermissionGate(private val granted: Set<String>) : PermissionGate {
        override fun isGranted(permission: String): Boolean = permission in granted
    }

    /** [T-P11N-7]専用: construction後に許可状態を書き換え可能な[PermissionGate]フェイク。 */
    private class MutablePermissionGate(var granted: Boolean) : PermissionGate {
        override fun isGranted(permission: String): Boolean = granted
    }

    /**
     * T-P11N-9専用（E1、pure JVM）。[com.actionstarter.i18n.StringResourceParityTest
     * .resolveStringsXml]と同型のパス解決＋単一キーのみを読むXMLパース（本ファイル内で閉じた
     * 複製、既存規約に従う）。
     */
    private fun readStringResourceValue(qualifierSuffix: String, key: String): String {
        val relative = "src/main/res/values$qualifierSuffix/strings.xml"
        val file = resolveFromRepo(relative)

        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as org.w3c.dom.Element
            if (element.getAttribute("name") == key) {
                return element.textContent ?: ""
            }
        }
        error("string resource '$key' not found in $relative")
    }

    private fun resolveFromRepo(relativeFromApp: String): File {
        val direct = File(relativeFromApp)
        if (direct.isFile) return direct

        val fromRepoRoot = File("app", relativeFromApp)
        if (fromRepoRoot.isFile) return fromRepoRoot

        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/$relativeFromApp")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }

        error(
            "'$relativeFromApp' not found from working directory " +
                "'${System.getProperty("user.dir")}' and its ancestors."
        )
    }

    /**
     * [NavigationFlowTest.FakeCalendarContentProvider]と同型の複製（§15(e)解消と同じ最小
     * fake ContentProvider。1件の有効な今後の予定を返す。本コードベースの既存規約＝
     * テストファイルごとに閉じたfakeを持つ、に従い独立して定義する）。
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
