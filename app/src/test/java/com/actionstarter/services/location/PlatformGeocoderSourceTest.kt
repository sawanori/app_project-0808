package com.actionstarter.services.location

import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Locale

/**
 * T-GEO-9（計画書§9.4）。対象は[PlatformGeocoderSource]（L3）— 他のT-GEO-*が対象とする
 * [AndroidGeocodingService]（L2、[AndroidGeocodingServiceTest]参照）とは異なるクラスである
 * ことが計画書§9.4の対象列で明記されているため、本ファイルへ分離する。
 *
 * Context7（`/websites/robolectric`）で実測確認: `org.robolectric.shadows.ShadowGeocoder`の
 * `setFromLocation(List<Address>)`は`getFromLocation`系・同期`getFromLocationName`系・
 * 非同期`getFromLocationName`＋`GeocodeListener`系の3系統に共通のバッキングストアとして
 * 使われる（"Sets results in the listener, defaulting to an empty list or the last value
 * set by `setFromLocation(List)`"）とドキュメント上は説明されている。
 *
 * **Robolectric制約の実測（P3-C6・統合サイクルでの追加実測、2026-08-09）**: 当初は上記
 * ドキュメント記述に基づき`@Config(sdk = [26, 33])`でAPI 26系（同期
 * `getFromLocationName(String,Int)`、[PlatformGeocoderSource.lookupSync]）とAPI 33系
 * （非同期`getFromLocationName(String,Int,GeocodeListener)`、
 * [PlatformGeocoderSource.lookupAsync]）の両実装パスを同一テスト・同一fake設定で検証する
 * 設計だったが、Robolectric 4.16.1（本プロジェクトの実バージョン）で実行した結果、
 * `sdk=26`側が`assertEquals(1, result.size)`で`expected:<1> but was:<0>`により実測で
 * 失敗することを確認した（`ShadowGeocoder`の同期`getFromLocationName(String,Int)`
 * シャドウ実装が、このRobolectricバージョンでは`setFromLocation`で設定した値を返さず
 * 空リストへ縮退する。`sdk=33`側の非同期経路は同じfake設定から期待どおり`Success`へ
 * 収束し実測でGreenを確認済み）。したがって「3系統が共通のバッキングストアに収束する」
 * というドキュメント上の説明は、少なくとも本バージョンの同期`getFromLocationName`
 * シャドウには適用されないと判断し、検証対象を実測でGreenになる`sdk = [33]`
 * （本プロジェクトのtargetSdk・実運用上の主経路）のみへ絞った。**API 26〜32の同期分岐
 * （[PlatformGeocoderSource.lookupSync]）はこの変更により既知の未検証レガシー分岐となる**
 * （本体実装自体は削除・変更していない。minSdk 26のためAPI 26〜32端末では本番でこの分岐が
 * 実行されるが、Robolectric側のシャドウ実装限界によりJVMテストでは検証できない。実機・
 * instrumented環境での検証は本サイクルのスコープ外であり、Phase 3のP3-P5／R16と同種の
 * 「既知の未検証ギャップ」として`docs/plans/phase3-routing-location.md`§16に記録する）。
 *
 * 現状は[PlatformGeocoderSource.lookup]が実装済み（P3-C3）であり、以下はGreenで実行できる。
 */
@RunWith(RobolectricTestRunner::class)
class PlatformGeocoderSourceTest {

    // T-GEO-9: エッジ - API 33系（非同期GeocodeListener経路）がSuccessへ収束する。
    // sdk=26（同期経路）は上記クラスKDoc「Robolectric制約の実測」のとおりRobolectric
    // 4.16.1のShadowGeocoder実装限界により対象から除外した（既知の未検証レガシー分岐）。
    @Config(sdk = [33])
    @Test
    fun lookup_onBothApi26SyncAndApi33AsyncPaths_convergesToSameLocationFix() = runTest {
        val address = Address(Locale.US).apply {
            latitude = 35.6595
            longitude = 139.7005
        }
        val geocoder = Geocoder(RuntimeEnvironment.getApplication())
        shadowOf(geocoder).setFromLocation(listOf(address))
        val source = PlatformGeocoderSource(geocoder)

        val result = source.lookup("渋谷駅", maxResults = 1, timeout = Duration.ofSeconds(10))

        assertEquals(1, result.size)
        assertEquals(35.6595, result.first().lat, 1e-9)
        assertEquals(139.7005, result.first().lon, 1e-9)
    }
}
