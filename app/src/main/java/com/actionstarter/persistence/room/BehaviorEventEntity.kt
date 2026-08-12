package com.actionstarter.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 10 C1（計画書`docs/plans/phase10-behavior-log-profile.md`§3.2、C-18「行動ログ」の解消）。
 * 行動ログ1件を表すRoomエンティティ。**タイトル生文・カレンダー本文・住所等のPIIは一切
 * 保持しない**（仕様§10「Behavioral Historyを外部LLMへ送らない」の精神をローカル保存にも
 * 適用、計画書§2）——[eventCategory]は書き込み時点で`EventCategoryClassifier.classify`の
 * 戻り値（カテゴリ文字列のみ）を計算して格納し、タイトル自体は呼び出し元のスコープを
 * 出ない。
 *
 * フィールド構成は計画書§3.2で確定した最小集合。**本クラスへ新規フィールドを追加する際は
 * 必ずタイトル・カレンダー本文等の生文を含まないことを確認すること**（T-P10-9が構造的に
 * 検証する）。
 *
 * @param domain `"plan"`／`"recovery"`の2値（計画書§3.2、レビュー§13 No.1で新設）。
 *   [eventType]が`AI_WORDING_OUTCOME`のときは`ContextualizationResult`（Plan）／
 *   `RecoveryContextualizationResult`（Recovery）のどちらに由来するかを表す。他4種の
 *   [eventType]はPlan確定後のExecution/Recoveryフローでのみ発生するため一律`"recovery"`。
 * @param eventType `STEP_DONE`/`STEP_SKIPPED`/`DELAY_DETECTED`/`RECOVERY_SELECTED`/
 *   `AI_WORDING_OUTCOME`の5種（計画書§2「仕様§53-54の広いイベント語彙のうち最小5種のみ実装」）。
 * @param semanticAction `RECOVERY_SELECTED`時のみ非null（`RecoveryOption.semanticAction`）。
 * @param stepType `STEP_DONE`時のみ非null。`domain.model.ExecutionStepType.name`
 *   （`TRANSITION`/`PREPARATION`/`DEPARTURE`/`TRAVEL`）。C3のPersonal Profile集計（計画書§3.3
 *   導出表）が`averageTransitionDuration`／`averagePreparationDuration`のどちらへ振り分けるかの
 *   フィルタ条件として使う——本フィールドがなければC3の集計クエリが対象ステップ種別を
 *   区別できない（C2実装時の追加発見。Room `version`は1のまま据え置く——本スキーマは
 *   いかなる実機・実ユーザーデータも経ていないため、マイグレーションではなく
 *   `app/schemas/`の1.json再エクスポートで対応する）。
 * @param durationMs `STEP_DONE`時の実績所要時間（クランプ後、負値・計画窓超過はnull）。
 * @param aiAdopted `AI_WORDING_OUTCOME`時のみ非null。`ContextualizationResult.Applied`／
 *   `RecoveryContextualizationResult.Applied`なら`true`。
 * @param fallbackReason `AI_WORDING_OUTCOME`時のみ非null。`Unchanged(plan, reason)`の
 *   `reason: AiFallbackReason`の`name`をそのまま格納する（`RecoveryOptionText`経由ではない、
 *   計画書§3.2「取得元を精密化」参照）。
 */
@Entity(tableName = "behavior_events")
data class BehaviorEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "domain")
    val domain: String,
    @ColumnInfo(name = "event_type")
    val eventType: String,
    @ColumnInfo(name = "event_category")
    val eventCategory: String,
    @ColumnInfo(name = "semantic_action")
    val semanticAction: String? = null,
    @ColumnInfo(name = "step_type")
    val stepType: String? = null,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,
    @ColumnInfo(name = "ai_adopted")
    val aiAdopted: Boolean? = null,
    @ColumnInfo(name = "fallback_reason")
    val fallbackReason: String? = null
) {
    companion object {
        const val DOMAIN_PLAN: String = "plan"
        const val DOMAIN_RECOVERY: String = "recovery"

        const val EVENT_TYPE_STEP_DONE: String = "STEP_DONE"
        const val EVENT_TYPE_STEP_SKIPPED: String = "STEP_SKIPPED"
        const val EVENT_TYPE_DELAY_DETECTED: String = "DELAY_DETECTED"
        const val EVENT_TYPE_RECOVERY_SELECTED: String = "RECOVERY_SELECTED"
        const val EVENT_TYPE_AI_WORDING_OUTCOME: String = "AI_WORDING_OUTCOME"
    }
}
