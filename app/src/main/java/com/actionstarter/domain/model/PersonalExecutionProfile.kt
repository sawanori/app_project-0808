package com.actionstarter.domain.model

import java.time.Duration

/**
 * 仕様§52準拠（Personal Execution Profile）。ADR-0010により全フィールドval化
 * （仕様書は全フィールドを`var`と表記しているが、本プロジェクトでは意図的にval化する。
 * 理由はADR-0010・[com.actionstarter.domain.model.ExecutionStep]のKDoc参照）。
 *
 * ユーザーごとの平均所要時間・遅延傾向を保持する。永続化はRoomで行う（Phase 10、
 * 仕様§74）。契約scaffold（C2）時点では永続化・更新ロジックは実装しない。
 */
data class PersonalExecutionProfile(
    val eventCategory: String,
    val averageTransitionDuration: Duration,
    val averagePreparationDuration: Duration,
    val averageResponseDelay: Duration,
    val averageDepartureDelay: Duration,
    val preferredArrivalBuffer: Duration
)
