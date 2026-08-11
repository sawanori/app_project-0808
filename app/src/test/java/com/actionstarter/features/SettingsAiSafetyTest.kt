package com.actionstarter.features

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.actionstarter.ai.AiFallbackReason
import com.actionstarter.ai.AiPreferencesImpl
import com.actionstarter.ai.LocalAiGateway
import com.actionstarter.ai.LocalLanguageModel
import com.actionstarter.ai.AiResult
import com.actionstarter.ai.SamplingPolicy
import com.actionstarter.ai.model.DeviceCapabilityImpl
import com.actionstarter.ai.model.ModelCatalogEntry
import com.actionstarter.ai.model.ModelLicense
import com.actionstarter.ai.model.ModelStorageImpl
import com.actionstarter.ai.model.ModelVerifierImpl
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.TransportMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * P7-C6（本体タスク指示「AI ON/OFFトグルの安全性」、計画書§8.6 #10・#11、T-GW-2・T-GW-3の
 * 実配線側からの回帰ロック）。
 *
 * [com.actionstarter.ai.LocalAiGateway]自体の5系統フォールバック網羅は`LocalAiGatewayTest`
 * （P7-C3〜C5、fake協力者、既に全件Green）が担う。本ファイルは**Settings画面が書き込む
 * 実[com.actionstarter.ai.AiPreferencesImpl]と実[com.actionstarter.ai.model.ModelStorageImpl]を
 * 使って**、「AI OFF（既定）ではローカルAIが一切起動せずBasic版で動作する」
 * ・「AI ON＋モデル未DLでもクラッシュせずBasicフォールバックする」の2点を、Settings実装が
 * 書き込む実コンポーネントの組み合わせで独立に再確認する（fakeの契約が正しくても実配線が
 * 間違っていれば意味がないため）。[LocalAiGateway]自体・`AiFallbackReason`は無変更（P7-C4/C5の
 * 既存実装をそのまま利用）。
 *
 * 実行画面へのAI実配線（Phase 8スコープ）はしない——本テストは`generatePlan`を直接呼ぶのみで、
 * どの画面からも呼び出し経路を配線しない。
 */
