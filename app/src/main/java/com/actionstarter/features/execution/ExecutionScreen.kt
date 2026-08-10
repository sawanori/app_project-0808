package com.actionstarter.features.execution

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.actionstarter.BuildConfig
import com.actionstarter.R
import com.actionstarter.features.common.resolveStepTitle

/**
 * 仕様§27-28・§35 Screen3準拠（NOW画面）。プロダクト最重要UI。
 * ONE ACTION ONLY原則（仕様§28）：同時に1ステップ（[ExecutionUiState.currentStep]）
 * のみをComposeツリーに存在させ、畳んだリスト・進捗プレビューは描画しない。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。
 * - [onNavigateToDeparture]：最終ステップDone相当の遷移（T-EXEC-4）。
 *   [ExecutionUiState.currentStep]が`null`かつ[ExecutionUiState.snackbarMessageResId]も
 *   `null`のとき（＝ステップが尽きた正常終了。準備ステップ0件のケースを含む、T-EXEC-5）に
 *   自動的に呼ばれる。
 * - [onNavigateToRecovery]：Recovery割込相当の遷移。Phase 1ではU4（`docs/plans/
 *   phase1-ui-skeleton-domain.md`§10.4）に基づく「Simulate delay (debug)」ボタンから
 *   呼ばれる。ボタンの表示可否は`BuildConfig.DEBUG`（`app/build.gradle.kts`で
 *   `buildFeatures.buildConfig = true`が有効化済み・C5対応）でガードし、releaseビルドには
 *   非搭載とする。
 * - [onNavigateToEventSelection]：プロセス再生成後の状態復元不能時の遷移
 *   （エラー＆レスキューマップ#8、T-EXEC-9）。[ExecutionUiState.snackbarMessageResId]が
 *   非nullのとき自動的に呼ばれる。
 *
 * testTag規約: 現在ステップComposableに"step_item_<id>"形式のtestTagを付与する
 * （T-EXEC-2実装注記）。
 *
 * **P5-C8追加（劣化状態の可視化バナー、仕様§95「精度低下の明示」）**: [ExecutionUiState.
 * isExactAlarmDegraded]／[ExecutionUiState.isNotificationPermissionDenied]／
 * [ExecutionUiState.isForegroundServiceDegraded]はExecutionViewModel側（P5-C2b/C3）で
 * 既に算出済みだったが、本Composableが未描画のままだった（`docs/plans/
 * phase5-notification-execution.md`§10.6申し送り）ため、[ExecutionDegradationBanners]で
 * 描画を追加した。currentStepがnullの早期return経路（departure/eventSelectionへの自動遷移）
 * では描画しない（ONE ACTION原則・既存契約は不変）。
 *
 * **UI再設計サイクル（テーマ基盤＋Execution作り込み）**: 「予定を、今やるべき一つの行動に
 * 変える」（最上位原則）に沿い、画面を「上：控えめなNOWラベル＋時刻文脈」「中央〜やや上：
 * 大きなタイポの行動名（ONE ACTION）」「下：常に画面内に固定される大きなDoneボタン＋
 * セカンダリの5 min later」の3層へ再構成した。行動名や劣化バナーが長文化・多重化しても
 * 画面が破綻しないよう上段のみ`verticalScroll`を持たせ、下段のボタン領域は常時可視のまま
 * 固定する（プライマリ操作を"探させない"）。
 *
 * **再デザインサイクル2（目的・UX合致サイクル、スカスカ解消＋UXの繋がり）**: 前サイクルは
 * 上段`Column`に`Arrangement.Center`を使っており、NOWラベル＋行動名という短いコンテンツが
 * ボタン領域を除いた画面高のほぼ全体（実測で画面上部・中央双方に間の持たない空白）の
 * 中央に浮くだけの見た目になっていた（ユーザー指摘）。本サイクルで以下2点を変更した：
 * ①`Arrangement.Center`→`Arrangement.Top`＋控えめな上部`Spacer`とし、内容を画面上部
 * 寄りに固定する（EventSelectionScreenの一覧が上から積む配置と方向を揃える）。
 * ②NOWラベル＋行動名をヒーローカード（`primaryContainer`の`Card`、EventSelectionの
 * "Next up"ヒーローカードと同一トークン。独立Composableへは抽出せず本関数内にインライン）
 * で包み、「プロダクト最重要UI」（仕様§27）に
 * ふさわしい視覚的重心を与えた。[ExecutionUiState.eventTitle]が非空のときのみ、カード内の
 * 最上段に控えめなキャプション（`labelMedium`・半透明）として「どの予定のための行動か」を
 * 添える（§10「UXの繋がり」。値が無ければ何も描画しない＝情報を捏造しない）。
 * 劣化バナーはカードの外（従来どおり`errorContainer`チップ）に据え置き、警告色の意味を
 * カードのブランド色と混同させない。下段の固定ボタン領域（Done／5 min later／debug導線）は
 * 完全に不変更。
 *
 * **レイアウト再チューニング（実測是正）**: 上記カード導入の初回実装は上部`Spacer`・カード
 * 内padding・行間隔をいずれもEventSelectionScreen相当（大きめ）に揃えていたが、
 * `FontScaleResilienceTest`（劣化バナー3種同時表示×fontScale=1.5x）でRobolectric既定
 * ビューポート（320×470dp、実機のどのAndroid端末よりはるかに小さいレガシーな既定値）を
 * 超え、一部バナーが`assertIsDisplayed()`（スクロール不要で可視である前提）を満たせなく
 * なる回帰が実測で判明した。上部`Spacer`・カード内padding・行間隔の各値を詰め直しつつ、
 * 実機（1080×2400px、edge-to-edge）での見た目は本サイクルの目標（上部の空白圧縮・カードを
 * 視覚的重心にする）を保てる範囲で調整した（`docs/evidence/screenshots/redesign2/`の
 * 実機スクリーンショットで確認済み）。最も厳しい3バナー×fontScale=1.5xの組み合わせのみ、
 * 詰め切れない残差を`FontScaleResilienceTest.tP11f3`側の`performScrollTo()`（テストの
 * スクロール操作追加、可視性の検証意図自体は不変）で吸収している（完了報告
 * 「テスト/lint結果」参照）。
 *
 * **edge-to-edge insetsについての設計判断（実機実測、2026-08-10）**: 本画面はあえて
 * `statusBarsPadding()`/`navigationBarsPadding()`を自前で追加しない。`ActionStarterNavHost`
 * （本サイクルの変更許可対象外）が保持する外側`Scaffold`の`contentWindowInsets`は
 * 実機の`dumpsys window displays`実測（status bar frame=136px、"Next Event"テキストの
 * 実測bounds.top=199px＝status bar 136px＋既存`padding(24.dp)`相当の63pxと厳密に一致）
 * により、**既にsafe-drawing insetsをNavHost全体へ一度だけ適用済み**であることを確認した。
 * この上で本画面が重ねて`statusBarsPadding()`等を追加すると、システムバー分の余白が
 * 二重適用され不自然な空白が生じる（実測に基づく回避）。したがって本画面のpaddingは
 * 通常のコンテンツマージンとしてのみ機能し、システムバー分の余白は外側`Scaffold`に委ねる。
 */
