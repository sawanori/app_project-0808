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
 * [StepPriority.REQUIRED]のステップIDを[skippedStepIds]に含められないという不変条件
 * （仕様§33、エラー＆レスキューマップ#13）は、生成側（C4実装の
 * [com.actionstarter.mock.MockRecoveryFactory]）の責務として満たす。本クラス自体の`init`では
 * 検証しない（KDoc・[StepPriority]参照）。
 */
data class RecoveryOption(
    val id: UUID,
    val semanticAction: String,
    val title: String,
    val explanation: String,
    val estimatedArrival: Instant?,
    val skippedStepIds: List<UUID>
)
