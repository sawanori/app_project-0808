package com.actionstarter.services.execution

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.services.location.ActivityLifecycleForegroundGate
import com.actionstarter.services.notification.DegradationReason

/**
 * F56/F57実装（計画書§6.1・§7.4）。[ExecutionForegroundService]のstart/stopを仲介し、
 * [isRunning]フラグ（プロセス内メモリのみ。プロセス死で自然にfalseへ戻るのが正しい挙動）を
 * 保持する。`ForegroundGate.isExecutionServiceRunning`への接続は
 * `ActionStarterApplication.onCreate()`（共有ファイル・P5-C6統合ウィンドウ）で
 * `foregroundGate.isExecutionServiceRunning = executionServiceController::isRunning`の
 * 1行で行う（M5-13、本クラスは変更しない）。
 *
 * **§95.1(b)の前提保護（T-FGS-5）**: [start]は[foregroundGate]の`isAppInForeground()`が
 * trueのときのみ起動を許す。`ForegroundGate`（`services/location/`の`fun interface`、
 * `isLocationAccessAllowed()`のみ公開）ではなく具象クラス[ActivityLifecycleForegroundGate]
 * を直接要求するのは、(a) `isAppInForeground()`が後者にのみ公開されたメソッドであり、
 * (b) `isLocationAccessAllowed() = isAppInForeground() || isExecutionServiceRunning()`を
 * 使うと`isExecutionServiceRunning`経由で本クラス自身の状態へ間接的に依存する循環になる
 * ため（計画書§7.4原文が`foregroundGate.isAppInForeground()`と明示している）。
 * `services/location/`のファイルは変更しない（既存の[ActivityLifecycleForegroundGate]を
 * そのまま参照するのみ）。
 *
 * **[isRunning]はP5-C1（本サイクル）で完全実装する（TDD例外。計画書§10.1 P5-C1行
 * 「例外的に完全実装するのは`ExecutionServiceController`の`isRunning`フラグのみ」）**:
 * 将来`ActionStarterApplication`へ無条件接続されると、`TODO()`のままでは
 * `isLocationAccessAllowed()`を呼ぶ全Robolectricテストが`NotImplementedError`で
 * 壊れる（ADR-0023の[ActivityLifecycleForegroundGate]実装判断と同型の構造的必然）。
 * [start]／[stop]自体（副作用のあるロジック）はP5-C2のRedテストに基づきP5-C3で実装する。
 *
 * 契約scaffold（P5-C1、TDD例外）時点では[start]／[stop]は宣言のみであり、実装本体は
 * P5-C3で行う。
 *
 * @param context Service起動に使う`applicationContext`。
 */
class ExecutionServiceController(
    private val context: Context,
    private val foregroundGate: ActivityLifecycleForegroundGate
) {
    /**
     * Execution Foreground Serviceが起動中かどうか。プロセス内メモリのみに保持し
     * 永続化しない（§7.4「プロセス死で自然にfalseへ戻るのが正しい挙動」）。
     */
    var isRunning: Boolean = false
        private set

    /**
     * P5-C3実装。[plan]でExecution Foreground Serviceを起動する（T-FGS-1〜T-FGS-6）。
     * 2段階でガードし、いずれも例外を待たず事前チェックで判定する（P5-P2実測に基づく
     * `ContextCompat.checkSelfPermission`事前チェック方式、`build/agent-logs/
     * p5-probes-device.md`「実装ガードレール」）:
     * 1. §95.1(b)の前提保護（T-FGS-5）: [foregroundGate]の`isAppInForeground()`が
     *    `false`なら起動を試みず[ExecutionServiceSkipReason.NOT_IN_FOREGROUND]で
     *    [ExecutionServiceStartResult.Skipped]を返す。
     * 2. 位置権限事前チェック（T-FGS-6）: `ACCESS_FINE_LOCATION`／`ACCESS_COARSE_LOCATION`の
     *    いずれも許可されていなければ起動を試みず
     *    [DegradationReason.FOREGROUND_SERVICE_UNAVAILABLE]で
     *    [ExecutionServiceStartResult.Degraded]を返す（P5-P2実測の`SecurityException`
     *    メッセージが要求する「いずれか1つ」の条件と一致）。
     *
     * いずれのガードも通過した場合のみ`startForegroundService`を実際に呼び、[isRunning]を
     * `true`にする。
     */
    fun start(plan: ExecutionPlan): ExecutionServiceStartResult {
        if (!foregroundGate.isAppInForeground()) {
            return ExecutionServiceStartResult.Skipped(ExecutionServiceSkipReason.NOT_IN_FOREGROUND)
        }

        val hasLocationPermission = hasAnyLocationPermission()
        if (!hasLocationPermission) {
            return ExecutionServiceStartResult.Degraded(DegradationReason.FOREGROUND_SERVICE_UNAVAILABLE)
        }

        context.startForegroundService(Intent(context, ExecutionForegroundService::class.java))
        isRunning = true
        return ExecutionServiceStartResult.Started
    }

    /** Execution Foreground Serviceを停止する（T-FGS-2）。 */
    fun stop() {
        context.stopService(Intent(context, ExecutionForegroundService::class.java))
        isRunning = false
    }

    private fun hasAnyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}

/** [ExecutionServiceController.start]の戻り値。 */
sealed interface ExecutionServiceStartResult {
    data object Started : ExecutionServiceStartResult
    data class Degraded(val reason: DegradationReason) : ExecutionServiceStartResult
    data class Skipped(val reason: ExecutionServiceSkipReason) : ExecutionServiceStartResult
}

/** [ExecutionServiceStartResult.Skipped]の理由（T-FGS-5）。 */
enum class ExecutionServiceSkipReason {
    NOT_IN_FOREGROUND
}
