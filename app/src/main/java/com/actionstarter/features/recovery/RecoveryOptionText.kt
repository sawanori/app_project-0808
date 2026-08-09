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
 * P6-C5統合ウィンドウでja/en両`strings.xml`へ同時追加する（計画書§6.4#3、
 * `StringResourceParityTest`が検査対象）。本サイクル（P6-C1）はハードコードせず宣言のみ行う。
 *
 * ロジック本体はP6-C4で実装する（TDD厳守。T-RECUI-2/3/8。本ファイルはP6-C1時点では宣言のみ）。
 */
@Composable
fun resolveRecoveryOptionTitle(semanticAction: String): String {
    TODO("P6-C4で実装（§7.7、T-RECUI-2/3。未知キーはフォールバック文言を返しクラッシュしない）")
}

/**
 * [resolveRecoveryOptionTitle]と対の説明文解決関数（§7.7）。[eta]は該当候補の
 * `RecoveryOption.estimatedArrival`（§32のETA表示要件）。`null`の場合はETAに触れない文言へ
 * フォールバックする契約とする想定（偽値を表示しない、T-RECUI-8。要検証・P6-C4で確定）。
 */
@Composable
fun resolveRecoveryOptionExplanation(semanticAction: String, eta: Instant?): String {
    TODO("P6-C4で実装（§7.7、T-RECUI-2/3/8）")
}
