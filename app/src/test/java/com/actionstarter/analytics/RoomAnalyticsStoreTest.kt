package com.actionstarter.analytics

import com.actionstarter.persistence.room.BehaviorEventDao
import com.actionstarter.persistence.room.BehaviorEventEntity
import com.actionstarter.persistence.room.PersonalExecutionProfileDao
import com.actionstarter.persistence.room.PersonalExecutionProfileEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 C1/C2（計画書§3.4・§7、Step 3 Red）。[RoomAnalyticsStore]の異常系
 * （T-P10-15「DB書き込み失敗がユーザー操作をブロックしない」・T-P10-17「全削除失敗を
 * サイレント化しない」）を、実DBではなくフェイクDAO（意図的に例外を投げる）で検証する。
 * C2でのインターフェース再設計（`record(BehaviorEventEntity)`廃止・`recordXxx`個別
 * メソッドへ移行）に伴い、代表として[AnalyticsStore.recordStepDone]で検証する。
 */
class RoomAnalyticsStoreTest {

    // T-P10-15: 異常 - DB書き込み失敗（IO例外）がユーザー操作（recordXxx呼び出し元）を
    // ブロックしない（try/catchでno-op化、例外を外へ投げない）。
    @Test
    fun tP10_15_recordStepDone_daoThrows_doesNotPropagateException() = runTest {
        val throwingDao = FakeBehaviorEventDao(throwOnInsert = true)
        val profileDao = FakePersonalExecutionProfileDao()
        val store = RoomAnalyticsStore(throwingDao, profileDao)

        // 例外が外へ伝播しないこと自体がアサーション（伝播すればテスト自体が失敗する）。
        store.recordStepDone(eventCategory = "medical", stepType = "PREPARATION", durationMs = 60_000L)

        assertTrue(
            "recordStepDone()呼び出しでinsertが試行されるべきです(T-P10-15)",
            throwingDao.insertAttempted
        )
    }

    // T-P10-17: 異常 - 全削除APIが失敗した場合、例外を握り潰さず呼び出し元へ結果を返す
    // （サイレント化しない）。
    @Test
    fun tP10_17_clearAll_daoThrows_returnsFailureResult() = runTest {
        val eventDao = FakeBehaviorEventDao(throwOnDeleteAll = true)
        val profileDao = FakePersonalExecutionProfileDao()
        val store = RoomAnalyticsStore(eventDao, profileDao)

        val result = store.clearAll()

        assertTrue(
            "clearAll()はDAOの例外を握り潰さずResult.failureとして返すべきです(T-P10-17)",
            result.isFailure
        )
    }

    // 正常系の対照: 例外が起きなければclearAll()はResult.successを返し両DAOのdeleteAllを呼ぶ。
    @Test
    fun tP10_16_clearAll_success_clearsBothDaos() = runTest {
        val eventDao = FakeBehaviorEventDao()
        val profileDao = FakePersonalExecutionProfileDao()
        val store = RoomAnalyticsStore(eventDao, profileDao)

        val result = store.clearAll()

        assertTrue("正常時はResult.successを返すべきです(T-P10-16)", result.isSuccess)
        assertEquals(1, eventDao.deleteAllCallCount)
        assertEquals(1, profileDao.deleteAllCallCount)
    }

