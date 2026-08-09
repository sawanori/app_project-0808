package com.actionstarter.features.departure

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import com.actionstarter.domain.valueobject.TransportMode
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 仕様§29・§35 Screen4準拠（Leave now画面）。
 *
 * [DepartureUiState.estimatedArrival]がnullのとき「移動時間未取得」と表示する（T-DEP-3、
 * エラー＆レスキューマップ#5）。[DepartureUiState.arrivalBuffer]が負値のとき、
 * テキストで明示する（T-DEP-2・T-DEP2-6。§63 color-only情報禁止のためテキストを併記する）。
 *
 * **P3-C5（Green）拡張**: 拡張済み[DepartureUiState]（`permissionState`／`isEtaStale`／
 * `etaFailureReason`／`locationAccuracyMeters`／`isDestinationUnresolved`／
 * `manualTravelMinutes`／`transportMode`）の描画分岐を追加した（計画書§9.7
 * T-DEP2-1〜6・T-PERM3-1〜5）。§89「巨大Composable禁止」に従い、状態ごとの表示を
 * private Composableへ分割する（[EventSelectionScreen]と同じ方針）。
 *
 * 画面遷移・権限リクエストはラムダ引数として受け取り、`ActivityResultLauncher`・
 * `NavController`・ライフサイクルのいずれも直接参照しない（§10.6疎結合規約）。
 * [onRequestLocationPermission]・[onOpenLocationSettings]・[onManualTravelMinutesChange]・
 * [onTransportModeSelected]はいずれも既定値`{}`を持つため、既存の`DepartureScreen(uiState = ...)`
 * 単一引数呼び出し（`ActionStarterNavHost`・既存4テスト`DepartureScreenTest`）は無変更のまま
 * コンパイル・動作する。実際のラムダ結線（`ActivityResultLauncher`の起動・ON_RESUME観測・
 * アプリ設定Intent起動）はNavHost所有のためP3-C6の対象（本サイクルでは行わない。完了報告
 * 「NavHost結線の要否判定」参照）。
 */
@Composable
fun DepartureScreen(
    uiState: DepartureUiState,
    onRequestLocationPermission: () -> Unit = {},
    onOpenLocationSettings: () -> Unit = {},
    onManualTravelMinutesChange: (Int?) -> Unit = {},
    onTransportModeSelected: (TransportMode) -> Unit = {}
) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(
            text = stringResource(R.string.departure_title),
            style = MaterialTheme.typography.headlineSmall
        )

        // Phase 1〜4から不変更の描画順序（T-DEP-1〜4・T-P4DEP系の回帰ガード。狭いテスト
        // viewportでも既存アサーション対象のノードが従来と同じ位置に来るよう、拡張分岐
        // （権限カード・stale注記・手動入力導線等）は本ブロックより後段にまとめる）。
        DepartureEtaSection(uiState = uiState, timeFormatter = timeFormatter)

        Button(onClick = { /* Phase 1未実装（T-DEP-4） */ }, enabled = uiState.isStartNavigationEnabled) {
            Text(text = stringResource(R.string.departure_start_navigation_button))
        }
        if (!uiState.isStartNavigationEnabled) {
            Text(
                text = stringResource(R.string.departure_start_navigation_disabled_reason),
                style = MaterialTheme.typography.bodySmall
            )
        }

        DeparturePermissionAndRoutingSection(
            uiState = uiState,
            onRequestLocationPermission = onRequestLocationPermission,
            onOpenLocationSettings = onOpenLocationSettings,
            onManualTravelMinutesChange = onManualTravelMinutesChange,
            onTransportModeSelected = onTransportModeSelected
        )
    }
}

/**
 * P3-C5拡張分岐（stale注記・EtaFailureReason別表示・権限説明カード・COARSE精度注記・
 * transport mode選択・手動入力導線・Settings導線）。§89「巨大Composable禁止」に従い
 * [DepartureScreen]本体から分離した。
 */
