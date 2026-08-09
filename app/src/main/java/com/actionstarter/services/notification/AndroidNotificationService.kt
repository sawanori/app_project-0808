package com.actionstarter.services.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import com.actionstarter.MainActivity
import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.navigation.Destinations
import com.actionstarter.persistence.ExecutionScheduleRecord
import com.actionstarter.persistence.ExecutionScheduleStore
import com.actionstarter.persistence.Trigger
import com.actionstarter.services.permission.PermissionGate
import java.time.Duration
import java.time.Instant
import java.util.UUID

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

    /**
     * P5-C3実装。[plan]からTRANSITION_START／DEPARTUREの2トリガー候補を導出し（RECOVERYは
     * 候補にすら含めない＝T-ALARM-7の回帰ガードを構造的に満たす）、`triggerAt <= now`の
     * ものを除外する（T-ALARM-6・S-9）。すべて過去なら[ScheduleSkipReason.NO_FUTURE_TRIGGERS]
     * で[ScheduleResult.Skipped]を返す（T-ALARM-8）。`requestCode`は同一planId・同一
     * semanticIdの既存レコードがあればそれを再利用し（PendingIntent一意性・冪等な再登録）、
     * なければ[ExecutionScheduleStore.nextRequestCode]から新規に払い出す。
     */
    override fun schedule(plan: ExecutionPlan): ScheduleResult {
        val planId = plan.event.id.toString()
        val now = Instant.now()
        val existingRecord = store.loadAll().records.firstOrNull { it.planId == planId }

        val candidates = listOf(
            TriggerCandidate(NotificationKind.TRANSITION_START, SEMANTIC_ID_TRANSITION, plan.transitionStart),
            TriggerCandidate(NotificationKind.DEPARTURE, SEMANTIC_ID_DEPARTURE, plan.departureTime)
        )
        val futureCandidates = candidates.filter { it.triggerAt.isAfter(now) }

        // Any candidate that is no longer in the future (e.g. re-scheduling after the
        // transition time already elapsed) must have its stale alarm cancelled so it doesn't
        // linger (error map #14, PendingIntent uniqueness/no stale duplicates).
        (candidates - futureCandidates.toSet()).forEach { stale ->
            existingRecord?.triggers?.firstOrNull { it.semanticId == stale.semanticId }
                ?.let { alarmScheduler.cancel(it.requestCode) }
        }

        if (futureCandidates.isEmpty()) {
            if (existingRecord != null && existingRecord.triggers.isNotEmpty()) {
                store.save(existingRecord.copy(triggers = emptyList()))
            }
            return ScheduleResult.Skipped(ScheduleSkipReason.NO_FUTURE_TRIGGERS)
        }

        var anyDegraded = false
        val newTriggers = futureCandidates.map { candidate ->
            val requestCode = existingRecord?.triggers
                ?.firstOrNull { it.semanticId == candidate.semanticId }
                ?.requestCode
                ?: store.nextRequestCode()

            val payload = NotificationPayload(
                planId = planId,
                requestCode = requestCode,
                stepSemanticId = candidate.semanticId,
                eventStartAt = plan.event.startDate,
                estimatedArrivalAt = plan.estimatedArrival,
                arrivalBuffer = plan.arrivalBuffer
            )
            val outcome = alarmScheduler.schedule(
                AlarmTrigger(triggerAt = candidate.triggerAt, kind = candidate.kind, payload = payload)
            )
            if (outcome == AlarmScheduleOutcome.DEGRADED_INEXACT) {
                anyDegraded = true
            }
            Trigger(
                kind = candidate.kind,
                stepId = stepIdFor(plan.event.id, candidate.semanticId),
                semanticId = candidate.semanticId,
                triggerAtEpochMillis = candidate.triggerAt.toEpochMilli(),
                requestCode = requestCode,
                fired = false
            )
        }

        store.save(
            ExecutionScheduleRecord(
                schemaVersion = ExecutionScheduleRecord.CURRENT_SCHEMA_VERSION,
                planId = planId,
                eventStartEpochMillis = plan.event.startDate.toEpochMilli(),
                estimatedArrivalEpochMillis = plan.estimatedArrival.toEpochMilli(),
                triggers = newTriggers
            )
        )

        return if (anyDegraded) {
            ScheduleResult.Degraded(newTriggers.size, DegradationReason.EXACT_ALARM_NOT_PERMITTED)
        } else {
            ScheduleResult.Exact(newTriggers.size)
        }
    }

    /** [planId]に紐づく登録済みアラームを全て取り消し、ストアレコードを削除する。 */
    override fun cancelAll(planId: String) {
        store.loadAll().records.firstOrNull { it.planId == planId }
            ?.triggers
            ?.forEach { alarmScheduler.cancel(it.requestCode) }
        store.clear(planId)
    }

    /**
     * P5-C3実装。POST_NOTIFICATIONS拒否（[permissionGate]）・通知ブロック
     * （[NotificationManagerCompat.areNotificationsEnabled]、T-NOTIF-5）のいずれの場合も
     * `notify`を呼ばず例外も投げず[NotifyResult.Skipped]を返す。通知IDには[payload]の
     * `requestCode`をそのまま流用し、同一kind・同一stepの再通知が同一IDで置換され
     * 増殖しないことを保証する（T-NOTIF-7）。
     */
    override fun notifyNow(kind: NotificationKind, payload: NotificationPayload): NotifyResult {
        if (!permissionGate.isGranted(POST_NOTIFICATIONS_PERMISSION)) {
            return NotifyResult.Skipped(DegradationReason.NOTIFICATIONS_DISABLED)
        }

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return NotifyResult.Skipped(DegradationReason.NOTIFICATIONS_DISABLED)
        }

        ensureChannel(manager, kind)

        val notification = NotificationCompat.Builder(context, channelIdFor(kind))
            .setContentTitle(contentBuilder.buildTitle(kind, payload))
            .setContentText(contentBuilder.buildText(kind, payload))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(kind, payload))
            .build()

        manager.notify(payload.requestCode, notification)
        return NotifyResult.Shown
    }

    /**
     * F54実装本体。永続化済み全レコードを走査し、各トリガーを(a)未来 → cancel後に再登録、
     * (b)過去だがイベント開始前かつ猶予（[NotificationDefaults.MISSED_TRIGGER_GRACE_PERIOD]）
     * 以内 → 即時発火（S-9）、(c)それ以外 → 破棄、の3つに分類する。TIMEZONE_CHANGED等でも
     * `triggerAtEpochMillis`（絶対時刻）を一切再計算しない（ADR-0008、T-BOOT-3）。
     * `arrivalBuffer`は[ExecutionScheduleRecord]がPIIゼロ設計のため永続化していない
     * （T-STORE-7）ため、`BasicPlanningEngine`の定義（`estimatedArrival = eventStart -
     * arrivalBuffer`、すなわち`arrivalBuffer = eventStart - estimatedArrival`）から
     * `eventStartEpochMillis`／`estimatedArrivalEpochMillis`の2フィールドを使って導出する
     * （`derivedArrivalBuffer`）。負値になった場合（過去データ等）は`Duration.ZERO`へ丸める。
     */
    override fun restoreFromStore(): ScheduleResult {
        val now = Instant.now()
        val loadResult = store.loadAll()
        var registeredCount = 0
        var anyDegraded = false

        loadResult.records.forEach { record ->
            // Always cancel first, then re-decide: guarantees no stale alarm survives a
            // reclassification (e.g. a trigger that used to be future is now past-due).
            record.triggers.forEach { alarmScheduler.cancel(it.requestCode) }

            val eventStart = Instant.ofEpochMilli(record.eventStartEpochMillis)
            val estimatedArrival = Instant.ofEpochMilli(record.estimatedArrivalEpochMillis)
            val derivedArrivalBuffer = Duration.between(estimatedArrival, eventStart).let { if (it.isNegative) Duration.ZERO else it }

            val triggersToKeep = mutableListOf<Trigger>()
            record.triggers.forEach { trigger ->
                val triggerAt = Instant.ofEpochMilli(trigger.triggerAtEpochMillis)
                val payload = NotificationPayload(
                    planId = record.planId,
                    requestCode = trigger.requestCode,
                    stepSemanticId = trigger.semanticId,
                    eventStartAt = eventStart,
                    estimatedArrivalAt = estimatedArrival,
                    arrivalBuffer = derivedArrivalBuffer
                )
                when {
                    triggerAt.isAfter(now) -> {
                        val outcome = alarmScheduler.schedule(
                            AlarmTrigger(triggerAt = triggerAt, kind = trigger.kind, payload = payload)
                        )
                        if (outcome == AlarmScheduleOutcome.DEGRADED_INEXACT) anyDegraded = true
                        registeredCount++
                        triggersToKeep += trigger
                    }

                    now.isBefore(eventStart) &&
                        Duration.between(triggerAt, now) <= NotificationDefaults.MISSED_TRIGGER_GRACE_PERIOD -> {
                        // S-9: missed but within grace period and the event hasn't started yet
                        // -> fire immediately instead of silently dropping it.
                        notifyNow(trigger.kind, payload)
                    }

                    else -> {
                        // S-9: missed beyond grace, or the event already started -> discard
                        // without firing (avoids surprising a user with a stale notification).
                    }
                }
            }

            if (triggersToKeep.isEmpty()) {
                store.clear(record.planId)
            } else if (triggersToKeep != record.triggers) {
                store.save(record.copy(triggers = triggersToKeep))
            }
        }

        return when {
            registeredCount == 0 -> ScheduleResult.Skipped(ScheduleSkipReason.NO_FUTURE_TRIGGERS)
            anyDegraded -> ScheduleResult.Degraded(registeredCount, DegradationReason.EXACT_ALARM_NOT_PERMITTED)
            else -> ScheduleResult.Exact(registeredCount)
        }
    }

    /**
     * P5-C3fix実装（Fable 5裁定2026-08-09、`docs/plans/phase5-notification-execution.md`
     * §10.7）。[NotificationService.isExactAlarmAvailable]のAndroid実装。[schedule]内の
     * exact/inexact分岐と同一の判定源（[canScheduleExactAlarms]、本パッケージの
     * [AlarmManagerAlarmScheduler.schedule]と共有するトップレベル関数）を使い、判定ロジックを
     * 重複させない。[alarmScheduler]（テストではfake差し替え可能）を経由しないのは、
     * [AlarmScheduler]契約自体には照会用メソッドがなく、契約変更はテスト影響範囲を
     * [NotificationService]のfakeへ限定する本サイクルの方針の対象外としたため——[context]から
     * 直接実`AlarmManager`を解決する。
     */
    override fun isExactAlarmAvailable(): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return canScheduleExactAlarms(alarmManager)
    }

    private fun ensureChannel(manager: NotificationManagerCompat, kind: NotificationKind) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(channelIdFor(kind), NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(channelNameFor(kind))
                .build()
        )
    }

    private fun channelIdFor(kind: NotificationKind): String = when (kind) {
        NotificationKind.TRANSITION_START -> "channel_transition_start"
        NotificationKind.DEPARTURE -> "channel_departure"
        // Phase 6 creates the Recovery channel (S-5); unreachable in Phase 5 (T-NOTIF-3 pins
        // the channel count at 2, not 3 -- this branch exists only for `when` exhaustiveness).
        NotificationKind.RECOVERY -> "channel_recovery"
    }

    private fun channelNameFor(kind: NotificationKind): CharSequence = when (kind) {
        NotificationKind.TRANSITION_START -> context.getString(com.actionstarter.R.string.step_title_transition)
        NotificationKind.DEPARTURE -> context.getString(com.actionstarter.R.string.departure_title)
        NotificationKind.RECOVERY -> context.getString(com.actionstarter.R.string.recovery_title)
    }

    /**
     * 通知タップ→`MainActivity`起動→route extra解決（F60、T-NOTIF-6）。`requestCode`を
     * PendingIntentのrequestCodeとして流用し、通知本体と同じ一意性規約に乗せる。
     * `isMutable=false`（[PendingIntentCompat]がAPIレベルに応じ`FLAG_IMMUTABLE`を付与、
     * API 31+要件）。
     */
    private fun buildContentIntent(kind: NotificationKind, payload: NotificationPayload): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(ROUTE_EXTRA_KEY, routeFor(kind))
        }
        return checkNotNull(
            PendingIntentCompat.getActivity(context, payload.requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)
        ) { "PendingIntentCompat.getActivity unexpectedly returned null without FLAG_NO_CREATE" }
    }

    private fun routeFor(kind: NotificationKind): String = when (kind) {
        NotificationKind.TRANSITION_START -> Destinations.Execution.route
        NotificationKind.DEPARTURE -> Destinations.Departure.route
        NotificationKind.RECOVERY -> Destinations.Execution.route
    }

    private fun stepIdFor(eventId: UUID, semanticId: String): UUID =
        UUID.nameUUIDFromBytes("$eventId:$semanticId".toByteArray())

    private data class TriggerCandidate(val kind: NotificationKind, val semanticId: String, val triggerAt: Instant)

    companion object {
        /** F60・エラー&レスキューマップ#17/#18。[com.actionstarter.MainActivity]の`onNewIntent`
         * が読み出す通知タップIntentのextraキー。 */
        private const val ROUTE_EXTRA_KEY = "route"

        private const val SEMANTIC_ID_TRANSITION = "transition"
        private const val SEMANTIC_ID_DEPARTURE = "departure"

        private val POST_NOTIFICATIONS_PERMISSION = android.Manifest.permission.POST_NOTIFICATIONS
    }
}
