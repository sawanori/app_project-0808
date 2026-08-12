package com.actionstarter.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Phase 10 C1（計画書§3.2）。[BehaviorEventEntity]へのアクセス窓口。
 * `RoomAnalyticsStore`（`analytics/RoomAnalyticsStore.kt`）経由でのみ呼ばれる想定
 * （features層は`AnalyticsStore`インターフェースのみ参照する既存層規律、`LocalAiGateway`と
 * 同型）。
 */
@Dao
interface BehaviorEventDao {

    @Insert
    suspend fun insert(event: BehaviorEventEntity)

    @Query("SELECT * FROM behavior_events ORDER BY timestamp DESC")
    suspend fun getAll(): List<BehaviorEventEntity>

    @Query(
        "SELECT * FROM behavior_events WHERE event_category = :eventCategory " +
            "AND event_type = :eventType AND timestamp > :sinceMillis ORDER BY timestamp DESC"
    )
    suspend fun getRecentByCategoryAndType(
        eventCategory: String,
        eventType: String,
        sinceMillis: Long
    ): List<BehaviorEventEntity>

    /**
     * 保持期間ローテーション（計画書§3.4「直近180日 or 直近500件の小さい方」）。
     * 件数上限のローテーションはC1では未実装——時間ベースの削除のみ先行導入し、件数上限は
     * C2以降の実装時に追加する（Step 3で件数ベースのSQL方針を確定する）。
     */
    @Query("DELETE FROM behavior_events WHERE timestamp < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long): Int

    @Query("DELETE FROM behavior_events")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM behavior_events")
    suspend fun count(): Int
}
