package com.actionstarter.recovery

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-BRE-32（計画書§8.2、`docs/plans/phase6-recovery-basic.md`§9エラーマップ#（構造ガード）・
 * §7.1「LLMに正確な移動時間・時刻演算・到着時刻演算・安全上重要な最終判断を決めさせない」）。
 *
 * `recovery/`パッケージ配下の全ソースファイルが`com.actionstarter.ai`パッケージを一切
 * 参照しないことをソーステキスト走査で検証する構造ガード（決定的処理のみで構成、仕様§13/§15）。
 * [com.actionstarter.planning.PlanningLlmIsolationTest]（Phase 4先例）と同じ趣旨・同じ多段
 * fallback方式の[resolveRecoveryPackageDir]を用いる。
 *
 * **本テストの性質に関する留意**: `recovery/`配下の現行ソース（`BasicRecoveryEngine.kt`・
 * `BasicRecoveryDefaults.kt`・`LatenessDetector.kt`・`RecoveryPlanApplier.kt`・
 * `RecoveryEngine.kt`、いずれもP6-C1時点で確認済み）は本ファイル作成時点で既に
 * `com.actionstarter.ai`を参照していないため、他のT-BRE系ケース
 * （`BasicRecoveryEngine.createRecoveryPlan`の`TODO()`起因で確実にRedになる）とは異なり、
 * 本ケースは実装前から既にGreenの見込みが高い。これは仕様不備ではなく、本ケースの目的が
 * 「現状の非参照を証明すること」ではなく「将来の変更で誤って参照を混入させてしまうことを防ぐ
 * 回帰ガード」であるため（[com.actionstarter.planning.PlanningLlmIsolationTest]のKDocと同じ趣旨）。
 */
class RecoveryLlmIsolationTest {

    // T-BRE-32: 正常系 - recovery/配下のソースがcom.actionstarter.aiを一切参照しない（構造ガード）
    @Test
    fun tBre32_recoveryPackageSources_doNotReferenceAiPackage() {
        val recoveryDir = resolveRecoveryPackageDir()
        val kotlinFiles = recoveryDir.listFiles { file -> file.isFile && file.extension == "kt" }

        assertTrue(
            "recovery/パッケージディレクトリにKotlinソースファイルが見つかりません: ${recoveryDir.absolutePath}",
            kotlinFiles != null && kotlinFiles.isNotEmpty()
        )

        val offendingFiles = kotlinFiles!!.filter { file ->
            file.readText().contains("com.actionstarter.ai")
        }

        assertTrue(
            "recovery/配下のソースがcom.actionstarter.aiを参照しています（仕様§15の決定的処理原則" +
                "違反）: ${offendingFiles.map { it.name }}",
            offendingFiles.isEmpty()
        )
    }

    /**
     * `recovery/`パッケージディレクトリ（`BasicRecoveryEngine.kt`等が置かれている
     * `src/main/java/com/actionstarter/recovery`）を解決する。
     * [com.actionstarter.di.AppContainerTest.resolveMockPackageDir]・
     * [com.actionstarter.planning.PlanningLlmIsolationTest.resolvePlanningPackageDir]と同じ
     * 多段fallback方式。ディレクトリ自体が見つからない場合は「参照がない」という誤った
     * Green判定（パス解決失敗によるサイレントな偽陽性）を防ぐため、例外で即座に失敗させる。
     */
    private fun resolveRecoveryPackageDir(): File {
        val relative = "src/main/java/com/actionstarter/recovery"

        val direct = File(relative)
        if (direct.isDirectory) return direct

        val fromRepoRoot = File("app", relative)
        if (fromRepoRoot.isDirectory) return fromRepoRoot

        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/$relative")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }

        error(
            "recoveryパッケージディレクトリが見つかりません。相対パス '$relative' を作業ディレクトリ " +
                "'${System.getProperty("user.dir")}' およびその祖先から探索しましたが解決できませんでした。"
        )
    }
}
