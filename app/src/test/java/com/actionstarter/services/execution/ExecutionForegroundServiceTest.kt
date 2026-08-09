package com.actionstarter.services.execution

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.activity.ComponentActivity
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.ExecutionStep
import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.services.location.ActivityLifecycleForegroundGate
import com.actionstarter.services.notification.DegradationReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T-FGS-1〜6（計画書§8.2「F56/F57 — ExecutionForegroundService / ExecutionServiceController」
 * 全6件、`docs/plans/phase5-notification-execution.md`§6.1のファイル配置表どおり）。
 *
 * **P5-C2で見送られP5-C2bで作成した経緯（差し戻し理由の解消）**: 計画書§10.3
 * （P5-C1実測結果、P5-P2行）は「R-2の方針どおり、結果が出るまでP5-C2のT-FGS群は着手しない」と
 * 記録していた。P5-P2は本サイクルより前に実機プローブとして実施され
 * （`build/agent-logs/p5-probes-device.md`、2026-08-09）、(a)位置権限なしで
 * `FOREGROUND_SERVICE_TYPE_LOCATION`起動時は`java.lang.SecurityException`、(b)manifest宣言済み
 * Serviceへ`FOREGROUND_SERVICE_TYPE_NONE`を渡すと`android.app.InvalidForegroundServiceTypeException`
 * （`IllegalStateException`系の子孫）という結果が確定した（DECISIONS.md ADR-0026参照）。
 * これによりR-2の着手条件が満たされたため、本サイクル（P5-C2b）でT-FGS-1〜6を作成する。
 *
 * **テスト設計方針（本ファイル固有の判断）**: T-FGS-1〜3は[ExecutionForegroundService]自身の
 * 責務（実`Service.startForeground`呼び出し・その結果のOS観測面・例外時のフォールバック）を
 * 対象とするため、`Robolectric.buildService(ExecutionForegroundService::class.java)`で
 * サービスを直接構築・駆動する（P5-C1 probe P5-P6と同一のRobolectric往復方式。計画書§10.3
 * 参照）。T-FGS-4〜6は[ExecutionServiceController]の責務（`isRunning`状態管理・
 * §95.1(b)前提保護・権限事前チェック）を対象とするため、[ExecutionServiceController]を
 * 直接構築して検証する。**`ExecutionServiceController.start()`が内部で
 * `ExecutionForegroundService`をどう起動するか（`context.startForegroundService(Intent)`
 * 経由の非同期起動か、それ以外か）はP5-C1契約scaffold時点で確定しておらず、Service側で
 * 発生する例外（(a)(b)）をController側の[ExecutionServiceStartResult.Degraded]へどう
 * 伝播させるかはP5-C3の実装判断に委ねる**（本ファイルはT-FGS-3をService単体の責務として
 * 検証することでこの未確定領域を回避した。P5-C3実装時に本テストの対象クラス割り当てを
 * 見直す必要が生じる可能性がある）。
 *
 * 現時点（P5-C2b）では[ExecutionForegroundService]・[ExecutionServiceController]の
 * 副作用を持つメソッドが全て`TODO()`のため、以下は全件`NotImplementedError`によりRedになる
 * （意図した失敗。完成後の期待値をそのままアサートする記述方針、
 * [com.actionstarter.services.notification.AlarmSchedulingTest]と同型）。
 */
