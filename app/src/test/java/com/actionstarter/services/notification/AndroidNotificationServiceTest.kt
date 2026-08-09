package com.actionstarter.services.notification

import android.app.NotificationManager
import android.content.Context
import com.actionstarter.MainActivity
import com.actionstarter.persistence.ExecutionScheduleLoadResult
import com.actionstarter.persistence.ExecutionScheduleRecord
import com.actionstarter.persistence.ExecutionScheduleStore
import com.actionstarter.services.permission.PermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant

/**
 * T-NOTIF-1〜8（計画書§8.2「F49/F52 — AndroidNotificationService」全10件のうち
 * E2区分8件。T-NOTIF-9はE1のため[NotificationLlmIsolationTest]、T-NOTIF-10は
 * [NotificationTriggerReceiverTest]へ分離。計画書§6.1のファイル配置表どおり本ファイルへ集約）。
 *
 * 対象は[AndroidNotificationService.notifyNow]（`AlarmScheduler`／`ExecutionScheduleStore`は
 * 即時通知の提示には関与しないためfakeで満たすのみ）。現時点（P5-C2）では[notifyNow]の全実装が
 * `TODO()`のため、以下は全件`NotImplementedError`によりRedになる（意図した失敗）。
 *
 * T-NOTIF-4（NotificationKind要素数=3）・T-NOTIF-8前半（[NotificationPayload]の宣言済み
 * フィールドにPII名を持たない構造ガード）は、P5-C1契約scaffold時点で該当の型がすでに
 * 確定・宣言済みであるため、[com.actionstarter.planning.PlanningLlmIsolationTest]
 * （T-BPE-28、`docs/plans/phase4-basic-engine.md`§9エラーマップ#17）と同種の理由で
 * **作成時点からGreenの見込みが高い回帰ガード**である（「将来の変更で誤って混入させることを
 * 防ぐ」ことが目的であり、「現状が誤っていることを示す」ことが目的ではないため）。
 */
