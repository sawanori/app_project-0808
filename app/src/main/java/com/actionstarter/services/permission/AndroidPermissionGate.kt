package com.actionstarter.services.permission

import android.content.Context

/**
 * [PermissionGate]のAndroid実装（計画書§7.3）。`ContextCompat.checkSelfPermission`
 * （`androidx.core:core-ktx` 1.16.0、既存依存）を用いてREAD_CALENDAR等（仕様§95.4）の
 * 許可状態を照会する。
 *
 * DI結線（`AppContainer`からの生成）は本サイクル（P2-C2）では行わない（P2-C5で結線する）。
 * 本体は本サイクル（P2-C2、契約scaffold・TDD例外）では`TODO()`とし、P2-C4で実装する。
 */
class AndroidPermissionGate(
    private val context: Context
) : PermissionGate {
    override fun isGranted(permission: String): Boolean {
        TODO("P2-C4で実装")
    }
}