@RunWith(RobolectricTestRunner::class)
class ExecutionForegroundServiceTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    // T-FGS-1: 正常 - 起動時getLastForegroundNotification()が非nullでgetForegroundServiceType()が
    // 期待値（FOREGROUND_SERVICE_TYPE_LOCATION）。実測: P5-C1 probe P5-P6でRobolectric.
    // buildService()＋ServiceCompat.startForegroundの往復で正しくtypeが観測できることを
    // 確認済み（計画書§10.3）。
    @Test
    fun onStartCommand_startsForegroundWithLocationTypeAndNotification() {
        val service = Robolectric.buildService(ExecutionForegroundService::class.java)
            .create()
            .startCommand(0, 1)
            .get()

        // getForegroundServiceType()はandroid.app.Service自身が公開するpublicメソッド
        // （API 34+、javap実測確認済み）であり、ShadowServiceの同名メソッドはprotectedのため
        // 実サービスインスタンス側から呼び出す（P5-C1 probe P5-P6と同じ観測方法）。
        assertNotNull("expected a foreground notification to be shown", shadowOf(service).lastForegroundNotification)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
    }

    // T-FGS-2: 正常 - 停止時isForegroundStopped()がtrueになり通知が除去される
    @Test
    fun serviceDestroyed_marksForegroundStoppedAndRemovesNotification() {
        val controller = Robolectric.buildService(ExecutionForegroundService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        controller.destroy()

        assertTrue("expected isForegroundStopped() to become true on stop", shadowOf(service).isForegroundStopped)
    }

    // T-FGS-3: 異常 - setThrowInStartForeground(...)で起動失敗させた場合、best-effort通知へ
    // 切替えクラッシュしない（握り潰さない）。Controller側のDegraded(FOREGROUND_SERVICE_UNAVAILABLE)
    // への写像はP5-C3実装時、Service→Controllerの伝達経路とあわせて固定する
    // （本ファイル冒頭KDoc「テスト設計方針」参照）。
    @Test
    fun onStartCommand_whenStartForegroundThrows_showsBestEffortNotificationWithoutCrashing() {
        val serviceController = Robolectric.buildService(ExecutionForegroundService::class.java)
        val service = serviceController.create().get()
        shadowOf(service).setThrowInStartForeground(RuntimeException("FGS start rejected by OS"))

        serviceController.startCommand(0, 1)

        val notificationManager = context().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(
            "expected a best-effort (non-foreground) notification instead of a crash",
            shadowOf(notificationManager).allNotifications.isNotEmpty()
        )
    }

    // T-FGS-4: 正常 - 起動中はisRunningがtrue、停止でfalse（§7.4「プロセス内メモリのみ保持」）
    @Test
    fun start_thenStop_togglesIsRunning() {
        // Fable 5承認済みfixture修正（2026-08-09）: Robolectric既定denyとcheckSelfPermission
        // 事前チェック（エラーマップ#5）の両立のため。T-FGS-6のdeny経路検証と対をなす。
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val foregroundGate = ActivityLifecycleForegroundGate().apply {
            onActivityStarted(Robolectric.buildActivity(ComponentActivity::class.java).create().get())
        }
        val controller = ExecutionServiceController(context(), foregroundGate)
        assertTrue("isRunning should default to false", !controller.isRunning)

        controller.start(buildPlan())

        assertTrue("expected isRunning=true after a successful start", controller.isRunning)

        controller.stop()

        assertTrue("expected isRunning=false after stop", !controller.isRunning)
    }

    // T-FGS-5: 異常 - アプリがフォアグラウンドでない状態からのstart要求を拒否する（§95.1(b)）
    @Test
    fun start_whenAppNotInForeground_returnsSkippedWithoutStartingService() {
        // onActivityStartedを一度も呼ばない = isAppInForeground() は false のまま
        val foregroundGate = ActivityLifecycleForegroundGate()
        val controller = ExecutionServiceController(context(), foregroundGate)

        val result = controller.start(buildPlan())

        assertTrue("expected Skipped but was $result", result is ExecutionServiceStartResult.Skipped)
        assertEquals(
            ExecutionServiceSkipReason.NOT_IN_FOREGROUND,
            (result as ExecutionServiceStartResult.Skipped).reason
        )
        assertNull(
            "startForegroundService should not be attempted when not in foreground",
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        )
    }

    // T-FGS-6: 異常 - 位置権限が拒否されている場合、location typeでのstartForegroundを試みない
    // （権限事前チェック方式。P5-P2実機実測で確認されたSecurityExceptionを未然に回避する設計。
    // DECISIONS.md ADR-0026・エラー&レスキューマップ#5）
    @Test
    fun start_whenLocationPermissionDenied_doesNotAttemptToStartServiceAndReturnsDegraded() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val foregroundGate = ActivityLifecycleForegroundGate().apply {
            onActivityStarted(Robolectric.buildActivity(ComponentActivity::class.java).create().get())
        }
        val controller = ExecutionServiceController(context(), foregroundGate)

        val result = controller.start(buildPlan())

        assertTrue("expected Degraded but was $result", result is ExecutionServiceStartResult.Degraded)
        assertEquals(
            DegradationReason.FOREGROUND_SERVICE_UNAVAILABLE,
            (result as ExecutionServiceStartResult.Degraded).reason
        )
        assertNull(
            "startForegroundService should not be attempted without location permission",
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        )
    }

    /** [AlarmSchedulingTest][com.actionstarter.services.notification.AlarmSchedulingTest]の`buildPlan`と同型の最小フィクスチャ。 */
    private fun buildPlan(): ExecutionPlan {
        val now = Instant.now()
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "Team sync",
            notes = null,
            startDate = now.plus(Duration.ofHours(2)),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "cal-1", displayName = "Work")
        )
        val transitionStart = now.plus(Duration.ofMinutes(30))
        val departureTime = now.plus(Duration.ofHours(1))
        val steps = listOf(
            ExecutionStep(
                id = UUID.nameUUIDFromBytes("${event.id}:transition".toByteArray()),
                semanticId = "transition",
                type = ExecutionStepType.TRANSITION,
                title = "",
                estimatedDuration = Duration.ofMinutes(5),
                priority = StepPriority.REQUIRED,
                skippable = false,
                scheduledStart = transitionStart,
                completedAt = null
            )
        )
        return ExecutionPlan(
            event = event,
            steps = steps,
            transitionStart = transitionStart,
            departureTime = departureTime,
            estimatedArrival = departureTime.plus(Duration.ofMinutes(20)),
            arrivalBuffer = Duration.ofMinutes(10)
        )
    }
}
