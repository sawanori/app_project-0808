package com.actionstarter.features.departure

import com.actionstarter.domain.valueobject.TransportMode
import java.time.Duration
import java.time.Instant

/**
 * 仕様§29・§35 Screen4準拠（DepartureScreen）。
 *
 * [estimatedArrival]がnullのとき「移動時間未取得」と表示する（T-DEP-3、
 * エラー＆レスキューマップ#5）。[arrivalBuffer]が負値のとき、色とテキストの両方で
 * 明示する（T-DEP-2）。[isStartNavigationEnabled]はPhase 1で「Start navigation」が
 * 未実装のためfalse固定（T-DEP-4）。
 *
 * **Phase 3 C2後半 scaffold追補（TDD例外、計画書§6.2）**: 以下5フィールドは、
 * P3-C5（Green）が実装する`DepartureViewModel`／`DepartureScreen`／[TravelTimeInput]／
 * [TransportModeSelector]が参照する状態を型として先行確定するために追加した。本追補時点では
 * いずれも[DepartureViewModel]から更新されず、既定値が既存テスト（T-DEP-1〜4、
 * T-P4DEP-1〜5）の通過に影響しない（既存4フィールドの型・既定値は不変更）。
 *
 * - [transportMode]: 初期値`TRANSIT`（S-3裁定、計画書§3.2）。UIの初期選択値にすぎず、
 *   ユーザーが常時変更可能（F26）。データモデル側は仕様§6必須項目どおり`transportMode`の
 *   抽象フィールドのままである。
 * - [isEtaStale]: `RouteEstimate.computedAt`がキャッシュ閾値（10分）より古いことに由来する
 *   ETAであることを示す（§95.6「ETA精度が低下する旨を画面上に明示する」、T-DEPVM-4・
 *   T-DEP2-2）。
 * - [etaFailureReason]: `RoutingException`（計画書§5.4）の写像先。失敗していない間は`null`。
 * - [permissionState]: 位置権限の要求前／拒否／許可（§95.4）。
 * - [manualTravelMinutes]: 権限拒否時等のTravel Time手動入力フォールバック値
 *   （S-2裁定、F28、[TravelTimeInput]が更新する）。
 *
 * **P3-C5（Green）追補**: 以下2フィールドはP3-C5（ui-implementer）が
 * [DepartureViewModel]の再計算ロジック実装に伴い追加した（計画書§9.7 T-PERM3-5・
 * エラー＆レスキューマップ#4／#11に対応する状態を保持するため）。いずれも末尾に追加し
 * 既定値を持つため、既存の名前付き引数によるコンストラクタ呼び出し（T-DEP-1〜4・
 * T-P4DEP-1〜5・T-DEP2-1〜6・T-PERM3-1〜5）はいずれも無変更で成立する。
 * - [locationAccuracyMeters]: 直近の[com.actionstarter.services.location.LocationResult.Success.accuracyMeters]。
 *   COARSE精度時の注記表示（[com.actionstarter.features.departure.CoarsePrecisionNotice]、
 *   S-1裁定・T-PERM3-5）に使う。
 * - [isDestinationUnresolved]: geocode `NoMatch`（会議室名・URL等）またはイベントに
 *   `locationName`が無い場合に`true`。エラー表示ではなく手動入力導線＋
 *   [R.string.departure_geocode_no_match_message]の表示条件に使う（T-DEPVM-5、
 *   計画書§5.3・§10#11）。
 *
 * **再デザインサイクル2追補（目的・UX合致サイクル、§10「UXの繋がり」）**: [eventTitle]は
 * 「どの予定のための行動か」という控えめな文脈をDepartureScreenへ添えるために追加した
 * （末尾追加・既定値`null`のため、既存の名前付き引数によるコンストラクタ呼び出し
 * （T-DEP-1〜4等の全既存テスト）は無変更で成立する）。[DepartureViewModel.applyPlanBaseline]が
 * `plan.event.title`をそのまま転記するのみで、新規の文言生成・整形ロジックは持たない
 * （無い情報を捏造しない。[eventStart]は既存フィールドをそのまま時刻表示に使い続けるため
 * 重複した時刻フィールドは追加しない）。
 */
data class DepartureUiState(
    val estimatedArrival: Instant? = null,
    val eventStart: Instant? = null,
    val arrivalBuffer: Duration? = null,
    val isStartNavigationEnabled: Boolean = false,
    val transportMode: TransportMode = TransportMode.TRANSIT,
    val isEtaStale: Boolean = false,
    val etaFailureReason: EtaFailureReason? = null,
    val permissionState: LocationPermissionState = LocationPermissionState.NOT_REQUESTED,
    val manualTravelMinutes: Int? = null,
    val locationAccuracyMeters: Float? = null,
    val isDestinationUnresolved: Boolean = false,
    val eventTitle: String? = null
)

/**
 * [DepartureUiState.etaFailureReason]の写像先（T-DEPVM-9「`RoutingException`の全サブクラスが
 * UI状態へ網羅的に写像される（`when`に`else`なし＝サイレント障害防止）」）。計画書§5.4の
 * `RoutingException`（sealed、8サブクラス）と1:1で対応させ、`DepartureViewModel`（P3-C5）が
 * `when`に`else`節を書かずに全サブクラスを網羅できることをコンパイラで保証する
 * （S-4裁定の推奨どおり、戻り値型・`RoutingService`契約は変更しない）。
 *
 * `RoutingException`の対応関係（値の追加・削除は`RoutingException`と同期させること）:
 */
enum class EtaFailureReason {
    NOT_CONFIGURED,      // RoutingException.NotConfigured（F29・APIキー未設定）
    OFFLINE,              // RoutingException.Offline
    TIMEOUT,              // RoutingException.Timeout
    UNAUTHORIZED,          // RoutingException.Unauthorized（HTTP 401/403）
    QUOTA_EXCEEDED,        // RoutingException.QuotaExceeded（HTTP 429）
    SERVER_ERROR,          // RoutingException.ServerError（HTTP 5xx）
    NO_ROUTE,              // RoutingException.NoRoute
    MALFORMED_RESPONSE     // RoutingException.MalformedResponse
}

/**
 * [DepartureUiState.permissionState]の値。ACCESS_FINE_LOCATIONの要求前／拒否／許可を表す
 * 最小enum（§95.4冒頭「該当機能を初めて利用するタイミングで要求し…」／エラー＆レスキュー
 * マップ#1〜3）。COARSEのみ許可時（S-1裁定・T-PERM3-5）の精度差の扱いはP3-C5の実装判断に
 * 委ね、本scaffoldでは状態を増やさない。
 */
enum class LocationPermissionState {
    NOT_REQUESTED,  // 初回表示の既定値。事前説明カードを表示し、system dialogは自動起動しない（T-PERM3-1）
    DENIED,         // ユーザーが拒否。手動Travel Time入力＋Settings導線へフォールバックする（T-PERM3-3）
    GRANTED         // 許可済み。自動ETA計算を行う
}
