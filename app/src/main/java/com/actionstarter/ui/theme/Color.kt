package com.actionstarter.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ブランドカラートークン（UI再設計サイクル、Execution/EventSelection画面の作り込み）。
 *
 * 「予定を、今やるべき一つの行動に変える」（One Action Only／認知負荷を下げる）という
 * プロダクトの最上位原則に沿い、Composeデフォルトの紫（`#6650a4`系）を脱し、
 * **深いティール（青緑）を単一アクセントに、彩度を抑えた中立グレーを基調**とした配色を
 * 採用する。ティールは「集中・落ち着き」（過度に主張しない寒色）と「信頼」（青系统の
 * 連想）を両立させる狙いで選定した。派手な複数アクセント色は使わず、アクセント1色＋
 * 中立色という指定どおりの構成にとどめる。
 *
 * ライト／ダーク双方を自前で明示定義し（`dynamicColor`は使用しない）、`Theme.kt`の
 * `lightColorScheme` / `darkColorScheme`へそのまま渡す。エラー系（`error*`）のみ
 * Material 3ベースラインの赤トーンをそのまま踏襲する（警告色としての一般的な認知に
 * 合わせるため、ブランド色で上書きしない）。
 *
 * 命名規約: `<role><Light|Dark>`（M3の`ColorScheme`ロール名 + 明暗サフィックス）。
 */

// ---- Light color scheme -----------------------------------------------------------------

val PrimaryLight = Color(0xFF12676C)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFA3EFEF)
val OnPrimaryContainerLight = Color(0xFF002020)

val SecondaryLight = Color(0xFF4A6363)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFCCE8E7)
val OnSecondaryContainerLight = Color(0xFF051F1F)

val TertiaryLight = Color(0xFF4B607C)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFD3E4FF)
val OnTertiaryContainerLight = Color(0xFF041C36)

val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val BackgroundLight = Color(0xFFF6FAF9)
val OnBackgroundLight = Color(0xFF171D1C)
val SurfaceLight = Color(0xFFF6FAF9)
val OnSurfaceLight = Color(0xFF171D1C)
val SurfaceVariantLight = Color(0xFFDAE5E3)
val OnSurfaceVariantLight = Color(0xFF3F4948)

val OutlineLight = Color(0xFF6F7978)
val OutlineVariantLight = Color(0xFFBEC9C7)
val ScrimLight = Color(0xFF000000)
val InverseSurfaceLight = Color(0xFF2B3231)
val InverseOnSurfaceLight = Color(0xFFECF2F0)
val InversePrimaryLight = Color(0xFF84D3D2)

// ---- Dark color scheme -------------------------------------------------------------------

val PrimaryDark = Color(0xFF84D3D2)
val OnPrimaryDark = Color(0xFF00373A)
val PrimaryContainerDark = Color(0xFF00504F)
val OnPrimaryContainerDark = Color(0xFFA3EFEF)

val SecondaryDark = Color(0xFFB2CCCB)
val OnSecondaryDark = Color(0xFF1C3535)
val SecondaryContainerDark = Color(0xFF324B4B)
val OnSecondaryContainerDark = Color(0xFFCCE8E7)

val TertiaryDark = Color(0xFFB3C8E8)
val OnTertiaryDark = Color(0xFF1C314C)
val TertiaryContainerDark = Color(0xFF334764)
val OnTertiaryContainerDark = Color(0xFFD3E4FF)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

val BackgroundDark = Color(0xFF0E1514)
val OnBackgroundDark = Color(0xFFDEE4E2)
val SurfaceDark = Color(0xFF0E1514)
val OnSurfaceDark = Color(0xFFDEE4E2)
val SurfaceVariantDark = Color(0xFF3F4948)
val OnSurfaceVariantDark = Color(0xFFBEC9C7)

val OutlineDark = Color(0xFF899392)
val OutlineVariantDark = Color(0xFF3F4948)
val ScrimDark = Color(0xFF000000)
val InverseSurfaceDark = Color(0xFFDEE4E2)
val InverseOnSurfaceDark = Color(0xFF2B3231)
val InversePrimaryDark = Color(0xFF12676C)
