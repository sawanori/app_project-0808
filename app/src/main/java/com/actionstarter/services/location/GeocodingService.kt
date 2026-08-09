package com.actionstarter.services.location

import com.actionstarter.domain.valueobject.Coordinate
import java.time.Duration

/**
 * F23実装（計画書§5.3）。目的地文字列（`ExecutionEvent`の自由記述位置）を座標へ解決する契約。
 *
 * 契約scaffold（P3-C1、TDD例外）時点では宣言のみであり、実装（[AndroidGeocodingService]）は
 * P3-C3で行う。
 */
interface GeocodingService {
    suspend fun geocode(locationName: String, timeout: Duration = DEFAULT_TIMEOUT): GeocodeResult

    companion object {
        // エラー＆レスキューマップ#14（計画書§10）「応答が返らない（タイムアウト）…既定10秒」に準拠。
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/**
 * [GeocodingService.geocode]の戻り値（計画書§5.3）。[NoMatch]を[Failure]から分離する理由:
 * `CalendarContract.EVENT_LOCATION`は自由記述であり、「渋谷区…」も「Zoom」も「会議室A」も
 * 同じ列に入る。geocode不能は多数派の正常系であり、これを`Failure`にすると
 * (a) retryすべきでないものをretryし、(b) UIが毎回エラーを出し、(c) §95.6の
 * 「retry 1回」規則が意味を失う（Phase 2 §4.5 / P-7申し送りへの回答）。
 */
sealed interface GeocodeResult {
    data class Success(val coordinate: Coordinate) : GeocodeResult

    /** 住所として解決できなかった（会議室名・"Zoom"・URL等）。異常ではない。retryしない・キャッシュする。 */
    data object NoMatch : GeocodeResult

    data class Failure(val reason: GeocodeFailureReason, val cause: Throwable?) : GeocodeResult
}

enum class GeocodeFailureReason { GEOCODER_UNAVAILABLE, NETWORK, TIMEOUT, INVALID_INPUT }
