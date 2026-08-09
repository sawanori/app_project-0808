package com.actionstarter.recovery

import com.actionstarter.domain.model.ExecutionPlan
import com.actionstarter.domain.model.RecoveryOption
import java.time.Clock

/**
 * 計画書§6.1・§7.4・§8.4準拠（F76、P6-C1 scaffold）。選択されたRecovery候補（[RecoveryOption]）を
 * 適用し、更新済みの[ExecutionPlan]を組み立てる（steps除去＋時刻再計算。仕様§34・§49）。
 *
 * **契約についての注記（P6-C1時点の判断・要検証）**: 計画書§7には他の新設ファイル
 * （[BasicRecoveryEngine]・[LatenessDetector]）と異なり、本クラスの正確なシグネチャを明示する
 * コード契約が示されていない（§6.1「選択案の適用＝steps除去＋時刻再計算」という一文のみ）。
 * 以下のシグネチャはT-APPLY-1〜7（計画書§8.4）から逆算した本サイクル（domain-implementer）の
 * scaffold判断であり、P6-C2（test-writer）／P6-C3実装時点で必要に応じて見直す。
 * - `apply(plan, option)` は [plan] から `option.skippedStepIds` に該当するstepsを除去した
 *   [ExecutionPlan] を返す（T-APPLY-1）。
 * - `option.skippedStepIds` に [com.actionstarter.domain.model.StepPriority.REQUIRED] のidが
 *   含まれる場合は `IllegalArgumentException`（信頼境界の二重防御、T-APPLY-3、§7.4層2）。
 * - `option.skippedStepIds` に `plan.steps` 側に存在しないidが含まれる場合も
 *   `IllegalArgumentException`（黙って無視しない、T-APPLY-5）。
 * - `departureTime`／`estimatedArrival` は除去後の残準備を基に、[clock] が示す時刻を基準として
 *   再計算する（T-APPLY-2・T-APPLY-4：`skippedStepIds`が空でも時刻は現在時刻基準で再計算）。
 *   `RoutingService` への追加呼び出しは行わない契約とする想定（§95.2のコスト方針を追加消費
 *   しないため。要検証）。
 * - `arrivalBuffer` は変更しない（T-APPLY-6、Phase 4 G-5の概念区別維持）。
 *
 * ロジック本体はP6-C3で実装する（TDD厳守。本ファイルはP6-C1時点では宣言のみ）。
 */
class RecoveryPlanApplier(
    private val clock: Clock = Clock.systemUTC()
) {
    fun apply(plan: ExecutionPlan, option: RecoveryOption): ExecutionPlan {
        TODO("P6-C3で実装（§7.4・§8.4、T-APPLY-1〜7）")
    }
}
