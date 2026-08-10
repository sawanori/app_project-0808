package com.actionstarter.features.recovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.actionstarter.R
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * 仕様§31-34・§35 Screen5準拠（Plan updated画面）。最大の独自価値。
 *
 * §10.6の疎結合規約により、画面遷移はラムダ引数として受け取り、NavController／NavHostを
 * 直接参照しない。[onNavigateToExecution]は「Use this plan」タップ相当の遷移。
 * 候補選択自体は自動適用されず（仕様§34、T-REC-5）、[selectedId]は画面内の「仮選択中」の
 * ローカル状態にとどめる。
 *
 * testTag規約: 各候補行を "recovery_option_item_<id>" 形式で付与する（T-REC-2/4/5）。
 * 行はテキスト検証のため`mergeDescendants = true`で子Textのセマンティクスをマージする。
 *
 * **P6-C3/C4実装（計画書§6.2・§7.7・F77）**: 各候補にETA行を追加した（§32、
 * `testTag("recovery_option_eta_<id>")`、T-RECUI-1/4/8）。`estimatedArrival == null`の候補は
 * ETA行を描画しない（偽値を表示しない、T-RECUI-8）。時刻フォーマットは`DepartureScreen.kt`の
 * `DepartureEtaSection`と同じ`DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)`
 * ＋`LocalConfiguration.current.locales[0]`パターンを踏襲する。
 *
 * **P6-C5実装（統合ウィンドウ）**: title/explanationは[resolveRecoveryOptionTitle]／
 * [resolveRecoveryOptionExplanation]（`option.semanticAction`経由）へ結線した
 * （旧: `RecoveryOption.title`／`explanation`フィールドを直接表示。`BasicRecoveryEngine`が
 * これらを常に空文字で生成する（ADR-0033）ようになったため、フィールド直接表示のままでは
 * 画面が空白になる。P6-C3/C4時点の`MockRecoveryFactory`はtitle/explanationへ英語文言を
 * 直接埋め込んでいたが、これは§7 Global-firstに反するハードコードでもあった）。
 * `RecoveryScreenTest.tRec2_threeOptions_allDisplayed`は本切り替えに伴いfixtureを更新済み
 * （既知semanticActionキーを使い解決後の`stringResource`と突き合わせる形へ。assertion強度は
 * 維持。§4.2 U-6で承認済み、`RecoveryScreenTest.kt`のKDoc参照）。
 *
 * [onUseThisPlan]は「Use this plan」タップ時に選択中の`selectedId`を渡して呼び出す
 * （`RecoveryPlanApplier`経由の適用、`RecoveryViewModel.useThisPlan`、T-RECVM-6/7）。
 *
 * **F81実装（P11-C3、T-P11A-6/7/8、§7.2）**: 各候補行の既存の空`semantics(mergeDescendants
 * = true) {}`へ、案の内容（title/explanation）＋ETA（`estimatedArrival`が非nullの場合のみ、
 * 偽情報を読み上げない・T-RECUI-8と同種の設計）＋選択状態（[R.string.recovery_option_selected_state_description]、
 * 選択中の行にのみ付加）を統合した`contentDescription`を実装した。
 *
 * **S-5裁定実装（P11-C3、T-P11A-11）**: 選択中の候補行に視覚的インジケータ
 * （`Modifier.background(color = primaryContainer)`）を追加した。TalkBack向けには標準の
 * `SemanticsProperties.Selected`（`Modifier.semantics { selected = ... }`）を全行へ付与し、
 * 色のみに依存しない（§63 color-only禁止）二重の伝達手段とする。
 *
 * **再デザインサイクル2（目的・UX合致サイクル）**: 前サイクルはExecution／EventSelectionの
 * みテーマ・カードを適用済みで、本画面は素の`Row`（`.background()`のみ手動適用）・無地
 * `Button`のままだった。本サイクルで①`background(colorScheme.background)`の明示、
 * ②各候補行を[Card]（[EventSelectionScreen]の`EventRow`と同じ「既存のtestTag／semantics／
 * clickableのmodifierチェーンをCardのmodifier引数へそのまま渡す」パターン）へ統合し、
 * 選択中は`primaryContainer`塗り、非選択は`surface`＋`outlineVariant`ボーダーで区別する
 * （S-5裁定の視覚的インジケータそのものは不変更、単に手動`.background()`からCardの
 * `colors`パラメータへ実装場所を移しただけ）、③Use this plan／See alternativesボタンを
 * shapes.large・height 52dpへ統一——を行った。可視テキストの内容・testTag・
 * `Modifier.semantics{contentDescription=...; selected=...}`はいずれも既存のまま1つも
 * 変更しておらず、`RecoveryScreenTest`・`RecoveryOptionDisplayTest`・
 * `AccessibilitySemanticsTest`（T-P11A-6〜8/10/11）・`FontScaleResilienceTest`の既存回帰
 * ガードへの影響はない。
 */
