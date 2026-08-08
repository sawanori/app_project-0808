package com.actionstarter.domain.model

import java.time.Duration
import java.time.Instant

/**
 * 仕様§49準拠（Execution Plan）。ADR-0010により全フィールドval化
 * （仕様書は`steps`／`transitionStart`／`departureTime`／`estimatedArrival`／
 * `arrivalBuffer`を`var`と表記しているが、本プロジェクトでは意図的にval化する。
 * 理由はADR-0010・[ExecutionStep]のKDoc参照）。
 *
 * [steps]はコンストラクタに渡された順序に関わらず、[ExecutionStep.scheduledStart]の昇順に
 * 正規化して保持する（T-DM-6）。`scheduledStart`が`null`（未スケジュール）のステップは
 * 末尾に配置する（`nullsLast()`。安定ソートのため、同一`scheduledStart`同士は入力順を保つ）。
 *
 * 実装メモ：本クラスは`data class`ではなく通常の`class`として実装し、[equals]・[hashCode]・
 * [toString]・[copy]を手動実装している。理由：Kotlinの`data class`は主コンストラクタの
 * 全パラメータが`val`/`var`プロパティであることを要求するため、「コンストラクタ引数は
 * そのまま受け取りつつ、内部で正規化した値を同名プロパティとして公開する」実装ができない
 * （`val steps`をコンストラクタ引数として宣言すると`init`内での再代入は不可、かつ別名の
 * 内部プロパティで正規化してもコンストラクタ引数と同名の公開プロパティを別途宣言することは
 * 名前衝突になるため）。公開コンストラクタのシグネチャ（引数名・型・順序・可視性）はC2確定の
 * ままいっさい変更していない。[equals]/[hashCode]/[copy]は`data class`が自動生成する場合と
 * 同じフィールド単位の構造的等価性・複製セマンティクスになるよう実装している。
 *
 * T-DM-9（[StepPriority.REQUIRED]のステップを省略候補にしないという不変条件）は、
 * Fable 5裁定（2026-08-08、[com.actionstarter.mock.MockRecoveryFactoryTest]のKDoc参照）に
 * よりファクトリ層（[com.actionstarter.recovery.RecoveryEngine]実装）の責務として
 * 再定義されたため、本クラスの`init`では実装しない（`ExecutionPlan`に
 * `skippedStepIds`フィールドは存在しない。仕様§49維持）。
 */
class ExecutionPlan(
    val event: ExecutionEvent,
    steps: List<ExecutionStep>,
    val transitionStart: Instant,
    val departureTime: Instant,
    val estimatedArrival: Instant,
    val arrivalBuffer: Duration
) {
    val steps: List<ExecutionStep> = steps.sortedWith(compareBy(nullsLast()) { it.scheduledStart })

    fun copy(
        event: ExecutionEvent = this.event,
        steps: List<ExecutionStep> = this.steps,
        transitionStart: Instant = this.transitionStart,
        departureTime: Instant = this.departureTime,
        estimatedArrival: Instant = this.estimatedArrival,
        arrivalBuffer: Duration = this.arrivalBuffer
    ): ExecutionPlan = ExecutionPlan(
        event = event,
        steps = steps,
        transitionStart = transitionStart,
        departureTime = departureTime,
        estimatedArrival = estimatedArrival,
        arrivalBuffer = arrivalBuffer
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExecutionPlan) return false
        return event == other.event &&
            steps == other.steps &&
            transitionStart == other.transitionStart &&
            departureTime == other.departureTime &&
            estimatedArrival == other.estimatedArrival &&
            arrivalBuffer == other.arrivalBuffer
    }

    override fun hashCode(): Int {
        var result = event.hashCode()
        result = 31 * result + steps.hashCode()
        result = 31 * result + transitionStart.hashCode()
        result = 31 * result + departureTime.hashCode()
        result = 31 * result + estimatedArrival.hashCode()
        result = 31 * result + arrivalBuffer.hashCode()
        return result
    }

    override fun toString(): String =
        "ExecutionPlan(event=$event, steps=$steps, transitionStart=$transitionStart, " +
            "departureTime=$departureTime, estimatedArrival=$estimatedArrival, arrivalBuffer=$arrivalBuffer)"
}
