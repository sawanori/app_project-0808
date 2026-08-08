package com.actionstarter.domain.model

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 仕様§48準拠（Step Model）。ADR-0010により全フィールドval化
 * （仕様書は`title`／`estimatedDuration`／`priority`／`skippable`／`scheduledStart`／
 * `completedAt`を`var`と表記しているが、本プロジェクトでは意図的にval化する）。
 *
 * 逸脱理由（ADR-0010）：`var`のまま生成後の再代入を許すと、`init`ブロックによる
 * 不変条件検証（例：`estimatedDuration`の負値拒否）を迂回してサイレント障害を生む経路が
 * 生まれる。状態変更は`copy()`を介して行い、`copy()`はコンストラクタを経由するため
 * `init`が再実行され、検証が常に効く状態を保証する。
 *
 * 契約scaffold（C2）時点では`estimatedDuration`の負値拒否等の不変条件（`init`検証）は
 * 未実装。C3のRedテスト作成後、C4で実装する（本ファイルではTDDのため意図的に未実装のまま
 * 据え置く。C2で実装するとC3のRedが成立しなくなるため）。
 */
data class ExecutionStep(
    val id: UUID,
    val semanticId: String,
    val type: ExecutionStepType,
    val title: String,
    val estimatedDuration: Duration,
    val priority: StepPriority,
    val skippable: Boolean,
    val scheduledStart: Instant?,
    val completedAt: Instant?
)
