package com.actionstarter.features

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.features.departure.DepartureScreen
import com.actionstarter.features.departure.DepartureUiState
import com.actionstarter.features.departure.EtaFailureReason
import com.actionstarter.features.departure.LocationPermissionRationaleCard
import com.actionstarter.features.departure.LocationPermissionState
import com.actionstarter.features.departure.TRAVEL_TIME_INPUT_TEST_TAG
import com.actionstarter.features.departure.TransportModeSelector
import com.actionstarter.features.departure.TravelTimeInput
import com.actionstarter.features.departure.transportModeSelectorTestTag
import com.actionstarter.features.eventselection.EventSelectionScreen
import com.actionstarter.features.eventselection.EventSelectionUiState
import com.actionstarter.features.execution.ExecutionScreen
import com.actionstarter.features.execution.ExecutionUiState
import com.actionstarter.features.planreview.PlanReviewScreen
import com.actionstarter.features.planreview.PlanReviewUiState
import com.actionstarter.features.recovery.RecoveryScreen
import com.actionstarter.features.recovery.RecoveryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * T-P11A-1〜12（計画書§8.3、F81、`docs/plans/phase11-i18n-a11y.md`§7.2・§12 S-5・§10 P11-C6）。
 *
 * 対象はアイコンラベルではなく、①警告状態の非視覚的伝達、②複合情報のグルーピング、
 * ③既存の空`semantics(mergeDescendants = true) {}`の完成（`ExecutionScreen.kt:86`・
 * `RecoveryScreen.kt:99`。加えて`PlanReviewScreen.kt:126`も同型で実在する）、
 * ④Recovery選択行の視覚的インジケータ（S-5裁定）の4点（計画書§0-2・§7.2）。
 *
 * ## T-P11A-12（P11-C6、F81取りこぼし是正）
 * 最終デバイスラウンド（P11-C4、`docs/plans/phase5-notification-execution.md`§10.12）の
 * 実機`uiautomator dump`でDeparture画面のみcontentDescriptionが0件と判明した（他4画面は
 * 実装済み）。計画書§6.1のfootprint表は元々Departureにも「`TravelTimeInput`・
 * `LocationPermissionRationaleCard`・設定導線ボタンへcontentDescription付与」を要求して
 * いたが、P11-C3（Green）の完了記録はEventSelection／PlanReview／Execution／Recoveryの
 * 4画面のみを対象として明記しており、実際にはDepartureへ実装されていなかった。tP11a12a〜e
 * はこの取りこぼしを埋める（詳細は`DepartureScreen.kt`のP11-C6 KDoc参照）。
 *
 * ## T-P11A-1（EventRow）の意図的なスコープ限定
 * `EventSelectionScreen.kt`の`EventRow`は、先頭行（`isNext`＝インデックス0、「次の予定」
 * バッジを子に持つ）についてのみ、既存の`Modifier.clickable`（`mergeDescendants = true`を
 * 内部で強制する。[developer.android.com/develop/ui/compose/accessibility/api-defaults]で
 * 実測確認済み）を意図的に使わず、`mergeDescendants = false`の`semantics { onClick = ... }`を
 * 用いている（`EventSelectionScreen.kt`のKDoc「行コンテナ自体はmergeDescendantsによる
 * セマンティクス統合を意図的に行わない」参照）。これは`EventSelectionListTest`
 * （T-SEL2-1・T-SEL2-8、変更禁止）が`hasAnyDescendant(hasTestTag("event_selection_next_badge"))`
 * を**デフォルト（マージ済み）ツリー**に対して要求しており、`mergeDescendants = true`にすると
 * `SemanticsProperties.TestTag`のマージポリシー（祖先の値を優先し子の値を捨てる）により
 * バッジのtestTagがマージ済みツリーから失われ、この既存テストが構造的に壊れるためである。
 * したがって本ファイルのT-P11A-1は、既に`clickable`経由で`mergeDescendants = true`が成立して
 * いる**先頭以外の行**（`event_selection_row_1`）を対象に検証する。先頭行（`event_selection_row_0`）
 * も`contentDescription`自体は持つ設計とするが、`mergeDescendants`は`false`のまま維持し、
 * バッジの個別可視性（T-SEL2-1/T-SEL2-8）を最優先で保護する（完了報告で開示する実装判断）。
 */
