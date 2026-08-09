package com.actionstarter.services.notification

import android.content.Context

/**
 * F49関連（計画書§6.1）。通知の文言・時刻フォーマットを組み立てる。S-8裁定により
 * `semanticId → @StringRes Int`の解決は非Composeの中立パッケージ
 * `com.actionstarter.i18n.StepTitleKeys`（ui-implementer所有、P5-C4で新設予定）を経由する
 * 契約とする。
 *
 * **本サイクル（P5-C1）時点の依存関係についての注記**: `i18n.StepTitleKeys`はP5-C4（並列
 * トラックB、ui-implementer担当）の成果物であり、本サイクル（P5-C1、domain-implementer・
 * 契約scaffold）時点ではまだ存在しない。そのため本ファイルは現時点でそれをimportしない。
 * P5-C3（並列トラックA、Green）実装時に参照し、P5-C5同期ポイントでAPI齟齬を解消する
 * （計画書§10.1 P5-C3〜C5）。
 *
 * ハードコードUI文字列を持ち込まない（§7）。DEPARTURE通知は到着予測・イベント開始・
 * bufferを含む（§29、T-NOTIF-2。「Leave now」だけでは不足）。
 *
 * 契約scaffold（P5-C1、TDD例外）時点では宣言のみであり、実装はP5-C3で行う。
 *
 * @param context string resource解決に使う`applicationContext`。
 */
class NotificationContentBuilder(private val context: Context) {
    /** TRANSITION_START／DEPARTURE通知のタイトルを組み立てる（T-NOTIF-1）。 */
    fun buildTitle(kind: NotificationKind, payload: NotificationPayload): String = TODO()

    /** 通知本文を組み立てる（T-NOTIF-2）。 */
    fun buildText(kind: NotificationKind, payload: NotificationPayload): String = TODO()
}
