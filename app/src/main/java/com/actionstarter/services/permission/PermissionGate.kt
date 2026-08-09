package com.actionstarter.services.permission

/**
 * 仕様§66「Permission」・§95.4（READ_CALENDAR行）準拠。UIから権限照会を分離してテスト可能に
 * する契約（計画書§7.3）。呼び出し元はREAD_CALENDAR等の権限文字列（`Manifest.permission.*`）
 * を渡し、許可状態のみを受け取る。権限リクエストUI（`rememberLauncherForActivityResult`）
 * 自体は本interfaceの責務外であり、`ActionStarterNavHost`側が保持する
 * （計画書§7.3「権限リクエストUIの配置」）。
 *
 * Android実装は[AndroidPermissionGate]（`ContextCompat.checkSelfPermission`使用）。
 * Robolectricでは`shadowOf(application).grantPermissions(...)`/`denyPermissions(...)`で
 * 状態を作る想定（P2-C2 probe対象P-4、計画書§13）。
 *
 * 契約scaffold（P2-C2、TDD例外・裁定B3の系）時点では宣言のみであり、実装は行わない
 * （P2-C4で[AndroidPermissionGate]として実装する）。
 */
interface PermissionGate {
    fun isGranted(permission: String): Boolean
}
