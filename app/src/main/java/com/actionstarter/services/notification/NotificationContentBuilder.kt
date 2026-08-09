package com.actionstarter.services.notification

import android.content.Context
import com.actionstarter.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * F49関連（計画書§6.1・§7.3）。通知の文言・時刻フォーマットを組み立てる。S-8裁定は
 * `semanticId → @StringRes Int`の解決を非Composeの中立パッケージ
 * `com.actionstarter.i18n.StepTitleKeys`（ui-implementer所有、P5-C4で新設予定）経由とする
 * 契約を定めている。
 *
 * **P5-C6統合ウィンドウでの差し替え（§10.6申し送り②の是正）**: P5-C3実装時点では
 * `strings.xml`が凍結されていたため、既存のDeparture／StepTitle／Execution文言
 * （`departure_title`等・`step_title_transition`・`execution_now_label`）を暫定的に
 * `context.getString(...)`で借用していた。P5-C6で通知専用のstring resource
 * （`notification_*`接頭辞、`values/strings.xml`／`values-ja/strings.xml`にja/en対で追加済み）へ
 * 差し替えた。DepartureScreen／StepTitleの文言と意味的には近いが、リソースIDを分離することで
 * 通知文言と画面文言が互いに独立して変更できるようにする（借用のままでは、将来Departure画面の
 * 表記だけを変えたいときに通知文言まで意図せず変わってしまう）。
 *
 * `i18n.StepTitleKeys`（ui-implementer・P5-C4成果物）は本書作成時点（P5-C6）でも
 * リポジトリに未作成であることを確認済み（`grep -rn StepTitleKeys app/src/main`で非存在を
 * 確認）。S-8が設計する`semanticId → @StringRes Int`経由への一本化はそちらが作成された
 * 時点でTRANSITION_START分岐を差し替える形の申し送り事項とする（本クラスの呼び出し側
 * シグネチャ・戻り値は不変のまま内部実装のみ差し替え可能）。
 *
 * DEPARTURE通知は到着予測・イベント開始・bufferを含む（§29、T-NOTIF-2。「Leave now」
 * だけでは不足）。
 *
 * @param context string resource解決に使う`applicationContext`。
 */
class NotificationContentBuilder(private val context: Context) {

    /** TRANSITION_START／DEPARTURE通知のタイトルを組み立てる（T-NOTIF-1）。 */
    fun buildTitle(kind: NotificationKind, payload: NotificationPayload): String = when (kind) {
        NotificationKind.TRANSITION_START -> context.getString(R.string.notification_transition_start_title)
        NotificationKind.DEPARTURE -> context.getString(R.string.notification_departure_title)
        // Phase 6 defines Recovery notification content (S-5); notifyNow(RECOVERY, ...) is never
        // invoked by any Phase 5 code path (AndroidNotificationService.schedule only derives
        // TRANSITION_START/DEPARTURE triggers), so this branch exists solely for `when`
        // exhaustiveness over the 3-member closed set (T-NOTIF-4). The string itself is real
        // (not empty) so Phase 6 can wire the fire path without also touching this file.
        NotificationKind.RECOVERY -> context.getString(R.string.notification_recovery_title)
    }

    /** 通知本文を組み立てる（T-NOTIF-2）。 */
    fun buildText(kind: NotificationKind, payload: NotificationPayload): String = when (kind) {
        NotificationKind.TRANSITION_START -> context.getString(R.string.notification_transition_start_text)
        NotificationKind.DEPARTURE -> buildDepartureText(payload)
        NotificationKind.RECOVERY -> context.getString(R.string.notification_recovery_text)
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
        val bufferText = context.getString(
            R.string.notification_departure_buffer_minutes_format,
            payload.arrivalBuffer.toMinutes()
        )

        return "${context.getString(R.string.notification_departure_title)} " +
            "${context.getString(R.string.notification_departure_arrival_label)} $arrivalText, " +
            "${context.getString(R.string.notification_departure_event_label)} $eventStartText, " +
            "${context.getString(R.string.notification_departure_buffer_label)} $bufferText"
    }

    private fun timeFormatter(): DateTimeFormatter {
        val locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
        return DateTimeFormatter.ofPattern("HH:mm", locale).withZone(ZoneId.systemDefault())
    }
}
