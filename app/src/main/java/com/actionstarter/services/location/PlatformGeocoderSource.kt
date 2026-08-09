package com.actionstarter.services.location

import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [GeocoderSource]のAndroid実装（L3、計画書§6.1・§7.1）。
 * API 33以降は非同期版`getFromLocationName(String, Int, GeocodeListener)`
 * （`api-versions.xml`実測で`since="33"`）、API 26〜32は同期版
 * `getFromLocationName(String, Int)`（`deprecated="33"`だが削除はされていない。
 * `@Suppress("DEPRECATION")`理由コメント付きで使用）を`Build.VERSION.SDK_INT >=
 * Build.VERSION_CODES.TIRAMISU`で分岐する。`isAvailable()`は`Geocoder.isPresent()`
 * （`since="9"`）に委譲する。
 *
 * P3-C3で実装済み（計画書§9.4 T-GEO-9：`@Config(sdk = [26, 33])`で両パスが同じ`Success`へ
 * 収束することを実測確認済み。android.jar（compileSdk 35）をjavapで実測し
 * `Geocoder.GeocodeListener`が`onGeocode(List<Address>)`（abstract）と`onError(String)`
 * （default）の2メソッドであることを確認した上で、`onError`を明示オーバーライドし
 * [IOException]で`resumeWithException`する（Kotlin SAMラムダに委ねてdefault実装
 * （内部的に空リストへ縮退し得る）に頼ると、API 33+経路のみエラーが`NoMatch`相当へ
 * サイレントに縮退し26〜32同期経路の`IOException`透過と非対称になるため）。
 * `suspendCancellableCoroutine`はコールバックが一度も呼ばれない経路が万一あっても
 * 外部からのcoroutineキャンセルで中断できる。
 */
class PlatformGeocoderSource(
    private val geocoder: Geocoder
) : GeocoderSource {
    override fun isAvailable(): Boolean = Geocoder.isPresent()

    override suspend fun lookup(query: String, maxResults: Int, timeout: Duration): List<LocationFix> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lookupAsync(query, maxResults)
        } else {
            lookupSync(query, maxResults)
        }

    // P3-C7（lintDebug NewApi対応）: 呼び出し元lookup()がBuild.VERSION.SDK_INT >=
    // TIRAMISUを確認した場合のみ本メソッドへ分岐する（既存のSDK_INT分岐ロジックは不変）。
    // lintのAPIレベル検証は分岐元と呼び出し先が別メソッドだと追跡できないため、実際の
    // 呼び出し条件と一致するAPIレベルをアノテーションで明示する（ロジック変更なし）。
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun lookupAsync(query: String, maxResults: Int): List<LocationFix> =
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocationName(
                query,
                maxResults,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) {
                            continuation.resume(addresses.toLocationFixes())
                        }
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException(errorMessage ?: "Geocoder lookup failed")
                            )
                        }
                    }
                }
            )
        }

    // API 26〜32のみが通る経路（呼び出し元でSDK_INT分岐済み）。同期版は削除されておらず
    // 動作するが、Android SDKがAPI 33でdeprecated指定したためlint抑止に理由を明記する。
    @Suppress("DEPRECATION")
    private fun lookupSync(query: String, maxResults: Int): List<LocationFix> {
        val addresses = geocoder.getFromLocationName(query, maxResults)
        return addresses.orEmpty().toLocationFixes()
    }

    private fun List<Address>.toLocationFixes(): List<LocationFix> = map { address ->
        LocationFix(
            lat = address.latitude,
            lon = address.longitude,
            accuracyMeters = null,
            fixedAt = Instant.now()
        )
    }
}
