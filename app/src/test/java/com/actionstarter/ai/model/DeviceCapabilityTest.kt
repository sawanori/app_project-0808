package com.actionstarter.ai.model

import android.app.ActivityManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild

/**
 * P7-C2（計画書§12.4・T-MDL-1〜3相当・F91）。[DeviceCapability]の失敗テスト（Red）。
 *
 * **計画書§12.1とのラベル不一致（Fable 5報告事項）**: 計画書§12.4は本ケース群を「E1」と
 * ラベルするが、[DeviceCapabilityImpl]のコンストラクタは`android.content.Context`を必須引数と
 * しており、これは計画書§12.1自身が定義する「E2: Robolectric（Context...）」の対象に該当する。
 * したがって本ファイルは実際には`@RunWith(RobolectricTestRunner::class)`のE2として実行する
 * （`:app:testDebugUnitTest`自体はE1/E2共通のGradleタスクであり実行手順自体に支障はないが、
 * §12.1/§12.4間のラベル不整合はP7-C2完了記録で報告する）。
 *
 * **P7契約確定での更新（Fable 5裁定5、2026-08-10、ADR-0048）**: [DeviceCapability]は具象
 * クラスからinterfaceへ変更され、実装が[DeviceCapabilityImpl]へ分離された。本ファイルは
 * 実装クラスを直接構築する（Robolectric実`Context`／実shadowで状態を制御する既存方針は
 * 無変更）ため、`DeviceCapability(context())`という construction 呼び出しを
 * `DeviceCapabilityImpl(context())`へ置き換えた以外はテストの意図・assertionを変更していない。
 *
 * `ActivityManager.MemoryInfo`（[setMemory]）・`Build.SUPPORTED_ABIS`（[setSupportedAbis]）は
 * いずれもRobolectricの標準shadow API（`Shadows.shadowOf(ActivityManager)`・`ShadowBuild`）で
 * 制御する。Context7（`/websites/robolectric`）で`ShadowActivityManager.setMemoryInfo`・
 * `ShadowBuild.setSupportedAbis`の実在を確認済み。
 *
 * [DeviceCapability.classify]／[DeviceCapability.isAbiSupported]／
 * [DeviceCapability.hasAvailableMemory]の本体はいずれも`TODO()`のため、以下は全件
 * `NotImplementedError`によりRedになるのが正しい。
 */
@RunWith(RobolectricTestRunner::class)
class DeviceCapabilityTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun activityManager(): ActivityManager =
        context().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private fun setMemory(totalMemBytes: Long, availMemBytes: Long) {
        val info = ActivityManager.MemoryInfo().apply {
            totalMem = totalMemBytes
            availMem = availMemBytes
            threshold = 0L
            lowMemory = false
        }
        shadowOf(activityManager()).setMemoryInfo(info)
    }

    private fun setSupportedAbis(vararg abis: String) {
        ShadowBuild.setSupportedAbis(abis)
    }

    companion object {
        private const val GB = 1024L * 1024 * 1024
    }

    // T-MDL-1相当: 正常系 - totalMemから正しい推奨段を返す（§5.3段1: 6GB以上8GB未満）
    @Test
    fun classify_sixGbTotalMem_returnsTier1Standard() {
        setMemory(totalMemBytes = 6L * GB, availMemBytes = 6L * GB)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_1_STANDARD, tier)
    }

    // T-MDL-2相当: エッジ - 段0/段1の境界値ちょうど（6GB未満は段0、6GB以上は段1）
    @Test
    fun classify_justUnderSixGb_returnsTier0Unsupported() {
        setMemory(totalMemBytes = 6L * GB - 1, availMemBytes = 6L * GB - 1)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_0_UNSUPPORTED, tier)
    }

    // T-MDL-2相当（追加境界）: 段1/段2の境界値（8GB以上はTIER_2_OPT_IN対象の下限）
    @Test
    fun classify_eightGbTotalMem_returnsTier2OptIn() {
        setMemory(totalMemBytes = 8L * GB, availMemBytes = 8L * GB)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_2_OPT_IN, tier)
    }

    // T-MDL-3相当: 異常系 - SUPPORTED_ABISにarm64-v8aがない → 非対応
    @Test
    fun isAbiSupported_noArm64_returnsFalse() {
        setSupportedAbis("armeabi-v7a", "armeabi")

        val supported = DeviceCapabilityImpl(context()).isAbiSupported()

        assertFalse(supported)
    }

    // T-MDL-3相当（対照ケース）: 正常系 - SUPPORTED_ABISにarm64-v8aがある → 対応
    @Test
    fun isAbiSupported_arm64Present_returnsTrue() {
        setSupportedAbis("arm64-v8a", "armeabi-v7a")

        val supported = DeviceCapabilityImpl(context()).isAbiSupported()

        assertTrue(supported)
    }

    // T-GW-5/9のfake化基盤: hasAvailableMemoryがavailMemベースで判定される（§8.6 #7の主防御、
    // Gemini G1 CRITICAL #3）
    @Test
    fun hasAvailableMemory_availMemBelowRequired_returnsFalse() {
        setMemory(totalMemBytes = 8L * GB, availMemBytes = 500L * 1024 * 1024) // 500MB空き

        val result = DeviceCapabilityImpl(context()).hasAvailableMemory(requiredBytes = 3L * GB)

        assertFalse(result)
    }

    @Test
    fun hasAvailableMemory_availMemAboveRequired_returnsTrue() {
        setMemory(totalMemBytes = 8L * GB, availMemBytes = 4L * GB)

        val result = DeviceCapabilityImpl(context()).hasAvailableMemory(requiredBytes = 3L * GB)

        assertTrue(result)
    }
}
