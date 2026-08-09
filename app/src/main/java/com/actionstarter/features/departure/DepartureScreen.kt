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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 *
 * **P11-C6（F81取りこぼし是正、`docs/plans/phase11-i18n-a11y.md`§10）**: 計画書§6.1の
 * footprint表はDepartureにも「`TravelTimeInput`・`LocationPermissionRationaleCard`・
 * 設定導線ボタンへcontentDescription付与」を要求していたが、P11-C3（Green）の完了記録は
 * EventSelection／PlanReview／Execution／Recoveryの4画面のみを対象として明記しており、
 * 実際にDepartureへは実装されていなかった（最終デバイスラウンド・P11-C4の実機ダンプで
 * content-desc 0件として発覚）。本サイクルで以下を追加した（T-P11A-12a〜e）:
 * ①ETA未取得・バッファ負値・[EtaFailureReason]メッセージ（いずれも色のみに依存する警告、
 * §63）へ[R.string.accessibility_warning_announcement]による非視覚的「警告」シグナルを追加
 * （[ExecutionScreen]の劣化バナー、T-P11A-4と同型）、②[TravelTimeInput]・
 * [TransportModeSelector]の各要素へ明示的`contentDescription`を追加（既存T-P11A-5は
 * mergedTextフォールバックを許容する設計だったが、フォールバックに頼らず実装した）、
 * ③[LocationPermissionRationaleCard]へタイトル＋説明文をグルーピングした
 * `contentDescription`を追加、④設定導線ボタン（"departure_location_open_settings_button"）
 * へ`contentDescription`を追加。いずれも新規の`mergeDescendants = true`境界を作らず
 * （既存の`testTag`を持つノードと同一ノードへ属性を追加する、または独立した非マージノードとして
 * 追加する設計のため、既存`testTag`ベースの回帰（T-PERM3-2/3・T-DEP2-3/4）への影響はない
 * （T-P11A-12d/eで直接re-verifyする）。新規文字列リソースは追加していない
 * （[R.string.accessibility_warning_announcement]・各種既存ラベル文言を再利用）。
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
        // P11-C6（F81取りこぼし是正、T-P11A-12a）: 上記2件と同型の警告シグナル追加。
        val etaFailureMessage = stringResource(etaFailureMessageRes(reason))
        val etaFailureWarningDescription =
            stringResource(R.string.accessibility_warning_announcement, etaFailureMessage)
        Text(
            text = etaFailureMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { contentDescription = etaFailureWarningDescription }
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
        // F83実配線（P11-C3、計画書§7.4、S-2裁定）: location_permission_denied_messageは
        // 従来UnusedResources警告対象の死蔵リソースだった。文言自体は自動取得不可全般に
        // 適用できる汎用文（「自動取得はできないが手動入力で続けられる」）のため、
        // showManualFallbackの3条件（DENIED／isDestinationUnresolved／etaFailureReason）
        // いずれの場合もTravelTimeInput直前の説明として据え置きの既存文言をそのまま表示する
        // （departure_eta_stale_notice等と同じパターン）。
        Text(
            text = stringResource(R.string.location_permission_denied_message),
            style = MaterialTheme.typography.bodySmall
        )
        TravelTimeInput(
            minutes = uiState.manualTravelMinutes,
            onMinutesChange = onManualTravelMinutesChange
        )
    }

    if (uiState.permissionState == LocationPermissionState.DENIED) {
        // P11-C6（F81取りこぼし是正、T-P11A-12e）: 計画書§6.1footprint表が元々要求していた
        // 設定導線ボタンへのcontentDescription付与。既存testTagと同一ノードへ追加するため
        // T-PERM3-3（可視テキストクエリ）への回帰影響はない。
        val openSettingsLabel = stringResource(R.string.location_open_settings_button)
        Button(
            onClick = onOpenLocationSettings,
            modifier = Modifier
                .testTag("departure_location_open_settings_button")
                .semantics { contentDescription = openSettingsLabel }
        ) {
            Text(text = openSettingsLabel)
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
        // P11-C6（F81取りこぼし是正、T-P11A-12a）: 色のみに依存する警告（§63）のため、
        // ExecutionScreenの劣化バナー（T-P11A-4）と同型でaccessibility_warning_announcement
        // による非視覚的シグナルを追加する。可視テキスト自体は不変更。
        val etaUnavailableMessage = stringResource(R.string.departure_eta_unavailable_message)
        val etaUnavailableWarningDescription =
            stringResource(R.string.accessibility_warning_announcement, etaUnavailableMessage)
        Text(
            text = etaUnavailableMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = etaUnavailableWarningDescription }
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
            // P11-C6（F81取りこぼし是正、T-P11A-12a）: 上記ETA未取得と同型の警告シグナル追加。
            val bufferNegativeMessage = stringResource(R.string.departure_buffer_negative_warning)
            val bufferNegativeWarningDescription =
                stringResource(R.string.accessibility_warning_announcement, bufferNegativeMessage)
            Text(
                text = bufferNegativeMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = bufferNegativeWarningDescription }
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
 *
 * **P11-C6（F81取りこぼし是正、T-P11A-12d）**: カード全体（タイトル＋説明文）をグルーピングした
 * `contentDescription`を外側`Column`（testTag "departure_location_permission_rationale_card"、
 * 新設）へ追加した。`mergeDescendants`は明示的に付与しない（既定値`false`のまま）ため、
 * 内部の`TextButton`（"departure_location_permission_grant_button"）は独立したノードのまま
 * デフォルト（マージ済み）ツリーで引き続きクエリ可能——T-PERM3-2（変更禁止）への回帰なし。
 */
@Composable
fun LocationPermissionRationaleCard(
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(R.string.location_permission_rationale_title)
    val message = stringResource(R.string.location_permission_rationale_message)
    val cardDescription = listOfNotNull(title, message).joinToString(" ")
    Column(
        modifier = modifier
            .testTag("departure_location_permission_rationale_card")
            .semantics { contentDescription = cardDescription }
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        Text(text = message, style = MaterialTheme.typography.bodySmall)
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
