package com.actionstarter.persistence

import android.content.SharedPreferences
import com.actionstarter.services.notification.NotificationKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * F55実装（計画書§6.1・§7.3、S-1裁定）。[ExecutionScheduleStore]のSharedPreferences実装。
 * `org.json`でシリアライズし（新規依存を追加しない。Android SDK同梱）、読み出し時に
 * [ExecutionScheduleRecord.CURRENT_SCHEMA_VERSION]との不一致・JSON破損のレコードを破棄する
 * （信頼境界、エラー&レスキューマップ#12）。
 *
 * **永続化フォーマット（P5-C3実装、本サイクルで確定）**: 全レコードを単一キー
 * [KEY_RECORDS]配下へJSON配列として保存する（P5-C2 test-writerの仮の取り決め
 * [com.actionstarter.persistence.ExecutionScheduleStoreTest]の`ASSUMED_RECORDS_KEY`と
 * 一致させた）。[loadAll]は2種類の信頼境界違反を区別して扱う: (a) 配列自体がJSONとして
 * 解析不能（破損） → 個々のレコード件数を特定できないため、破損イベント1件として
 * `discardedCount = 1`・`records = emptyList()`を返す（T-STORE-4）。(b) 配列は解析できるが
 * 個々の要素の`schemaVersion`が[ExecutionScheduleRecord.CURRENT_SCHEMA_VERSION]と不一致、
 * または要素自体の構造が壊れている → その要素だけを1件破棄しカウントする（T-STORE-3）。
 * いずれも例外を外へ伝播させず、空を返して沈黙するのではなく`discardedCount`で必ず
 * 表面化する。
 *
 * [nextRequestCode]が払い出すカウンタ自体も[KEY_NEXT_REQUEST_CODE]として本ストアへ
 * 永続化する（プロセス内メモリのみで保持すると再起動のたびにリセットされ、requestCode
 * 再利用によるPendingIntent衝突が発生するため。計画書§7.3「PendingIntent一意性の規約」）。
 *
 * @param preferences 専用ファイル名で取得済みの`SharedPreferences`
 *   （呼び出し側が`context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)`で用意する）。
 */
