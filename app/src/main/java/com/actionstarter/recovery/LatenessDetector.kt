package com.actionstarter.recovery

import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.RecoveryContext
import java.time.Duration
import java.time.Instant

/**
 * 計画書§7.6準拠（F71/F72、P6-C1 scaffold）。[LatenessDetector.evaluate] の決定的な判定結果。
 *
 * 発火条件は `ETA(∅) > event.startDate` の1点のみ（A案のまま進むと予定が成立しない）。
 * `currentTime > plannedDepartureTime` は [WillMissEvent.behindSchedule] として**情報のみ**
 * 返し、トリガーにはしない（誤発火防止・§7.6）。境界は等号込みで [OnTrack]（T-LATE-3）。
 */
sealed interface LatenessVerdict {
    /** 予定が成立する（`ETA(∅) <= event.startDate`、境界の等号を含む）。 */
    data object OnTrack : LatenessVerdict

    /**
     * 予定が成立しない。
     *
     * @param delay `projectedArrival - event.startDate`（正の遅延量）
     * @param projectedArrival A案のまま進んだ場合の到着見込み（`ETA(∅)`）
     * @param behindSchedule `currentTime > plannedDepartureTime` と一致する情報のみのフラグ
     *   （§7.6：トリガーには使わない。誤発火防止）
     */
    data class WillMissEvent(
        val delay: Duration,
        val projectedArrival: Instant,
        val behindSchedule: Boolean
    ) : LatenessVerdict
}

/**
 * 計画書§7.6準拠（F71/F72、P6-C1 scaffold）。Phase 5（通知／FGS／AlarmManager）に一切依存しない
 * フォアグラウンド限定の遅延判定（仕様§30・§13「遅延検知」）。
 *
 * 純関数として公開する: [context] 以外のシステム時刻・状態を参照しない（`currentTime` は
 * [RecoveryContext.currentTime] 由来）。呼び出し契機（Execution route入場時／Recovery画面表示時）
 * の結線は呼び出し側（`ActionStarterNavHost`・`RecoveryViewModel`）の責務であり、本オブジェクトは
 * 評価APIの提供のみを行う。Phase 5がstep progression（Done/Snooze）実装後にこれを呼び出す契機を
 * 追加する（§7.6申し送り）。
 *
 * P6-C3実装（T-LATE-1〜10）。計画書§7.2のR_all定義（TRANSITION/PREPARATIONのみ・
 * `completedAt == null`のみを合算）をそのまま流用する（`BasicRecoveryEngine`と同一の
 * 決定的計算式。§13「数値計算は必ず通常コード」）。
 */
object LatenessDetector {
    fun evaluate(context: RecoveryContext): LatenessVerdict {
        require(!context.latestTravelEstimate.isNegative) {
            "RecoveryContext.latestTravelEstimate must not be negative, was ${context.latestTravelEstimate}"
        }

        val remainingPreparation = context.unfinishedSteps
            .filter {
                it.completedAt == null &&
                    (it.type == ExecutionStepType.TRANSITION || it.type == ExecutionStepType.PREPARATION)
            }
            .fold(Duration.ZERO) { acc, step -> acc.plus(step.estimatedDuration) }

        val projectedArrival: Instant = context.currentTime
            .plus(remainingPreparation)
            .plus(context.latestTravelEstimate)

        return if (!projectedArrival.isAfter(context.event.startDate)) {
            LatenessVerdict.OnTrack
        } else {
            LatenessVerdict.WillMissEvent(
                delay = Duration.between(context.event.startDate, projectedArrival),
                projectedArrival = projectedArrival,
                behindSchedule = context.currentTime.isAfter(context.plannedDepartureTime)
            )
        }
    }
}