@Composable
private fun DeparturePermissionAndRoutingSection(
    uiState: DepartureUiState,
    onRequestLocationPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onManualTravelMinutesChange: (Int?) -> Unit,
    onTransportModeSelected: (TransportMode) -> Unit
) {
    if (uiState.permissionState == LocationPermissionState.NOT_REQUESTED) {
        LocationPermissionRationaleCard(onRequestLocationPermission = onRequestLocationPermission)
    }

    if (uiState.isEtaStale) {
        Text(
            text = stringResource(R.string.departure_eta_stale_notice),
            style = MaterialTheme.typography.bodySmall
        )
    }

    uiState.etaFailureReason?.let { reason ->
        Text(
            text = stringResource(etaFailureMessageRes(reason)),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (uiState.isDestinationUnresolved) {
        Text(
            text = stringResource(R.string.departure_geocode_no_match_message),
            style = MaterialTheme.typography.bodySmall
        )
    }

    CoarsePrecisionNotice(accuracyMeters = uiState.locationAccuracyMeters)

    // NOT_REQUESTEDの間（まだ権限フローに入っていない）は、選んでも意味を持たない
    // transport mode選択UIを表示しない。事前説明カード（上）が唯一のアクション導線になる
    // （T-PERM3-1）。DENIED/GRANTEDへ遷移した後は常に表示する（F26）。
    if (uiState.permissionState != LocationPermissionState.NOT_REQUESTED) {
        TransportModeSelector(
            selectedMode = uiState.transportMode,
            onModeSelected = onTransportModeSelected
        )
    }

    val showManualFallback = uiState.permissionState == LocationPermissionState.DENIED ||
        uiState.isDestinationUnresolved ||
        uiState.etaFailureReason != null
    if (showManualFallback) {
        TravelTimeInput(
            minutes = uiState.manualTravelMinutes,
            onMinutesChange = onManualTravelMinutesChange
        )
    }

    if (uiState.permissionState == LocationPermissionState.DENIED) {
        Button(
            onClick = onOpenLocationSettings,
            modifier = Modifier.testTag("departure_location_open_settings_button")
        ) {
            Text(text = stringResource(R.string.location_open_settings_button))
        }
    }
}

/**
 * ETA／Event／Bufferの3要素（§35 Screen4）。Phase 1〜4から不変更のUIツリー
 * （T-DEP-1〜4・T-P4DEP系・T-DEP2-1／6の回帰ガード対象）。
 */
@Composable
private fun DepartureEtaSection(uiState: DepartureUiState, timeFormatter: DateTimeFormatter) {
    Text(
        text = stringResource(R.string.departure_estimated_arrival_label),
        style = MaterialTheme.typography.labelLarge
    )
    val estimatedArrival = uiState.estimatedArrival
    if (estimatedArrival != null) {
        Text(text = timeFormatter.format(ZonedDateTime.ofInstant(estimatedArrival, ZoneId.systemDefault())))
    } else {
        Text(
            text = stringResource(R.string.departure_eta_unavailable_message),
            color = MaterialTheme.colorScheme.error
        )
    }

    Text(
        text = stringResource(R.string.departure_event_label),
        style = MaterialTheme.typography.labelLarge
    )
    val eventStart = uiState.eventStart
    if (eventStart != null) {
        Text(text = timeFormatter.format(ZonedDateTime.ofInstant(eventStart, ZoneId.systemDefault())))
    }

    Text(
        text = stringResource(R.string.departure_buffer_label),
        style = MaterialTheme.typography.labelLarge
    )
    val buffer = uiState.arrivalBuffer
    if (buffer != null) {
        if (buffer.isNegative) {
            Text(
                text = stringResource(R.string.departure_buffer_negative_warning),
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Text(text = stringResource(R.string.departure_buffer_minutes_format, buffer.toMinutes()))
        }
    }
}

/**
 * [EtaFailureReason]ごとのユーザー向けメッセージ（T-DEPVM-9の写像先をUIでも8値網羅で
 * 表示する。else禁止の網羅`when`）。[EtaFailureReason.MALFORMED_RESPONSE]は内部的な
 * パース失敗でありユーザーが取れるアクションが他と異ならないため、既存の汎用メッセージ
 * （[R.string.departure_eta_unavailable_message]）を再利用する。
 */
private fun etaFailureMessageRes(reason: EtaFailureReason): Int = when (reason) {
    EtaFailureReason.NOT_CONFIGURED -> R.string.departure_eta_not_configured_message
    EtaFailureReason.OFFLINE -> R.string.departure_eta_offline_message
    EtaFailureReason.TIMEOUT -> R.string.departure_eta_timeout_message
    EtaFailureReason.UNAUTHORIZED -> R.string.departure_eta_unauthorized_message
    EtaFailureReason.QUOTA_EXCEEDED -> R.string.departure_eta_quota_exceeded_message
    EtaFailureReason.SERVER_ERROR -> R.string.departure_eta_server_error_message
    EtaFailureReason.NO_ROUTE -> R.string.departure_eta_no_route_message
    EtaFailureReason.MALFORMED_RESPONSE -> R.string.departure_eta_unavailable_message
}

/**
 * 位置権限の事前説明カード（F21、計画書§9.7 T-PERM3-1／2、§95.4「該当機能を初めて利用する
 * タイミングで要求し、アプリ起動時に一括要求しない」）。実コンポーネント化
 * （[com.actionstarter.features.DepartureRoutingScreenTest]のローカルstub
 * `DepartureLocationPermissionRationaleCardStub`から差し替え）。system dialogの起動は
 * このComposable自身では行わない（`ActivityResultLauncher`はNavHost所有、§10.6）。
 * ボタンタップで[onRequestLocationPermission]を1回だけ呼ぶ（T-PERM3-2）。
 *
 * testTag: "departure_location_permission_grant_button"（T-PERM3-2契約）。
 */
@Composable
fun LocationPermissionRationaleCard(
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.location_permission_rationale_title),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = stringResource(R.string.location_permission_rationale_message),
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(
            onClick = onRequestLocationPermission,
            modifier = Modifier.testTag("departure_location_permission_grant_button")
        ) {
            Text(text = stringResource(R.string.location_permission_grant_button), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * COARSEのみ許可（precise拒否）時の精度低下注記（F21、S-1裁定、計画書§9.7 T-PERM3-5）。
 * 実コンポーネント化（`DepartureRoutingScreenTest`のローカルstub
 * `DepartureCoarsePrecisionNoticeStub`から差し替え）。[accuracyMeters]が
 * [COARSE_ACCURACY_THRESHOLD_METERS]（目安100m、実際のCOARSE精度は数百m〜数km規模になり
 * 得るため十分低い閾値を採る）を超える場合にのみ表示する。`null`（未取得）では何も
 * 描画しない。
 */
@Composable
fun CoarsePrecisionNotice(accuracyMeters: Float?, modifier: Modifier = Modifier) {
    if (accuracyMeters != null && accuracyMeters > COARSE_ACCURACY_THRESHOLD_METERS) {
        Text(
            text = stringResource(R.string.location_permission_coarse_only_notice),
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier
        )
    }
}

private const val COARSE_ACCURACY_THRESHOLD_METERS: Float = 100f
