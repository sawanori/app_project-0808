package com.actionstarter.analytics

import com.actionstarter.persistence.room.BehaviorEventDao
import com.actionstarter.persistence.room.BehaviorEventEntity
import com.actionstarter.persistence.room.PersonalExecutionProfileDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 10 C1/C2（計画書§3.4「書き込み/clearAll排他」、レビュー§13 No.6・Gemini G6）。
 * [AnalyticsStore]のRoom実装。各`recordXxx`メソッドは対応する[BehaviorEventEntity]を内部で
 * 構築し[insert]（[record]private helper）へ渡す——features層はこの変換ロジックを知らない。
 *
 * **[mutex]による直列化**: 通常の記録・将来の集計（C3）・[clearAll]の全操作を単一の
 * [Mutex]で直列化する。[clearAll]実行中に別コルーチンからの記録が割り込み、削除直後に
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
}
