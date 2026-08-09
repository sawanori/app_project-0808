package com.actionstarter.services.routing

import com.actionstarter.domain.valueobject.Coordinate
import com.actionstarter.domain.valueobject.RouteEstimate
import com.actionstarter.domain.valueobject.TransportMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * F25実装（計画書§6.1・§8）。プロセス内メモリのみのキャッシュ（**永続化しない**、
 * Phase 10まで持ち込まない、計画書§8「永続化」行）。キャッシュキーは
 * `(destination: Coordinate, mode: TransportMode)`、保存値は[RouteEstimate]＋計算時の
 * `origin`／`departureDate`（計画書§8冒頭）。
 *
 * **P3-C4実装メモ**: `(destination, mode)`をキーとする[ConcurrentHashMap]で保持する
 * （`Coordinate`はdata class・`TransportMode`はenumのため、いずれも構造的等価性による
 * 完全一致キー比較が既定で成立する、T-CACHE-5/T-CACHE-6）。永続化しない・エビクションも
 * 行わない（計画書§8「永続化: しない（プロセス内メモリのみ）」。エビクション方針自体は
 * §8に規定がなく、本サイクルのスコープ外）。
 */
class RouteEstimateCache {
    /** 保存値。計画書§8「保存値: RouteEstimate＋計算時のorigin: Coordinate＋departureDate: Instant」。 */
    data class Entry(
        val estimate: RouteEstimate,
        val origin: Coordinate,
        val departureDate: Instant
    )

    private data class Key(val destination: Coordinate, val mode: TransportMode)

    private val entries = ConcurrentHashMap<Key, Entry>()

    fun get(destination: Coordinate, mode: TransportMode): Entry? =
        entries[Key(destination, mode)]

    fun put(destination: Coordinate, mode: TransportMode, entry: Entry) {
        entries[Key(destination, mode)] = entry
    }
}
