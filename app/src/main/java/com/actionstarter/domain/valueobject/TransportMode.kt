package com.actionstarter.domain.valueobject

/**
 * 仕様§9準拠（移動手段）。Travel Timeの抽象化のための移動手段種別。
 */
enum class TransportMode {
    WALKING,
    DRIVING,
    TRANSIT,
    CYCLING
}
