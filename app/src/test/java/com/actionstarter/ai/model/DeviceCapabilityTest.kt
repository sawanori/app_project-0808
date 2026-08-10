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

    // T-DCT-11[既存期待値更新]: エッジ - 6GiB-1は新契約（5GiB/7GiB境界）では境界外の一点であり
    // TIER_1_STANDARDが正しい（旧閾値6GBでの境界=TIER_0_UNSUPPORTEDから変更。
    // メソッド名もreturnsTier0Unsupported→returnsTier1Standardへ改名、計画書§5参照）
    @Test
    fun classify_justUnderSixGb_returnsTier1Standard() {
        setMemory(totalMemBytes = 6L * GB - 1, availMemBytes = 6L * GB - 1)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_1_STANDARD, tier)
    }

    // T-MDL-2相当（追加境界）: 段1/段2の境界値（8GB以上はTIER_2_OPT_IN対象の下限）
    @Test
    fun classify_eightGbTotalMem_returnsTier2OptIn() {
        setMemory(totalMemBytes = 8L * GB, availMemBytes = 8L * GB)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_2_OPT_IN, tier)
    }

    // T-DCT-1[Red・主目的ケース]: 正常系 - A54実測相当のtotalMem(5.5GiB)はTIER_1_STANDARD
    // （旧閾値6GBでは非対応(TIER_0)に落ちるため失敗するのが正しい。計画書§5・§0参照）
    @Test
    fun classify_fivePointFiveGbTotalMem_returnsTier1Standard() {
        val totalMem = 5L * GB + GB / 2 // 5.5GiB = 5,905,580,032B（A54実測相当）
        setMemory(totalMemBytes = totalMem, availMemBytes = totalMem)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_1_STANDARD, tier)
    }

    // T-DCT-2[Red]: 正常系 - 表記8GB実測相当のtotalMem(7.4GiB)はTIER_2_OPT_IN
    @Test
    fun classify_sevenPointFourGbTotalMem_returnsTier2OptIn() {
        val totalMem = 7_945_689_498L // 7.4GiB相当（表記8GB実測相当）
        setMemory(totalMemBytes = totalMem, availMemBytes = totalMem)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_2_OPT_IN, tier)
    }

    // T-DCT-5[born-green]: 異常系 - 表記4GB実測相当のtotalMem(3.6GiB)を誤ってTIER_1へ
    // 受け入れない（誤受け入れ防止）
    @Test
    fun classify_threePointSixGbTotalMem_returnsTier0Unsupported() {
        val totalMem = 3_865_470_566L // 3.6GiB相当（表記4GB実測相当）
        setMemory(totalMemBytes = totalMem, availMemBytes = totalMem)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_0_UNSUPPORTED, tier)
    }

    // T-DCT-6[born-green]: 異常系 - totalMem=0（未取得・異常値の代表）でも安全側の
    // TIER_0_UNSUPPORTEDを返す
    @Test
    fun classify_zeroTotalMem_returnsTier0Unsupported() {
        setMemory(totalMemBytes = 0L, availMemBytes = 0L)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_0_UNSUPPORTED, tier)
    }

    // T-DCT-7[Red]: エッジ - 新下限境界ちょうど(5GiB)はTIER_1_STANDARD（以上で段1）
    @Test
    fun classify_fiveGbTotalMem_returnsTier1Standard() {
        setMemory(totalMemBytes = 5L * GB, availMemBytes = 5L * GB)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_1_STANDARD, tier)
    }

    // T-DCT-8[born-green]: エッジ - 新下限境界の下側(5GiB-1)はTIER_0_UNSUPPORTEDのまま
    @Test
    fun classify_justUnderFiveGb_returnsTier0Unsupported() {
        setMemory(totalMemBytes = 5L * GB - 1, availMemBytes = 5L * GB - 1)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_0_UNSUPPORTED, tier)
    }

    // T-DCT-9[Red]: エッジ - 新段2下限境界ちょうど(7GiB)はTIER_2_OPT_IN
    @Test
    fun classify_sevenGbTotalMem_returnsTier2OptIn() {
        setMemory(totalMemBytes = 7L * GB, availMemBytes = 7L * GB)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_2_OPT_IN, tier)
    }

    // T-DCT-10[born-green]: エッジ - 新段2境界の下側(7GiB-1)はTIER_1_STANDARDのまま
    @Test
    fun classify_justUnderSevenGb_returnsTier1Standard() {
        setMemory(totalMemBytes = 7L * GB - 1, availMemBytes = 7L * GB - 1)

        val tier = DeviceCapabilityImpl(context()).classify()

        assertEquals(DeviceTier.TIER_1_STANDARD, tier)
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
