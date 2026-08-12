package com.actionstarter.analytics

import com.actionstarter.persistence.room.BehaviorEventEntity

/**
 * Phase 10 C1（計画書`docs/plans/phase10-behavior-log-profile.md`§2・§3、ADR-0049決定5
 * 「T-GW-14はPhase 10（`AnalyticsStore`導入）／Phase 12（Analytics実装）とともに実装する」の
 * 回答）。行動ログ・Personal Profileへの唯一の書き込み窓口。features層（`ExecutionViewModel`・
 * `RecoveryViewModel`・`PlanReviewViewModel`）はこのインターフェースのみを参照し、
 * `persistence.room`配下のRoom型を直接参照しない（`LocalAiGateway`と同型の層規律）。
 *
 * **本フェーズ（C1）はscaffoldに必要な最小メソッドのみ宣言する**——各イベント種別ごとの
 * 便利メソッド（`logStepDone`等）・Personal Profile集計の読み出しはC2/C3で追加する。
 */
interface AnalyticsStore {

    /**
     * 行動ログ1件を記録する。**失敗しても例外を外へ出さない**（計画書§8「行動ログ書き込み」
     * ——ログは補助データでありユーザー操作をブロックしない）。呼び出し元は戻り値を待たずに
     * 発火してよい設計を想定する。
     */
    suspend fun record(event: BehaviorEventEntity)

    /**
     * 行動ログ・Personal Profileの全件を削除する。**失敗を握り潰さない**（計画書§8
     * 「全データ削除」——破壊的操作のためサイレント化しない）。呼び出し元（Settings画面）が
     * 成功/失敗をユーザーへ明示する。
     */
    suspend fun clearAll(): Result<Unit>
}
