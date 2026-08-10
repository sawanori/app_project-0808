package com.actionstarter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * タイポグラフィトークン（UI再設計サイクル）。Material 3既定の型スケール（サイズ・行送りは
 * `androidx.compose.material3.Typography()`の既定値をそのまま踏襲）に対し、Execution画面の
 * 「今やる1つの行動」（displayスケール）とEventSelectionのカード階層（title/body/label）を
 * 明快に区別するため、**ウェイトとletterSpacingのみ**を上書きする。カスタムフォント
 * ファイルは同梱しないため`FontFamily`は指定せず既定（システムSans-serif）を使う。
 *
 * 各`copy()`はMaterial 3既定の`Typography()`インスタンスが返す該当スタイルを起点に
 * ウェイト等だけを変える（サイズ・行送り等の基礎値はM3既定のまま）。
 */
private val baseTypography = Typography()

val ActionStarterTypography = Typography(
    displayLarge = baseTypography.displayLarge.copy(fontWeight = FontWeight.Bold),
    displayMedium = baseTypography.displayMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp
    ),
    displaySmall = baseTypography.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = baseTypography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = baseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = baseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = baseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = baseTypography.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = baseTypography.bodyLarge,
    bodyMedium = baseTypography.bodyMedium,
    bodySmall = baseTypography.bodySmall,
    labelLarge = baseTypography.labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp
    ),
    labelMedium = baseTypography.labelMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp
    ),
    labelSmall = baseTypography.labelSmall
)
