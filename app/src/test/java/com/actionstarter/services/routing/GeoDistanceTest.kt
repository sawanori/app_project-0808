package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-GEODIST-1〜3（計画書§9.2）。対象は[GeoDistance]（L1純粋関数、Haversine距離）。
 * 現状（P3-C1契約scaffold）は[GeoDistance.meters]の本文が`TODO("P3-C4で実装")`のため、
 * 以下は全件`NotImplementedError`によりRedになる（意図した失敗）。
 *
 * 期待値は「同一経度・緯度差のみ」という特殊配置（[meridianOffset]）を使うことで手計算
 * 誤差を避けている。この配置ではΔλ=0のためHaversine公式のsin²(Δλ/2)項が厳密に0となり、
 * 弧長が d = R × Δφ(rad) という恒等式へ厳密に単純化される（近似ではなく数学的に厳密。
 * asin(sin(x)) = x が主値域で成立するため）。
 *
 * 地球半径Rの具体的な値は計画書が指定していないため、Haversine実装で最も一般的な
 * R=6371000m（平均半径）を仮定して期待値を算出し、T-GEODIST-1/3は計画書§9.2が明記する
 * 許容誤差±0.5%で検証する（WGS84長半径6378137m等の他の一般的なR選択でも差は約0.11%で
 * あり、±0.5%許容誤差内に収まる。ただしP3-C4がこの範囲を超えて大きく異なるRを採用した
 * 場合は本テストの期待値の見直しが必要になる可能性がある。P3-C2完了報告で申し送り）。
 */
class GeoDistanceTest {

    // T-GEODIST-1: 正常 - 既知の2点間距離が許容誤差（±0.5%）内
    @Test
    fun meters_betweenTwoKnownPoints_isWithinHalfPercentTolerance() {
        val a = Coordinate(lat = 35.0, lon = 139.0)
        val expectedMeters = 111_195.0 // 約1度の緯度差（同経度）
        val b = meridianOffset(a, meters = expectedMeters)

        val result = GeoDistance.meters(a, b)

        assertWithinTolerancePercent(expectedMeters, result, tolerancePercent = 0.5)
    }

    // T-GEODIST-2: エッジ - 同一座標 → 0m（NaNにならない）
    @Test
    fun meters_betweenIdenticalCoordinates_isZeroNotNaN() {
        val point = Coordinate(lat = 35.6812, lon = 139.7671)

        val result = GeoDistance.meters(point, point)

        assertFalse("distance between identical coordinates must not be NaN", result.isNaN())
        assertEquals(0.0, result, 1e-6)
    }

    // T-GEODIST-3: エッジ - 経度180度線を跨ぐ2点で最短距離（地球一周側にならない）
    @Test
    fun meters_acrossAntimeridian_takesShortestPathNotTheLongWayAround() {
        val a = Coordinate(lat = 0.0, lon = 179.5)
        val b = Coordinate(lat = 0.0, lon = -179.5) // aから東へ経度1度分（180度線を跨ぐ）
        val expectedMeters = 111_195.0 // 赤道上の経度1度分はlat=0のため緯度1度分と同じ弧長

        val result = GeoDistance.meters(a, b)

        // 正しい最短距離は約111,195mだが、誤って地球を反対回りに計算すると
        // 経度359度分＝約39,942kmになる。200km未満という緩い上限だけでも
        // 「長い方を回っていない」ことを明確に検知できる（tolerance検証と二重に固定する）。
        assertTrue(
            "expected the shortest path across the antimeridian, but got ${result}m",
            result < 200_000.0
        )
        assertWithinTolerancePercent(expectedMeters, result, tolerancePercent = 0.5)
    }

    private companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /** [from]と同経度のまま、北へ[meters]分だけ緯度をずらした座標を返す（クラスKDoc参照）。 */
        fun meridianOffset(from: Coordinate, meters: Double): Coordinate {
            val deltaLatDegrees = Math.toDegrees(meters / EARTH_RADIUS_METERS)
            return Coordinate(lat = from.lat + deltaLatDegrees, lon = from.lon)
        }

        fun assertWithinTolerancePercent(expectedMeters: Double, actualMeters: Double, tolerancePercent: Double) {
            val allowedDelta = expectedMeters * (tolerancePercent / 100.0)
            assertEquals(expectedMeters, actualMeters, allowedDelta)
        }
    }
}
