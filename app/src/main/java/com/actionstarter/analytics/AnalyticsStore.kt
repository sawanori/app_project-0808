package com.actionstarter.analytics

import com.actionstarter.domain.model.PersonalExecutionProfile

/**
 * Phase 10 C2（計画書`docs/plans/phase10-behavior-log-profile.md`§2・§3.2、レビュー§13
 * No.1）。[AnalyticsDomain.PLAN]／[AnalyticsDomain.RECOVERY]の2値。`AI_WORDING_OUTCOME`が
 * `ContextualizationResult`（Plan）／`RecoveryContextualizationResult`（Recovery）のどちらに
 * 由来するかを区別する。他4種のイベント種別はPlan確定後のExecution/Recoveryフローでのみ
 * 発生するため一律[RECOVERY]（計画書§3.2、「Recovery画面限定」ではなく「Plan確定後の
 * ランタイム全体」を指す値として運用する設計判断）。
 */
enum class AnalyticsDomain {
    PLAN,
    RECOVERY
}

/**
 * Phase 10 C1/C2（計画書§2・§3、ADR-0049決定5「T-GW-14はPhase 10（`AnalyticsStore`導入）／
 * Phase 12（Analytics実装）とともに実装する」の回答）。行動ログ・Personal Profileへの
 * 唯一の書き込み窓口。features層（`ExecutionViewModel`・`RecoveryViewModel`・
 * `PlanReviewViewModel`）はこのインターフェースのみを参照し、`persistence.room`配下の
 * Room型（`BehaviorEventEntity`等）を直接参照しない（`LocalAiGateway`と同型の層規律）。
 *
 * **C2でイベント種別ごとの意味付きメソッドへ再設計**（C1時点の汎用`record(BehaviorEventEntity)`
 * は、features層がRoom型を直接構築する必要があり層規律に反することが実装時に判明したため
 * 廃止した）。各メソッドはタイトル生文を一切引数に取らない——呼び出し元が
 * `EventCategoryClassifier.classify`済みの[eventCategory]のみを渡す設計を型で強制する。
 */
interface AnalyticsStore {

    /**
     * ステップ完了（計画書§3.2「STEP_DONE」、`ExecutionViewModel.handleConfirmedPlanDone`）。
     * @param stepType `domain.model.ExecutionStepType.name`。
     * @param durationMs 呼び出し元で妥当性クランプ済みの値（負値・計画窓超過は`null`）。
     */
    suspend fun recordStepDone(eventCategory: String, stepType: String, durationMs: Long?)

    /** ステップスキップ（計画書§3.2「STEP_SKIPPED」、`RecoveryViewModel.useThisPlan`経路）。 */
    suspend fun recordStepSkipped(eventCategory: String, semanticAction: String)

    /**
     * 遅延検出（計画書§3.2「DELAY_DETECTED」、レビュー§13 No.4）。`RecoveryViewModel.init`で
     * 1回のみ記録する（Recovery画面遷移＝遅延確定の唯一の経路のため、ViewModelライフサイクル
     * 単位で重複排除が自然に成立する）。
     */
    suspend fun recordDelayDetected(eventCategory: String)

    /** Recovery選択（計画書§3.2「RECOVERY_SELECTED」、`RecoveryViewModel.useThisPlan`）。 */
    suspend fun recordRecoverySelected(eventCategory: String, semanticAction: String)

    /**
     * AI文言採否（計画書§3.2「AI_WORDING_OUTCOME」、レビューCRITICAL・§13 No.1）。Plan側
     * （`PlanReviewViewModel`、`ContextualizationResult`）・Recovery側（`RecoveryViewModel`、
     * `RecoveryContextualizationResult`）の両方から呼ばれる。[fallbackReason]は
     * `Unchanged(plan, reason: AiFallbackReason)`の`reason.name`（[aiAdopted]が`false`の
     * ときのみ非null）。
     */
    suspend fun recordAiWordingOutcome(domain: AnalyticsDomain, eventCategory: String, aiAdopted: Boolean, fallbackReason: String?)

    /**
     * Phase 10 C3（計画書§3.3、修正版計画コーディネーター指示）。[eventCategory]のPersonal
     * Profileを返す。**返す型は`domain.model.PersonalExecutionProfile`であり、Room型ではない**
     * （`PersonalExecutionProfile`自体はドメインモデルであり本メソッドを層規律違反にしない）。
     *
     * 対象カテゴリのイベントが1件もない（永続化された行自体が存在しない）場合は`null`を返す
     * （計画書§3.3「イベント0件のカテゴリはPersonalExecutionProfileを返さない」、
     * `PlanningContext.profile`の既存nullable設計・初回利用フローと整合）。行は存在するが
     * 個別フィールドにサンプルがない場合（例: 対象カテゴリでTRANSITION種別のステップが
     * まだ一度も完了していない）、および仕様上計測手段が存在しない3フィールド
     * （`averageResponseDelay`／`averageDepartureDelay`／`preferredArrivalBuffer`）は
     * `Duration.ZERO`をプレースホルダとして埋める（`PersonalExecutionProfileEntity`のKDoc
     * 「C3で解消した設計テンション」参照）。
     */
    suspend fun getProfile(eventCategory: String): PersonalExecutionProfile?

    /**
     * 行動ログ・Personal Profileの全件を削除する。**失敗を握り潰さない**（計画書§8
     * 「全データ削除」——破壊的操作のためサイレント化しない）。呼び出し元（Settings画面）が
     * 成功/失敗をユーザーへ明示する。
     */
    suspend fun clearAll(): Result<Unit>
}
