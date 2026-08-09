package com.actionstarter.services.notification

import java.time.Instant

/**
 * L3境界（計画書§7.3）。`android.app.AlarmManager`を隠蔽する通常interface
 * （`fun interface`ではない——予約と取消の2責務を持つため。`services/location/
 * RawLocationSource`（Phase 3）と同型のL3パターン）。
 *
 * **実測（P5-C1 probe P5-P3）**: Robolectric `ShadowAlarmManager`は
 * `setCanScheduleExactAlarms(false)`の状態でも実`AlarmManager.setExactAndAllowWhileIdle`を
 * 例外なく受理し`windowLengthMs = WINDOW_EXACT`のまま記録する（=exact/inexactの自動判定・
 * フォールバックはOSレイヤーでは一切シミュレートされない）。したがって
 * **exact可否の判定と、それに基づくAPI選択（`setExactAndAllowWhileIdle`か
 * `setAndAllowWhileIdle`か）は実装（[AlarmManagerAlarmScheduler]）が`canScheduleExactAlarms()`
 * を明示チェックして行う必要がある**（呼び出しの結果から自動的に判別できない）。
 * 詳細は計画書§10.2 P5-P3実測結果を参照。
 *
 * 実装は[AlarmManagerAlarmScheduler]（P5-C1では宣言のみ、P5-C3で実装）。JVMテストでは
 * fake実装を注入して`android.app.AlarmManager`に一切触れずにテストできる。
 */
interface AlarmScheduler {
    /**
     * [trigger]を予約する。exact許可判定・`SecurityException`捕捉・inexactフォールバックは
     * 実装（[AlarmManagerAlarmScheduler]）の責務（エラー&レスキューマップ#1・#2）。
     * 同一`requestCode`（[trigger]の[NotificationPayload.requestCode]）での再呼び出しは
     * 既存アラームを置換する（T-ALARM-4）。
     */
    fun schedule(trigger: AlarmTrigger): AlarmScheduleOutcome

    /** 指定`requestCode`のアラームを取り消す。存在しない場合は何もしない（T-BOOT-5相当）。 */
    fun cancel(requestCode: Int)
}

/** [AlarmScheduler.schedule]の入力（計画書§7.3）。`requestCode`は[payload]から取得する。 */
data class AlarmTrigger(
    val triggerAt: Instant,
    val kind: NotificationKind,
    val payload: NotificationPayload
)

/** [AlarmScheduler.schedule]の結果。exact登録できたか、inexactへ縮退したか。 */
enum class AlarmScheduleOutcome {
    EXACT,
    DEGRADED_INEXACT
}
