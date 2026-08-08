package com.actionstarter.domain.model

/**
 * 仕様§48準拠（Step Model）。ExecutionStepの種別。
 */
enum class ExecutionStepType {
    TRANSITION,
    PREPARATION,
    DEPARTURE,
    TRAVEL
}
