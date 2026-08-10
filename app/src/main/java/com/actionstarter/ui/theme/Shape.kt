package com.actionstarter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状トークン（UI再設計サイクル）。Material 3のデフォルトよりわずかに大きい角丸を採用し、
 * 「集中・落ち着き」の柔らかい印象を補強する（Execution画面のカード状ボタン・
 * EventSelectionのイベントカード・バナーチップ等で共通利用する）。
 * ハードコードした`RoundedCornerShape`を各画面に散らさず、本トークン経由で参照する。
 */
val ActionStarterShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
