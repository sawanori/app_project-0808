package com.actionstarter.features

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.R
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelDownloadFailureReason
import com.actionstarter.ai.model.ModelLicense
import com.actionstarter.features.settings.DeviceUnsupportedReason
import com.actionstarter.features.settings.ModelDownloadStatus
import com.actionstarter.features.settings.SettingsScreen
import com.actionstarter.features.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * P7-C6（計画書§12.6 T-SET-1〜6、F97、§13.5 S-6範囲裁定）。[SettingsScreen]のGreenテスト。
 *
 * [SettingsScreen]は本ファイル作成時点（Red scaffold）で空実装のため、以下は全件
 * 「期待するノードが見つからない」ことによりRedになるのが正しい（`DepartureScreenTest`等の
 * 既存Red先例と同型）。
 *
 * testTag規約: "settings_back_button" / "settings_ai_toggle" /
 * "settings_ai_unsupported_reason" / "settings_model_status_text" /
 * "settings_download_progress" / "settings_download_button"（初回DL・retry兼用） /
 * "settings_delete_button" / "settings_capacity_required_text" /
 * "settings_capacity_available_text" / "settings_capacity_insufficient_warning"。
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val installedEntry: ModelCatalogEntry = ModelCatalogEntry(
        id = "test-model",
        displayName = "Test Model",
        downloadUrl = "https://example.invalid/test-model.litertlm",
        sha256 = "0".repeat(64),
        sizeBytes = 1_000_000L,
        peakRamBytes = 1L,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false
    )

    // ------------------------------------------------------------------
    // T-SET-1/2: AIトグル
    // ------------------------------------------------------------------

    @Test
    fun aiToggle_offState_isDisplayedAndOff() {
        composeTestRule.setContent {
            SettingsScreen(uiState = SettingsUiState(aiEnabled = false, selectedModel = installedEntry))
        }

        composeTestRule.onNodeWithTag("settings_ai_toggle").assertIsDisplayed().assertIsOff()
    }

    @Test
    fun aiToggle_onState_isOn() {
        composeTestRule.setContent {
            SettingsScreen(uiState = SettingsUiState(aiEnabled = true, selectedModel = installedEntry))
        }

        composeTestRule.onNodeWithTag("settings_ai_toggle").assertIsOn()
    }

    @Test
    fun aiToggle_tap_invokesOnAiEnabledToggledWithNewValue() {
        var receivedValue: Boolean? = null
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(aiEnabled = false, selectedModel = installedEntry),
                onAiEnabledToggled = { receivedValue = it }
            )
        }

        composeTestRule.onNodeWithTag("settings_ai_toggle").performClick()

        assertEqualsBoolean(true, receivedValue)
    }

    // ------------------------------------------------------------------
    // T-SET-3: 非対応端末
    // ------------------------------------------------------------------

    @Test
    fun aiToggle_disabledWhenRamUnsupported_showsRamReasonText() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(
                    isDeviceSupported = false,
                    deviceUnsupportedReason = DeviceUnsupportedReason.INSUFFICIENT_RAM,
                    selectedModel = installedEntry
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("settings_ai_toggle").assertIsNotEnabled()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_ai_unsupported_ram_reason)).assertIsDisplayed()
    }

    @Test
    fun aiToggle_disabledWhenAbiUnsupported_showsAbiReasonText() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(
                    isDeviceSupported = false,
                    deviceUnsupportedReason = DeviceUnsupportedReason.UNSUPPORTED_ABI,
                    selectedModel = installedEntry
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("settings_ai_toggle").assertIsNotEnabled()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_ai_unsupported_abi_reason)).assertIsDisplayed()
    }

    // ------------------------------------------------------------------
    // T-SET-4: モデル状態表示
    // ------------------------------------------------------------------

    @Test
    fun modelStatus_notInstalled_showsDownloadButton_hidesDeleteButton() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(modelStatus = ModelDownloadStatus.NotInstalled, selectedModel = installedEntry)
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_status_not_installed)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_download_button").assertIsDisplayed()
    }

    @Test
    fun modelStatus_downloading_showsProgressIndicatorWithPercent() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(
                    modelStatus = ModelDownloadStatus.Downloading(bytesDownloaded = 500_000L, totalBytes = 1_000_000L),
                    selectedModel = installedEntry
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("settings_download_progress").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_status_downloading_format, 50)).assertIsDisplayed()
    }

    @Test
    fun modelStatus_installed_showsDeleteButton_hidesDownloadButton() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(modelStatus = ModelDownloadStatus.Installed, selectedModel = installedEntry)
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_status_installed)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_delete_button").assertIsDisplayed()
    }

    @Test
    fun modelStatus_failedVerification_showsRetryButton_andVerificationFailureText() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(
                    modelStatus = ModelDownloadStatus.Failed(ModelDownloadFailureReason.VERIFICATION_FAILED),
                    selectedModel = installedEntry
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_status_failed_verification)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_download_button").assertIsDisplayed()
    }

    @Test
    fun modelStatus_failedInsufficientStorage_showsDedicatedFailureText() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(
                    modelStatus = ModelDownloadStatus.Failed(ModelDownloadFailureReason.INSUFFICIENT_STORAGE),
                    selectedModel = installedEntry
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_status_failed_insufficient_storage))
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------------
    // DL/削除の導線タップ
    // ------------------------------------------------------------------

    @Test
    fun downloadButton_tap_invokesOnDownloadRequested() {
        var invoked = false
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(modelStatus = ModelDownloadStatus.NotInstalled, selectedModel = installedEntry),
                onDownloadRequested = { invoked = true }
            )
        }

        composeTestRule.onNodeWithTag("settings_download_button").performClick()

        assertEqualsBoolean(true, invoked)
    }

    @Test
    fun deleteButton_tap_invokesOnDeleteRequested() {
        var invoked = false
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(modelStatus = ModelDownloadStatus.Installed, selectedModel = installedEntry),
                onDeleteRequested = { invoked = true }
            )
        }

        composeTestRule.onNodeWithTag("settings_delete_button").performClick()

        assertEqualsBoolean(true, invoked)
    }

    @Test
    fun backButton_tap_invokesOnNavigateBack() {
        var invoked = false
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(selectedModel = installedEntry),
                onNavigateBack = { invoked = true }
            )
        }

        composeTestRule.onNodeWithTag("settings_back_button").performClick()

        assertEqualsBoolean(true, invoked)
    }

    // ------------------------------------------------------------------
    // T-SET-5: 容量表示
    // ------------------------------------------------------------------

    @Test
    fun capacity_alwaysDisplaysRequiredAndAvailableTexts() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(
                    selectedModel = installedEntry,
                    requiredBytesForDownload = 1_500_000L,
                    availableBytes = 2_000_000L
                )
            )
        }

        // Settings画面のCard2枚（AIトグル区画＋モデル区画）はRobolectric既定ビューポート
        // （320×470dp、実機のどのAndroid端末よりはるかに小さいレガシーな既定値）を超えるため、
        // 容量表示（モデル区画の末尾）はperformScrollTo()が必要（FontScaleResilienceTestの
        // 既存T-P11F-3/5と同型の対応）。
        composeTestRule.onNodeWithTag("settings_capacity_required_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_capacity_available_text").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun capacity_insufficientWarning_shownWhenAvailableBelowRequired() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState(
                    selectedModel = installedEntry,
                    requiredBytesForDownload = 2_000_000L,
                    availableBytes = 500_000L
                )
            )
        }

        composeTestRule.onNodeWithTag("settings_capacity_insufficient_warning").performScrollTo().assertIsDisplayed()
    }

    private fun assertEqualsBoolean(expected: Boolean, actual: Boolean?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
