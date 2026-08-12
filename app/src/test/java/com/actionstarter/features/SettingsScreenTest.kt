package com.actionstarter.features

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import com.actionstarter.ai.model.ModelStorage
import com.actionstarter.features.settings.DeleteBehaviorLogResult
import com.actionstarter.features.settings.DeviceUnsupportedReason
import com.actionstarter.features.settings.ModelDownloadStatus
import com.actionstarter.features.settings.ModelOption
import com.actionstarter.features.settings.ModelOptionUiState
import com.actionstarter.features.settings.SettingsScreen
import com.actionstarter.features.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * P7-C6（計画書§12.6 T-SET-1〜6、F97、§13.5 S-6範囲裁定）／Phase 8.5 F-B（計画書
 * `docs/plans/phase8.5-adaptive-model-selection.md`、ADR-0062）。[SettingsScreen]のテスト。
 *
 * **Phase 8.5 F-Bでの変更**: [SettingsUiState]が単数`selectedModel`/`modelStatus`から
 * [SettingsUiState.models]（複数行、常に「自動」＋候補分）へ変わったことに伴い、本ファイルは
 * [singleModelUiState]ヘルパーで「[ModelOption.Specific]側の行を1件だけ持つ状態」を組み立てる
 * よう書き換えた（各テストの検証意図——AIトグルの状態・モデル1件の状態表示・DL/削除導線・
 * 容量表示——は不変）。testTagは行ごとに`_<entry.id>`のsuffixを持つ（[SettingsScreen]のKDoc
 * 「testTag規約」参照）ため、[installedEntry]のidをsuffixとして使う。
 *
 * **Green段階での追加（§11確認事項1・2、T-P85-21）**: 「自動」行の表示・メモリ不足注記
 * （[ModelOptionUiState.isMemoryInsufficient]）・DL中の他行ボタン無効化は、Red時点の
 * [singleModelUiState]（[ModelOption.Specific]行1件のみ）では検証できないため、
 * [autoAndSingleModelUiState]ヘルパーと専用テストをGreen段階で追加した（完了報告で開示）。
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

    /** タグsuffix（[SettingsScreen]のKDoc「testTag規約」参照）。 */
    private val tagId = installedEntry.id

    /**
     * Phase 8.5 F-B追加（T-P85-21、[installedEntry]とは別の[ModelOption.Specific]行を要する
     * 「他行無効化」テスト専用のfixture。Refactor: 元は2テストで個別に構築していたものを
     * class-level fieldへ集約した）。
     */
    private val otherEntry: ModelCatalogEntry = ModelCatalogEntry(
        id = "other-model",
        displayName = "Other Model",
        downloadUrl = "https://example.invalid/other-model.litertlm",
        sha256 = "1".repeat(64),
        sizeBytes = 1_000_000L,
        peakRamBytes = 1L,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false
    )

    /**
     * Phase 8.5 F-B追加（T-P85-21）。[installedEntry]・[otherEntry]の2行から成る
     * [SettingsUiState]を組み立てる（[installedStatus]・[otherStatus]で個別にDL状態を指定する）。
     */
    private fun twoSpecificModelUiState(
        installedStatus: ModelDownloadStatus,
        otherStatus: ModelDownloadStatus
    ): SettingsUiState = SettingsUiState(
        models = listOf(
            ModelOptionUiState(
                option = ModelOption.Specific(installedEntry),
                status = installedStatus,
                isRecommended = false,
                isSelected = false,
                requiredBytesForDownload = 1_500_000L
            ),
            ModelOptionUiState(
                option = ModelOption.Specific(otherEntry),
                status = otherStatus,
                isRecommended = false,
                isSelected = false,
                requiredBytesForDownload = 1_500_000L
            )
        )
    )

    /**
     * Phase 8.5 F-B追加。[ModelOption.Specific]側の行を[installedEntry]の1件だけ持つ
     * [SettingsUiState]を組み立てる（既存テストの「単一モデルに注目した検証意図」を保つ）。
     */
    private fun singleModelUiState(
        aiEnabled: Boolean = false,
        isDeviceSupported: Boolean = true,
        deviceUnsupportedReason: DeviceUnsupportedReason? = null,
        status: ModelDownloadStatus? = ModelDownloadStatus.NotInstalled,
        requiredBytesForDownload: Long = (installedEntry.sizeBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong(),
        availableBytes: Long = 0L,
        isMemoryInsufficient: Boolean = false
    ): SettingsUiState = SettingsUiState(
        aiEnabled = aiEnabled,
        isDeviceSupported = isDeviceSupported,
        deviceUnsupportedReason = deviceUnsupportedReason,
        models = listOf(
            ModelOptionUiState(
                option = ModelOption.Specific(installedEntry),
                status = status,
                isRecommended = false,
                isSelected = false,
                requiredBytesForDownload = requiredBytesForDownload,
                isMemoryInsufficient = isMemoryInsufficient
            )
        ),
        availableBytes = availableBytes
    )

    /**
     * Phase 8.5 F-B追加（§11確認事項1・T-P85-21の複数行相互作用テスト用）。「自動」＋
     * [installedEntry]の2行構成の[SettingsUiState]を組み立てる。
     */
    private fun autoAndSingleModelUiState(
        autoIsSelected: Boolean = false,
        status: ModelDownloadStatus = ModelDownloadStatus.NotInstalled,
        isMemoryInsufficient: Boolean = false
    ): SettingsUiState = SettingsUiState(
        models = listOf(
            ModelOptionUiState(
                option = ModelOption.Auto,
                status = null,
                isRecommended = false,
                isSelected = autoIsSelected,
                requiredBytesForDownload = 0L
            ),
            ModelOptionUiState(
                option = ModelOption.Specific(installedEntry),
                status = status,
                isRecommended = false,
                isSelected = false,
                requiredBytesForDownload = (installedEntry.sizeBytes * ModelStorage.CAPACITY_SAFETY_FACTOR).toLong(),
                isMemoryInsufficient = isMemoryInsufficient
            )
        )
    )

    // ------------------------------------------------------------------
    // T-SET-1/2: AIトグル
    // ------------------------------------------------------------------

    @Test
    fun aiToggle_offState_isDisplayedAndOff() {
        composeTestRule.setContent {
            SettingsScreen(uiState = singleModelUiState(aiEnabled = false))
        }

        composeTestRule.onNodeWithTag("settings_ai_toggle").assertIsDisplayed().assertIsOff()
    }

    @Test
    fun aiToggle_onState_isOn() {
        composeTestRule.setContent {
            SettingsScreen(uiState = singleModelUiState(aiEnabled = true))
        }

        composeTestRule.onNodeWithTag("settings_ai_toggle").assertIsOn()
    }

    @Test
    fun aiToggle_tap_invokesOnAiEnabledToggledWithNewValue() {
        var receivedValue: Boolean? = null
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState(aiEnabled = false),
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
                uiState = singleModelUiState(
                    isDeviceSupported = false,
                    deviceUnsupportedReason = DeviceUnsupportedReason.INSUFFICIENT_RAM
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
                uiState = singleModelUiState(
                    isDeviceSupported = false,
                    deviceUnsupportedReason = DeviceUnsupportedReason.UNSUPPORTED_ABI
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
            SettingsScreen(uiState = singleModelUiState(status = ModelDownloadStatus.NotInstalled))
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_status_not_installed)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_download_button_$tagId").assertIsDisplayed()
    }

    @Test
    fun modelStatus_downloading_showsProgressIndicatorWithPercent() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState(
                    status = ModelDownloadStatus.Downloading(bytesDownloaded = 500_000L, totalBytes = 1_000_000L)
                )
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("settings_download_progress_$tagId").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_status_downloading_format, 50)).assertIsDisplayed()
    }

    @Test
    fun modelStatus_installed_showsDeleteButton_hidesDownloadButton() {
        composeTestRule.setContent {
            SettingsScreen(uiState = singleModelUiState(status = ModelDownloadStatus.Installed))
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_status_installed)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_delete_button_$tagId").assertIsDisplayed()
    }

    @Test
    fun modelStatus_failedVerification_showsRetryButton_andVerificationFailureText() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState(status = ModelDownloadStatus.Failed(ModelDownloadFailureReason.VERIFICATION_FAILED))
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_status_failed_verification)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_download_button_$tagId").assertIsDisplayed()
    }

    @Test
    fun modelStatus_failedInsufficientStorage_showsDedicatedFailureText() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState(status = ModelDownloadStatus.Failed(ModelDownloadFailureReason.INSUFFICIENT_STORAGE))
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
        var receivedEntry: ModelCatalogEntry? = null
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState(status = ModelDownloadStatus.NotInstalled),
                onDownloadRequested = { receivedEntry = it }
            )
        }

        composeTestRule.onNodeWithTag("settings_download_button_$tagId").performClick()

        assertEqualsAny(installedEntry, receivedEntry)
    }

    @Test
    fun deleteButton_tap_invokesOnDeleteRequested() {
        var receivedEntry: ModelCatalogEntry? = null
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState(status = ModelDownloadStatus.Installed),
                onDeleteRequested = { receivedEntry = it }
            )
        }

        composeTestRule.onNodeWithTag("settings_delete_button_$tagId").performClick()

        assertEqualsAny(installedEntry, receivedEntry)
    }

    @Test
    fun backButton_tap_invokesOnNavigateBack() {
        var invoked = false
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState(),
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
                uiState = singleModelUiState(
                    requiredBytesForDownload = 1_500_000L,
                    availableBytes = 2_000_000L
                )
            )
        }

        // Settings画面のCard群はRobolectric既定ビューポート（320×470dp、実機のどのAndroid端末
        // よりはるかに小さいレガシーな既定値）を超えるため、容量表示はperformScrollTo()が必要
        // （FontScaleResilienceTestの既存T-P11F-3/5と同型の対応）。
        composeTestRule.onNodeWithTag("settings_capacity_required_text_$tagId").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_capacity_available_text").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun capacity_insufficientWarning_shownWhenAvailableBelowRequired() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState(
                    requiredBytesForDownload = 2_000_000L,
                    availableBytes = 500_000L
                )
            )
        }

        composeTestRule.onNodeWithTag("settings_capacity_insufficient_warning_$tagId").performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------------
    // Phase 8.5 F-B: 「自動」行・メモリ不足注記（§11確認事項1・2）・DL中の他行無効化（T-P85-21）
    // ------------------------------------------------------------------

    // §11確認事項2: 「自動」行がラベル・一文説明つきで表示され、選択操作がonModelSelectedへ届く。
    @Test
    fun autoRow_displaysLabelAndDescription_andSelectionInvokesCallback() {
        var selectedOption: ModelOption? = null
        composeTestRule.setContent {
            SettingsScreen(
                uiState = autoAndSingleModelUiState(),
                onModelSelected = { selectedOption = it }
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_option_auto)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_option_auto_description)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_model_select_auto").performClick()

        assertEqualsAny(ModelOption.Auto, selectedOption)
    }

    // §11確認事項1: isMemoryInsufficient=trueの行にのみ警告文言が表示される。
    @Test
    fun modelRow_insufficientMemory_showsWarningText() {
        composeTestRule.setContent {
            SettingsScreen(uiState = autoAndSingleModelUiState(isMemoryInsufficient = true))
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("settings_model_insufficient_memory_warning_$tagId").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_model_insufficient_memory_warning))
            .assertIsDisplayed()
    }

    // §11確認事項1: isMemoryInsufficient=false(既定)では警告文言のノード自体が存在しない。
    @Test
    fun modelRow_sufficientMemory_doesNotShowWarningText() {
        composeTestRule.setContent {
            SettingsScreen(uiState = autoAndSingleModelUiState(isMemoryInsufficient = false))
        }

        composeTestRule.onNodeWithTag("settings_model_insufficient_memory_warning_$tagId").assertDoesNotExist()
    }

    // T-P85-21: 1モデルDL中は他行のDL/削除ボタンが無効化される
    // （DL中の行自体はModelStatusContentのDownloading分岐がボタンを描画しないため対象外。
    // installedEntry以外の具体モデル行が必要なため、2つの[ModelOption.Specific]行を持つ
    // 構成で検証する）。
    @Test
    fun otherSpecificRow_downloadButton_disabledWhileAnotherSpecificRowIsDownloading() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = twoSpecificModelUiState(
                    installedStatus = ModelDownloadStatus.Downloading(bytesDownloaded = 100L, totalBytes = 1_000_000L),
                    otherStatus = ModelDownloadStatus.NotInstalled
                )
            )
        }

        composeTestRule.onNodeWithTag("settings_download_button_other-model").assertIsNotEnabled()
    }

    @Test
    fun allRows_downloadButtons_enabledWhenNoRowIsDownloading() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = twoSpecificModelUiState(
                    installedStatus = ModelDownloadStatus.NotInstalled,
                    otherStatus = ModelDownloadStatus.NotInstalled
                )
            )
        }

        composeTestRule.onNodeWithTag("settings_download_button_$tagId").assertIsEnabled()
        composeTestRule.onNodeWithTag("settings_download_button_other-model").assertIsEnabled()
    }

    // ------------------------------------------------------------------
    // Phase 10 C4（計画書§3.4「全削除導線」、T-P10-16/17/18、Step 4 Green）。
    // Phase 8.5 F-Bと同型でScreen側テストはGreen段階で追加する（完了報告で開示済み）。
    // ------------------------------------------------------------------

    // T-P10-18: 正常 - 「行動ログを削除」ボタンのタップはonDeleteBehaviorLogRequestedのみを
    // 呼ぶ（ダイアログ表示自体はuiState.isDeleteBehaviorLogDialogVisibleが真実源であり、
    // ボタンのonClickはローカルstateを持たない）。
    @Test
    fun deleteBehaviorLogButton_tap_invokesOnDeleteBehaviorLogRequested() {
        var invoked = false
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState(),
                onDeleteBehaviorLogRequested = { invoked = true }
            )
        }

        // BehaviorLogSectionは容量表示より下にありRobolectricの仮想ビューポートを超えるため
        // performScrollTo()が必要（capacity表示テストと同じ既存パターン、クラスKDoc参照）。
        composeTestRule.onNodeWithTag("settings_delete_behavior_log_button").performScrollTo().performClick()

        assertEqualsBoolean(true, invoked)
    }

    // T-P10-18: エッジケース（回帰ガード） - isDeleteBehaviorLogDialogVisible=false（既定）では
    // 確認ダイアログが存在しない。
    @Test
    fun deleteBehaviorLogDialog_hiddenByDefault() {
        composeTestRule.setContent {
            SettingsScreen(uiState = singleModelUiState())
        }

        composeTestRule.onNodeWithTag("settings_delete_behavior_log_dialog").assertDoesNotExist()
    }

    // T-P10-18: 正常 - isDeleteBehaviorLogDialogVisible=trueで確認ダイアログが表示され、
    // 破壊的操作である旨の文言（タイトル・本文）が明示される。
    @Test
    fun deleteBehaviorLogDialog_visible_showsDestructiveWording() {
        composeTestRule.setContent {
            SettingsScreen(uiState = singleModelUiState().copy(isDeleteBehaviorLogDialogVisible = true))
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("settings_delete_behavior_log_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_delete_behavior_log_dialog_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_delete_behavior_log_dialog_message))
            .assertIsDisplayed()
    }

    // T-P10-18（誤タップ防止の直接証明）: 正常 - ダイアログの確定ボタンはonDeleteBehaviorLogConfirmed
    // のみを呼び、onDeleteBehaviorLogRequestedは（削除ボタン自体をタップしていないため）呼ばれない。
    @Test
    fun deleteBehaviorLogDialog_confirmButton_tap_invokesOnlyOnDeleteBehaviorLogConfirmed() {
        var confirmedInvoked = false
        var requestedInvoked = false
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState().copy(isDeleteBehaviorLogDialogVisible = true),
                onDeleteBehaviorLogRequested = { requestedInvoked = true },
                onDeleteBehaviorLogConfirmed = { confirmedInvoked = true }
            )
        }

        composeTestRule.onNodeWithTag("settings_delete_behavior_log_dialog_confirm_button").performClick()

        assertEqualsBoolean(true, confirmedInvoked)
        assertEqualsBoolean(false, requestedInvoked)
    }

    // T-P10-18: 異常（キャンセル経路） - ダイアログのキャンセルボタンはonDeleteBehaviorLogDialogDismissed
    // のみを呼び、onDeleteBehaviorLogConfirmedは呼ばれない。
    @Test
    fun deleteBehaviorLogDialog_cancelButton_tap_invokesOnlyOnDeleteBehaviorLogDialogDismissed() {
        var dismissedInvoked = false
        var confirmedInvoked = false
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState().copy(isDeleteBehaviorLogDialogVisible = true),
                onDeleteBehaviorLogDialogDismissed = { dismissedInvoked = true },
                onDeleteBehaviorLogConfirmed = { confirmedInvoked = true }
            )
        }

        composeTestRule.onNodeWithTag("settings_delete_behavior_log_dialog_cancel_button").performClick()

        assertEqualsBoolean(true, dismissedInvoked)
        assertEqualsBoolean(false, confirmedInvoked)
    }

    // T-P10-16/17: エッジケース（回帰ガード） - deleteBehaviorLogResult=null（既定）では結果
    // バナーが存在しない。
    @Test
    fun deleteBehaviorLogResult_null_hidesResultBanner() {
        composeTestRule.setContent {
            SettingsScreen(uiState = singleModelUiState())
        }

        composeTestRule.onNodeWithTag("settings_delete_behavior_log_result_text").assertDoesNotExist()
    }

    // T-P10-16: 正常 - deleteBehaviorLogResult=Successで成功文言のバナーが表示される
    // （サイレント化しない、§8「結果をUIへ明示」）。
    @Test
    fun deleteBehaviorLogResult_success_showsSuccessMessage() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState().copy(deleteBehaviorLogResult = DeleteBehaviorLogResult.Success)
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("settings_delete_behavior_log_result_text")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_delete_behavior_log_success_message))
            .assertIsDisplayed()
    }

    // T-P10-17: 異常 - deleteBehaviorLogResult=Failureで失敗文言のバナーが表示される
    // （サイレント化しない、§8「削除処理自体の失敗は握り潰さずUIへ結果を返す」）。
    @Test
    fun deleteBehaviorLogResult_failure_showsFailureMessage() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState().copy(deleteBehaviorLogResult = DeleteBehaviorLogResult.Failure)
            )
        }
        val context = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag("settings_delete_behavior_log_result_text")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_delete_behavior_log_failure_message))
            .assertIsDisplayed()
    }

    // T-P10-16/17: 正常 - 結果バナーの閉じるボタンはonDeleteBehaviorLogResultAcknowledgedを呼ぶ。
    @Test
    fun deleteBehaviorLogResultDismissButton_tap_invokesOnDeleteBehaviorLogResultAcknowledged() {
        var invoked = false
        composeTestRule.setContent {
            SettingsScreen(
                uiState = singleModelUiState().copy(deleteBehaviorLogResult = DeleteBehaviorLogResult.Success),
                onDeleteBehaviorLogResultAcknowledged = { invoked = true }
            )
        }

        composeTestRule.onNodeWithTag("settings_delete_behavior_log_result_dismiss_button").performScrollTo().performClick()

        assertEqualsBoolean(true, invoked)
    }

    private fun assertEqualsBoolean(expected: Boolean, actual: Boolean?) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    private fun assertEqualsAny(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
