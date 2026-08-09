package com.actionstarter.services.location

import android.annotation.SuppressLint
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [RawLocationSource]のPlay Services実装（L3、計画書§6.1）。
 * `FusedLocationProviderClient.getCurrentLocation(CurrentLocationRequest, CancellationToken):
 * Task<Location>`を`suspendCancellableCoroutine`で包む（計画書§7.1実測。
 * `CurrentLocationRequest.Builder`／`Priority.PRIORITY_BALANCED_POWER_ACCURACY`が
 * play-services-location 21.4.0のclasses.jarに実在することをjavapで実測確認済み）。
 * ロジックを持たない（テストはinstrumentedのみ、計画書§6.1。本クラスは22件のJVM契約
 * テスト対象外だが、P3-C3のファイルフットプリント上はdomain-implementer A所管のため
 * 同時にTODOを解消する）。
 *
 * P3-C3で実装済み: `CancellationTokenSource`を`invokeOnCancellation`でPlay Services側の
 * `Task`にも伝播させる（coroutineキャンセル時にFused側のリクエストも中断させ、無駄な
 * 測位を継続させない）。`getCurrentLocation`が返す`Location`がnullの場合は
 * `LocationFix`へ変換せず`null`をそのまま返す（`FusedLocationService`側がnull→
 * `UNAVAILABLE`へ写像する契約、T-LOCSVC-4）。`Task`の失敗（`addOnFailureListener`）は
 * 例外として透過させる（`FusedLocationService`側で`SecurityException`のみ捕捉し
 * `PermissionDenied`へ写像、それ以外は呼び出し元へ伝播する契約）。
 */
class FusedRawLocationSource(
    private val client: FusedLocationProviderClient
) : RawLocationSource {
    // P3-C7（lintDebug MissingPermission対応）: ACCESS_FINE_LOCATIONの許否判定は
    // 呼び出し元のFusedLocationService（L2、PermissionGate経由）が本メソッド呼び出し前に
    // 完了させる契約であり（クラスKDoc・FusedLocationService.currentLocation参照）、
    // 未許可でここへ到達することはない。SecurityExceptionが飛んだ場合もL2側catch節で
    // 捕捉される（同KDoc参照）。lintはこの呼び出し元境界を跨いだ検証ができないため
    // 抑止する（ロジック変更なし・マーカーアノテーションのみ）。
    @SuppressLint("MissingPermission")
    override suspend fun currentFix(timeout: Duration): LocationFix? =
        suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellationTokenSource.cancel() }

            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(timeout.toMillis())
                .build()

            client.getCurrentLocation(request, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    if (continuation.isActive) {
                        val fix = location?.let {
                            LocationFix(
                                lat = it.latitude,
                                lon = it.longitude,
                                accuracyMeters = if (it.hasAccuracy()) it.accuracy else null,
                                fixedAt = Instant.ofEpochMilli(it.time)
                            )
                        }
                        continuation.resume(fix)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }
}
