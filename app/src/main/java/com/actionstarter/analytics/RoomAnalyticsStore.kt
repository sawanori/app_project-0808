package com.actionstarter.analytics

import com.actionstarter.domain.model.ExecutionStepType
import com.actionstarter.domain.model.PersonalExecutionProfile
import com.actionstarter.persistence.room.BehaviorEventDao
import com.actionstarter.persistence.room.BehaviorEventEntity
import com.actionstarter.persistence.room.PersonalExecutionProfileDao
import com.actionstarter.persistence.room.PersonalExecutionProfileEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * Phase 10 C1/C2/C3（計画書§3.4「書き込み/clearAll排他」、レビュー§13 No.6・Gemini G6）。
 * [AnalyticsStore]のRoom実装。各`recordXxx`メソッドは対応する[BehaviorEventEntity]を内部で
 * 構築し[insert]（[record]private helper）へ渡す——features層はこの変換ロジックを知らない。
 *
 * **[mutex]による直列化**: 通常の記録・Personal Profile集計（C3）・[clearAll]の全操作を
 * 単一の[Mutex]で直列化する。[clearAll]実行中に別コルーチンからの記録が割り込み、削除直後に
 * 古いデータが復活する競合を防ぐ（T-P10-16b）。
 *
 * **例外方針**: 記録系メソッドは[CancellationException]のみ再送出し、それ以外の
 * [Throwable]は握り潰してno-op化する（`LocalAiGateway`の§8.7原則3・T-GW-13と同型）。
 * [clearAll]は[CancellationException]を除く例外を[Result.failure]として呼び出し元へ返す
 * （サイレント化しない、計画書§8）。
 */
