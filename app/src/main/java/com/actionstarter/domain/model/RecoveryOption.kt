package com.actionstarter.domain.model

import java.time.Instant
import java.util.UUID

/**
 * 仕様§51準拠（Recovery Option）。ADR-0010によりval化（仕様書は元々全フィールドval）。
 *
 * [RecoveryPlan]（ADR-0005補完型）が保持するRecovery提案の1候補。最大3件まで
 * （仕様§32、[RecoveryPlan]のKDoc参照）。[semanticAction]は仕様§21の
 * `action_type`に相当する言語非依存な内部ID、UI表示文言は別途[title]／[explanation]
 * として分離する。
 *
 * 契約scaffold（C2）時点では、[StepPriority.REQUIRED]のステップIDを[skippedStepIds]に
 * 含められないという不変条件（仕様§33、エラー＆レスキューマップ#13）は未実装
 * （C3のRedテスト作成後、C4で実装する）。
 */
data class RecoveryOption(
    val id: UUID,
    val semanticAction: String,
    val title: String,
    val explanation: String,
    val estimatedArrival: Instant?,
    val skippedStepIds: List<UUID>
)
