package com.actionstarter.persistence

import android.content.Context
import com.actionstarter.domain.model.PlanningContext
import com.actionstarter.domain.model.ExecutionEvent
import com.actionstarter.domain.valueobject.CalendarSource
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.planning.BasicPlanningEngine
import com.actionstarter.services.notification.NotificationKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * T-STORE-1〜8（計画書§8.2「F55 — ExecutionScheduleStore」全8件、E2・Robolectric）。
 *
 * 対象は[SharedPreferencesExecutionScheduleStore]（[ExecutionScheduleStore]契約実装、S-1）。
 * 現時点（P5-C2）では全メソッドが`TODO()`のため、以下は全件`NotImplementedError`によりRedに
 * なる（意図した失敗）。本ファイルは独立したSharedPreferencesインスタンスを自前で構築・使用
 * するため（T-ALARM-10・[com.actionstarter.services.notification.ScheduleRestoreReceiverTest]
 * とは異なり）SharedPreferences名は本ファイル内で完結し、他コンポーネントとの命名一致を
 * 仮定する必要はない。
 *
 * T-STORE-3（schemaVersion不一致）は[ExecutionScheduleStore.save]がフィールド値をそのまま
 * 永続化し検証は読み出し側（[ExecutionScheduleStore.loadAll]）でのみ行う、というKDoc
 * （「読み出し時に...schemaVersionとの不一致...のレコードを破棄する」）どおりの設計を前提に、
 * `save()`自体でwrong-schemaVersionレコードを書き込む方式で内部JSON形式に一切依存せず検証する。
 *
 * T-STORE-4（JSON破損）は内部シリアライズ形式（キー名）がP5-C1契約scaffold時点で未確定
 * （[SharedPreferencesExecutionScheduleStore]KDocも`NAME`をプレースホルダとして書くのみ）
 * であるため、test-writerの仮の取り決め（[ASSUMED_RECORDS_KEY]）で直接
 * `SharedPreferences.Editor`へ不正JSON文字列を書き込む。T-BOOT側のSharedPreferences名の
 * 仮定より確度が低い（キー名だけでなく1レコード/1キーか全レコード1キーかという内部構造も
 * 未確定なため）。P5-C3実装時に本ケースの妥当性を確認すること（差し戻し事項として報告）。
 */
