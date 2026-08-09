package com.actionstarter.services.location

import com.actionstarter.domain.valueobject.Coordinate
import java.time.Duration
import java.time.Instant

/**
 * 仕様§43準拠（`Services`直下、名前のみ・シグネチャ未定義⇒ADR記録トリガー②）。
 * F22実装。単発の現在地fixを取得する契約（計画書§5.2）。
 *
 * 継続監視（`requestLocationUpdates`相当）は行わない（§58「常時監視を前提にしない」）。
 * 失敗表現を[LocationResult]の型で持つ（`Location?`直接返却では「取れなかった」
 * 「精度が悪い」「権限がない」が全部nullへ潰れ、§95.1が名指しで警告する
 * 「サイレントな位置取得失敗（SecurityException／null位置）」を防げないため）。
 *
 * 契約scaffold（P3-C1、TDD例外）時点では宣言のみであり、実装（[FusedLocationService]）は
 * P3-C3で行う。
 */
interface LocationService {
    suspend fun currentLocation(timeout: Duration = DEFAULT_TIMEOUT): LocationResult

    companion object {
        // エラー＆レスキューマップ#7（計画書§10）「タイムアウト（既定10秒）」に準拠。
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/**
 * [LocationService.currentLocation]の戻り値（計画書§5.2）。sealed interfaceにより、
 * 呼び出し側は`else`節なしの網羅`when`で分岐できる（サイレント障害の型による排除）。
 */
sealed interface LocationResult {
    data class Success(
        val coordinate: Coordinate,
        val accuracyMeters: Float?,
        val fixedAt: Instant
    ) : LocationResult

    data object PermissionDenied : LocationResult

    data class Failure(val reason: LocationFailureReason, val cause: Throwable?) : LocationResult
}

/** 計画書§5.2。現在地取得が失敗しうる理由の網羅（エラー＆レスキューマップ#5〜10に対応）。 */
enum class LocationFailureReason {
    /** エラー＆レスキューマップ#8: 端末の位置情報設定がOFF。 */
    LOCATION_DISABLED,

    /** エラー＆レスキューマップ#9: Google Play services不在・要更新。 */
    PLAY_SERVICES_UNAVAILABLE,

    /** エラー＆レスキューマップ#7: 既定10秒のタイムアウト超過。 */
    TIMEOUT,

    /** エラー＆レスキューマップ#6: fixがnull、または座標が不正。 */
    UNAVAILABLE,

    /** エラー＆レスキューマップ#10: §95.1 While-in-use制約。[ForegroundGate]が偽を返した。 */
    BACKGROUND_RESTRICTED
}
