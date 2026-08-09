package com.actionstarter.planning

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-BPE-28（計画書§8.2、`docs/plans/phase4-basic-engine.md`§9エラーマップ#17）。
 *
 * `planning/`パッケージ配下の全ソースファイルが`com.actionstarter.ai`パッケージ・
 * `LocalLanguageModel`のいずれも参照しないことをソーステキスト走査で検証する構造ガード
 * （決定的処理のみで構成、仕様§15。Local AIが停止・非対応の端末でもBasic Engineが独立して
 * 動作し続けることを保証する、仕様§19原則）。
 *
 * [resolvePlanningPackageDir]は[com.actionstarter.di.AppContainerTest.resolveMockPackageDir]・
 * [com.actionstarter.i18n.StringResourceParityTest.resolveStringsXml]と同じ多段fallback方式で
 * `planning/`パッケージディレクトリを解決する（working directoryがGradle実行コンテキストにより
 * `app/`直下・リポジトリルート・その祖先のいずれにもなり得るため）。
 *
 * **本テストの性質に関する留意**: `planning/`配下の現行ソース（`BasicPlanningEngine.kt`・
 * `BasicPlanningDefaults.kt`・`PlanningEngine.kt`、いずれもP4-C1時点で確認済み）は
 * 本ファイル作成時点で既に`com.actionstarter.ai`・`LocalLanguageModel`のいずれも参照して
 * いないため、他のT-BPEケース（`BasicPlanningEngine.createPlan`の`TODO()`起因で確実にRedに
 * なる）とは異なり、本ケースは実装前から既にGreenの見込みが高い。これは仕様不備ではなく、
 * 本ケースの目的が「現状の非参照を証明すること」ではなく「将来の変更で誤って参照を
 * 混入させてしまうことを防ぐ回帰ガード」であるため（§9エラーマップ#17の記載どおり）。
 */
class PlanningLlmIsolationTest {

    // T-BPE-28: 正常系 - planning/配下のソースがcom.actionstarter.ai / LocalLanguageModelを
    // 一切参照しない（構造ガード）
    @Test
    fun tBpe28_planningPackageSources_doNotReferenceAiPackageOrLocalLanguageModel() {
        val planningDir = resolvePlanningPackageDir()
        val kotlinFiles = planningDir.listFiles { file -> file.isFile && file.extension == "kt" }

        assertTrue(
            "planning/パッケージディレクトリにKotlinソースファイルが見つかりません: ${planningDir.absolutePath}",
            kotlinFiles != null && kotlinFiles.isNotEmpty()
        )

        val offendingFiles = kotlinFiles!!.filter { file ->
            val text = file.readText()
            text.contains("com.actionstarter.ai") || text.contains("LocalLanguageModel")
        }

        assertTrue(
            "planning/配下のソースがcom.actionstarter.aiまたはLocalLanguageModelを参照しています" +
                "（仕様§15の決定的処理原則違反）: ${offendingFiles.map { it.name }}",
            offendingFiles.isEmpty()
        )
    }

    /**
     * `planning/`パッケージディレクトリ（`BasicPlanningEngine.kt`等が置かれている
     * `src/main/java/com/actionstarter/planning`）を解決する。ディレクトリ自体が
     * 見つからない場合は「参照がない」という誤ったGreen判定（パス解決失敗によるサイレントな
     * 偽陽性）を防ぐため、例外で即座に失敗させる。
     */
    private fun resolvePlanningPackageDir(): File {
        val relative = "src/main/java/com/actionstarter/planning"

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
            "planningパッケージディレクトリが見つかりません。相対パス '$relative' を作業ディレクトリ " +
                "'${System.getProperty("user.dir")}' およびその祖先から探索しましたが解決できませんでした。"
        )
    }
}