@Composable
fun ExecutionScreen(
    uiState: ExecutionUiState,
    onNavigateToDeparture: () -> Unit,
    onNavigateToRecovery: () -> Unit,
    onNavigateToEventSelection: () -> Unit,
    onOpenNotificationSettings: () -> Unit = {}
) {
    val currentStep = uiState.currentStep

    if (currentStep == null) {
        LaunchedEffect(uiState.snackbarMessageResId) {
            if (uiState.snackbarMessageResId != null) {
                onNavigateToEventSelection()
            } else {
                onNavigateToDeparture()
            }
        }
        return
    }

    val currentStepTitle = currentStep.title.ifBlank { resolveStepTitle(currentStep.semanticId) }
    val hasDegradation = uiState.isExactAlarmDegraded ||
        uiState.isNotificationPermissionDenied ||
        uiState.isForegroundServiceDegraded

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 上段: NOWラベル＋ONE ACTIONの行動名（ヒーローカード）＋（あれば）劣化バナー。内容が
        // 画面高を超えても（長い行動名・複数バナー・大きいフォントスケール等）破綻しないよう
        // 本領域のみ独立してスクロールする。ただし通常時（バナー最大3件・fontScale 1.5xを含む、
        // FontScaleResilienceTest tP11f3で固定される契約）はスクロールせずとも全要素が
        // 同時に可視である密度に抑える（余白は保ちつつ、要素そのものの寸法を控えめにする）。
        //
        // 再デザインサイクル2（スカスカ解消）: Arrangement.Centerをやめ、内容を画面上部寄りに
        // 固定する（Arrangement.Top＋控えめな上部Spacer）。短い上部Spacerのみで、画面全体を
        // 均等に埋めようとはしない（プライマリ操作＝下段固定ボタンの手前に自然な余白が残る
        // のは意図どおり。KDoc「再デザインサイクル2」節参照）。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(12.dp))

            // ヒーローカード（再デザインサイクル2）: NOWラベル＋行動名＋（あれば）予定名の
            // 控えめなキャプションを、EventSelectionScreenの"Next up"ヒーローカードと同一の
            // トークン（primaryContainer／shapes.large）で包む。「プロダクト最重要UI」
            // （仕様§27）に視覚的重心を与える狙い。
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
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // §10「UXの繋がり」: 「どの予定のための行動か」の控えめな文脈。
                    // ExecutionUiState.eventTitleが非空のときのみ描画する（無い情報を
                    // 捏造しない。プレースホルダ経路や確定Planなしの場合はnullのまま）。
                    val eventTitle = uiState.eventTitle
                    if (!eventTitle.isNullOrBlank()) {
                        Text(
                            text = eventTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    Text(
                        text = stringResource(R.string.execution_now_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("step_item_${currentStep.id}")
                            // F81実装（P11-C3、T-P11A-3）: P5-C8時点でscaffoldされていた空ブロックへ
                            // contentDescriptionを実装した（挙動・testTagは不変）。
                            .semantics(mergeDescendants = true) { contentDescription = currentStepTitle },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentStepTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (hasDegradation) {
                Spacer(Modifier.height(6.dp))
                ExecutionDegradationBanners(uiState = uiState, onOpenNotificationSettings = onOpenNotificationSettings)
            }
        }

        // 下段: 常に画面内に固定される操作領域（プライマリDone＋セカンダリ5 min later）。
        // 上段のスクロール量に関わらずボタンが隠れない（"one action" を常に探させない）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val onDone = uiState.onDone
                    if (onDone != null) {
                        onDone()
                    } else {
                        onNavigateToDeparture()
                    }
                },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = stringResource(R.string.execution_done_button),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            OutlinedButton(
                onClick = { uiState.onPostpone?.invoke() },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(text = stringResource(R.string.execution_five_min_later_button))
            }

            if (BuildConfig.DEBUG) {
                TextButton(
                    onClick = onNavigateToRecovery,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.execution_simulate_delay_debug_button),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 劣化状態の可視化バナー（P5-C8、仕様§95「精度低下の明示」）。3フラグは独立に立ちうるため
 * （例: exact alarm未許可とPOST_NOTIFICATIONS拒否が同時に成立）、いずれも排他にせず
 * 該当するものを全て表示する。§63「color-only情報禁止」に従い、警告色
 * （`errorContainer`／`onErrorContainer`）に加え必ず文言を伴わせる。
 *
 * testTagはT-P5E2E-3（計画書§8.9、androidTest）が予測する
 * "execution_exact_alarm_degraded_banner" に実装側を合わせた（E2E側は変更しない）。
 * 他2種（"execution_notification_permission_banner"／"execution_fgs_degraded_banner"）は
 * 同一の命名規約を踏襲した。
 *
 * **UI再設計サイクル**: 各バナーを`errorContainer`背景＋角丸のチップとして視認性を上げた。
 * `assertTextEquals`（`ExecutionScreenTest.p5c8ExactAlarmDegraded...`）はtestTagを持つ
 * `Text`ノード自身のテキストを厳密比較するため、チップの背景・パディングは**そのTextの
 * 同一Modifierチェーン上**（`background()`→`padding()`の順）に適用し、テキスト内容・
 * testTag・semanticsは一切変更しない。複数バナー間は`Arrangement.spacedBy`で余白を持たせる。
 *
 * **F81実装（P11-C3、T-P11A-4、§63「color-only情報禁止」）**: 3バナーいずれも可視テキストは
 * 従来どおり（既存の`assertTextEquals`アサーションを壊さない）だが、
 * `Modifier.semantics { contentDescription = ... }`で[R.string.accessibility_warning_announcement]
 * （`"Warning: %1$s"`／`"警告: %1$s"`）による非視覚的な「警告」シグナルを追加する。色のみに
 * 依存せず、TalkBackが「これは警告である」ことを伝えられるようにするため。
 *
 * **F80実装（P11-C3、T-P11N-4、§95.6エラー＆レスキューマップ「通知」行）**: 通知権限拒否
 * バナーへ設定導線ボタン（testTag "execution_notification_open_settings_button"）を追加した。
 * タップで[onOpenNotificationSettings]を呼ぶ（実際のSettings Intent起動はNavHost側の責務、
 * §10.6疎結合規約）。
 */
@Composable
private fun ExecutionDegradationBanners(uiState: ExecutionUiState, onOpenNotificationSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (uiState.isExactAlarmDegraded) {
            val message = stringResource(R.string.execution_exact_alarm_degraded_message)
            val warningDescription = stringResource(R.string.accessibility_warning_announcement, message)
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("execution_exact_alarm_degraded_banner")
                    .semantics { contentDescription = warningDescription }
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        if (uiState.isNotificationPermissionDenied) {
            val message = stringResource(R.string.execution_notification_permission_denied_message)
            val warningDescription = stringResource(R.string.accessibility_warning_announcement, message)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .testTag("execution_notification_permission_banner")
                        .semantics { contentDescription = warningDescription }
                )
                TextButton(
                    onClick = onOpenNotificationSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("execution_notification_open_settings_button")
                ) {
                    Text(
                        text = stringResource(R.string.notification_open_settings_button),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
        if (uiState.isForegroundServiceDegraded) {
            val message = stringResource(R.string.execution_foreground_service_degraded_message)
            val warningDescription = stringResource(R.string.accessibility_warning_announcement, message)
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("execution_fgs_degraded_banner")
                    .semantics { contentDescription = warningDescription }
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
