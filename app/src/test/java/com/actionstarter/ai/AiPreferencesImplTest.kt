package com.actionstarter.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P7-C6（計画書§12.6 T-SET-1〜2、F92、ADR-0052/0048）。[AiPreferencesImpl]自身の直接テスト。
 *
 * P7-C3は`LocalAiGatewayTest`の全ケースをGreen化するための前倒し最小実装として
 * [AiPreferencesImpl]の本体を書いたが（同ファイルKDoc「P7-C3での最小実装」参照）、
 * [AiPreferencesImpl]自体を直接対象とするテストファイルはこれまで存在しなかった
 * （`LocalAiGatewayTest`は実装を借用するだけで契約自体は検証しない）。本ファイルがP7-C6で
 * その空白を埋める。
 *
 * **E2（Robolectric必須）**: [android.content.SharedPreferences]を使うため
 * `@RunWith(AndroidJUnit4::class)`（本プロジェクトの既存規約、`AppContainerTest`等と同型）。
 *
 * T-SET-2「プロセス再生成後も保持される」の再現方法: 同一`SharedPreferences`ファイル名を
 * バックエンドに持つ**2つの独立した`AiPreferencesImpl`インスタンス**を生成し、片方への
 * 書き込みがもう片方の読み取りに反映されることで「永続化」を検証する（プロセス再生成時に
 * `AppContainer`が新しい`AiPreferencesImpl`インスタンスを生成し直すのと同型の状況）。
 */
@RunWith(AndroidJUnit4::class)
class AiPreferencesImplTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun freshPreferences(fileName: String): AiPreferencesImpl {
        val prefs = context().getSharedPreferences(fileName, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return AiPreferencesImpl(prefs)
    }

    // T-SET-1: 正常系 - 初回起動時（未書き込み）aiEnabled == false（§19の既定OFF）。
    @Test
    fun aiEnabled_freshInstance_defaultsToFalse() {
        val preferences = freshPreferences("ai_preferences_test_1")

        assertFalse(
            "初回起動時のaiEnabledはfalse（既定OFF、§19）であるべきです（T-SET-1）",
            preferences.aiEnabled
        )
        assertEquals(AiPreferences.DEFAULT_AI_ENABLED, preferences.aiEnabled)
    }

    // T-SET-2: 正常系 - トグルONで永続化され、プロセス再生成後も保持される。
    @Test
    fun aiEnabled_setTrue_persistsAcrossNewInstanceBackedBySameSharedPreferences() {
        val fileName = "ai_preferences_test_2"
        val beforeRestart = freshPreferences(fileName)

        beforeRestart.aiEnabled = true

        // 「プロセス再生成」を、同一SharedPreferencesファイルを見る新規インスタンスの生成で模す。
        val afterRestart = AiPreferencesImpl(context().getSharedPreferences(fileName, Context.MODE_PRIVATE))

        assertTrue(
            "aiEnabled=trueは新規AiPreferencesImplインスタンス（プロセス再生成相当）でも" +
                "保持されているべきです（T-SET-2）",
            afterRestart.aiEnabled
        )
    }

    // T-SET-2エッジ: 異常系 - OFFへ戻した場合も同様に永続化される（ON固定の一方向バグ防止）。
    @Test
    fun aiEnabled_setBackToFalseAfterTrue_persistsFalse() {
        val fileName = "ai_preferences_test_3"
        val preferences = freshPreferences(fileName)
        preferences.aiEnabled = true

        preferences.aiEnabled = false
        val afterRestart = AiPreferencesImpl(context().getSharedPreferences(fileName, Context.MODE_PRIVATE))

        assertFalse(
            "aiEnabledをfalseへ戻した場合も永続化されているべきです",
            afterRestart.aiEnabled
        )
    }

    // Phase 8.5（既定モデル=auto、ADR-0062・計画書§3設計3・§11確認事項3）: 正常系 -
    // 初回起動時selectedModelIdはAUTO_SELECT_MODEL_IDセンチネルを返す。
    // 【期待値更新（P7-C6「既定=Gemma4-E2B」からの正当な契約変更、承認済み）】A54実機実測
    // （phase8-a54-ram-tier-fix.md§10.3）で日常使用の表記6GB機はGemma4を恒常的にロードできない
    // ことが判明したため、既定を空きメモリに応じた自動選択へ改訂した。
    @Test
    fun selectedModelId_freshInstance_defaultsToAutoSelectSentinel() {
        val preferences = freshPreferences("ai_preferences_test_4")

        assertEquals(
            "初回起動時のselectedModelIdは自動選択センチネルであるべきです" +
                "(Phase 8.5、ADR-0062でP7-C6「既定=Gemma4」から改訂)",
            AiPreferences.AUTO_SELECT_MODEL_ID,
            preferences.selectedModelId
        )
        assertEquals(AiPreferences.DEFAULT_SELECTED_MODEL_ID, preferences.selectedModelId)
    }

    // 正常系 - selectedModelIdを明示的に書き込んだ場合はその値が永続化される（既定値への
    // フォールバックが「未書き込み時のみ」であることの回帰ロック）。
    @Test
    fun selectedModelId_explicitlySet_persistsExplicitValue_notDefault() {
        val fileName = "ai_preferences_test_5"
        val preferences = freshPreferences(fileName)

        preferences.selectedModelId = "some-other-model-id"
        val afterRestart = AiPreferencesImpl(context().getSharedPreferences(fileName, Context.MODE_PRIVATE))

        assertEquals("some-other-model-id", afterRestart.selectedModelId)
    }
}
