package com.actionstarter.services.notification

import java.time.Duration

/**
 * 仕様未定義プレースホルダの隔離先（S-9、ADR-0015の先例。計画書§6.1・§7.3・§9#11）。
 *
 * [MISSED_TRIGGER_GRACE_PERIOD]: 復元時に`triggerAt <= now`のトリガーを即時発火させるか
 * 破棄するかの猶予値。仕様に根拠となる数値記載はなく、S-9裁定の既定15分をそのまま採用する。
 * `now`がイベント開始時刻より前かつ経過が本値以内なら即時発火、それ以外は破棄する
 * （無条件即時発火は、電源を切って翌日起動したユーザーに古い出発通知を投げる事故になるため
 * 却下）。
 */
object NotificationDefaults {
    val MISSED_TRIGGER_GRACE_PERIOD: Duration = Duration.ofMinutes(15)
}
