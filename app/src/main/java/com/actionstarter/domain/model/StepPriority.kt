package com.actionstarter.domain.model

/**
 * 仕様§48準拠（Step Model）。ExecutionStepの優先度。
 *
 * [REQUIRED]のステップはRecovery省略候補として提示してはならない（仕様§33、
 * エラー＆レスキューマップ#13）。この制約はC4で[com.actionstarter.recovery.RecoveryEngine]
 * 実装（[com.actionstarter.mock.MockRecoveryFactory]、Phase 1限定）のRecoveryOption生成
 * ロジックとして実装済み。
 */
enum class StepPriority {
    REQUIRED,
    IMPORTANT,
    OPTIONAL
}