@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-10T00:00:00Z")

    private fun sampleEvent(
        title: String = "Product Shoot",
        locationName: String? = "Shibuya",
        startDate: Instant = fixedNow.plus(1, ChronoUnit.HOURS)
    ): ExecutionEvent = ExecutionEvent(
        id = UUID.randomUUID(),
        externalCalendarId = null,
        title = title,
        notes = null,
        startDate = startDate,
        locationName = locationName,
        coordinates = if (locationName != null) Coordinate(35.6, 139.7) else null,
        sourceCalendar = CalendarSource(id = "mock", displayName = "Mock Calendar")
    )

    private fun sampleExecutionStep(
        title: String = "Get dressed",
        scheduledStart: Instant? = fixedNow
    ): ExecutionStep = ExecutionStep(
        id = UUID.randomUUID(),
        semanticId = "preparation",
        type = ExecutionStepType.PREPARATION,
        title = title,
        estimatedDuration = Duration.ofMinutes(10),
        priority = StepPriority.IMPORTANT,
        skippable = true,
        scheduledStart = scheduledStart,
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

    private fun sampleRecoveryOption(
        semanticAction: String,
        estimatedArrival: Instant? = fixedNow.plus(58, ChronoUnit.MINUTES)
    ): RecoveryOption = RecoveryOption(
        id = UUID.randomUUID(),
        semanticAction = semanticAction,
        title = semanticAction,
        explanation = "$semanticAction explanation",
        estimatedArrival = estimatedArrival,
        skippedStepIds = emptyList()
    )

    // T-P11A-1: 正常系 - EventSelectionのEventRowが1つのmergeDescendantsノードとして、日時・
    // タイトル・場所を含むcontentDescriptionを持つ（先頭以外の行を対象とする理由はクラスKDoc参照）。
    @Test
    fun tP11a1_eventRow_nonFirstRow_exposesGroupedContentDescriptionWithTitleAndLocation() {
        val first = sampleEvent(title = "Team Standup")
        val second = sampleEvent(
            title = "Client Call",
            locationName = "Shibuya Office",
            startDate = fixedNow.plus(3, ChronoUnit.HOURS)
        )
        composeTestRule.setContent {
            EventSelectionScreen(
                uiState = EventSelectionUiState.Content(events = listOf(first, second)),
                onNavigateToPlanReview = {}
            )
        }

        val node = composeTestRule.onNodeWithTag("event_selection_row_1").fetchSemanticsNode()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ").orEmpty()

        assertTrue(
            "expected row's grouped contentDescription ('$description') to include the event title",
            description.contains(second.title)
        )
        assertTrue(
            "expected row's grouped contentDescription ('$description') to include the location",
            description.contains(second.locationName!!)
        )
    }

    // T-P11A-2: 正常系 - PlanReviewのPlanReviewStepRowが時刻＋タイトルを含むcontentDescriptionを持つ。
    @Test
    fun tP11a2_planReviewStepRow_exposesContentDescriptionWithScheduledTimeAndTitle() {
        val step = sampleExecutionStep(title = "Get dressed", scheduledStart = fixedNow.plus(15, ChronoUnit.MINUTES))
        composeTestRule.setContent {
            PlanReviewScreen(
                uiState = PlanReviewUiState(plan = planWith(listOf(step)), isBehindSchedule = false, isEditEnabled = false),
                onNavigateToExecution = {}
            )
        }

        val node = composeTestRule.onNodeWithTag("plan_review_step_item").fetchSemanticsNode()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ").orEmpty()

        assertTrue(
            "expected step row's contentDescription ('$description') to include the step title",
            description.contains(step.title)
        )
        assertTrue("expected step row's contentDescription to be non-blank overall", description.isNotBlank())
    }

    // T-P11A-3: 正常系 - Executionの現在ステップ表示（semantics(mergeDescendants=true)）が
    // 非空のcontentDescriptionを持つ。
    @Test
    fun tP11a3_executionCurrentStepDisplay_exposesNonBlankContentDescription() {
        val step = sampleExecutionStep(title = "Get dressed")
        composeTestRule.setContent {
            ExecutionScreen(
                uiState = ExecutionUiState(currentStep = step, currentStepIndex = 0),
                onNavigateToDeparture = {},
                onNavigateToRecovery = {},
                onNavigateToEventSelection = {}
            )
        }

        val node = composeTestRule.onNodeWithTag("step_item_${step.id}").fetchSemanticsNode()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ").orEmpty()

        assertTrue("expected current-step contentDescription to be non-blank", description.isNotBlank())
    }

    // T-P11A-4: 正常系 - Executionの劣化バナー3種（exact alarm／通知／FGS）がいずれも「警告」で
    // あることをcontentDescriptionまたは同等のsemanticsで伝える（§63 color-only禁止）。
    // 可視テキスト（assertTextEqualsで既存テストが固定済み）はそのままに、contentDescription側
    // にのみ非視覚的な警告シグナルを追加する設計のため、「可視テキストとcontentDescriptionが
    // 一致しない（＝何かが追加されている）」ことをロケール非依存に検証する。
    @Test
    fun tP11a4_degradationBanners_conveyWarningNonVisually_viaContentDescriptionDistinctFromPlainText() {
        composeTestRule.setContent {
            ExecutionScreen(
                uiState = ExecutionUiState(
                    currentStep = sampleExecutionStep(),
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
        val context = RuntimeEnvironment.getApplication()

        val bannersAndVisibleText = listOf(
            "execution_exact_alarm_degraded_banner" to context.getString(R.string.execution_exact_alarm_degraded_message),
            "execution_notification_permission_banner" to
                context.getString(R.string.execution_notification_permission_denied_message),
            "execution_fgs_degraded_banner" to context.getString(R.string.execution_foreground_service_degraded_message)
        )

        bannersAndVisibleText.forEach { (tag, visibleText) ->
            val node = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode()
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ").orEmpty()

            assertTrue("banner '$tag' contentDescription must not be blank", description.isNotBlank())
            assertNotEquals(
                "banner '$tag' contentDescription must convey 'warning' non-visually (must differ from " +
                    "the plain visible message, not just repeat it)",
                visibleText,
                description
            )
        }
    }

    // T-P11A-5: 正常系 - DepartureのTransportModeSelector各選択肢が識別可能なcontentDescription
    // を持つ（Material3 FilterChipが内部でラベルTextをマージ済みの可能性があるため、
    // contentDescriptionが無い場合はマージ済みTextへフォールバックして判定する）。
    @Test
    fun tP11a5_transportModeSelector_eachOptionHasDistinctIdentifiableAccessibleDescription() {
        composeTestRule.setContent {
            TransportModeSelector(selectedMode = TransportMode.TRANSIT, onModeSelected = {})
        }

        val descriptionsByMode = TransportMode.entries.associateWith { mode ->
            val node = composeTestRule.onNodeWithTag(transportModeSelectorTestTag(mode)).fetchSemanticsNode()
            val contentDescription = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            val mergedText = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            (contentDescription ?: mergedText).orEmpty()
        }

        TransportMode.entries.forEach { mode ->
            assertTrue(
                "transport mode $mode must expose a non-blank accessible description",
                descriptionsByMode.getValue(mode).isNotBlank()
            )
        }
        assertEquals(
            "each transport mode's accessible description must be distinct from the others",
            TransportMode.entries.size,
            descriptionsByMode.values.toSet().size
        )
    }

    // T-P11A-6: 正常系 - Recovery候補行がmergeDescendantsノードとして、案の内容＋ETA＋選択状態
    // を含むcontentDescriptionを持つ。
    @Test
    fun tP11a6_recoveryOptionRow_exposesGroupedContentDescriptionWithTitleAndEta() {
        val option = sampleRecoveryOption(semanticAction = "keep_all_steps")
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = listOf(option), selectedOptionId = null),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        val node = composeTestRule.onNodeWithTag("recovery_option_item_${option.id}").fetchSemanticsNode()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ").orEmpty()

        assertTrue(
            "expected option row's contentDescription ('$description') to include the resolved title",
            description.contains(context.getString(R.string.recovery_option_title_keep_all_steps))
        )
        assertTrue(
            "expected option row's contentDescription ('$description') to include the ETA label",
            description.contains(context.getString(R.string.recovery_option_eta_label))
        )
    }

    // T-P11A-7: エッジケース - Recovery候補行のうちselectedIdと一致する行のcontentDescriptionが
    // 「選択中」を含む一方、他の行は含まない。
    @Test
    fun tP11a7_recoveryOptionRow_onlySelectedRowContentDescriptionIncludesSelectedMarker() {
        val selected = sampleRecoveryOption(semanticAction = "keep_all_steps")
        val other = sampleRecoveryOption(semanticAction = "change_transport_mode")
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = listOf(selected, other), selectedOptionId = selected.id),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()
        val selectedMarker = context.getString(R.string.recovery_option_selected_state_description)

        val selectedDescription = composeTestRule.onNodeWithTag("recovery_option_item_${selected.id}")
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ").orEmpty()
        val otherDescription = composeTestRule.onNodeWithTag("recovery_option_item_${other.id}")
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ").orEmpty()

        assertTrue(
            "selected row's contentDescription ('$selectedDescription') must include the selected marker",
            selectedDescription.contains(selectedMarker)
        )
        assertFalse(
            "non-selected row's contentDescription ('$otherDescription') must not include the selected marker",
            otherDescription.contains(selectedMarker)
        )
    }

    // T-P11A-8: エッジケース - estimatedArrival == nullの候補行はETA情報をcontentDescriptionに
    // 含めない（偽情報を読み上げない、T-RECUI-8と同種の設計）。
    @Test
    fun tP11a8_recoveryOptionRow_nullEstimatedArrival_omitsEtaFromContentDescription() {
        val option = sampleRecoveryOption(semanticAction = "keep_all_steps", estimatedArrival = null)
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = listOf(option), selectedOptionId = null),
                onNavigateToExecution = {}
            )
        }
        val context = RuntimeEnvironment.getApplication()

        val description = composeTestRule.onNodeWithTag("recovery_option_item_${option.id}")
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ").orEmpty()

        assertFalse(
            "contentDescription ('$description') must not fabricate ETA info when estimatedArrival is null",
            description.contains(context.getString(R.string.recovery_option_eta_label))
        )
    }

    // T-P11A-9: 正常系 - ja/en両ロケールで新規contentDescription文言が非空かつ相互に異なる
    // （notification_open_settings_buttonはT-P11N-9が別途担当。ここでは
    // accessibility_warning_announcement／recovery_option_selected_state_descriptionの2件）。
    @Test
    fun tP11a9_newContentDescriptionWording_nonBlankAndDistinctBetweenEnAndJa() {
        val keys = listOf("accessibility_warning_announcement", "recovery_option_selected_state_description")

        val enContext = RuntimeEnvironment.getApplication()
        val enValues = keys.associateWith { key ->
            val resId = enContext.resources.getIdentifier(key, "string", enContext.packageName)
            assertTrue("string resource '$key' not found (en)", resId != 0)
            enContext.getString(resId)
        }

        RuntimeEnvironment.setQualifiers("+ja")
        val jaContext = RuntimeEnvironment.getApplication()
        val jaValues = keys.associateWith { key ->
            val resId = jaContext.resources.getIdentifier(key, "string", jaContext.packageName)
            assertTrue("string resource '$key' not found (ja)", resId != 0)
            jaContext.getString(resId)
        }

        for (key in keys) {
            assertTrue("en value for '$key' must not be blank", enValues.getValue(key).isNotBlank())
            assertTrue("ja value for '$key' must not be blank", jaValues.getValue(key).isNotBlank())
            assertNotEquals(
                "en/ja values for '$key' must differ (real translation, not a copy)",
                enValues.getValue(key),
                jaValues.getValue(key)
            )
        }
    }

    // T-P11A-10: 回帰 - mergeDescendants追加後も既存testTagベースの主要アサーション
    // （T-EXEC-2、T-REC-2の代表サンプル）が成立する。両サンプルを単一コンポジションで検証する
    // （ComposeContentTestRuleのsetContent()はテスト1件につき1回のみ呼べる制約のため）。
    // 両ComposableのルートがModifier.fillMaxSize()であるため、無制約のColumnへそのまま並べると
    // 測定上競合する（片方が高さを使い切る）。Box(Modifier.height(...))で明示的に区切ったうえで
    // 外側全体をDeviceConfigurationOverride.ForcedSizeで十分大きい仮想ウィンドウへ固定する
    // （Robolectricの既定画面サイズに依存しない。FontScaleResilienceTestのT-P11F-7と同じ対応）。
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tP11a10_mergeDescendantsAdditions_keepExistingTagBasedQueriesWorking_sampledFromTExec2AndTRec2() {
        val step = sampleExecutionStep(title = "Get dressed")
        val option = sampleRecoveryOption(semanticAction = "keep_all_steps")

        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 1600.dp))) {
                Column {
                    Box(modifier = Modifier.height(700.dp)) {
                        ExecutionScreen(
                            uiState = ExecutionUiState(currentStep = step, currentStepIndex = 0),
                            onNavigateToDeparture = {},
                            onNavigateToRecovery = {},
                            onNavigateToEventSelection = {}
                        )
                    }
                    Box(modifier = Modifier.height(700.dp)) {
                        RecoveryScreen(
                            uiState = RecoveryUiState(options = listOf(option), selectedOptionId = null),
                            onNavigateToExecution = {}
                        )
                    }
                }
            }
        }

        // T-EXEC-2 sample: the current-step row's own testTag remains queryable in the default
        // merged tree (mergeDescendants=true was already true before Phase 11; only the block's
        // content changed).
        composeTestRule.onNodeWithTag("step_item_${step.id}").assertIsDisplayed()

        // T-REC-2 sample: the option row's own testTag remains queryable and clickable.
        composeTestRule.onNodeWithTag("recovery_option_item_${option.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recovery_option_item_${option.id}").performClick()
    }

    // T-P11A-11: 正常系 - Recovery候補行のうち選択中の行に、他の行と異なる視覚的インジケータが
    // 適用される（§12 S-5裁定）。ピクセル単位の背景色検証はCompose Testの標準APIを超えるため
    // （§7.3と同じ限界。実際の見た目はG4-E目視確認に委ねる）、標準の選択状態semantics
    // （SemanticsProperties.Selected。TalkBackが選択状態を判別するための標準プロパティで、
    // Modifier.background(...)による視覚的インジケータと対にして実装する設計とする）で
    // 選択/非選択が構造的に区別できることを検証する。
    @Test
    fun tP11a11_recoverySelectedRow_exposesSelectedStateDistinctFromUnselectedRows() {
        val selected = sampleRecoveryOption(semanticAction = "keep_all_steps")
        val other = sampleRecoveryOption(semanticAction = "change_transport_mode")
        composeTestRule.setContent {
            RecoveryScreen(
                uiState = RecoveryUiState(options = listOf(selected, other), selectedOptionId = selected.id),
                onNavigateToExecution = {}
            )
        }

        val selectedNode = composeTestRule.onNodeWithTag("recovery_option_item_${selected.id}").fetchSemanticsNode()
        val otherNode = composeTestRule.onNodeWithTag("recovery_option_item_${other.id}").fetchSemanticsNode()

        assertEquals(
            "the tentatively-selected row must expose Selected=true (paired with a visual " +
                "background/border indicator per §12 S-5; color-only is not used, §63)",
            true,
            selectedNode.config.getOrNull(SemanticsProperties.Selected)
        )
        assertEquals(
            "non-selected rows must expose Selected=false",
            false,
            otherNode.config.getOrNull(SemanticsProperties.Selected)
        )
    }

    // ============================================================
    // T-P11A-12（P11-C6、Phase 11 F81取りこぼし是正）。クラスKDoc「T-P11A-12」節参照。
    // ============================================================

    // T-P11A-12a: 正常系 - Departureの警告表示3種（ETA未取得／バッファ負値／
    // EtaFailureReason）がいずれも「警告」であることをcontentDescriptionで非視覚的に伝える
    // （T-P11A-4と同型、§63 color-only禁止）。可視テキストと異なることをロケール非依存に
    // 検証する。
    @Test
    fun tP11a12a_departureWarningTexts_conveyWarningNonVisually_viaContentDescriptionDistinctFromPlainText() {
        composeTestRule.setContent {
            DepartureScreen(
                uiState = DepartureUiState(
                    estimatedArrival = null,
                    eventStart = fixedNow.plus(60, ChronoUnit.MINUTES),
                    arrivalBuffer = Duration.ofMinutes(-5),
                    etaFailureReason = EtaFailureReason.OFFLINE,
                    permissionState = LocationPermissionState.GRANTED
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        val visibleTexts = listOf(
            context.getString(R.string.departure_eta_unavailable_message),
            context.getString(R.string.departure_buffer_negative_warning),
            context.getString(R.string.departure_eta_offline_message)
        )

        visibleTexts.forEach { visibleText ->
            val node = composeTestRule.onNodeWithText(visibleText).fetchSemanticsNode()
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ").orEmpty()

            assertTrue("warning text '$visibleText' contentDescription must not be blank", description.isNotBlank())
            assertNotEquals(
                "warning text '$visibleText' contentDescription must convey 'warning' non-visually (must differ " +
                    "from the plain visible message, not just repeat it)",
                visibleText,
                description
            )
        }
    }

    // T-P11A-12b: 正常系 - Departureの手動Travel Time入力欄（TravelTimeInput）が明示的な
    // 非空contentDescriptionを持つ（T-P11A-5と異なりmergedTextフォールバックに頼らない）。
    @Test
    fun tP11a12b_travelTimeInput_exposesExplicitNonBlankContentDescription() {
        composeTestRule.setContent {
            TravelTimeInput(minutes = null, onMinutesChange = {})
        }

        val node = composeTestRule.onNodeWithTag(TRAVEL_TIME_INPUT_TEST_TAG).fetchSemanticsNode()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

        assertTrue(
            "travel time input must expose an explicit (non-fallback) contentDescription",
            !description.isNullOrBlank()
        )
    }

    // T-P11A-12c: 正常系 - DepartureのTransportModeSelector各選択肢が明示的contentDescription
    // （mergedTextフォールバックでなく実プロパティとして）を持ち、相互に異なる（T-P11A-5は
    // 「contentDescriptionまたはmergedText」を許容する設計だったが、本ケースはP11-C6の
    // Green実装がcontentDescriptionプロパティ自体を実装したことを直接検証する）。
    @Test
    fun tP11a12c_transportModeSelector_eachOptionHasExplicitDistinctContentDescription() {
        composeTestRule.setContent {
            TransportModeSelector(selectedMode = TransportMode.TRANSIT, onModeSelected = {})
        }

        val descriptionsByMode = TransportMode.entries.associateWith { mode ->
            composeTestRule.onNodeWithTag(transportModeSelectorTestTag(mode)).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        }

        TransportMode.entries.forEach { mode ->
            assertTrue(
                "transport mode $mode must expose an explicit (non-fallback) contentDescription",
                !descriptionsByMode.getValue(mode).isNullOrBlank()
            )
        }
        assertEquals(
            "each transport mode's explicit contentDescription must be distinct from the others",
            TransportMode.entries.size,
            descriptionsByMode.values.toSet().size
        )
    }

    // T-P11A-12d: 正常系 - LocationPermissionRationaleCardがタイトル＋説明文を含む
    // contentDescriptionを持つ一方、既存の"departure_location_permission_grant_button"
    // testTagクエリ（T-PERM3-2、変更禁止）が引き続きデフォルト（マージ済み）ツリーで成立する
    // （回帰保護。カード側はmergeDescendants=trueを新設せず、ボタンと独立した別ノードへ
    // contentDescriptionを付与する設計のため、ボタンのtestTagは失われない）。
    @Test
    fun tP11a12d_locationPermissionRationaleCard_exposesGroupedContentDescription_grantButtonTagStillQueryable() {
        composeTestRule.setContent {
            LocationPermissionRationaleCard(onRequestLocationPermission = {})
        }
        val context = RuntimeEnvironment.getApplication()
        val title = context.getString(R.string.location_permission_rationale_title)
        val message = context.getString(R.string.location_permission_rationale_message)

        val cardNode = composeTestRule.onNodeWithTag("departure_location_permission_rationale_card")
            .fetchSemanticsNode()
        val cardDescription = cardNode.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ").orEmpty()

        assertTrue(
            "rationale card's contentDescription ('$cardDescription') must include the title",
            cardDescription.contains(title)
        )
        assertTrue(
            "rationale card's contentDescription ('$cardDescription') must include the message",
            cardDescription.contains(message)
        )

        // 回帰保護（T-PERM3-2、変更禁止）: ボタンのtestTagは引き続きデフォルトツリーで見つかる。
        composeTestRule.onNodeWithTag("departure_location_permission_grant_button").assertIsDisplayed()
    }

    // T-P11A-12e: 正常系 - Departure DENIED状態の設定導線ボタン
    // （"departure_location_open_settings_button"）が非空のcontentDescriptionを持つ一方、
    // 既存のtestTagクエリ・可視テキストクエリ（T-PERM3-3、変更禁止）が引き続き成立する
    // （回帰保護。ボタン自身のノードへcontentDescriptionを追加するのみで新規の
    // mergeDescendantsは発生しないため、子Textは失われない）。
    @Test
    fun tP11a12e_openSettingsButton_exposesNonBlankContentDescription_regressionSafeForExistingQueries() {
        composeTestRule.setContent {
            DepartureScreen(uiState = DepartureUiState(permissionState = LocationPermissionState.DENIED))
        }
        val context = RuntimeEnvironment.getApplication()

        val node = composeTestRule.onNodeWithTag("departure_location_open_settings_button").fetchSemanticsNode()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

        assertTrue(
            "open-settings button must expose a non-blank contentDescription",
            !description.isNullOrBlank()
        )

        // 回帰保護（T-PERM3-3、変更禁止）: 可視テキストによるクエリも引き続き成立する。
        composeTestRule.onNodeWithText(context.getString(R.string.location_open_settings_button))
            .assertIsDisplayed()
    }
}
