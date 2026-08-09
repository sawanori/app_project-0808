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
 * 対応する `recovery_option_title_*` / `recovery_option_explanation_*` の文字列リソースキーは
 * P6-C5統合ウィンドウでja/en両`strings.xml`へ同時追加する予定（計画書§6.4#3、
 * `StringResourceParityTest`が検査対象）。
 *
 * **P6-C3/C4実装時点の暫定方針（strings.xml編集はC5専有のため実施不可。完了報告参照）**:
 * 既知4キー・未知キーいずれも`stringResource(R.string.step_title_fallback)`
 * （`features/common/StepTitle.kt`の`resolveStepTitle`が使う既存の汎用フォールバック文言）へ
 * 暫定的にフォールバックする。ハードコード禁止（Global-first・ADR-0018）の制約下でも
 * `stringResource`経由の非空文字列を返せる最小実装であり、T-RECUI-2（既知4キー→非空文字列）・
 * T-RECUI-3（未知キー→クラッシュしないフォールバック）の双方をGreen化する。
 * **本関数は現時点でどの画面からも呼ばれていない**（[RecoveryScreen]は既存の
 * `RecoveryOption.title`/`explanation`フィールドを直接表示したままであり、
 * `RecoveryScreenTest`の既存アサーション（`onNodeWithText(option.title)`等）を壊さないための
 * 意図的な選択。詳細は`RecoveryScreen.kt`のKDoc・完了報告参照）ため、この暫定文言が
 * エンドユーザーへ実際に見えることはない。C5で`BasicRecoveryEngine`が
 * `AppContainer.recoveryEngine`へ差し替わり`title`/`explanation`が空文字固定になる時点で、
 * 本関数を`RecoveryScreen`へ結線し、かつstrings.xmlへ専用キーを追加する必要がある
 * （TODO、完了報告のC5申し送り参照）。
 */
@Composable
fun resolveRecoveryOptionTitle(semanticAction: String): String {
    // TODO(P6-C5): strings.xmlへ recovery_option_title_keep_all_steps / _skip_optional_steps /
    // _skip_optional_and_important_steps / _change_transport_mode（ja/en）を追加し、既知4キーを
    // それぞれ専用のstringResourceへ解決するよう差し替える。
    return when (semanticAction) {
        "keep_all_steps",
        "skip_optional_steps",
        "skip_optional_and_important_steps",
        "change_transport_mode" -> stringResource(R.string.step_title_fallback)
        else -> stringResource(R.string.step_title_fallback)
    }
}

/**
 * [resolveRecoveryOptionTitle]と対の説明文解決関数（§7.7）。[eta]は該当候補の
 * `RecoveryOption.estimatedArrival`（§32のETA表示要件）。
 *
 * `eta`はP6-C5でstrings.xmlへ専用の`recovery_option_explanation_*`キー（ETAをフォーマット文字列
 * として埋め込む形式）が追加された時点で使用する想定のため、現時点では未使用のまま宣言のみ
 * 保持する（[resolveRecoveryOptionTitle]と同じ暫定フォールバック方針。T-RECUI-2/3/8を
 * Green化する最小実装）。
 */
@Composable
fun resolveRecoveryOptionExplanation(semanticAction: String, eta: Instant?): String {
    // TODO(P6-C5): resolveRecoveryOptionTitleと同じ4キーをrecovery_option_explanation_*へ解決し、
    // etaをフォーマットして埋め込む（現状は未使用引数。strings.xml編集がC5専有のため据え置き）。
    return stringResource(R.string.step_title_fallback)
}
