package com.actionstarter.features.departure

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
 *
 * **再デザインサイクル2（目的・UX合致サイクル）**: 前サイクルはPlanReview／Recovery同様、
 * テーマ未適用のまま（`.padding(8.dp)`という極端に狭い余白・`background()`未設定・カード
 * 未使用）だった。本サイクルで①`background(colorScheme.background)`の明示、
 * ②`.padding(8.dp)`→`horizontal/vertical`への是正、③ETA/Event/Bufferの3要素を
 * [ExecutionScreen]のヒーローカードと同一トークン（`primaryContainer`・`shapes.large`）の
 * カードへ統合（Estimated arrivalを`displaySmall`で最も強調し、Event／Bufferは軽い2列で
 * 補助表示）、④ETA未取得・バッファ負値・[EtaFailureReason]・stale注記・geocode未解決・
 * 手動フォールバック案内の各文言を共通ヘルパー（[DepartureWarningChip]／
 * [DepartureInfoNotice]）へ統一、⑤[DepartureUiState.eventTitle]が非空のとき見出し直下に
 * 控えめなキャプションとして「どの予定のための出発か」を添える（§10「UXの繋がり」。
 * 無ければ非表示＝情報を捏造しない）——を行った。
 * 可視テキストの内容・`testTag`・`Modifier.semantics{contentDescription=...}`はいずれも
 * 既存のまま1つも変更していない（[DepartureWarningChip]／[DepartureInfoNotice]は既存の
 * Text呼び出しを共通スタイルへ差し替えただけで、テキスト自体・警告semanticsの付与有無は
 * 元の分岐と1:1対応）。
 *
 * **レイアウト再チューニング（実測是正）**: 初回実装は[DepartureWarningChip]／
 * [DepartureInfoNotice]を`errorContainer`／`surfaceVariant`塗りの背景箱（チップ）として
 * 実装し、各セクション間にも比較的大きめの`Spacer`を入れていたが、`DepartureScreenTest`・
 * `DepartureRoutingScreenTest`・`FontScaleResilienceTest`・`AccessibilitySemanticsTest`を
 * 実行したところ、権限拒否（DENIED）状態のように複数の条件分岐が同時に積み上がる状態で
 * Robolectric既定ビューポート（`@Config(qualifiers=...)`未指定）の高さを超え、
 * 一部要素が`assertIsDisplayed()`（スクロール不要で可視である前提）を満たせなくなる回帰が
 * 実測で判明した（9件失敗）。背景箱を撤去してプレーンな色付きテキストへ簡素化し、
 * 各`Spacer`・カード内padding・行間隔を実測しながら詰め直すことで全件解消した
 * （テキスト内容・testTag・contentDescriptionは一切変更していない。完了報告
 * 「テスト/lint結果」参照）。
 *
 * **edge-to-edge insetsについての設計判断**: [ExecutionScreen]・[EventSelectionScreen]と
 * 同じ理由により、本画面もstatusBars／navigationBarsのpaddingを自前で追加しない
 * （`ActionStarterNavHost`側の外側`Scaffold`が既にsafe-drawing insetsをNavHost全体へ
 * 一度だけ適用済み）。
 */