@Composable
fun RecoveryScreen(
    uiState: RecoveryUiState,
    onNavigateToExecution: () -> Unit,
    onUseThisPlan: (UUID?) -> Unit = {}
) {
    var selectedId by rememberSaveable(uiState.selectedOptionId) {
        mutableStateOf(uiState.selectedOptionId)
    }
    val locale = LocalConfiguration.current.locales[0]
    val etaTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // F82実装（P11-C3、T-P11F-5）: fontScale=1.5x実測で、候補3件表示時に「Use this
            // plan」ボタンがビューポート外へ押し出され非表示になることが判明した（root
            // Columnがscroll不可のfillMaxSizeのみだったため）。DepartureScreenの既存パターン
            // （verticalScroll(rememberScrollState())）を踏襲し、内容が画面高を超える場合に
            // スクロールで到達可能にする（レイアウト自体の再設計ではない軽微な調整、
            // 計画書§12 S-6裁定の許容範囲内）。
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.recovery_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.recovery_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(6.dp))

        if (uiState.options.isEmpty()) {
            // 候補0件（エラー＆レスキューマップ#11）：案内文言のみを手動導線として提示する
            // （§34の確認操作前提を守るため、Use this plan／See alternativesは表示しない）。
            Text(
                text = stringResource(R.string.recovery_no_options_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                uiState.options.forEach { option ->
                    val isSelected = option.id == selectedId
                    val resolvedTitle = resolveRecoveryOptionTitle(option.semanticAction)
                    val resolvedExplanation = resolveRecoveryOptionExplanation(option.semanticAction, option.estimatedArrival)
                    val eta = option.estimatedArrival
                    // T-RECUI-8/T-P11A-8: estimatedArrival == nullの候補はETA情報を一切
                    // 組み立てない（表示・contentDescription双方で偽情報を出さない）。
                    val formattedEta = eta?.let {
                        stringResource(R.string.recovery_option_eta_label) + " " +
                            etaTimeFormatter.format(ZonedDateTime.ofInstant(it, ZoneId.systemDefault()))
                    }
                    val selectedStateDescription = if (isSelected) {
                        stringResource(R.string.recovery_option_selected_state_description)
                    } else {
                        null
                    }
                    val optionContentDescription = listOfNotNull(
                        resolvedTitle,
                        resolvedExplanation,
                        formattedEta,
                        selectedStateDescription
                    ).joinToString(", ")

                    Card(
                        modifier = Modifier
                            .testTag("recovery_option_item_${option.id}")
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                contentDescription = optionContentDescription
                                selected = isSelected
                            }
                            .clickable { selectedId = option.id },
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            // S-5裁定（T-P11A-11）: 選択中の候補行の視覚的インジケータ。色のみに
                            // 依存しないよう、上記semanticsのselected/contentDescriptionと併用する
                            // （§63）。以前は手動.background()だったが、実装場所をCardのcolors
                            // パラメータへ移しただけで挙動・意味は不変。
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        ),
                        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = resolvedTitle,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = resolvedExplanation,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (eta != null && formattedEta != null) {
                                Text(
                                    text = formattedEta,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("recovery_option_eta_${option.id}")
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        onUseThisPlan(selectedId)
                        onNavigateToExecution()
                    },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Text(text = stringResource(R.string.recovery_use_this_plan_button), style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(
                    onClick = { /* Phase 1: 候補切替は表示のみ（画面内で完結） */ },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Text(text = stringResource(R.string.recovery_see_alternatives_button))
                }
            }
        }
    }
}
