package com.actionstarter.services.notification

import android.content.Context
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.persistence.ExecutionScheduleStore
import com.actionstarter.services.permission.PermissionGate

/**
 * F49/F52実装（計画書§6.1・§7.3）。[NotificationService]のAndroid実装。
 * `NotificationManagerCompat`＋チャネル管理＋[NotificationContentBuilder]による文言解決を
 * 束ね、[ExecutionPlan]からトリガー列を導出して[ExecutionScheduleStore]に保存したうえで
 * [AlarmScheduler]へ登録を依頼する（計画書§7.3が挙げる
 * `AlarmSchedulingCoordinator`の責務は本クラス内へ折り込む——計画書§6.1のフットプリント
 * 一覧が独立ファイルとして列挙していないため）。
 *
 * POST_NOTIFICATIONS権限判定は既存[PermissionGate.isGranted]の汎用シグネチャを再利用する
 * （計画書§6.4「Phase 2領域」注記のとおり、`services/permission/`への変更は不要）。
 *
 * 契約scaffold（P5-C1、TDD例外）時点では宣言のみであり、実装本体はP5-C3で行う。
 */
class AndroidNotificationService(
    private val context: Context,
    private val store: ExecutionScheduleStore,
    private val alarmScheduler: AlarmScheduler,
    private val contentBuilder: NotificationContentBuilder,
    private val permissionGate: PermissionGate
) : NotificationService {
    override fun schedule(plan: ExecutionPlan): ScheduleResult = TODO()

    override fun cancelAll(planId: String) {
        TODO()
    }

    override fun notifyNow(kind: NotificationKind, payload: NotificationPayload): NotifyResult = TODO()

    override fun restoreFromStore(): ScheduleResult = TODO()
}
