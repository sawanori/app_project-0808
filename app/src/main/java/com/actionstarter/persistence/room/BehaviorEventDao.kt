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

    /**
     * Phase 10 C3（計画書§3.3導出表、レビュー§13 No.9b）。`STEP_DONE`の実績所要時間を
     * カテゴリ・ステップ種別で絞り込み、直近[sinceMillis]以降・直近[maxCount]件の**両方**を
     * 満たす行のみ返す——「直近180日 or 直近500件のいずれか小さい方」を`timestamp`述語＋
     * `LIMIT`の組み合わせで1クエリで表現する（`ORDER BY timestamp DESC LIMIT`が新しい方から
     * [maxCount]件に絞り、`WHERE timestamp > :sinceMillis`がそのうち180日超のものを除外する）。
     * 保持期間ローテーション（物理削除）の実行タイミングに集計結果が左右されない設計
     * （レビュー§13 No.9b、ローテーション自体は独立した関心事として別途扱う）。
     */
    @Query(
        "SELECT * FROM behavior_events WHERE event_category = :eventCategory " +
            "AND event_type = :eventType AND step_type = :stepType AND timestamp > :sinceMillis " +
            "ORDER BY timestamp DESC LIMIT :maxCount"
    )
    suspend fun getRecentStepDurations(
        eventCategory: String,
        eventType: String,
        stepType: String,
        sinceMillis: Long,
        maxCount: Int
    ): List<BehaviorEventEntity>

    /**
     * 保持期間ローテーション（計画書§3.4「直近180日 or 直近500件の小さい方」）。
     * 件数上限のローテーションはC1では未実装——時間ベースの削除のみ先行導入し、件数上限は
     * 別途の物理削除実装時に追加する（[getRecentStepDurations]の`LIMIT`は集計クエリ自体の
     * 窓であり、物理削除とは独立、レビュー§13 No.9b）。
     */
    @Query("DELETE FROM behavior_events WHERE timestamp < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long): Int

    @Query("DELETE FROM behavior_events")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM behavior_events")
    suspend fun count(): Int
}
