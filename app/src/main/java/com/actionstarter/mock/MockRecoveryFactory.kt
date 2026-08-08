package com.actionstarter.mock

import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryPlan
import com.actionstarter.recovery.RecoveryEngine

/**
 * Phase 1限定のMock実装（計画書§8 U6）。Recovery候補（最大3件、仕様§32）を生成する。
 * Phase 6（仕様§70 Recovery Basic、`docs/TEAMS.md`§5）でBasicRecoveryEngine実装に
 * 置き換わり次第、本クラスは削除する。
 *
 * §11.2の表には本クラス名を直接対象とするT-MOCK-*行はないが、RecoveryScreen（F8、
 * T-REC-1〜6）がRecovery候補を表示するために必要な供給源として、
 * [com.actionstarter.mock.MockPlanFactory]と対になる構成で用意する。
 *
 * [RecoveryEngine]を実装するのは、仕様書§7.1のレイヤー越境禁止規約に従うため。これにより
 * `features/recovery/RecoveryViewModel`が受け取る`RecoveryEngine`型の実引数として、
 * C4/C5のDI結線時に本クラスをそのまま注入できる（コンストラクタシグネチャの変更が不要）。
 *
 * 契約scaffold追補（C2b）時点では本文は`TODO("C4で実装")`とし、ロジックは未実装。
 */
class MockRecoveryFactory : RecoveryEngine {
    override suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan {
        TODO("C4で実装")
    }
}
