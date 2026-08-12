package com.actionstarter.persistence.room

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Modifier

/**
 * Phase 10 C1（計画書`docs/plans/phase10-behavior-log-profile.md`§3.1・§7、Step 3 Red）。
 * in-memory Room DBのRobolectric JVMテスト可能性（T-P10-1）と、[BehaviorEventEntity]が
 * タイトル生文を一切保持しない構造であることの回帰ガード（T-P10-9）。
 */
@RunWith(RobolectricTestRunner::class)
class BehaviorLogDatabaseTest {

    private fun inMemoryDb(): BehaviorLogDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BehaviorLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    // T-P10-1: 正常 - in-memory DBが正常にopenし、DAO経由でinsert/queryが往復する。
    @Test
    fun tP10_1_inMemoryDatabase_insertAndQuery_roundTrips() = runTest {
        val db = inMemoryDb()
        try {
            val dao = db.behaviorEventDao()
            val event = BehaviorEventEntity(
                timestamp = 1_700_000_000_000L,
                domain = BehaviorEventEntity.DOMAIN_RECOVERY,
                eventType = BehaviorEventEntity.EVENT_TYPE_STEP_DONE,
                eventCategory = "medical",
                durationMs = 120_000L
            )

            dao.insert(event)
            val all = dao.getAll()

            assertEquals(
                "in-memory DBへのinsert後、getAll()が挿入した1件を返すべきです(T-P10-1)",
                1,
                all.size
            )
            assertEquals("medical", all.single().eventCategory)
            assertEquals(120_000L, all.single().durationMs)
        } finally {
            db.close()
        }
    }

    // T-P10-9: 異常（回帰ガード） - いかなるBehaviorEventEntityカラムにもタイトル生文が
    // 格納されない（プライバシー回帰防止のpinningテスト）。フィールド集合が計画書§3.2で
    // 確定した9項目ちょうどであることを構造的に固定し、将来タイトル相当のフィールドが
    // 誤って追加されないことを検証する。
    @Test
    fun tP10_9_behaviorEventEntity_neverDeclaresRawTitleField() {
        val expectedFieldNames = setOf(
            "id",
            "timestamp",
            "domain",
            "eventType",
            "eventCategory",
            "semanticAction",
            "stepType",
            "durationMs",
            "aiAdopted",
            "fallbackReason"
        )
        val forbiddenSubstrings = listOf("title", "Title", "notes", "Notes", "calendarBody", "location", "address")

        val instanceFieldNames = BehaviorEventEntity::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(
            "BehaviorEventEntityのフィールド集合は計画書§3.2確定の9項目ちょうどであるべきです" +
                "(T-P10-9)。新規フィールド追加時はタイトル生文を含まないことを確認したうえで" +
                "本テストの期待値を更新すること。",
            expectedFieldNames,
            instanceFieldNames
        )
        forbiddenSubstrings.forEach { forbidden ->
            assertTrue(
                "BehaviorEventEntityにタイトル/カレンダー本文/住所相当のフィールド名" +
                    "『$forbidden』を含めてはいけません(T-P10-9、仕様§10継承)",
                instanceFieldNames.none { it.contains(forbidden, ignoreCase = true) }
            )
        }
    }
}
