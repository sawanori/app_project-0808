package com.actionstarter.domain

import com.actionstarter.domain.valueobject.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * T-DM-1〜T-DM-5（計画書§11.2 F1）。[Coordinate]の`init`不変条件（範囲検証・NaN拒否）を
 * 検証する。信頼境界（GPS・Geocoding API・カレンダー位置情報等の外部入力）に対応するための
 * 検証であり、Coordinate.ktのKDoc参照。
 *
 * 契約scaffold（C2）時点では[Coordinate]に`init`検証がないため、異常系ケースは現状
 * 例外が送出されず`assertThrows`自体が失敗する形でRedになる（意図した失敗。ADR-0010）。
 */
class CoordinateTest {

    // T-DM-1: Coordinate(35.6, 139.7)の生成が成功する
    @Test
    fun construct_validTokyoCoordinate_succeeds() {
        val coordinate = Coordinate(lat = 35.6, lon = 139.7)

        assertEquals(35.6, coordinate.lat, 0.0)
        assertEquals(139.7, coordinate.lon, 0.0)
    }

    // T-DM-2: lat=91で生成するとIllegalArgumentException
    @Test
    fun construct_latAbove90_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(lat = 91.0, lon = 0.0)
        }
    }

    // T-DM-2（ADR-0010追補・G1クロスレビューA2）: copy()実行時にも同じ検証が働く
    @Test
    fun copy_latAbove90_throwsIllegalArgumentException() {
        val valid = Coordinate(lat = 0.0, lon = 0.0)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(lat = 91.0)
        }
    }

    // T-DM-3: lon=181で生成するとIllegalArgumentException
    @Test
    fun construct_lonAbove180_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(lat = 0.0, lon = 181.0)
        }
    }

    // T-DM-3（ADR-0010追補）: copy()実行時にも同じ検証が働く
    @Test
    fun copy_lonAbove180_throwsIllegalArgumentException() {
        val valid = Coordinate(lat = 0.0, lon = 0.0)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(lon = 181.0)
        }
    }

    // T-DM-4: 境界値±90／±180ちょうどでは生成が成功する
    @Test
    fun construct_exactBoundaryValues_succeeds() {
        assertEquals(90.0, Coordinate(lat = 90.0, lon = 0.0).lat, 0.0)
        assertEquals(-90.0, Coordinate(lat = -90.0, lon = 0.0).lat, 0.0)
        assertEquals(180.0, Coordinate(lat = 0.0, lon = 180.0).lon, 0.0)
        assertEquals(-180.0, Coordinate(lat = 0.0, lon = -180.0).lon, 0.0)
    }

    // T-DM-4（ADR-0010追補）: copy()実行時の境界値でも成功する
    @Test
    fun copy_exactBoundaryValues_succeeds() {
        val valid = Coordinate(lat = 0.0, lon = 0.0)

        assertEquals(90.0, valid.copy(lat = 90.0).lat, 0.0)
        assertEquals(-180.0, valid.copy(lon = -180.0).lon, 0.0)
    }

    // T-DM-5: latがNaNだと例外
    @Test
    fun construct_latIsNaN_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(lat = Double.NaN, lon = 0.0)
        }
    }

    // T-DM-5: lonがNaNだと例外
    @Test
    fun construct_lonIsNaN_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(lat = 0.0, lon = Double.NaN)
        }
    }

    // T-DM-5（ADR-0010追補）: copy()実行時にも同じ検証が働く
    @Test
    fun copy_latIsNaN_throwsIllegalArgumentException() {
        val valid = Coordinate(lat = 0.0, lon = 0.0)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(lat = Double.NaN)
        }
    }
}
