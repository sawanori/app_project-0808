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
 *
 * **P7-C7拡張（計画書§9.2穴A・穴B、`docs/plans/phase7-local-llm-foundation.md`§14 P7-C7）**:
 * 元々の実装は非再帰列挙（`listFiles{}`）・禁止語2種（`com.actionstarter.ai`/
 * `LocalLanguageModel`）のみであった。Phase 7でLiteRT-LMランタイム（`com.google.ai.edge.litertlm`）
 * と将来の代替ランタイム候補パッケージ（`com.actionstarter.llm`）が導入されたため、以下2点を
 * **強化方向のみ**で拡張する（TEAMS §2の既存テスト変更条件、assertion強度を下げない）。
 * 1. **穴B対処**: `listFiles{}`（直下のみ）を`walkTopDown()`（再帰）へ変更し、`planning/`配下に
 *    将来サブディレクトリが作られても検出漏れが起きないようにする（再帰性のメタ検証は
 *    [com.actionstarter.ai.IsolationGuardSupportTest]のT-AIISO-8参照。同一のKotlin標準
 *    ライブラリ関数`File.walkTopDown()`・同一の述語を用いるため、当該メタテストの実証は
 *    本ガードの穴B対処にもそのまま適用できる）。
 * 2. **穴A・穴C対処**: 禁止語へ`com.google.ai.edge.litertlm`・`com.actionstarter.llm`を追加し、
 *    `recovery`/`services.notification`の同種ガードと禁止語リストを完全に統一する。
 */
class PlanningLlmIsolationTest {

    // T-BPE-28: 正常系 - planning/配下のソース（再帰）がcom.actionstarter.ai / LocalLanguageModel /
    // com.google.ai.edge.litertlm / com.actionstarter.llmのいずれも参照しない（構造ガード、
    // P7-C7で再帰化＋禁止語拡張）
    @Test
    fun tBpe28_planningPackageSources_doNotReferenceAiPackageOrLocalLanguageModel() {
        val planningDir = resolvePlanningPackageDir()
        val kotlinFiles = planningDir.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()

        assertTrue(
            "planning/パッケージディレクトリにKotlinソースファイルが見つかりません: ${planningDir.absolutePath}",
            kotlinFiles.isNotEmpty()
        )

        val bannedReferences = listOf(
            "com.actionstarter.ai",
            "LocalLanguageModel",
            "com.google.ai.edge.litertlm",
            "com.actionstarter.llm"
        )
        val offendingFiles = kotlinFiles.filter { file ->
            val text = file.readText()
            bannedReferences.any { text.contains(it) }
        }

        assertTrue(
            "planning/配下のソースが禁止参照($bannedReferences)を含んでいます" +
                "（仕様§15の決定的処理原則違反、P7-C7で禁止語拡張）: ${offendingFiles.map { it.name }}",
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