@Composable
fun DepartureScreen(
    uiState: DepartureUiState,
    onRequestLocationPermission: () -> Unit = {},
    onOpenLocationSettings: () -> Unit = {},
    onManualTravelMinutesChange: (Int?) -> Unit = {},
    onTransportModeSelected: (TransportMode) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    // 出発画面欠陥修正（`docs/plans/departure-screen-fixes.md`§3.3、T-DEPFIX-7/8、欠陥③）。
    // 戻るボタンはSettingsScreenの"settings_back_button"と同型（TextButton、タイトル直上）。
    // ActionStarterNavHostのDepartureRouteがonNavigateBackへEventSelection遷移
    // （popUpTo inclusive）を実配線し、システムBack（BackHandler）も同一の遷移へ揃える。
    val locale = LocalConfiguration.current.locales[0]
    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        TextButton(
            onClick = onNavigateBack,
            modifier = Modifier.testTag("departure_back_button")
        ) {
            Text(stringResource(R.string.departure_back_button_label))
        }
        Text(
            text = stringResource(R.string.departure_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        // §10「UXの繋がり」: 「どの予定のための出発か」の控えめな文脈。eventTitleが非空の
        // ときのみ描画する（無い情報を捏造しない）。
        val eventTitle = uiState.eventTitle
        if (!eventTitle.isNullOrBlank()) {
            Text(
                text = eventTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(6.dp))

        // Phase 1〜4から不変更の描画順序（T-DEP-1〜4・T-P4DEP系の回帰ガード。狭いテスト
        // viewportでも既存アサーション対象のノードが従来と同じ位置に来るよう、拡張分岐
        // （権限カード・stale注記・手動入力導線等）は本ブロックより後段にまとめる）。
        DepartureEtaSection(uiState = uiState, timeFormatter = timeFormatter)

        Spacer(Modifier.height(6.dp))

        Column {
            Button(
                onClick = { /* Phase 1未実装（T-DEP-4） */ },
                enabled = uiState.isStartNavigationEnabled,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text(
                    text = stringResource(R.string.departure_start_navigation_button),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (!uiState.isStartNavigationEnabled) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.departure_start_navigation_disabled_reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(6.dp))

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
 *
 * **再デザインサイクル2**: 全分岐を`Column(verticalArrangement = Arrangement.spacedBy(14.dp))`
 * で包み、分岐間に一貫した余白を持たせた（従来は分岐同士が直接連なりSpacerも余白もなかった）。
 * 各分岐が描画するテキスト内容・testTag・semanticsは不変（[DepartureWarningChip]／
 * [DepartureInfoNotice]は見た目の共通化のみ）。
 */
@Composable
private fun DeparturePermissionAndRoutingSection(
    uiState: DepartureUiState,
    onRequestLocationPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onManualTravelMinutesChange: (Int?) -> Unit,
    onTransportModeSelected: (TransportMode) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 出発画面欠陥修正（`docs/plans/departure-screen-fixes.md`§3.2、欠陥②、T-DEPFIX-6a/6b、
        // アーキテクトレビューPass 1指摘反映）: 行き先未解決時は権限状態に関わらず権限カードを
        // 一切出さない（位置権限の設定を開いても行き先未解決という真の問題は解決しないため、
        // 的外れな誘導を避ける）。
        if (uiState.permissionState == LocationPermissionState.NOT_REQUESTED && !uiState.isDestinationUnresolved) {
            LocationPermissionRationaleCard(onRequestLocationPermission = onRequestLocationPermission)
        }

        if (uiState.isEtaStale) {
            DepartureInfoNotice(text = stringResource(R.string.departure_eta_stale_notice))
        }

        uiState.etaFailureReason?.let { reason ->
            // P11-C6（F81取りこぼし是正、T-P11A-12a）: 上記2件と同型の警告シグナル追加。
            DepartureWarningChip(message = stringResource(etaFailureMessageRes(reason)))
        }

        if (uiState.isDestinationUnresolved) {
            DepartureInfoNotice(text = stringResource(R.string.departure_geocode_no_match_message))
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DepartureInfoNotice(text = stringResource(R.string.location_permission_denied_message))
                TravelTimeInput(
                    minutes = uiState.manualTravelMinutes,
                    onMinutesChange = onManualTravelMinutesChange
                )
            }
        }

        // 出発画面欠陥修正（欠陥②、T-DEPFIX-6b、アーキテクトレビューPass 1指摘反映）: 上の
        // Rationaleカードと同じ理由で`&& !uiState.isDestinationUnresolved`を追加した。
        if (uiState.permissionState == LocationPermissionState.DENIED && !uiState.isDestinationUnresolved) {
            // P11-C6（F81取りこぼし是正、T-P11A-12e）: 計画書§6.1footprint表が元々要求していた
            // 設定導線ボタンへのcontentDescription付与。既存testTagと同一ノードへ追加するため
            // T-PERM3-3（可視テキストクエリ）への回帰影響はない。
            val openSettingsLabel = stringResource(R.string.location_open_settings_button)
            Button(
                onClick = onOpenLocationSettings,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("departure_location_open_settings_button")
                    .semantics { contentDescription = openSettingsLabel }
            ) {
                Text(text = openSettingsLabel)
            }
        }
    }
}

/**
 * ETA／Event／Bufferの3要素（§35 Screen4）。可視テキスト・testTag・semanticsは
 * Phase 1〜4から不変更（T-DEP-1〜4・T-P4DEP系・T-DEP2-1／6の回帰ガード対象）。
 *
 * **再デザインサイクル2**: [ExecutionScreen]のヒーローカードと同一トークン
 * （`primaryContainer`・`shapes.large`）のCardへ統合した。Estimated arrivalを
 * `displaySmall`で最も強調し（このカードの中心情報＝「Leave now」画面の主目的）、
 * Event／Bufferは2列の軽い補助表示にとどめる。ETA未取得・バッファ負値の警告は、
 * 値が入るはずだった箇所（時刻／Buffer値の位置）へ`error`色のテキストとしてそのまま
 * 差し込む（当初は別チップとしてカード外へ切り出す設計だったが、狭いRobolectric既定
 * ビューポート（320×470dp）で複数要素が積み上がる状態が`assertIsDisplayed()`を満たせなく
 * なる実測回帰を招いたため、値の置き場所を再利用する現在の形に統合した。完了報告
 * 「レイアウト再チューニング」参照。accessibility_warning_announcementによる警告
 * semanticsは変更なく維持する）。
 */
@Composable
private fun DepartureEtaSection(uiState: DepartureUiState, timeFormatter: DateTimeFormatter) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(R.string.departure_estimated_arrival_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            val estimatedArrival = uiState.estimatedArrival
            if (estimatedArrival != null) {
                Text(
                    text = timeFormatter.format(ZonedDateTime.ofInstant(estimatedArrival, ZoneId.systemDefault())),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                // P11-C6（T-P11A-12a）: 色のみに依存する警告（§63）のため、ExecutionScreenの
                // 劣化バナー（T-P11A-4）と同型でaccessibility_warning_announcementによる
                // 非視覚的シグナルを持つ。可視テキスト自体は不変更。
                val etaUnavailableMessage = stringResource(R.string.departure_eta_unavailable_message)
                val etaUnavailableWarningDescription =
                    stringResource(R.string.accessibility_warning_announcement, etaUnavailableMessage)
                Text(
                    text = etaUnavailableMessage,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { contentDescription = etaUnavailableWarningDescription }
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = stringResource(R.string.departure_event_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                    val eventStart = uiState.eventStart
                    if (eventStart != null) {
                        Text(
                            text = timeFormatter.format(ZonedDateTime.ofInstant(eventStart, ZoneId.systemDefault())),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.departure_buffer_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        textAlign = TextAlign.End
                    )
                    val buffer = uiState.arrivalBuffer
                    if (buffer != null) {
                        if (buffer.isNegative) {
                            // P11-C6（T-P11A-12a）: 上記ETA未取得と同型の警告シグナル追加。
                            val bufferNegativeMessage = stringResource(R.string.departure_buffer_negative_warning)
                            val bufferNegativeWarningDescription =
                                stringResource(R.string.accessibility_warning_announcement, bufferNegativeMessage)
                            Text(
                                text = bufferNegativeMessage,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.End,
                                modifier = Modifier.semantics { contentDescription = bufferNegativeWarningDescription }
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.departure_buffer_minutes_format, buffer.toMinutes()),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 警告テキスト共通スタイル（再デザインサイクル2、§63 color-only禁止）。当初は
 * ExecutionScreenの劣化バナーと同じ`errorContainer`塗りの箱（チップ）だったが、
 * DepartureScreenは条件分岐が多く（権限状態×ETA失敗理由×stale等が同時に積み上がる）、
 * 箱の背景＋パディング分の高さがRobolectric既定ビューポートでのDENIED状態
 * （`DepartureRoutingScreenTest`のtPerm3_3・`FontScaleResilienceTest`のtP11f4）で
 * `assertIsDisplayed()`超過（要スクロール＝ビューポート外）を引き起こすことが実測で判明した
 * ため、背景箱をやめ`error`色のプレーンテキストへ簡素化した（実測に基づく調整、
 * 完了報告「レイアウト再チューニング」参照）。[message]の可視テキストに加え、
 * [R.string.accessibility_warning_announcement]でラップした非視覚的「警告」シグナルは
 * 引き続き必ず付与する（§63、呼び出し側で個別にsemanticsを組み立てる必要をなくす）。
 *
 * 呼び出し対象: ETA未取得（[R.string.departure_eta_unavailable_message]）・バッファ負値
 * （[R.string.departure_buffer_negative_warning]）・[EtaFailureReason]別メッセージの3種
 * （いずれも既存でaccessibility_warning_announcementが付与されていた分岐、P11-C6実装）。
 */
@Composable
private fun DepartureWarningChip(message: String, modifier: Modifier = Modifier) {
    val warningDescription = stringResource(R.string.accessibility_warning_announcement, message)
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = warningDescription }
    )
}

/**
 * 情報テキスト共通スタイル（再デザインサイクル2）。[DepartureWarningChip]と同じ理由
 * （ビューポート超過の実測是正）で背景箱をやめプレーンテキストへ簡素化した。これらは
 * 警告ではなく中立的な案内文言（stale注記・geocode未解決・手動入力導線の説明）であり、
 * 既存実装でも`accessibility_warning_announcement`を付与していなかったため
 * （P11-C6のスコープは明示的にETA未取得／バッファ負値／EtaFailureReasonの3種のみ）、
 * 本ヘルパーも警告semanticsは付与しない（既存の非警告扱いを維持する）。
 */
@Composable
private fun DepartureInfoNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * [DepartureUiState.etaFailureReason]の写像先（T-DEPVM-9の写像先をUIでも8値網羅で
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
 *
 * **再デザインサイクル2**: 外側コンテナの`testTag`／`semantics`は完全に不変更のまま、
 * `surfaceVariant`背景＋`shapes.large`のカード風スタイルを追加した（他画面のOutlinedCard
 * 相当の落ち着いた重み。事前説明カードは能動的な訴求ではなく中立的な案内のため、
 * `primaryContainer`の強い色は使わない）。
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
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
            .padding(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
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
 *
 * **再デザインサイクル2**: [DepartureInfoNotice]と同一の中立チップスタイルへ統一した
 * （可視テキスト・非表示条件は不変更）。
 */
@Composable
fun CoarsePrecisionNotice(accuracyMeters: Float?, modifier: Modifier = Modifier) {
    if (accuracyMeters != null && accuracyMeters > COARSE_ACCURACY_THRESHOLD_METERS) {
        DepartureInfoNotice(
            text = stringResource(R.string.location_permission_coarse_only_notice),
            modifier = modifier
        )
    }
}

private const val COARSE_ACCURACY_THRESHOLD_METERS: Float = 100f
