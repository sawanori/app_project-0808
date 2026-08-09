package com.actionstarter.services.notification

import android.content.Context
import com.actionstarter.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * F49関連（計画書§6.1）。通知の文言・時刻フォーマットを組み立てる。S-8裁定は
 * `semanticId → @StringRes Int`の解決を非Composeの中立パッケージ
 * `com.actionstarter.i18n.StepTitleKeys`（ui-implementer所有、P5-C4で新設予定）経由とする
 * 契約だが定めている。
 *
 * **P5-C3実装時点の暫定解決（本サイクルの制約による判断）**: 本タスクの制約により
 * `strings.xml`／`values-ja/strings.xml`は凍結（P5-C4／統合ウィンドウ対象）であり、
 * `i18n.StepTitleKeys`（ui-implementer・P5-C4成果物）も本サイクル時点で未作成のため、
 * S-8が設計する経路をそのまま使うことができない。ハードコードUI文字列を新規に
 * 追加しない（§7）という制約を満たすため、本クラスは**新規stringを一切追加せず、
 * 既存のstring resource（`R.string.departure_title`／`departure_estimated_arrival_label`／
 * `departure_event_label`／`departure_buffer_label`／`departure_buffer_minutes_format`／
 * `step_title_transition`／`execution_now_label`。いずれもP1〜P4で確定済み・
 * `StringResourceParityTest`のja/en対象）のみを`context.getString(...)`で組み合わせて
 * 通知本文を構築する**。DEPARTURE本文は§29が要求する「到着予測・イベント開始・buffer」の
 * 3要素を、まさにこれらの値を表示するDepartureScreen（`features/departure/
 * DepartureScreen.kt`）と同一のラベル資源で構成しているため意味的に正確である。
 * TRANSITION_START本文は専用stringが存在しないため`execution_now_label`＋
 * `step_title_transition`の組み合わせで暫定する（文言の練り上げはP5-C4/C5でui-implementerが
 * 専用stringを追加してから行う想定。§14申し送り事項）。
 *
 * 契約scaffold（P5-C1）で`i18n.StepTitleKeys`経由に一本化する設計が正式に確定した場合、
 * 本クラスの内部実装のみを差し替える（呼び出し側シグネチャ・戻り値は不変）。
 *
 * DEPARTURE通知は到着予測・イベント開始・bufferを含む（§29、T-NOTIF-2。「Leave now」
 * だけでは不足）。
 *
 * @param context string resource解決に使う`applicationContext`。
 */
class NotificationContentBuilder(private val context: Context) {

    /** TRANSITION_START／DEPARTURE通知のタイトルを組み立てる（T-NOTIF-1）。 */
    fun buildTitle(kind: NotificationKind, payload: NotificationPayload): String = when (kind) {
        NotificationKind.TRANSITION_START -> context.getString(R.string.step_title_transition)
        NotificationKind.DEPARTURE -> context.getString(R.string.departure_title)
        // Phase 6 defines Recovery notification content (S-5); notifyNow(RECOVERY, ...) is never
        // invoked by any Phase 5 code path (AndroidNotificationService.schedule only derives
        // TRANSITION_START/DEPARTURE triggers), so this branch exists solely for `when`
        // exhaustiveness over the 3-member closed set (T-NOTIF-4).
        NotificationKind.RECOVERY -> ""
    }

    /** 通知本文を組み立てる（T-NOTIF-2）。 */
    fun buildText(kind: NotificationKind, payload: NotificationPayload): String = when (kind) {
        NotificationKind.TRANSITION_START ->
            "${context.getString(R.string.execution_now_label)}: ${context.getString(R.string.step_title_transition)}"
        NotificationKind.DEPARTURE -> buildDepartureText(payload)
        NotificationKind.RECOVERY -> ""
    }

    /**
     * §29「単に『出発時間です』では不足」の要求どおり、到着予測・イベント開始・bufferの
     * 3要素すべてを含める。[NotificationPayload.estimatedArrivalAt]が変わればテキストも
     * 追随して変わる（T-NOTIF-2、ハードコード固定文言でないことの検証）。
     */
    private fun buildDepartureText(payload: NotificationPayload): String {
        val formatter = timeFormatter()
        val arrivalText = formatter.format(payload.estimatedArrivalAt)
        val eventStartText = formatter.format(payload.eventStartAt)
        val bufferText = context.getString(R.string.departure_buffer_minutes_format, payload.arrivalBuffer.toMinutes())

        return "${context.getString(R.string.departure_title)} " +
            "${context.getString(R.string.departure_estimated_arrival_label)} $arrivalText, " +
            "${context.getString(R.string.departure_event_label)} $eventStartText, " +
            "${context.getString(R.string.departure_buffer_label)} $bufferText"
    }

    private fun timeFormatter(): DateTimeFormatter {
        val locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
        return DateTimeFormatter.ofPattern("HH:mm", locale).withZone(ZoneId.systemDefault())
    }
}
