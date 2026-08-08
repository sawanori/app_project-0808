package com.actionstarter.recovery

import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryPlan

/**
 * 仕様§45準拠（RecoveryEngine Interface）。
 *
 * Reality Check（仕様§30）で検出された遅延状況（[RecoveryContext]）から、
 * 最大3件のRecovery候補を含む[RecoveryPlan]（仕様§32、ADR-0005で補完定義）を
 * 生成する契約。Basic Engine（Phase 6）とLocal AI Engine（Phase 9）の両方が
 * このinterfaceを実装し、呼び出し側（RecoveryScreen/ViewModel）からは交換可能に扱う。
 *
 * 契約scaffold（C2）時点では宣言のみであり、実装は行わない。
 */
interface RecoveryEngine {
    suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan
}
