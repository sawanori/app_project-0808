package com.actionstarter.services.notification

import com.actionstarter.domain.model.ExecutionPlan
import java.time.Duration
import java.time.Instant

/**
 * 仕様§43準拠（`Services`直下、F49）。通知3種（§62）の提示・Exact Alarmの予約を扱う
 * L1契約（計画書§7.3）。`AlarmManager`／`NotificationManager`等のAndroid型を一切
 * 外へ出さない。
 *
 * [schedule]／[cancelAll]／[notifyNow]は計画書§7.3が明示するシグネチャそのもの
 * （いずれも非suspend。`AlarmManager`／`NotificationManagerCompat`呼び出しは同期API）。
 *
 * [restoreFromStore]はF54（`ScheduleRestoreReceiver`のBOOT/TIME/TIMEZONE/EXACT_ALARM
 * 権限変更再登録、および「アプリ起動時の整合性チェック再登録」）が必要とするが、計画書§7.3の
 * 3メソッド列挙には明記されていなかったため、P5-C1（本サイクル・契約scaffold）でシグネチャ
 * 未定義箇所を補完した（ADR-0022と同型の対応）。ブート後は永続化済み
 * [com.actionstarter.persistence.ExecutionScheduleRecord]（PIIゼロ）のみが手元にあり、
 * [schedule]が要求する完全な[ExecutionPlan]は存在しないため、専用の再登録経路が必要になる。
 *
 * 契約scaffold（P5-C1、TDD例外）時点では宣言のみであり、実装（[AndroidNotificationService]）
 * はP5-C3で行う。
 */
interface NotificationService {
    /** [plan]からトリガー列を導出し、ストアへ保存したうえでアラームを登録する。 */
    fun schedule(plan: ExecutionPlan): ScheduleResult

    /** [planId]に紐づく登録済みアラーム・ストアレコードを全て取り消す。 */
    fun cancelAll(planId: String)

    /** アラーム発火時に即座通知を提示する（[NotificationTriggerReceiver]から呼ばれる）。 */
    fun notifyNow(kind: NotificationKind, payload: NotificationPayload): NotifyResult

    /** F54。永続化済み[com.actionstarter.persistence.ExecutionScheduleRecord]群から再登録する。 */
    fun restoreFromStore(): ScheduleResult

    /**
     * exact alarm可否は現在の能力状態であり、[schedule]の一回の結果ではない——ユーザーが設定画面
     * から随時許可/剥奪し得るため読み取り専用照会を契約化。ADR-0026・P5-P1実測（API 35初期値
     * false）参照。Fable 5承認済み契約追加2026-08-09。
     */
    fun isExactAlarmAvailable(): Boolean
}

/**
 * 通知3種（§62、閉じた集合。T-NOTIF-4で要素数3を回帰ロック）。[RECOVERY]はenum定数の
 * 宣言のみ（S-5、R-7）。Phase 5では発火経路・チャネル生成を作らない
 * （`recovery/`パッケージへの依存は一切作らない。Phase 6＝§70で発火経路を実装する）。
 */
enum class NotificationKind {
    TRANSITION_START,
    DEPARTURE,
    RECOVERY
}

/**
 * 通知提示・アラーム予約に必要な最小情報（計画書§7.3）。**PIIゼロ**（§58/§60、T-NOTIF-8・
 * T-STORE-7）: イベントタイトル・住所・座標を一切含めない。文言は
 * [NotificationContentBuilder]が[stepSemanticId]＋時刻から組み立てる（S-8）。
 */
data class NotificationPayload(
    val planId: String,
    val requestCode: Int,
    val stepSemanticId: String,
    val eventStartAt: Instant,
    val estimatedArrivalAt: Instant,
    val arrivalBuffer: Duration
)

/**
 * [NotificationService.schedule]／[NotificationService.restoreFromStore]の戻り値
 * （計画書§7.3）。`Boolean`／`Unit`を返さない——exactで組めたのかinexactに落ちたのかが
 * 戻り値に現れないと、§95.6が要求する「精度低下をユーザーに明示」を実装する手段が消える
 * （Pass 1サイレント障害）。
 */
sealed interface ScheduleResult {
    /** exactでcount件のアラームを登録した。 */
    data class Exact(val count: Int) : ScheduleResult

    /** count件を登録したが、いずれかの理由で精度が低下した。 */
    data class Degraded(val count: Int, val reason: DegradationReason) : ScheduleResult

    /** 1件も登録しなかった（例: 全トリガーが過去。T-ALARM-8）。 */
    data class Skipped(val reason: ScheduleSkipReason) : ScheduleResult
}

/** [NotificationService.notifyNow]の戻り値。 */
sealed interface NotifyResult {
    data object Shown : NotifyResult
    data class Skipped(val reason: DegradationReason) : NotifyResult
}

/**
 * 劣化理由（計画書§7.3・エラー&レスキューマップ#1〜#6）。[NotificationService]と
 * `services/execution/ExecutionServiceController`（F56/F57）双方の劣化表現で共有する
 * 横断的な理由コードである（[FOREGROUND_SERVICE_UNAVAILABLE]はFGS起動失敗時に
 * ExecutionServiceController側からも参照される。エラー&レスキューマップ#5・#6）。
 */
enum class DegradationReason {
    EXACT_ALARM_NOT_PERMITTED,
    NOTIFICATIONS_DISABLED,
    FOREGROUND_SERVICE_UNAVAILABLE
}

/** [ScheduleResult.Skipped]の理由（T-ALARM-8）。 */
enum class ScheduleSkipReason {
    NO_FUTURE_TRIGGERS
}
