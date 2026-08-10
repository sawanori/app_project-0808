package com.actionstarter.ai.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * F91契約（計画書§7.1・§5.3・§8.2・§8.6 #1・#2・#7・§14 P7-C1／P7契約確定）。端末対応可否と
 * モデル段の判定interface。静的判定（[classify]・[isAbiSupported]、totalMem／ABIベース）と、
 * 動的判定（[hasAvailableMemory]、その時点の空きメモリベース）の両方を担う。
 *
 * **interface化（Fable 5裁定5、2026-08-10、ADR-0048）**: P7-C2完了記録の差し戻し事項4
 * （「本プロジェクトの他のDI境界〔`LocalLanguageModel`等〕がinterfaceであるのに対し、本型は
 * 具象クラスでfake差し替えできない」）への回答として、具象クラスから本interfaceへ変更した。
 * 実装は[DeviceCapabilityImpl]（同ファイル）へ分離する。既存の他のDI境界
 * （`CalendarService`／`RoutingService`等）と同じ「interfaceでfake差し替え可能」という
 * 本プロジェクトの規約に合わせ、テスト容易性を確保する。
 *
 * **動的チェックを本interfaceへ置く理由**: [com.actionstarter.ai.LocalAiGateway]の§8.6 #7
 * （OOM事前ガード・主防御、Gemini G1 CRITICAL #3）が要求する
 * `ActivityManager.getMemoryInfo().availMem`確認は、[classify]と同じく`ActivityManager`／
 * `Context`ベースの端末状態問い合わせであり、テスト時は「fakeのメモリ情報プロバイダ」
 * （計画書§12.5 T-GW-5）に差し替え可能である必要がある点も静的判定と共通するため、同一型
 * へ統合する（新規の別インタフェースを設けない）。
 *
 * **段の対応（§5.3）**: [DeviceTier.TIER_0_UNSUPPORTED] = 6GB未満（§95.3、Local AI対象外）。
 * [DeviceTier.TIER_1_STANDARD] = 6GB以上（既定、Qwen3-0.6B級）。
 * [DeviceTier.TIER_2_OPT_IN] = 8GB以上かつ実機プローブ確認済み（オプトイン、より大きいモデル）。
 * 段3（4B級）はGalaxy Aクラスでは対象外のため本enumに含めない（§5.3明記）。モデル名を
 * [DeviceTier]の値名に含めない（§17「モデル名を製品仕様として固定しない」。段とモデルの
 * 対応表自体は[ModelCatalog]側・将来のSettings実装〔F97〕が持つ）。
 *
 * 契約scaffold（TDD厳守）時点では[DeviceCapabilityImpl]の各メソッド本体は宣言のみであり、
 * 実装本体はP7-C4（[classify]・[isAbiSupported]、T-MDL-1〜3）およびP7-C5
 * （[hasAvailableMemory]、T-GW-5・T-GW-9）で行う。
 */
interface DeviceCapability {
    /** `totalMem`ベースの段階判定（§5.3）。 */
    fun classify(): DeviceTier

    /** `Build.SUPPORTED_ABIS`に`arm64-v8a`を含むか（§8.2・T-MDL-3）。 */
    fun isAbiSupported(): Boolean

    /**
     * その時点で[requiredBytes]（安全マージン込みの必要ピークRAM）以上の空きメモリがあるか
     * （§8.6 #7、`ActivityManager.getMemoryInfo().availMem`ベース）。
     */
    fun hasAvailableMemory(requiredBytes: Long): Boolean

    companion object {
        /** §95.3・§5.3段0上限。この値未満はLocal AI対象外。 */
        const val TIER_0_MAX_TOTAL_MEM_BYTES: Long = 6L * 1024 * 1024 * 1024

        /** §5.3段2下限（オプトイン条件の一部。実機プローブ確認と併せて判定）。 */
        const val TIER_2_MIN_TOTAL_MEM_BYTES: Long = 8L * 1024 * 1024 * 1024
    }
}

/** [DeviceCapability.classify]の戻り値（§5.3の段0〜段2に対応）。 */
enum class DeviceTier {
    TIER_0_UNSUPPORTED,
    TIER_1_STANDARD,
    TIER_2_OPT_IN
}

/**
 * [DeviceCapability]の実装（Fable 5裁定5、ADR-0048）。`ActivityManager`／`Build`ベースの
 * 実端末状態を参照する。テストでは本クラスをRobolectric shadow経由で使うか、
 * [DeviceCapability]interfaceをfake実装で差し替える。
 *
 * **P7-C3実装済み（Green）**: `totalMem`ベースの段判定は[DeviceCapability.
 * TIER_0_MAX_TOTAL_MEM_BYTES]／[DeviceCapability.TIER_2_MIN_TOTAL_MEM_BYTES]の2閾値で
 * 3区分に振り分ける（T-MDL-1〜2。境界値は「未満／以上」で判定し、ちょうど6GBは段1・
 * ちょうど8GBは段2）。[hasAvailableMemory]は`ActivityManager.MemoryInfo.availMem`と
 * [requiredBytes]の単純比較（`>=`で合格）であり、判定自体に副作用はない
 * （T-GW-5・T-GW-9のfake化基盤。§8.6 #7の事前ガードが呼び出す）。
 *
 * @param context `ActivityManager`取得に用いる`applicationContext`。
 */
class DeviceCapabilityImpl(private val context: Context) : DeviceCapability {

    override fun classify(): DeviceTier {
        val totalMem = currentMemoryInfo().totalMem
        return when {
            totalMem < DeviceCapability.TIER_0_MAX_TOTAL_MEM_BYTES -> DeviceTier.TIER_0_UNSUPPORTED
            totalMem >= DeviceCapability.TIER_2_MIN_TOTAL_MEM_BYTES -> DeviceTier.TIER_2_OPT_IN
            else -> DeviceTier.TIER_1_STANDARD
        }
    }

    override fun isAbiSupported(): Boolean = REQUIRED_ABI in Build.SUPPORTED_ABIS

    override fun hasAvailableMemory(requiredBytes: Long): Boolean =
        currentMemoryInfo().availMem >= requiredBytes

    private fun currentMemoryInfo(): ActivityManager.MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info
    }

    companion object {
        /** §8.2「armeabi-v7a非対応」。AAR 0.15.0が同梱する2ABIの1つ（V-1実測）。 */
        private const val REQUIRED_ABI: String = "arm64-v8a"
    }
}
