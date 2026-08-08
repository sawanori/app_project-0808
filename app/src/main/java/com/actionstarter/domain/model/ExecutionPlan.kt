package com.actionstarter.domain.model

import java.time.Duration
import java.time.Instant

/**
 * 仕様§49準拠（Execution Plan）。ADR-0010により全フィールドval化
 * （仕様書は`steps`／`transitionStart`／`departureTime`／`estimatedArrival`／
 * `arrivalBuffer`を`var`と表記しているが、本プロジェクトでは意図的にval化する。
 * 理由はADR-0010・[ExecutionStep]のKDoc参照）。
 *
 * 契約scaffold（C2）時点では以下の不変条件は未実装（C4で実装）：
 * - `steps`が昇順で保持されること（T-DM-6）
 * - `skippedStepIds`に[StepPriority.REQUIRED]のステップIDを含められないこと（仕様§33、T-DM-9）
 */
data class ExecutionPlan(
    val event: ExecutionEvent,
    val steps: List<ExecutionStep>,
    val transitionStart: Instant,
    val departureTime: Instant,
    val estimatedArrival: Instant,
    val arrivalBuffer: Duration
)
