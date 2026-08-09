package com.actionstarter.persistence

import android.content.SharedPreferences

/**
 * F55実装（計画書§6.1・§7.3、S-1裁定）。[ExecutionScheduleStore]のSharedPreferences実装。
 * `org.json`でシリアライズし（新規依存を追加しない。Android SDK同梱）、読み出し時に
 * [ExecutionScheduleRecord.CURRENT_SCHEMA_VERSION]との不一致・JSON破損のレコードを破棄する
 * （信頼境界、エラー&レスキューマップ#12）。
 *
 * [nextRequestCode]が払い出すカウンタ自体も本ストアへ永続化する必要がある
 * （プロセス内メモリのみで保持すると再起動のたびにリセットされ、requestCode再利用による
 * PendingIntent衝突が発生するため。計画書§7.3「PendingIntent一意性の規約」）。
 *
 * 契約scaffold（P5-C1、TDD例外）時点では宣言のみであり、実装本体はP5-C3で行う。
 *
 * @param preferences 専用ファイル名で取得済みの`SharedPreferences`
 *   （呼び出し側が`context.getSharedPreferences(NAME, MODE_PRIVATE)`で用意する）。
 */
class SharedPreferencesExecutionScheduleStore(
    private val preferences: SharedPreferences
) : ExecutionScheduleStore {
    override fun nextRequestCode(): Int = TODO()

    override fun save(record: ExecutionScheduleRecord) {
        TODO()
    }

    override fun loadAll(): ExecutionScheduleLoadResult = TODO()

    override fun clear(planId: String) {
        TODO()
    }
}
