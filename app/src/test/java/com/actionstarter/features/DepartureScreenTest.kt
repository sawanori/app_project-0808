package com.actionstarter.features

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.features.departure.DepartureScreen
import com.actionstarter.features.departure.DepartureUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * F7 — Departure画面（計画書§11.2、T-DEP-1〜4）。
 *
 * DepartureScreenは現時点（C2契約scaffold）で本文が空実装のため、以下のテストは
 * すべて「期待する要素が見つからない」ことによりRedになるのが正しい。
 * DepartureScreenは画面遷移ラムダを持たない（計画書§10.6 KDoc参照：Phase 1では
 * Departureが遷移の終端であり遷移先が存在しないため）。
 *
 * T-DEP-2の「色」による明示は本Robolectricテストの対象外（テキストのみ検証。
 * 色の検証はスクリーンショット/視覚回帰の領域でありG4-Eスコープ外）。
 */
@RunWith(AndroidJUnit4::class)
class DepartureScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixedNow: Instant = Instant.parse("2026-08-08T00:00:00Z")

    // T-DEP-1: 正常系 - Screen4（Leave）の表示要素が揃っている
    @Test
    fun tDep1_populatedState_displaysAllRequiredElements() {
        composeTestRule.setContent {
            DepartureScreen(
                uiState = DepartureUiState(
                    estimatedArrival = fixedNow.plus(52, ChronoUnit.MINUTES),
                    eventStart = fixedNow.plus(60, ChronoUnit.MINUTES),
                    arrivalBuffer = Duration.ofMinutes(8),
                    isStartNavigationEnabled = false
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.departure_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_estimated_arrival_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_event_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_buffer_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_start_navigation_button)).assertIsDisplayed()
    }

    // T-DEP-2: エッジケース - バッファが負値のとき、色とテキストの両方で明示する
    @Test
    fun tDep2_negativeBuffer_showsLatenessWarningText() {
        composeTestRule.setContent {
            DepartureScreen(
                uiState = DepartureUiState(
                    estimatedArrival = fixedNow.plus(65, ChronoUnit.MINUTES),
                    eventStart = fixedNow.plus(60, ChronoUnit.MINUTES),
                    arrivalBuffer = Duration.ofMinutes(-5),
                    isStartNavigationEnabled = false
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.departure_buffer_negative_warning)).assertIsDisplayed()
    }

    // T-DEP-3: エッジケース - ETAが取得できていない場合「移動時間未取得」と表示する
    @Test
    fun tDep3_missingEstimatedArrival_showsEtaUnavailableMessage() {
        composeTestRule.setContent {
            DepartureScreen(
                uiState = DepartureUiState(
                    estimatedArrival = null,
                    eventStart = fixedNow.plus(60, ChronoUnit.MINUTES),
                    arrivalBuffer = null,
                    isStartNavigationEnabled = false
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.departure_eta_unavailable_message)).assertIsDisplayed()
    }

    // T-DEP-4: 正常系 - 「Start navigation」ボタンは無効化され、理由が表示される（Phase 1では未実装）
    @Test
    fun tDep4_startNavigationButton_disabledWithReason() {
        composeTestRule.setContent {
            DepartureScreen(
                uiState = DepartureUiState(
                    estimatedArrival = fixedNow.plus(52, ChronoUnit.MINUTES),
                    eventStart = fixedNow.plus(60, ChronoUnit.MINUTES),
                    arrivalBuffer = Duration.ofMinutes(8),
                    isStartNavigationEnabled = false
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.departure_start_navigation_button)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(context.getString(R.string.departure_start_navigation_disabled_reason)).assertIsDisplayed()
    }
}