    // T-P10-16b（計画書§3.4「書き込み/clearAll排他」、レビュー§13 No.6・Gemini G6、C4 Green）:
    // 異常（競合ケース） - clearAll()実行中(mutex取得中)に別コルーチンからのrecord系書き込みを
    // 試みても、Mutex直列化により削除後に古いデータが残らない。
    //
    // **タイミング制御にCompletableDeferredを使う理由（T-P95-55、LocalAiGatewayTestと同型の
    // 前例を踏襲、コーディネーター指示）**: `runTest`の既定`StandardTestDispatcher`は`async{}`の
    // 子コルーチンを即座には実行しない（呼び出し元がsuspendして初めてスケジューラが進行させる）
    // ため、単純に`async{ record }`の直後で`clearAll()`を呼んでも、record側がまだmutexを
    // 取得していない可能性があり、テストが偽陽性になりうる。[OrderingTrackingBehaviorEventDao.
    // onInsertStarted]フックを`insertStartedSignal.await()`で待つことで、「recordStepSkipped()が
    // mutex.withLockの内側へ入りinsert()を呼び、その中のdelayでサスペンドした直後」
    // （＝mutexを確実に保持中）まで到達してからclearAll()を呼ぶ。
    //
    // Mutexによる直列化が正しく機能していれば、clearAll()側のcoroutineはmutex.withLockで
    // ブロックされ、record側のinsert()（200ms遅延）が完了しmutexを解放するまでdeleteAll()へ
    // 到達できない。orderingLogへ「insertCompleted」が必ず「deleteAllInvoked」より先に
    // 記録されることが、この直列化の決定的な証拠となる（mutexが壊れていれば
    // StandardTestDispatcherはinsertのdelay中にclearAll側のdeleteAll()を先に走らせてしまい
    // 順序が逆転する）。
    @Test
    fun tP10_16b_clearAll_concurrentWithInFlightRecord_mutexSerializes_noStaleDataSurvives() = runTest {
        val insertStartedSignal = CompletableDeferred<Unit>()
        val orderingLog = mutableListOf<String>()
        val eventDao = OrderingTrackingBehaviorEventDao(
            insertDelayMillis = 200L,
            onInsertStarted = { insertStartedSignal.complete(Unit) },
            orderingLog = orderingLog
        )
        val profileDao = FakePersonalExecutionProfileDao()
        val store = RoomAnalyticsStore(eventDao, profileDao)

        val recordJob = async {
            store.recordStepSkipped(eventCategory = "medical", semanticAction = "test-action")
        }
        // record()が実際にmutexを取得しinsert()の内側で確実にサスペンドするまで待つ(上記コメント参照)。
        insertStartedSignal.await()

        val clearAllJob = async { store.clearAll() }

        recordJob.await()
        clearAllJob.await()

        assertEquals(
            "Mutex直列化によりinsert完了が必ずdeleteAll呼び出しより先に記録されるべきです" +
                "(T-P10-16b、RoomAnalyticsStoreのクラスKDoc「mutexによる直列化」参照)",
            listOf("insertCompleted", "deleteAllInvoked"),
            orderingLog
        )
        assertEquals(
            "先に書き込みが進行中だったrecordのデータも、後続のclearAll()で完全に削除され" +
                "残らないべきです(T-P10-16b、「削除後に古いデータが残らない」)",
            0,
            eventDao.rowCount
        )
    }

    /**
     * T-P10-16b専用fake（計画書§3.4、C4 Green）。[insertDelayMillis]で[insert]を人工的に
     * 遅延させ、[onInsertStarted]で「mutex内へ入りinsert実行を開始した」タイミングをテストへ
     * 伝える（T-P95-55の[onGeneratePlanStarted]と同型のシグナル方式）。[orderingLog]へ
     * `insertCompleted`／`deleteAllInvoked`を実際に発生した順序で追記し、Mutex直列化の
     * 決定的証拠とする。
     */
    private class OrderingTrackingBehaviorEventDao(
        private val insertDelayMillis: Long,
        private val onInsertStarted: () -> Unit,
        private val orderingLog: MutableList<String>
    ) : BehaviorEventDao {
        var rowCount: Int = 0
            private set

        override suspend fun insert(event: BehaviorEventEntity) {
            onInsertStarted()
            delay(insertDelayMillis)
            rowCount++
            orderingLog += "insertCompleted"
        }

        override suspend fun getAll(): List<BehaviorEventEntity> = emptyList()

        override suspend fun getRecentStepDurations(
            eventCategory: String,
            eventType: String,
            stepType: String,
            sinceMillis: Long,
            maxCount: Int
        ): List<BehaviorEventEntity> = emptyList()

        override suspend fun deleteOlderThan(beforeMillis: Long): Int = 0

        override suspend fun deleteAll() {
            orderingLog += "deleteAllInvoked"
            rowCount = 0
        }

        override suspend fun count(): Int = rowCount
    }

    private class FakeBehaviorEventDao(
        private val throwOnInsert: Boolean = false,
        private val throwOnDeleteAll: Boolean = false
    ) : BehaviorEventDao {
        var insertAttempted: Boolean = false
            private set
        var deleteAllCallCount: Int = 0
            private set

        override suspend fun insert(event: BehaviorEventEntity) {
            insertAttempted = true
            if (throwOnInsert) throw IllegalStateException("simulated Room I/O failure")
        }

        override suspend fun getAll(): List<BehaviorEventEntity> = emptyList()

        override suspend fun getRecentStepDurations(
            eventCategory: String,
            eventType: String,
            stepType: String,
            sinceMillis: Long,
            maxCount: Int
        ): List<BehaviorEventEntity> = emptyList()

        override suspend fun deleteOlderThan(beforeMillis: Long): Int = 0

        override suspend fun deleteAll() {
            deleteAllCallCount++
            if (throwOnDeleteAll) throw IllegalStateException("simulated Room I/O failure")
        }

        override suspend fun count(): Int = 0
    }

    private class FakePersonalExecutionProfileDao : PersonalExecutionProfileDao {
        var deleteAllCallCount: Int = 0
            private set

        override suspend fun upsert(profile: PersonalExecutionProfileEntity) = Unit

        override suspend fun getByCategory(eventCategory: String): PersonalExecutionProfileEntity? = null

        override suspend fun deleteAll() {
            deleteAllCallCount++
        }
    }
}