@RunWith(RobolectricTestRunner::class)
class ExecutionScheduleStoreTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun newStore(prefsName: String = "test_execution_schedule_store"): SharedPreferencesExecutionScheduleStore {
        val prefs = context().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return SharedPreferencesExecutionScheduleStore(prefs)
    }

    private fun sampleRecord(
        planId: String = "plan-1",
        schemaVersion: Int = ExecutionScheduleRecord.CURRENT_SCHEMA_VERSION
    ): ExecutionScheduleRecord = ExecutionScheduleRecord(
        schemaVersion = schemaVersion,
        planId = planId,
        eventStartEpochMillis = Instant.parse("2026-08-10T09:00:00Z").toEpochMilli(),
        estimatedArrivalEpochMillis = Instant.parse("2026-08-10T08:50:00Z").toEpochMilli(),
        triggers = listOf(
            Trigger(
                kind = NotificationKind.TRANSITION_START,
                stepId = UUID.nameUUIDFromBytes("$planId:transition".toByteArray()),
                semanticId = "transition",
                triggerAtEpochMillis = Instant.parse("2026-08-10T08:00:00Z").toEpochMilli(),
                requestCode = 1,
                fired = false
            ),
            Trigger(
                kind = NotificationKind.DEPARTURE,
                stepId = UUID.nameUUIDFromBytes("$planId:departure".toByteArray()),
                semanticId = "departure",
                triggerAtEpochMillis = Instant.parse("2026-08-10T08:40:00Z").toEpochMilli(),
                requestCode = 2,
                fired = true
            )
        )
    )

    // T-STORE-1: 正常 - save→loadのラウンドトリップで全フィールドが一致する
    @Test
    fun saveThenLoadAll_roundTrip_preservesAllFields() {
        val store = newStore()
        val record = sampleRecord()

        store.save(record)
        val result = store.loadAll()

        assertEquals(0, result.discardedCount)
        assertEquals(1, result.records.size)
        assertEquals(record, result.records.first())
    }

    // T-STORE-2: 正常 - 空ストアからのloadは空を返す（nullや例外でない）
    @Test
    fun loadAll_onEmptyStore_returnsEmptyResultNotNullOrException() {
        val store = newStore()

        val result = store.loadAll()

        assertTrue(result.records.isEmpty())
        assertEquals(0, result.discardedCount)
    }

    // T-STORE-3: 異常（信頼境界） - schemaVersion不一致のレコードを破棄し空を返す
    @Test
    fun loadAll_whenSchemaVersionMismatches_discardsRecordAndReportsCount() {
        val store = newStore()
        store.save(sampleRecord(schemaVersion = ExecutionScheduleRecord.CURRENT_SCHEMA_VERSION + 999))

        val result = store.loadAll()

        assertTrue("mismatched-schema record must not be returned", result.records.isEmpty())
        assertEquals(1, result.discardedCount)
    }

    // T-STORE-4: 異常（信頼境界） - 壊れたJSONでクラッシュせず破棄し、結果型で
    // 「破棄した」ことを返す（キー名は仮の取り決め[ASSUMED_RECORDS_KEY]、差し戻し事項）
    @Test
    fun loadAll_whenUnderlyingJsonIsCorrupted_discardsWithoutCrashingAndReportsCount() {
        val prefsName = "test_execution_schedule_store_corrupt"
        val prefs = context().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().clear().putString(ASSUMED_RECORDS_KEY, "{not valid json,,,").commit()
        val store = SharedPreferencesExecutionScheduleStore(prefs)

        val result = store.loadAll()

        assertTrue("corrupted JSON must not crash loadAll(), and must not silently return as if nothing were stored", result.records.isEmpty())
        assertEquals(1, result.discardedCount)
    }

    // T-STORE-5: 正常 - clear()でレコードが消える
    @Test
    fun clear_afterSave_removesTheRecord() {
        val store = newStore()
        store.save(sampleRecord(planId = "plan-to-clear"))

        store.clear("plan-to-clear")

        assertTrue(store.loadAll().records.isEmpty())
    }

    // T-STORE-6: エッジ - clear(planId)は対象Planのみを消し、他Planのレコード・当該Plan内の
    // 一部トリガーがfired=trueであるかどうか（Execution完了/中断いずれの状態遷移途中か）に
    // 関わらず正しく対象を消す（Execution完了・中断のいずれの経路でも呼ばれるclear()が
    // 常に正しくスコープされることの検証）
    @Test
    fun clear_scopesToTargetPlanOnly_regardlessOfPartiallyFiredTriggers() {
        val store = newStore()
        val interruptedPlan = sampleRecord(planId = "plan-interrupted").copy(
            triggers = listOf(
                Trigger(NotificationKind.TRANSITION_START, UUID.randomUUID(), "transition", 1L, 1, fired = true),
                Trigger(NotificationKind.DEPARTURE, UUID.randomUUID(), "departure", 2L, 2, fired = false)
            )
        )
        val otherPlan = sampleRecord(planId = "plan-other")
        store.save(interruptedPlan)
        store.save(otherPlan)

        store.clear("plan-interrupted")

        val remaining = store.loadAll().records
        assertEquals(1, remaining.size)
        assertEquals("plan-other", remaining.first().planId)
    }

    // T-STORE-7: 回帰ガード - 保存内容にイベントタイトル・住所・座標が含まれない（PIIゼロ）。
    // ExecutionScheduleRecord/TriggerはP5-C1契約scaffold時点で確定済みの型のため、本ケースは
    // 作成時点からGreenの見込みが高い（PlanningLlmIsolationTest/T-BPE-28と同種の回帰ガード）。
    @Test
    fun executionScheduleRecordAndTrigger_declaredFields_containNoPiiNamedFields() {
        // "lat"/"lon" (as short substrings) deliberately excluded: they risk false-positiving
        // against legitimate "...AtEpochMillis"/"...At"-suffixed timestamp field names.
        // "latitude"/"longitude" and "coordinate" cover the same PII concern precisely.
        val forbiddenSubstrings = listOf("title", "address", "location", "coordinate", "notes", "latitude", "longitude")
        val recordFields = ExecutionScheduleRecord::class.java.declaredFields.map { it.name.lowercase() }
        val triggerFields = Trigger::class.java.declaredFields.map { it.name.lowercase() }

        val offending = (recordFields + triggerFields).filter { name -> forbiddenSubstrings.any { name.contains(it) } }

        assertTrue(
            "ExecutionScheduleRecord/Trigger must stay PII-free (§58/§60); offending fields: $offending",
            offending.isEmpty()
        )
    }

    // T-STORE-8: エッジ - 同一event/semanticIdで再planningしたPlanのステップidが保存済み
    // stepIdと一致する（ADR-0017の決定的UUID生成`UUID.nameUUIDFromBytes("$eventId:$semanticId")`
    // への依存を、BasicPlanningEngine.createPlanの実呼び出しでロックする。数式を手書きで
    // 複製せず、本番実装を直接2回呼んで一致を確認するためADR-0017実装からの乖離を検知できる）
    @Test
    fun storedStepId_afterReplanningSameEvent_matchesRegeneratedPlanStepId() = runTest {
        val store = newStore()
        val engine = BasicPlanningEngine()
        val event = ExecutionEvent(
            id = UUID.randomUUID(),
            externalCalendarId = null,
            title = "Weekly sync",
            notes = null,
            startDate = Instant.parse("2026-08-10T10:00:00Z"),
            locationName = null,
            coordinates = null,
            sourceCalendar = CalendarSource(id = "cal-1", displayName = "Work")
        )
        val planningContext = PlanningContext(
            event = event,
            now = Instant.parse("2026-08-10T07:00:00Z"),
            zoneId = ZoneId.of("UTC"),
            locale = Locale.US,
            transportMode = TransportMode.WALKING,
            travelEstimate = null,
            arrivalBuffer = Duration.ofMinutes(5),
            profile = null
        )

        val firstPlan = engine.createPlan(planningContext)
        val transitionStep = firstPlan.steps.first { it.semanticId == "transition" }
        store.save(
            sampleRecord(planId = event.id.toString()).copy(
                triggers = listOf(
                    Trigger(
                        kind = NotificationKind.TRANSITION_START,
                        stepId = transitionStep.id,
                        semanticId = "transition",
                        triggerAtEpochMillis = 1L,
                        requestCode = 1,
                        fired = false
                    )
                )
            )
        )

        val secondPlan = engine.createPlan(planningContext)
        val regeneratedTransitionStep = secondPlan.steps.first { it.semanticId == "transition" }
        val storedStepId = store.loadAll().records.first { it.planId == event.id.toString() }
            .triggers.first { it.semanticId == "transition" }.stepId

        assertEquals(
            "ADR-0017 decisive stepId generation must be stable across replanning the same event",
            regeneratedTransitionStep.id,
            storedStepId
        )
    }

    private companion object {
        /**
         * 【仮の取り決め・P5-C3実装整合確認事項、差し戻し事項】T-STORE-4専用。
         * `SharedPreferencesExecutionScheduleStore`が全レコードをこのキー配下へJSON配列として
         * 保存する、というtest-writerの仮定。実装がplanIdごとに別キーを用いる等、異なる内部
         * 構造を採る場合は本ケースの前提が崩れるため、P5-C3実装時に妥当性を再確認すること。
         */
        const val ASSUMED_RECORDS_KEY = "records"
    }
}
