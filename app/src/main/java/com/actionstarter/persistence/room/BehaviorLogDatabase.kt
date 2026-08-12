package com.actionstarter.persistence.room

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Phase 10 C1（計画書§3.1、本プロジェクト初のDB）。行動ログ・Personal Profileの永続化基盤。
 *
 * `version = 1`（新規DB、移行元スキーマなし）。`exportSchema = true`
 * （`app/build.gradle.kts`の`ksp { arg("room.schemaLocation", ...) }`で`app/schemas/`へ出力、
 * 計画書§12確認事項5「確定」）。**次回スキーマ変更時にmigrationテストを追加する**（v1時点は
 * 移行元が存在しないため対象外、計画書§3.1）。
 *
 * ファイル名`behavior_log.db`は`AppContainer`側で固定し、バックアップ除外ルール
 * （`data_extraction_rules.xml`・`backup_rules.xml`）が参照する`-wal`/`-shm`サイドカーの
 * 除外対象と一致させる（計画書§3.4、レビューCRITICAL・§13 No.2）。
 */
@Database(
    entities = [BehaviorEventEntity::class, PersonalExecutionProfileEntity::class],
    version = 1,
    exportSchema = true
)
abstract class BehaviorLogDatabase : RoomDatabase() {
    abstract fun behaviorEventDao(): BehaviorEventDao
    abstract fun personalExecutionProfileDao(): PersonalExecutionProfileDao

    companion object {
        /**
         * 実DBファイル名。バックアップ除外XML（`app/src/main/res/xml/data_extraction_rules.xml`・
         * `backup_rules.xml`）が`<exclude domain="database" path="...">`で参照する値と
         * 手動同期が必要——T-P10-9bがXML側の記述を検証するpinningテスト。
         */
        const val DATABASE_NAME: String = "behavior_log.db"
    }
}