class SharedPreferencesExecutionScheduleStore(
    private val preferences: SharedPreferences
) : ExecutionScheduleStore {

    override fun nextRequestCode(): Int {
        val current = preferences.getInt(KEY_NEXT_REQUEST_CODE, 0)
        preferences.edit().putInt(KEY_NEXT_REQUEST_CODE, current + 1).commit()
        return current
    }

    override fun save(record: ExecutionScheduleRecord) {
        val kept = JSONArray()
        val existing = readRawRecordsArrayOrEmpty()
        for (index in 0 until existing.length()) {
            val entry = existing.optJSONObject(index) ?: continue
            if (entry.optString(FIELD_PLAN_ID, "") != record.planId) {
                kept.put(entry)
            }
        }
        kept.put(record.toJson())
        preferences.edit().putString(KEY_RECORDS, kept.toString()).commit()
    }

    override fun loadAll(): ExecutionScheduleLoadResult {
        val raw = preferences.getString(KEY_RECORDS, null)
            ?: return ExecutionScheduleLoadResult(records = emptyList(), discardedCount = 0)

        val array = try {
            JSONArray(raw)
        } catch (malformedTopLevelJson: JSONException) {
            // Whole blob is unparsable: we cannot know how many records were lost, so we
            // report a single discard event rather than silently returning an empty result
            // (error map #12: "空を返して沈黙しない").
            return ExecutionScheduleLoadResult(records = emptyList(), discardedCount = 1)
        }

        val records = mutableListOf<ExecutionScheduleRecord>()
        var discardedCount = 0
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index)
            val record = entry?.let(::parseRecordOrNull)
            when {
                entry == null || record == null -> discardedCount++
                record.schemaVersion != ExecutionScheduleRecord.CURRENT_SCHEMA_VERSION -> discardedCount++
                else -> records += record
            }
        }
        return ExecutionScheduleLoadResult(records = records, discardedCount = discardedCount)
    }

    override fun clear(planId: String) {
        val kept = JSONArray()
        val existing = readRawRecordsArrayOrEmpty()
        for (index in 0 until existing.length()) {
            val entry = existing.optJSONObject(index) ?: continue
            if (entry.optString(FIELD_PLAN_ID, "") != planId) {
                kept.put(entry)
            }
        }
        preferences.edit().putString(KEY_RECORDS, kept.toString()).commit()
    }

    private fun readRawRecordsArrayOrEmpty(): JSONArray {
        val raw = preferences.getString(KEY_RECORDS, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (malformed: JSONException) {
            JSONArray()
        }
    }

    /** Returns `null` (never throws) when [entry]'s structure doesn't match the expected shape. */
    private fun parseRecordOrNull(entry: JSONObject): ExecutionScheduleRecord? = try {
        val triggersJson = entry.getJSONArray(FIELD_TRIGGERS)
        val triggers = (0 until triggersJson.length()).map { index ->
            triggersJson.getJSONObject(index).toTrigger()
        }
        ExecutionScheduleRecord(
            schemaVersion = entry.getInt(FIELD_SCHEMA_VERSION),
            planId = entry.getString(FIELD_PLAN_ID),
            eventStartEpochMillis = entry.getLong(FIELD_EVENT_START_EPOCH_MILLIS),
            estimatedArrivalEpochMillis = entry.getLong(FIELD_ESTIMATED_ARRIVAL_EPOCH_MILLIS),
            triggers = triggers
        )
    } catch (malformedEntry: JSONException) {
        null
    } catch (malformedEnumOrUuid: IllegalArgumentException) {
        null
    }

    private fun JSONObject.toTrigger(): Trigger = Trigger(
        kind = NotificationKind.valueOf(getString(FIELD_KIND)),
        stepId = UUID.fromString(getString(FIELD_STEP_ID)),
        semanticId = getString(FIELD_SEMANTIC_ID),
        triggerAtEpochMillis = getLong(FIELD_TRIGGER_AT_EPOCH_MILLIS),
        requestCode = getInt(FIELD_REQUEST_CODE),
        fired = getBoolean(FIELD_FIRED)
    )

    private fun ExecutionScheduleRecord.toJson(): JSONObject = JSONObject().apply {
        put(FIELD_SCHEMA_VERSION, schemaVersion)
        put(FIELD_PLAN_ID, planId)
        put(FIELD_EVENT_START_EPOCH_MILLIS, eventStartEpochMillis)
        put(FIELD_ESTIMATED_ARRIVAL_EPOCH_MILLIS, estimatedArrivalEpochMillis)
        put(
            FIELD_TRIGGERS,
            JSONArray().apply { triggers.forEach { put(it.toJson()) } }
        )
    }

    private fun Trigger.toJson(): JSONObject = JSONObject().apply {
        put(FIELD_KIND, kind.name)
        put(FIELD_STEP_ID, stepId.toString())
        put(FIELD_SEMANTIC_ID, semanticId)
        put(FIELD_TRIGGER_AT_EPOCH_MILLIS, triggerAtEpochMillis)
        put(FIELD_REQUEST_CODE, requestCode)
        put(FIELD_FIRED, fired)
    }

    companion object {
        /**
         * SharedPreferencesファイル名。[com.actionstarter.services.notification.AlarmSchedulingTest]
         * （T-ALARM-10）・[com.actionstarter.services.notification.ScheduleRestoreReceiverTest]
         * （T-BOOT-1〜4）が仮採用していた`ASSUMED_SCHEDULE_STORE_PREFS_NAME`と同一値
         * （P5-C3実装整合確認・確定）。[com.actionstarter.services.notification.
         * NotificationTriggerReceiver]・[com.actionstarter.services.notification.
         * ScheduleRestoreReceiver]・DI結線（P5-C6統合ウィンドウ）はいずれも本定数を参照する。
         */
        const val PREFS_NAME: String = "com.actionstarter.execution_schedule_store"

        private const val KEY_RECORDS = "records"
        private const val KEY_NEXT_REQUEST_CODE = "next_request_code"

        private const val FIELD_SCHEMA_VERSION = "schemaVersion"
        private const val FIELD_PLAN_ID = "planId"
        private const val FIELD_EVENT_START_EPOCH_MILLIS = "eventStartEpochMillis"
        private const val FIELD_ESTIMATED_ARRIVAL_EPOCH_MILLIS = "estimatedArrivalEpochMillis"
        private const val FIELD_TRIGGERS = "triggers"
        private const val FIELD_KIND = "kind"
        private const val FIELD_STEP_ID = "stepId"
        private const val FIELD_SEMANTIC_ID = "semanticId"
        private const val FIELD_TRIGGER_AT_EPOCH_MILLIS = "triggerAtEpochMillis"
        private const val FIELD_REQUEST_CODE = "requestCode"
        private const val FIELD_FIRED = "fired"
    }
}