@RunWith(RobolectricTestRunner::class)
class AndroidNotificationServiceTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun notificationManager(): NotificationManager =
        context().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun payload(requestCode: Int = 1, estimatedArrivalAt: Instant = Instant.parse("2026-08-10T08:50:00Z")): NotificationPayload =
        NotificationPayload(
            planId = "plan-1",
            requestCode = requestCode,
            stepSemanticId = "transition",
            eventStartAt = Instant.parse("2026-08-10T09:00:00Z"),
            estimatedArrivalAt = estimatedArrivalAt,
            arrivalBuffer = Duration.ofMinutes(10)
        )

    private fun buildService(permissionGate: PermissionGate = AlwaysGrantedPermissionGate()): AndroidNotificationService =
        AndroidNotificationService(
            context = context(),
            store = FakeExecutionScheduleStore(),
            alarmScheduler = NoOpAlarmScheduler(),
            contentBuilder = NotificationContentBuilder(context()),
            permissionGate = permissionGate
        )

    // T-NOTIF-1: 正常 - TRANSITION_START通知が1件だけ提示される
    @Test
    fun notifyNow_transitionStart_showsExactlyOneNotification() {
        val service = buildService()

        val result = service.notifyNow(NotificationKind.TRANSITION_START, payload())

        assertEquals(NotifyResult.Shown, result)
        val shadow = shadowOf(notificationManager())
        assertEquals(1, shadow.allNotifications.size)
        val title = shadow.allNotifications.first().extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
        assertNotNull("notification title must be present (string resource resolved, not blank)", title)
        assertTrue(title.toString().isNotBlank())
    }

    // T-NOTIF-2: 正常 - DEPARTURE通知が到着予測を含む（「出発時間です」だけの固定文言でない。
    // 到着予測を変えると本文が追随して変わることで、ハードコード文言でないことを検証する）
    @Test
    fun notifyNow_departure_textReflectsEstimatedArrivalPayload() {
        val service = buildService()

        service.notifyNow(NotificationKind.DEPARTURE, payload(requestCode = 1, estimatedArrivalAt = Instant.parse("2026-08-10T08:50:00Z")))
        val firstText = shadowOf(notificationManager()).allNotifications.first()
            .extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        notificationManager().cancelAll()

        service.notifyNow(NotificationKind.DEPARTURE, payload(requestCode = 1, estimatedArrivalAt = Instant.parse("2026-08-10T09:20:00Z")))
        val secondText = shadowOf(notificationManager()).allNotifications.first()
            .extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()

        assertTrue(firstText.isNotBlank())
        assertNotEquals(
            "DEPARTURE notification text must vary with estimatedArrivalAt (not a fixed 'Leave now' string, §29)",
            firstText,
            secondText
        )
    }

    // T-NOTIF-3: 回帰ガード - getNotificationChannels()が宣言済みチャネル数と完全一致する
    // （TRANSITION_START・DEPARTUREの2種のみ。RECOVERYチャネルは作らない、S-5）
    @Test
    fun notifyNow_forEachUsedKind_createsExactlyOneChannelPerKindNoMore() {
        val service = buildService()

        service.notifyNow(NotificationKind.TRANSITION_START, payload(requestCode = 1))
        service.notifyNow(NotificationKind.DEPARTURE, payload(requestCode = 2))

        val expectedChannelCount = NotificationKind.entries.count { it != NotificationKind.RECOVERY }
        assertEquals(2, expectedChannelCount)
        assertEquals(expectedChannelCount, shadowOf(notificationManager()).notificationChannels.size)
    }

    // T-NOTIF-4: 回帰ガード - NotificationKindの要素数が3（TRANSITION_START/DEPARTURE/RECOVERY）
    // P5-C1契約scaffold時点で確定済みの型のため、本ケースは作成時点からGreenの見込みが高い
    // （上記クラスKDoc参照。T-BPE-28と同種の「将来の変更で3種固定が崩れることを防ぐ」目的）。
    @Test
    fun notificationKind_hasExactlyThreeClosedSetMembers() {
        assertEquals(3, NotificationKind.entries.size)
        assertEquals(
            setOf(NotificationKind.TRANSITION_START, NotificationKind.DEPARTURE, NotificationKind.RECOVERY),
            NotificationKind.entries.toSet()
        )
    }

    // T-NOTIF-5: 異常 - setNotificationsEnabled(false)のときnotifyを呼ばずSkipped
    // (NOTIFICATIONS_DISABLED)を返す（例外を投げない・握り潰さない）
    @Test
    fun notifyNow_whenNotificationsDisabled_skipsWithoutPostingAndWithoutThrowing() {
        shadowOf(notificationManager()).setNotificationsEnabled(false)
        val service = buildService()

        val result = service.notifyNow(NotificationKind.TRANSITION_START, payload())

        assertEquals(NotifyResult.Skipped(DegradationReason.NOTIFICATIONS_DISABLED), result)
        assertEquals(0, shadowOf(notificationManager()).allNotifications.size)
    }

    // T-NOTIF-6: 正常 - 通知タップIntentがMainActivity宛て・route extraを持ち・
    // FLAG_IMMUTABLEである（Intent extraのキー名"route"は計画書F60/エラーマップ#17-18が
    // 一貫して用いる語をtest-writerが採用した。P5-C3実装時に整合を確認すること）
    @Test
    fun notifyNow_contentIntent_targetsMainActivityWithRouteExtraAndIsImmutable() {
        val service = buildService()

        service.notifyNow(NotificationKind.TRANSITION_START, payload())

        val notification = shadowOf(notificationManager()).allNotifications.first()
        val contentIntent = notification.contentIntent
        assertNotNull("notification.contentIntent must be set (tap must open the app)", contentIntent)
        val shadowPendingIntent = shadowOf(contentIntent)
        assertTrue("contentIntent must be FLAG_IMMUTABLE (API 31+ requirement, PendingIntentCompat)", shadowPendingIntent.isImmutable)
        val savedIntent = shadowPendingIntent.savedIntent
        assertNotNull(savedIntent)
        assertEquals(MainActivity::class.java.name, savedIntent.component?.className)
        assertNotNull("tap intent must carry a route extra so NavHost can resolve the destination (F60)", savedIntent.getStringExtra("route"))
    }

    // T-NOTIF-7: エッジ - 同一kind・同一stepの再通知が同一notification idで置換され、
    // 通知が増殖しない
    @Test
    fun notifyNow_calledTwiceForSameKindAndRequestCode_replacesRatherThanDuplicates() {
        val service = buildService()

        service.notifyNow(NotificationKind.TRANSITION_START, payload(requestCode = 55))
        service.notifyNow(NotificationKind.TRANSITION_START, payload(requestCode = 55))

        assertEquals(1, shadowOf(notificationManager()).allNotifications.size)
    }

    // T-NOTIF-8: 回帰ガード - 通知IntentにイベントタイトルなどのPII文字列が含まれない。
    // NotificationPayloadの宣言済みフィールド自体にPII名を持たないことを構造的に固定する
    // （T-NOTIF-4と同種、P5-C1契約scaffold時点で確定済みのためGreenの見込みが高い）。
    @Test
    fun notificationPayload_declaredFields_containNoPiiNamedFields() {
        // "lat"/"lon" (as short substrings) deliberately excluded: they false-positive against
        // legitimate field names such as "estimatedArrivalAt" (contains "...rrivalAt" -> "lat").
        // "latitude"/"longitude" and "coordinate" cover the same PII concern precisely.
        val forbiddenSubstrings = listOf("title", "address", "location", "coordinate", "notes", "latitude", "longitude")
        val fieldNames = NotificationPayload::class.java.declaredFields.map { it.name.lowercase() }

        val offending = fieldNames.filter { name -> forbiddenSubstrings.any { name.contains(it) } }

        assertTrue(
            "NotificationPayload must stay PII-free (§58/§60); offending fields: $offending",
            offending.isEmpty()
        )
    }

    // ---- 共有フィクスチャ ---------------------------------------------------------------

    private class NoOpAlarmScheduler : AlarmScheduler {
        override fun schedule(trigger: AlarmTrigger): AlarmScheduleOutcome = AlarmScheduleOutcome.EXACT
        override fun cancel(requestCode: Int) = Unit
    }

    private class FakeExecutionScheduleStore : ExecutionScheduleStore {
        private var counter = 0
        private val records = mutableListOf<ExecutionScheduleRecord>()

        override fun nextRequestCode(): Int = counter++
        override fun save(record: ExecutionScheduleRecord) {
            records.removeAll { it.planId == record.planId }
            records += record
        }
        override fun loadAll(): ExecutionScheduleLoadResult = ExecutionScheduleLoadResult(records.toList(), 0)
        override fun clear(planId: String) {
            records.removeAll { it.planId == planId }
        }
    }

    private class AlwaysGrantedPermissionGate : PermissionGate {
        override fun isGranted(permission: String): Boolean = true
    }
}