class RoomAnalyticsStore(
    private val behaviorEventDao: BehaviorEventDao,
    private val personalExecutionProfileDao: PersonalExecutionProfileDao,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AnalyticsStore {

    private val mutex = Mutex()

    override suspend fun recordStepDone(eventCategory: String, stepType: String, durationMs: Long?) {
        record(
            BehaviorEventEntity(
                timestamp = nowMillis(),
                domain = BehaviorEventEntity.DOMAIN_RECOVERY,
                eventType = BehaviorEventEntity.EVENT_TYPE_STEP_DONE,
                eventCategory = eventCategory,
                stepType = stepType,
                durationMs = durationMs
            )
        )
        // Phase 10 C3（計画書§3.3「集計トリガー」）: STEP_DONE記録の都度、該当カテゴリの
        // Personal Profileを再集計してupsertする。recordと同じmutex配下（下記
        // recomputeAndUpsertProfileがmutex.withLockを持つため、record自体は既にmutex解放後の
        // 状態でここへ到達する——2回別々にmutexを取得する設計。再入不可のMutexを直接ネストしない
        // ため）。
        recomputeAndUpsertProfile(eventCategory)
    }

    override suspend fun recordStepSkipped(eventCategory: String, semanticAction: String) {
        record(
            BehaviorEventEntity(
                timestamp = nowMillis(),
                domain = BehaviorEventEntity.DOMAIN_RECOVERY,
                eventType = BehaviorEventEntity.EVENT_TYPE_STEP_SKIPPED,
                eventCategory = eventCategory,
                semanticAction = semanticAction
            )
        )
    }

    override suspend fun recordDelayDetected(eventCategory: String) {
        record(
            BehaviorEventEntity(
                timestamp = nowMillis(),
                domain = BehaviorEventEntity.DOMAIN_RECOVERY,
                eventType = BehaviorEventEntity.EVENT_TYPE_DELAY_DETECTED,
                eventCategory = eventCategory
            )
        )
    }

    override suspend fun recordRecoverySelected(eventCategory: String, semanticAction: String) {
        record(
            BehaviorEventEntity(
                timestamp = nowMillis(),
                domain = BehaviorEventEntity.DOMAIN_RECOVERY,
                eventType = BehaviorEventEntity.EVENT_TYPE_RECOVERY_SELECTED,
                eventCategory = eventCategory,
                semanticAction = semanticAction
            )
        )
    }

    override suspend fun recordAiWordingOutcome(
        domain: AnalyticsDomain,
        eventCategory: String,
        aiAdopted: Boolean,
        fallbackReason: String?
    ) {
        record(
            BehaviorEventEntity(
                timestamp = nowMillis(),
                domain = when (domain) {
                    AnalyticsDomain.PLAN -> BehaviorEventEntity.DOMAIN_PLAN
                    AnalyticsDomain.RECOVERY -> BehaviorEventEntity.DOMAIN_RECOVERY
                },
                eventType = BehaviorEventEntity.EVENT_TYPE_AI_WORDING_OUTCOME,
                eventCategory = eventCategory,
                aiAdopted = aiAdopted,
                fallbackReason = fallbackReason
            )
        )
    }

    private suspend fun record(event: BehaviorEventEntity) {
        mutex.withLock {
            try {
                behaviorEventDao.insert(event)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 計画書§8「行動ログ書き込み」: ログは補助データでありユーザー操作を
                // ブロックしない。Room I/O例外等はここで吸収する。
            }
        }
    }

    /**
     * Phase 10 C3（計画書§3.3導出表）。[eventCategory]の`TRANSITION`／`PREPARATION`
     * ステップ完了実績（直近180日 or 直近500件の小さい方、[BehaviorEventDao.
     * getRecentStepDurations]が1クエリで両方を満たす）から中央値を算出し、
     * [PersonalExecutionProfileEntity]をupsertする。**集計自体もrecordと同じ例外方針**
     * （記録は補助データでありユーザー操作をブロックしない、失敗はno-op化）。
     */
    private suspend fun recomputeAndUpsertProfile(eventCategory: String) {
        mutex.withLock {
            try {
                val sinceMillis = nowMillis() - RETENTION_WINDOW_MILLIS
                val transitionDurations = behaviorEventDao.getRecentStepDurations(
                    eventCategory = eventCategory,
                    eventType = BehaviorEventEntity.EVENT_TYPE_STEP_DONE,
                    stepType = ExecutionStepType.TRANSITION.name,
                    sinceMillis = sinceMillis,
                    maxCount = RETENTION_MAX_COUNT
                ).mapNotNull { it.durationMs }
                val preparationDurations = behaviorEventDao.getRecentStepDurations(
                    eventCategory = eventCategory,
                    eventType = BehaviorEventEntity.EVENT_TYPE_STEP_DONE,
                    stepType = ExecutionStepType.PREPARATION.name,
                    sinceMillis = sinceMillis,
                    maxCount = RETENTION_MAX_COUNT
                ).mapNotNull { it.durationMs }

                personalExecutionProfileDao.upsert(
                    PersonalExecutionProfileEntity(
                        eventCategory = eventCategory,
                        averageTransitionDurationMs = median(transitionDurations),
                        averagePreparationDurationMs = median(preparationDurations)
                        // averageResponseDelayMs／averageDepartureDelayMs／preferredArrivalBufferMs
                        // は計測手段がないため既定null（計画書§3.3、レビューCRITICAL・§13 No.3）。
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 集計もログと同じく補助データ。失敗してもSTEP_DONE記録自体（既に完了済み）を
                // 巻き戻さず、次回記録時の再集計に委ねる。
            }
        }
    }

    /** 偶数件は中央2件の平均（切り捨て）、奇数件は中央値そのもの。空リストは`null`。 */
    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    /**
     * **例外方針（record系と同型・実装時の回帰発見で追加）**: 呼び出し元
     * （`PlanReviewViewModel.buildPlanningContext`が委譲する`init`の非同期精緻化ステップ）は
     * この戻り値をBasic Plan再構築の入力（`PlanningContext.profile`）へ使うため、本メソッドが
     * 例外を投げるとその精緻化ステップが失敗しうる。§19精神を読み取り経路にも適用し、
     * 失敗時は`null`（＝Profile未取得、`BasicPlanningEngine`の既存フォールバックへ委ねる）へ
     * 縮退する。
     *
     * **時間予算（[kotlinx.coroutines.withTimeoutOrNull]）は採用しない（実装時の教訓）**:
     * 当初、初回発見した回帰（`NavigationFlowTest`等でPlanが表示されなくなる）への対策として
     * 追加を試みたが、`kotlinx-coroutines-test`の`runTest`は仮想時刻で動作し、Roomの内部DAO
     * 呼び出しは（Room自身のクエリexecutorへディスパッチするため）仮想時刻を進めない実時間の
     * 処理となる——`withTimeoutOrNull`が仮想時刻基準で即座にタイムアウト側へ倒れ、実際には
     * データが存在するのに`getProfile`が常に`null`を返す偽陰性をテスト側で引き起こした
     * （Context7で`kotlinx.coroutines`の`runTest`仮想時刻ドキュメントを確認し原因を特定）。
     * 根本の回帰自体は`PlanReviewViewModel.init`の設計変更（Profile取得をtravelEstimateと
     * 同型の非同期精緻化ステップへ分離し、初回Basic Plan表示の同期パスから完全に外した設計、
     * クラスKDoc参照）で解消済みのため、本メソッド側の時間予算は不要と判断し撤回した。
     */
    override suspend fun getProfile(eventCategory: String): PersonalExecutionProfile? {
        val entity = try {
            personalExecutionProfileDao.getByCategory(eventCategory)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        } ?: return null
        return PersonalExecutionProfile(
            eventCategory = entity.eventCategory,
            averageTransitionDuration = entity.averageTransitionDurationMs?.let { Duration.ofMillis(it) }
                ?: Duration.ZERO,
            averagePreparationDuration = entity.averagePreparationDurationMs?.let { Duration.ofMillis(it) }
                ?: Duration.ZERO,
            // 計測手段がなく常にnullの3フィールド（計画書§3.3・PersonalExecutionProfileEntityの
            // KDoc「C3で解消した設計テンション」参照）。プレースホルダZEROと実測ゼロは
            // 本フェーズでは区別しない（将来フェーズへ申し送り済み）。
            averageResponseDelay = entity.averageResponseDelayMs?.let { Duration.ofMillis(it) } ?: Duration.ZERO,
            averageDepartureDelay = entity.averageDepartureDelayMs?.let { Duration.ofMillis(it) } ?: Duration.ZERO,
            preferredArrivalBuffer = entity.preferredArrivalBufferMs?.let { Duration.ofMillis(it) } ?: Duration.ZERO
        )
    }

    override suspend fun clearAll(): Result<Unit> = mutex.withLock {
        try {
            behaviorEventDao.deleteAll()
            personalExecutionProfileDao.deleteAll()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 計画書§8「全データ削除」: 破壊的操作のためサイレント化せず呼び出し元へ返す。
            Result.failure(t)
        }
    }

    private companion object {
        /** 計画書§3.4・§12確認事項3「確定: 直近180日 or 直近500件の小さい方」。 */
        const val RETENTION_WINDOW_MILLIS: Long = 180L * 24 * 60 * 60 * 1000
        const val RETENTION_MAX_COUNT: Int = 500
    }
}
