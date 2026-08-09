package com.actionstarter.recovery

import com.actionstarter.domain.model.RecoveryContext
import com.actionstarter.domain.model.RecoveryPlan
import com.actionstarter.domain.valueobject.TransportMode
import com.actionstarter.services.routing.RoutingService

/**
 * 仕様§68 Phase 6「Recovery Basic」・§13 Basic Engineの本番実装（F70/F73/F74/F75、
 * 計画書§7.1、P6-C1 scaffold）。
 *
 * `mock/MockRecoveryFactory.kt`（Phase 1限定Mock）が実装していた3案固定生成を、§31〜§33から
 * 導出した4規則・カスケード型の完全決定的な候補生成（A: そのまま出発／B: OPTIONAL省略／
 * C: OPTIONAL+IMPORTANT省略／D: 移動手段変更）へ昇格させる（計画書§7.2〜§7.5）。
 *
 * [RecoveryEngine]契約（§45）は変更しない。[RecoveryContext]（§50）も変更しない。
 * `title`/`explanation` は常に空文字とし、`semanticAction` をUI層のlocalizationキーとして
 * 解決する（S-4、ADR-0018のRecoveryへの拡張。ADR起票候補は計画書§7.9参照。**DECISIONS.mdへの
 * 実際の起票はP6-C5統合ウィンドウで行う** — 本サイクルの制約と計画書§6.4の対象整理の詳細は
 * 本サイクル報告を参照）。
 *
 * `RecoveryOption.id` は `UUID.nameUUIDFromBytes` による決定的生成とし、idシードは
 * `"${event.id}:${semanticAction}:${skippedStepIds.sorted().joinToString(",")}"` とする
 * （ADR-0017のRecoveryへの拡張。`estimatedArrival`はシードに含めない。計画書§7.1）。
 *
 * [routingService] はDI供給インスタンス（`CachingRoutingService` または
 * `UnconfiguredRoutingService`）を必ず注入する。本クラス自身は `RoutesApiRoutingService` を
 * 直接生成しない（§95.2、T-BRE-18/19の構造ガード対象）。[currentTransportMode] は仕様§50に
 * `transportMode` フィールドが存在しないための代替供給経路（S-3・§4.2 U-4）で、既定値は
 * [BasicRecoveryDefaults.DEFAULT_TRANSPORT_MODE] を用いる。
 *
 * ロジック本体はP6-C3で実装する（TDD厳守。T-BRE-1〜32。本ファイルはP6-C1時点では宣言のみ）。
 */
class BasicRecoveryEngine(
    private val routingService: RoutingService,
    private val defaults: BasicRecoveryDefaults = BasicRecoveryDefaults,
    private val currentTransportMode: () -> TransportMode = { BasicRecoveryDefaults.DEFAULT_TRANSPORT_MODE }
) : RecoveryEngine {
    override suspend fun createRecoveryPlan(context: RecoveryContext): RecoveryPlan {
        TODO("P6-C3で実装（§7.1〜§7.5、T-BRE-1〜32）")
    }
}
