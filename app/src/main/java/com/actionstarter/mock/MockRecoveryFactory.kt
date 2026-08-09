package com.actionstarter.mock

import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryOption
import com.actionstarter.domain.model.RecoveryPlan
import com.actionstarter.domain.model.StepPriority
import com.actionstarter.recovery.RecoveryEngine
import java.util.UUID

/**
 * Phase 1限定のMock実装（計画書§8 U6）。Recovery候補（最大3件、仕様§32）を生成する。
 * Phase 6（仕様§70 Recovery Basic、`docs/TEAMS.md`§5）でBasicRecoveryEngine実装に
 * 置き換わり次第、本クラスは削除する。
 *
 * §11.2の表には本クラス名を直接対象とするT-MOCK-*行はないが、RecoveryScreen（F8、
 * T-REC-1〜6）がRecovery候補を表示するために必要な供給源として、PlanReviewScreen向けの
 * [com.actionstarter.planning.BasicPlanningEngine]と役割上対応する構成で用意する
 * （`MockPlanFactory`はP4-C5統合ウィンドウで削除されBasicPlanningEngineへ完全昇格済み。
 * `docs/plans/phase4-basic-engine.md`§6.3・§7.2、P4-C6申し送り修正）。
 *
 * [RecoveryEngine]を実装するのは、仕様書§7.1のレイヤー越境禁止規約に従うため。これにより
 * `features/recovery/RecoveryViewModel`が受け取る`RecoveryEngine`型の実引数として、
 * C4/C5のDI結線時に本クラスをそのまま注入できる（コンストラクタシグネチャの変更が不要）。
 *
 * C4で実装。T-DM-9（ファクトリ層契約として再定義。Fable 5裁定 2026-08-08、
 * [com.actionstarter.mock.MockRecoveryFactoryTest]のKDoc参照）を満たすため、
 * [StepPriority.REQUIRED]のステップのIDは、いかなる[RecoveryOption.skippedStepIds]にも
 * 含めない（仕様§33：安全・必須物は勝手に省略しない）。省略候補にできるのは
 * `skippable == true`かつ`priority != StepPriority.REQUIRED`のステップのみとする。
 *
 * 最大3件（仕様§32、[RecoveryPlan]の`init`検証）の候補を、省略範囲が異なる3段階
 * （省略なし／OPTIONALのみ省略／OPTIONAL＋IMPORTANT省略）で生成する。決定的計算のみで
 * 構成し（仕様§13/§15）、LLMは使用しない。
 */
class MockRecoveryFactory : RecoveryEngine {
    override suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan {
        val skippableNonRequired = context.unfinishedSteps.filter {
            it.skippable && it.priority != StepPriority.REQUIRED
        }
        val optionalOnlyIds = skippableNonRequired
            .filter { it.priority == StepPriority.OPTIONAL }
            .map { it.id }
        val optionalAndImportantIds = skippableNonRequired.map { it.id }

        val estimatedArrivalWithoutSkip = context.currentTime.plus(context.latestTravelEstimate)

        val options = buildList {
            add(
                RecoveryOption(
                    id = UUID.randomUUID(),
                    semanticAction = "continue_as_planned",
                    title = "Continue as planned",
                    explanation = "Keep the current plan without skipping any step.",
                    estimatedArrival = estimatedArrivalWithoutSkip,
                    skippedStepIds = emptyList()
                )
            )
            if (optionalOnlyIds.isNotEmpty()) {
                add(
                    RecoveryOption(
                        id = UUID.randomUUID(),
                        semanticAction = "skip_optional_steps",
                        title = "Skip optional steps",
                        explanation = "Skip optional steps to save time.",
                        estimatedArrival = estimatedArrivalWithoutSkip,
                        skippedStepIds = optionalOnlyIds
                    )
                )
            }
            if (optionalAndImportantIds.size > optionalOnlyIds.size) {
                add(
                    RecoveryOption(
                        id = UUID.randomUUID(),
                        semanticAction = "skip_optional_and_important_steps",
                        title = "Skip optional and important steps",
                        explanation = "Skip optional and important (but skippable) steps to save more time.",
                        estimatedArrival = estimatedArrivalWithoutSkip,
                        skippedStepIds = optionalAndImportantIds
                    )
                )
            }
        }

        return RecoveryPlan(options = options.take(3))
    }
}
