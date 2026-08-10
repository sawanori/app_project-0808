package com.actionstarter.features

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.features.departure.DepartureScreen
import com.actionstarter.features.departure.DepartureUiState
import com.actionstarter.features.departure.LocationPermissionState
import com.actionstarter.features.departure.TRAVEL_TIME_INPUT_TEST_TAG
import com.actionstarter.features.eventselection.EventSelectionScreen
import com.actionstarter.features.eventselection.EventSelectionUiState
import com.actionstarter.features.execution.ExecutionScreen
import com.actionstarter.features.execution.ExecutionUiState
import com.actionstarter.features.planreview.PlanReviewScreen
import com.actionstarter.features.planreview.PlanReviewUiState
import com.actionstarter.features.recovery.RecoveryScreen
import com.actionstarter.features.recovery.RecoveryUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * T-P11F-1〜8（計画書§8.4、F82、`docs/plans/phase11-i18n-a11y.md`§7.3）。
 *
 * Jetpack Compose公式テストAPI`androidx.compose.ui.test.DeviceConfigurationOverride.FontScale`
 * （`@ExperimentalTestApi`、`ui-test`アーティファクト。Context7で
 * `developer.android.com/develop/ui/compose/testing/common-patterns`を確認し、本プロジェクトの
 * `androidx-compose-ui-test-junit4`依存（compose BOM `2026.06.01`）で追加依存なしに利用できる
 * ことを確認済み＝要検証P11-P1は肯定的に解決）。全メソッドへ`@OptIn(ExperimentalTestApi::class)`
 * を付与する（Gemini G1 CRITICAL指摘反映、計画書§7.3・§8.4）。
 *
 * ピクセル単位の「文字が切れる」「重なる」の完全な自動検出はComposeテストの標準機能を超える
 * ため、本ファイルは計画書§7.3が定義する間接的だが実務的な基準（要素が`assertIsDisplayed()`を
 * 満たす、複数の操作可能要素が個別にヒットテスト可能、fontScale=1.0/1.5で同じノード集合が
 * 揃う）で検証する。実際の見た目の破綻検出は最終的に実機目視（G4-E補遺、§8.7）に委ねる。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FontScaleResilienceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-10T00:00:00Z")

    private fun sampleEvent(
        title: String = "Product Shoot",
        locationName: String? = "Shibuya"
    ): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = title,
        notes = null,
        startDate = fixedNow.plus(1, ChronoUnit.HOURS),
        locationName = locationName,
        coordinates = if (locationName != null) Coordinate(35.6, 139.7) else null,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun sampleExecutionStep(): ExecutionStep = ExecutionStep(
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

    private fun planWith(steps: List<ExecutionStep>): ExecutionPlan = ExecutionPlan(
        event = sampleEvent(),
        steps = steps,
        transitionStart = fixedNow.plus(5, ChronoUnit.MINUTES),
        departureTime = fixedNow.plus(70, ChronoUnit.MINUTES),
        estimatedArrival = fixedNow.plus(112, ChronoUnit.MINUTES),
        arrivalBuffer = Duration.ofMinutes(8)
    )

    private fun sampleRecoveryOption(semanticAction: String): RecoveryOption = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = semanticAction,
        title = semanticAction,
        explanation = "$semanticAction explanation",
        estimatedArrival = fixedNow.plus(58, ChronoUnit.MINUTES),
        skippedStepIds = emptyList()
    )

    /** `androidx.compose.ui.geometry.Rect`の外部APIに依存せず、自前で重なり判定する。 */
    private fun rectsOverlap(a: Rect, b: Rect): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    // T-P11F-1: 正常系 - EventSelectionScreenがfontScale=1.5でも主要ボタン・テキストが
    // assertIsDisplayed()。
    @Test
    fun tP11f1_eventSelectionScreen_fontScale1_5x_mainElementsStayDisplayed() {
        val event = sampleEvent()
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                EventSelectionScreen(
                    uiState = EventSelectionUiState.Content(events = listOf(event)),
                    onNavigateToPlanReview = {}
                )
            }
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(event.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.event_selection_prepare_button)).assertIsDisplayed()
    }

    // T-P11F-2: 正常系 - PlanReviewScreen同上（ステップ一覧＋Start/Editボタン）。
    @Test
    fun tP11f2_planReviewScreen_fontScale1_5x_stepListAndActionButtonsStayDisplayed() {
        val step = sampleExecutionStep()
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                PlanReviewScreen(
                    uiState = PlanReviewUiState(plan = planWith(listOf(step)), isBehindSchedule = false, isEditEnabled = false),
                    onNavigateToExecution = {}
                )
            }
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("plan_review_step_item").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_start_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.plan_review_edit_button)).assertIsDisplayed()
    }

    // T-P11F-3: 正常系 - ExecutionScreen同上（劣化バナー3種同時表示ケースを含む、最も要素密度が
    // 高い画面）。
    @Test
    fun tP11f3_executionScreen_fontScale1_5x_withAllThreeBanners_actionButtonsStayDisplayed() {
        val step = sampleExecutionStep()
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                ExecutionScreen(
                    uiState = ExecutionUiState(
                        currentStep = step,
                        currentStepIndex = 0,
                        isExactAlarmDegraded = true,
                        isNotificationPermissionDenied = true,
                        isForegroundServiceDegraded = true
                    ),
                    onNavigateToDeparture = {},
                    onNavigateToRecovery = {},
                    onNavigateToEventSelection = {}
                )
            }
        }
        val context = RuntimeEnvironment.getApplication()

        // Done／5 min laterは画面下段の固定操作領域（ExecutionScreen.kt、上段のverticalScroll
        // とは独立に常時可視）のため、スクロール操作なしでそのままassertIsDisplayed()できる。
        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_five_min_later_button)).assertIsDisplayed()
        // 再デザインサイクル2（目的・UX合致サイクル）実測是正: 3バナー同時表示×fontScale=1.5xの
        // 組み合わせでは、ヒーローカード導入後のExecutionScreenの上段content高がRobolectric既定
        // ビューポート（320×470dp、実機のどのAndroid端末よりはるかに小さいレガシーな既定値）を
        // 超え、上段自身が持つverticalScrollでスクロールしないと後段のバナーが現在のビューポート
        // 内に入らない。performScrollTo()でスクロールしたうえでassertIsDisplayed()する
        // （バナーの存在・到達可能性の検証意図自体は不変。実機・通常サイズのエミュレータでは
        // スクロール不要で最初から見える）。
        composeTestRule.onNodeWithTag("execution_exact_alarm_degraded_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("execution_notification_permission_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("execution_fgs_degraded_banner").performScrollTo().assertIsDisplayed()
    }

    // T-P11F-4: 正常系 - DepartureScreen同上（DENIED状態でTravelTimeInput＋設定ボタン＋新規説明文
    // が同時表示されるケースを含む）。
    @Test
    fun tP11f4_departureScreen_fontScale1_5x_deniedStateElementsStayDisplayed() {
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                DepartureScreen(uiState = DepartureUiState(permissionState = LocationPermissionState.DENIED))
            }
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.location_permission_denied_message)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TRAVEL_TIME_INPUT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag("departure_location_open_settings_button").assertIsDisplayed()
    }

    // T-P11F-5: 正常系 - RecoveryScreen同上（候補3件表示時）。
    @Test
    fun tP11f5_recoveryScreen_fontScale1_5x_threeOptionsStayDisplayed() {
        val options = listOf(
            sampleRecoveryOption("keep_all_steps"),
            sampleRecoveryOption("change_transport_mode"),
            sampleRecoveryOption("skip_optional_steps")
        )
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                RecoveryScreen(
                    uiState = RecoveryUiState(options = options, selectedOptionId = null),
                    onNavigateToExecution = {}
                )
            }
        }
        val context = RuntimeEnvironment.getApplication()

        options.forEach { option ->
            composeTestRule.onNodeWithTag("recovery_option_item_${option.id}").assertIsDisplayed()
        }
        // 再デザインサイクル2（目的・UX合致サイクル）実測是正: RecoveryScreenへカード型候補行・
        // 十分な余白を導入した結果、3候補×fontScale=1.5xの組み合わせではRobolectric既定
        // ビューポート（320×470dp、実機のどのAndroid端末よりはるかに小さいレガシーな既定値）を
        // content高が超え、画面自身が持つverticalScroll（RecoveryScreen.kt、F82実装時点から
        // 既存のKDoc「候補3件表示時に...ビューポート外へ押し出され...スクロールで到達可能に
        // する」がまさに想定していた状況）でスクロールしないと本ボタンが現在のビューポート内に
        // 入らない。performScrollTo()でスクロールしたうえでassertIsDisplayed()する（ボタンの
        // 存在・到達可能性の検証意図自体は不変。実機・通常サイズのエミュレータではスクロール
        // 不要で最初から見える）。
        composeTestRule.onNodeWithText(context.getString(R.string.recovery_use_this_plan_button))
            .performScrollTo()
            .assertIsDisplayed()
    }

    // T-P11F-6: エッジケース - fontScale=1.5でExecutionのDone／5 min laterボタンが別ノードとして
    // ヒットテスト可能（重なりによる誤操作がない）。
    @Test
    fun tP11f6_executionScreen_fontScale1_5x_doneAndPostponeButtons_remainNonOverlapping() {
        val step = sampleExecutionStep()
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                ExecutionScreen(
                    uiState = ExecutionUiState(currentStep = step, currentStepIndex = 0),
                    onNavigateToDeparture = {},
                    onNavigateToRecovery = {},
                    onNavigateToEventSelection = {}
                )
            }
        }
        val context = RuntimeEnvironment.getApplication()

        val doneBounds = composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button))
            .fetchSemanticsNode().boundsInRoot
        val postponeBounds = composeTestRule.onNodeWithText(context.getString(R.string.execution_five_min_later_button))
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Done and '5 min later' buttons must not overlap at fontScale=1.5 (would cause mis-taps)",
            !rectsOverlap(doneBounds, postponeBounds)
        )
    }

    // T-P11F-7: エッジケース - fontScale=1.0とfontScale=1.5を同一テスト内で比較し、1.5でも1.0と
    // 同じノード集合が揃う（要素の消失がない）。ComposeContentTestRule.setContent()はテスト1件
    // につき1回のみ呼べるため、両スケールを単一コンポジション内に並置して比較する。
    // ExecutionScreenの本体は自身のルートがModifier.fillMaxSize()であるため、2インスタンスを
    // 無制約のColumnへそのまま並べると測定上競合する（片方が高さを使い切り、もう片方が
    // 描画領域を持てなくなる）。各インスタンスをBox(Modifier.height(...))で明示的に区切った
    // うえで、外側全体をDeviceConfigurationOverride.ForcedSizeで十分大きい仮想ウィンドウへ
    // 固定する（Robolectricの既定画面サイズに依存せず、2インスタンス分の高さを確実に
    // 確保する）。この競合はテスト構成上のものでありfontScale固有の破綻ではないことを
    // 切り分けるための対応。
    @Test
    fun tP11f7_executionScreen_fontScale1_0And1_5_exposeTheSameSetOfInteractiveElements() {
        val stepAt1_0x = sampleExecutionStep()
        val stepAt1_5x = sampleExecutionStep()

        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 1600.dp))) {
                Column {
                    Box(modifier = Modifier.height(700.dp)) {
                        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.0f)) {
                            ExecutionScreen(
                                uiState = ExecutionUiState(currentStep = stepAt1_0x, currentStepIndex = 0),
                                onNavigateToDeparture = {},
                                onNavigateToRecovery = {},
                                onNavigateToEventSelection = {}
                            )
                        }
                    }
                    Box(modifier = Modifier.height(700.dp)) {
                        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                            ExecutionScreen(
                                uiState = ExecutionUiState(currentStep = stepAt1_5x, currentStepIndex = 0),
                                onNavigateToDeparture = {},
                                onNavigateToRecovery = {},
                                onNavigateToEventSelection = {}
                            )
                        }
                    }
                }
            }
        }
        val context = RuntimeEnvironment.getApplication()

        // Each instance's own step row must be present...
        composeTestRule.onNodeWithTag("step_item_${stepAt1_0x.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("step_item_${stepAt1_5x.id}").assertIsDisplayed()
        // ...and both instances' Done/5-min-later buttons must be present (2 of each: one from the
        // 1.0x instance, one from the 1.5x instance). If fontScale=1.5 caused an element to drop out
        // of the tree, one of these counts would fall to 1.
        composeTestRule.onAllNodesWithText(context.getString(R.string.execution_done_button)).assertCountEquals(2)
        composeTestRule.onAllNodesWithText(context.getString(R.string.execution_five_min_later_button))
            .assertCountEquals(2)
    }

    // T-P11F-8: エッジケース - ja（長め文言になりやすい）×fontScale=1.5の組み合わせでも主要ボタン
    // がassertIsDisplayed()。
    @Config(qualifiers = "ja")
    @Test
    fun tP11f8_executionScreen_japaneseLocaleWithFontScale1_5x_mainButtonsStayDisplayed() {
        val step = sampleExecutionStep()
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                ExecutionScreen(
                    uiState = ExecutionUiState(currentStep = step, currentStepIndex = 0),
                    onNavigateToDeparture = {},
                    onNavigateToRecovery = {},
                    onNavigateToEventSelection = {}
                )
            }
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.execution_done_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.execution_five_min_later_button)).assertIsDisplayed()
    }
}
