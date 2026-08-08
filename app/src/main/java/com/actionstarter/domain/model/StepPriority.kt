package com.actionstarter.domain.model

/**
 * 仕様§48準拠（Step Model）。ExecutionStepの優先度。
 *
 * [REQUIRED]のステップはRecovery省略候補として提示してはならない（仕様§33、
 * エラー＆レスキューマップ#13）。この制約はC4で[com.actionstarter.recovery.RecoveryEngine]
 * 実装時にRecoveryOption生成ロジックとして実装する（契約scaffold時点=C2では未実装）。
 */
enum class StepPriority {
    REQUIRED,
    IMPORTANT,
    OPTIONAL
}