@RunWith(AndroidJUnit4::class)
class SettingsAiSafetyTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun activityManager(): ActivityManager =
        context().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** 端末は常に対応可能（RAM/ABIとも十分）にしておき、AI_DISABLED/MODEL_NOT_INSTALLEDの
     * 分岐だけを単独で検証できるようにする。 */
    private fun makeDeviceSupported() {
        val info = ActivityManager.MemoryInfo().apply {
            totalMem = 8L * 1024 * 1024 * 1024
            availMem = 8L * 1024 * 1024 * 1024
            threshold = 0L
            lowMemory = false
        }
        shadowOf(activityManager()).setMemoryInfo(info)
        ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a"))
    }

    private val neverInvokedEntry = ModelCatalogEntry(
        id = "settings-ai-safety-test-model",
        displayName = "Settings AI Safety Test Model",
        downloadUrl = "https://example.invalid/settings-ai-safety-test-model.litertlm",
        sha256 = "0".repeat(64),
        sizeBytes = 1_000_000L,
        peakRamBytes = 1L,
        contextLength = 1,
        quantization = "test",
        license = ModelLicense.APACHE_2_0,
        requiresNoticeFile = false
    )

    /** [LocalLanguageModel.generatePlan]／[LocalLanguageModel.generateRecovery]が
     * 1回でも呼ばれたらテストを即座に失敗させるガード（サイレントな誤り呼び出しの検出）。 */
    private val neverInvokedModel = object : LocalLanguageModel {
        override val modelIdentifier: String = "never-invoked"

        // Phase 8.5（計画書§3設計5、ADR-0062）: LocalLanguageModel.generatePlanへmodelPath引数が
        // 追加されたことに伴う機械的なシグネチャ追随のみ。本オブジェクトは「呼ばれてはいけない」
        // ことの検証専用のためロジック・アサーションは無変更（§2参照）。
        override suspend fun generatePlan(context: PlanningContext, modelPath: String, samplingPolicy: SamplingPolicy): String =
            error("generatePlan must never be invoked in this scenario (AI safety regression, P7-C6)")

        // Phase 9（計画書`docs/plans/phase9-recovery-ai.md`§3.2）: シグネチャの機械的追随のみ。
        // 本オブジェクトは「呼ばれてはいけない」ことの検証専用のためロジック・アサーションは
        // 無変更（クラスKDoc参照）。
        override suspend fun generateRecovery(
            context: RecoveryContext,
            options: List<RecoveryOption>,
            modelPath: String,
            samplingPolicy: SamplingPolicy
        ): String = error("generateRecovery must never be invoked in this scenario (AI safety regression, P7-C6)")
    }

    private fun samplePlanningContext(): PlanningContext = PlanningContext(
        event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "Settings AI safety test event",
            notes = null,
            startDate = Instant.parse("2026-08-10T09:00:00Z"),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "test", displayName = "Test Calendar")
        ),
        now = Instant.parse("2026-08-10T07:00:00Z"),
        zoneId = ZoneId.of("UTC"),
        locale = Locale.US,
        transportMode = TransportMode.WALKING,
        travelEstimate = Duration.ofMinutes(20),
        arrivalBuffer = Duration.ofMinutes(10),
        profile = null
    )

    // AI OFF（既定）: generatePlanはAI_DISABLEDへ即座にFallbackし、モデルは一切呼ばれない。
    @Test
    fun aiDisabled_default_generatePlan_neverInvokesModel_returnsAiDisabledFallback() = runTest {
        makeDeviceSupported()
        val preferences = AiPreferencesImpl(
            context().getSharedPreferences("settings_ai_safety_test_off", Context.MODE_PRIVATE)
        ).apply { context().getSharedPreferences("settings_ai_safety_test_off", Context.MODE_PRIVATE).edit().clear().commit() }
        // aiEnabledへ触れない = Settings画面が一度もトグルされていない初期状態(既定OFF)を再現。
        assertFalse("前提: aiEnabledは既定でfalseであるべきです(T-SET-1)", preferences.aiEnabled)

        val modelStorage = ModelStorageImpl(context(), catalog = listOf(neverInvokedEntry))
        val gateway = LocalAiGateway(
            model = neverInvokedModel,
            modelStorage = modelStorage,
            modelVerifier = ModelVerifierImpl(),
            deviceCapability = DeviceCapabilityImpl(context()),
            preferences = preferences
        )

        val result = gateway.generatePlan(samplePlanningContext())

        assertTrue(result is AiResult.Fallback)
        assertEquals(AiFallbackReason.AI_DISABLED, (result as AiResult.Fallback).reason)
    }

    // AI ON（Settingsでトグル済み）＋モデル未DL: クラッシュせずMODEL_NOT_INSTALLEDへFallbackし、
    // モデルは一切呼ばれない（Basicフォールバック、タスク指示「ON+モデル未DLでもクラッシュしない」）。
    @Test
    fun aiEnabled_modelNotInstalled_generatePlan_neverInvokesModel_returnsModelNotInstalledFallback_noCrash() = runTest {
        makeDeviceSupported()
        val prefsFileName = "settings_ai_safety_test_on_not_installed"
        context().getSharedPreferences(prefsFileName, Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = AiPreferencesImpl(context().getSharedPreferences(prefsFileName, Context.MODE_PRIVATE))
        // SettingsのAIトグルON操作を再現する。
        preferences.aiEnabled = true

        // モデル未DL: ModelStorageImplのfinalFileには何も置かない（neverInvokedEntryのファイルは
        // 実在しない）。
        val modelStorage = ModelStorageImpl(context(), catalog = listOf(neverInvokedEntry))
        val gateway = LocalAiGateway(
            model = neverInvokedModel,
            modelStorage = modelStorage,
            modelVerifier = ModelVerifierImpl(),
            deviceCapability = DeviceCapabilityImpl(context()),
            preferences = preferences
        )

        val result = gateway.generatePlan(samplePlanningContext())

        assertTrue("AI ON+モデル未DLでも例外を投げずAiResultで返るべきです(クラッシュしない)", result is AiResult.Fallback)
        assertEquals(AiFallbackReason.MODEL_NOT_INSTALLED, (result as AiResult.Fallback).reason)
    }
}
