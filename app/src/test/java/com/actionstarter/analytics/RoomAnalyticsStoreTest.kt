package com.actionstarter.analytics

import com.actionstarter.persistence.room.BehaviorEventDao
import com.actionstarter.persistence.room.BehaviorEventEntity
import com.actionstarter.persistence.room.PersonalExecutionProfileDao
import com.actionstarter.persistence.room.PersonalExecutionProfileEntity
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
