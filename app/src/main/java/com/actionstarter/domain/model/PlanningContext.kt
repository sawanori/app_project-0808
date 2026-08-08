package com.actionstarter.domain.model

import com.actionstarter.domain.valueobject.TransportMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * ADR-0005により仕様書未定義のため計画書§9.2で補完定義する型。
 * [com.actionstarter.planning.PlanningEngine.createPlan]（仕様§44）の入力。
 *
 * [now]／[zoneId]／[locale]は仕様§8（時刻・地域対応）の方針に従い、内部時刻を
 * `java.time`（`Instant`/`ZoneId`/`Locale`）で分離して扱うために保持する。
 * [travelEstimate]は場所情報のないイベント（エラー＆レスキューマップ#5）に対応する
 * ためnull許容。[profile]は初回利用時（学習データなし）に対応するためnull許容。
 */
data class PlanningContext(
    val event: ExecutionEvent,
    val now: Instant,
    val zoneId: ZoneId,
    val locale: Locale,
    val transportMode: TransportMode,
    val travelEstimate: Duration?,
    val arrivalBuffer: Duration,
    val profile: PersonalExecutionProfile?
)
