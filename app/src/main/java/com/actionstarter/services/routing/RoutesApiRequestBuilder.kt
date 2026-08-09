package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.TransportMode
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * L1純粋関数（F24、計画書§6.1・§7.2 P3-P6）。ComputeRoutesリクエストJSON生成
 * （引数は[Coordinate]・[TransportMode]・[Instant]のみに型で限定し、`ExecutionEvent`を
 * 渡せない設計にする。§58/§60/§95.2、エラー＆レスキューマップ#21）。
 *
 * **P3-P6実測確定内容（2026-08-09、Context7で該当ライブラリなし→公式ドキュメント
 * `developers.google.com/maps/documentation/routes/` 配下の各ページをWebFetchで実測。
 * 出典は各項目末尾）**:
 * - エンドポイント: `POST https://routes.googleapis.com/directions/v2:computeRoutes`
 *   （[developers.google.com/maps/documentation/routes/compute_route_directions](
 *   https://developers.google.com/maps/documentation/routes/compute_route_directions)）
 * - 認証ヘッダ: `X-Goog-Api-Key: <key>`（同上）
 * - `X-Goog-FieldMask`ヘッダは**必須**（未指定時はAPIが拒否する旨がドキュメントに明記。
 *   ただし拒否時の具体的なHTTPステータス／エラーボディの文字列は本セッションでは
 *   未確認＝未検証）。duration値のみ必要な場合の最小値は`routes.duration`
 *   （[…/reference/rest/v2/TopLevel/computeRoutes](
 *   https://developers.google.com/maps/documentation/routes/reference/rest/v2/TopLevel/computeRoutes)）
 * - body: `origin.location.latLng.latitude`／`origin.location.latLng.longitude`、
 *   `destination`も同型（`Waypoint`→`Location`→`LatLng`）。`travelMode`・
 *   `departureTime`・`routingPreference`は`origin`/`destination`と同階層の
 *   トップレベルフィールド（同上）
 * - `departureTime`はRFC3339タイムスタンプ文字列（例: `"2014-10-02T15:01:23Z"`、同上）
 * - `travelMode`（型`RouteTravelMode`）の実際の列挙値: `TRAVEL_MODE_UNSPECIFIED`
 *   （既定=DRIVE）／`DRIVE`／`BICYCLE`／`WALK`／`TWO_WHEELER`／`TRANSIT`。
 *   **`DRIVING`／`WALKING`／`CYCLING`という綴りは存在しない**（
 *   […/reference/rest/v2/RouteTravelMode](
 *   https://developers.google.com/maps/documentation/routes/reference/rest/v2/RouteTravelMode)）。
 *   →本プロジェクトの[TransportMode]｛WALKING, DRIVING, TRANSIT, CYCLING｝は
 *   ｛WALK, DRIVE, TRANSIT, BICYCLE｝へ写像する実装が必要（T-ROUTEREQ-2で漏れなく検証）
 * - `routingPreference: TRAFFIC_AWARE`はtravelModeがDRIVEまたはTWO_WHEELERの場合のみ
 *   指定可（それ以外は失敗する旨がドキュメントに明記、同上）。~~**MVPでは指定しない**方針
 *   （§95.2コスト方針、計画書§7.2）に変更なし~~ → **P3-C9でこの方針を修正（Fable 5裁定、
 *   curl実測2026-08-09）**: `DRIVE + departureTime`（routingPreference未指定＝既定の
 *   `TRAFFIC_UNAWARE`）はHTTP 400
 *   `"Timestamp cannot be set for TRAFFIC_UNAWARE routing mode."`を返すことが実測確定し、
 *   **現行実装はDRIVEが常に失敗する**バグだったと判明した。`DRIVE + departureTime +
 *   "routingPreference":"TRAFFIC_AWARE"`はHTTP 200（duration実測あり）。したがって
 *   travelModeがDRIVEの場合のみ`"routingPreference":"TRAFFIC_AWARE"`をbodyへ追加する
 *   （WALK/BICYCLE/TRANSITはrouting Preferenceなしで実測済みHTTP 200のため付与しない。
 *   APIがDRIVE系以外へのroutingPreference指定を拒否するため）。departureTimeは全モード
 *   維持する（本アプリは未来出発時刻のETAがコア機能のため）
 * - **P3-C10実測確定内容（2026-08-09、Fable 5裁定＝ADR-0030）**: `departureTime`に「現在時刻
 *   ぴったり」（バッファ0の`Instant.now()`）を送ると、ネットワーク往復＋サーバ側処理の間に
 *   過去時刻へずれ、Routes APIがHTTP 400 `"Timestamp must be set to a future time."`
 *   （`INVALID_ARGUMENT`）を返すことが実測確定した（DRIVE/WALK系。host／emulator／Googleサーバの
 *   3者時計比較により時計ズレが原因でないことも確認済み＝ADR-0029付記2・`RoutesApiLiveTest`
 *   クラスKDoc参照。TRANSITは有効経路0件の応答経路が本検証より先に短絡すると推測されこの検証を
 *   受けない）。本番`DepartureViewModel.estimateAndApplyRoute`は`departureDate = clock.instant()`
 *   （バッファ0）をそのまま渡す設計だが、これは「今すぐ出発する」という意味論として正しく、
 *   呼び出し元を変更すべき欠陥ではない。したがってこの対応はRoutes API固有の作法（FieldMask
 *   必須・DRIVE限定TRAFFIC_AWAREと同格）として**本ビルダー（アダプタ層）の責務**と裁定された
 *   （Fable 5裁定、ADR-0030）：[build]は新規`clock`パラメータ（既定`Clock.systemUTC()`、
 *   既存呼び出し元は無変更で動作＝デフォルト引数）を受け取り、送信する`departureTime`を
 *   `max(departureTime, clock.instant() + 120秒)`へクランプする。120秒はC9実測で十分性が
 *   確認された安全マージン（opt-inテスト`RoutesApiLiveTest.FUTURE_DEPARTURE_BUFFER`と同根拠）
 * - Android REST直叩き時の`X-Android-Package`/`X-Android-Cert`ヘッダについて、Googleの
 *   セキュリティベストプラクティスは "Android: Use the X-Android-Package and
 *   X-Android-Cert HTTP headers" と案内する一方、"Android and iOS application
 *   restrictions may not be fully supported on older legacy Google Maps Platform
 *   services" とも注記する（[…/api-security-best-practices](
 *   https://developers.google.com/maps/api-security-best-practices)）。**Routes API v2
 *   固有の対応可否は本セッションでは未確認＝未検証**（計画書§13依頼文に記載済みの
 *   残存不確定事項のまま）
 *
 * **P3-C4実装メモ**: `org.json`は使わない。`RoutesApiRequestBuilderTest`のクラスKDocが
 * 明記するとおり、Robolectric無しのプレーンJUnit（`src/test`、`@RunWith`指定なし）では
 * `org.json.*`はAndroidスタブ実装（メソッド本体が`Stub!`例外）であり、P3-P4（Robolectric上
 * でのorg.json動作可否）自体が計画書§16で「未検証」のまま残っている。したがって本ビルダー
 * は固定形状のJSONを`String`テンプレートで直接組み立てる（依存追加ゼロ、§88）。
 */
object RoutesApiRequestBuilder {
    // P3-C10実測確定（本ファイルKDoc）: ネットワーク往復＋サーバ処理の間にdepartureTimeが
    // 過去時刻へずれ「Timestamp must be set to a future time.」（HTTP 400）を引き起こす競合を
    // 回避するための安全マージン。C9実測（opt-inテストのFUTURE_DEPARTURE_BUFFER）で十分性を確認済み。
    private val MINIMUM_FUTURE_BUFFER: Duration = Duration.ofSeconds(120)

    fun build(
        origin: Coordinate,
        destination: Coordinate,
        mode: TransportMode,
        departureTime: Instant,
        clock: Clock = Clock.systemUTC()
    ): String {
        val travelMode = travelModeOf(mode)
        return buildString {
            append('{')
            append("\"origin\":{\"location\":{\"latLng\":{")
            append("\"latitude\":").append(origin.lat)
            append(",\"longitude\":").append(origin.lon)
            append("}}},")
            append("\"destination\":{\"location\":{\"latLng\":{")
            append("\"latitude\":").append(destination.lat)
            append(",\"longitude\":").append(destination.lon)
            append("}}},")
            append("\"travelMode\":\"").append(travelMode).append("\",")
            // P3-C9実測確定（本ファイルKDoc）: DRIVEはTRAFFIC_AWAREを明示しないと
            // departureTime付きリクエストがHTTP 400になる。DRIVE以外（WALK/BICYCLE/TRANSIT）
            // はrouting Preferenceなしで実測済みHTTP 200のため付与しない。
            if (travelMode == "DRIVE") {
                append("\"routingPreference\":\"TRAFFIC_AWARE\",")
            }
            // Instant.toString()はナノ秒=0のときRFC3339（例: "2026-08-09T01:23:45Z"）を
            // 小数部なしで返す（java.time.Instant契約、ISO_INSTANTフォーマッタ準拠）。
            append("\"departureTime\":\"").append(clampToFuture(departureTime, clock)).append('"')
            append('}')
        }
    }

    /**
     * P3-C10実測確定（本ファイルKDoc）: `departureTime`が`clock.instant() + 120秒`より過去
     * （＝クランプ閾値未満）の場合のみ`clock.instant() + 120秒`へ引き上げる
     * （`max(departureTime, clock.instant() + 120秒)`と等価）。クランプ閾値以上の未来値は
     * 無加工でそのまま返す（T-ROUTEREQ-7が固定するpass-through契約）。クランプ閾値未満の
     * 値はどれだけ過去でも同じ床値へ寄せられる（T-ROUTEREQ-6が境界値、T-ROUTEREQ-3改め
     * `build_withFarPastDepartureDate_clampsToClockPlus120Seconds`が大きく過去の値を検証）。
     */
    private fun clampToFuture(departureTime: Instant, clock: Clock): Instant {
        val earliestAllowed = clock.instant().plus(MINIMUM_FUTURE_BUFFER)
        return if (departureTime.isBefore(earliestAllowed)) earliestAllowed else departureTime
    }

    /**
     * P3-P6実測確定（本ファイルKDoc）: WALKING→WALK／DRIVING→DRIVE／TRANSIT→TRANSIT／
     * CYCLING→BICYCLE。DRIVING/WALKING/CYCLINGという綴りはRoutes API側に存在しない。
     */
    private fun travelModeOf(mode: TransportMode): String = when (mode) {
        TransportMode.WALKING -> "WALK"
        TransportMode.DRIVING -> "DRIVE"
        TransportMode.TRANSIT -> "TRANSIT"
        TransportMode.CYCLING -> "BICYCLE"
    }
}
