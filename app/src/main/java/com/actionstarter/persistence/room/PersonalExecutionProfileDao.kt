package com.actionstarter.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Phase 10 C1（計画書§3.3）。[PersonalExecutionProfileEntity]へのアクセス窓口。
 * `eventCategory`が主キーのため[upsert]は`REPLACE`戦略で置換する（カテゴリ単位で1行のみ
 * 保持する設計と整合）。
 */
@Dao
interface PersonalExecutionProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: PersonalExecutionProfileEntity)

    @Query("SELECT * FROM personal_execution_profiles WHERE event_category = :eventCategory")
    suspend fun getByCategory(eventCategory: String): PersonalExecutionProfileEntity?

    @Query("DELETE FROM personal_execution_profiles")
    suspend fun deleteAll()
}
