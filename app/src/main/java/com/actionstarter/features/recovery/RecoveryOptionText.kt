package com.actionstarter.features.recovery

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.actionstarter.R
import java.time.Instant

/**
 * 計画書§7.7準拠（F77、P6-C1 scaffold）。[com.actionstarter.domain.model.RecoveryOption
 * .semanticAction]（`BasicRecoveryEngine`がADR-0018拡張により空文字固定で生成する`title`／
 * `explanation`の代わりに持つlocalizationキー）を`stringResource`解決し、表示用文言を返す
 * （`features/common/StepTitle.kt`の`resolveStepTitle`先例をそのまま踏襲）。
 *
 * 既知4キー（`keep_all_steps`／`skip_optional_steps`／`skip_optional_and_important_steps`／
 * `change_transport_mode`、§7.3）を`stringResource`へ解決する。未知キーはフォールバック文言を
 * 返し例外を投げない（T-RECUI-3）。
 *
 * **P6-C5実装（統合ウィンドウ、計画書§6.4行3）**: `recovery_option_title_*` /
 * `recovery_option_explanation_*`の4キー×ja/enを`values/strings.xml`／`values-ja/strings.xml`へ
 * 追加し（`StringResourceParityTest`のキー集合一致・非空検証対象）、既知4キーをそれぞれ専用の
 * `stringResource`へ解決するよう本関数を差し替えた（P6-C3/C4時点の暫定フォールバック
 * ——既知4キー・未知キーいずれも`step_title_fallback`へ一律フォールバックする実装——を解消）。
 * 未知キーは引き続き`stringResource(R.string.step_title_fallback)`
 * （`features/common/StepTitle.kt`の`resolveStepTitle`が使う既存の汎用フォールバック文言）へ
 * フォールバックし例外を投げない（T-RECUI-3）。
 *
 * **本関数はP6-C5で`RecoveryScreen`へ結線済み**（`RecoveryScreen.kt`のKDoc参照）。
 * `BasicRecoveryEngine`は`title`/`explanation`を常に空文字で生成するため（ADR-0033）、
 * 画面表示は本関数の解決結果に完全に依存する。
 */
@Composable
fun resolveRecoveryOptionTitle(semanticAction: String): String = when (semanticAction) {
    "keep_all_steps" -> stringResource(R.string.recovery_option_title_keep_all_steps)
    "skip_optional_steps" -> stringResource(R.string.recovery_option_title_skip_optional_steps)
    "skip_optional_and_important_steps" ->
        stringResource(R.string.recovery_option_title_skip_optional_and_important_steps)
    "change_transport_mode" -> stringResource(R.string.recovery_option_title_change_transport_mode)
    else -> stringResource(R.string.step_title_fallback)
}

/**
 * [resolveRecoveryOptionTitle]と対の説明文解決関数（§7.7）。[eta]は該当候補の
 * `RecoveryOption.estimatedArrival`（§32のETA表示要件）。
 *
 * **[eta]は意図的に未使用のまま残す（P6-C5、設計判断）**: `RecoveryScreen`は既に候補ごとに
 * 専用のETA行（`recovery_option_eta_label`＋整形済み時刻、testTag
 * `"recovery_option_eta_<id>"`、T-RECUI-1/4/8）を独立して描画している。説明文の中へもETAを
 * 埋め込むと同一情報が1候補につき2箇所（説明文＋専用ETA行）に重複表示されることになるため、
 * 本関数では埋め込まない。シグネチャに[eta]を残しているのは、`RecoveryScreen`側の呼び出し
 * （`resolveRecoveryOptionExplanation(option.semanticAction, option.estimatedArrival)`）と
 * `RecoveryOptionDisplayTest`（T-RECUI-2）の既存呼び出しの両方に影響を与えずに将来この方針を
 * 再検討できるようにするため。
 */
@Composable
fun resolveRecoveryOptionExplanation(semanticAction: String, eta: Instant?): String = when (semanticAction) {
    "keep_all_steps" -> stringResource(R.string.recovery_option_explanation_keep_all_steps)
    "skip_optional_steps" -> stringResource(R.string.recovery_option_explanation_skip_optional_steps)
    "skip_optional_and_important_steps" ->
        stringResource(R.string.recovery_option_explanation_skip_optional_and_important_steps)
    "change_transport_mode" -> stringResource(R.string.recovery_option_explanation_change_transport_mode)
    else -> stringResource(R.string.step_title_fallback)
}
