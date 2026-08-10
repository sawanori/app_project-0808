package com.actionstarter.ai

import java.io.File

/**
 * P7-C7（計画書§9.2穴B対処・§9.3・§14 P7-C7、ADR-0060）。AI隔離ガード群
 * （T-AIISO-4／T-AIISO-5／T-AIISO-6／T-AIISO-7／T-AIISO-9）が共有する走査ユーティリティ。
 *
 * **既存3ガード（T-BPE-28／T-BRE-32／T-NOTIF-9、Phase 4〜6由来）は本オブジェクトを使わない**:
 * それぞれ独自の`resolveXxxPackageDir()`と全文単純部分文字列マッチを個別に持つ既存スタイルを
 * 維持し（§9.2「強化方向のみ」、既存の確立された実装への不要な依存追加を避ける）、再帰化・
 * 禁止語拡張のみを各ファイル内へ直接インライン適用した。本オブジェクトはP7-C7が新設する
 * 5本の新規ガード専用の共通基盤である。
 *
 * **検出方式（コメント除去付き部分文字列マッチ）が既存3ガードの「全文単純一致」と異なる理由
 * （ADR-0060）**: 既存3ガードは対象パッケージ（`planning`/`recovery`/`services.notification`）が
 * 禁止語（`com.actionstarter.ai`等）についてKDocで言及する正当な理由を持たない前提が成立して
 * いた。一方、本オブジェクトが支えるT-AIISO-6／T-AIISO-9は`ai/`パッケージ**自身**の依存境界を
 * 検証するため、`ai/`配下のファイルが自らのアーキテクチャ規律（「litertlmはadapter配下のみ」等）
 * をKDocで説明することは正当かつ有益であり、実際に`AIRecoveryResponse.kt`・
 * `BenchmarkMetricsSource.kt`・`PlanPromptBuilder.kt`・`LocalAiGateway.kt`がそうしている
 * （P7-C1〜C8で作成された既存KDoc）。全文単純一致ではこれらの正当なドキュメントを誤検出し、
 * 「プライバシー構造保証」とは無関係なKDoc文言の書き換えを強制することになる。
 * [stripComments]でコメントを除去してからマッチすることで、**実コード（import文・インライン
 * 完全修飾参照）のみ**を検出対象にし、この種の誤検出を避ける。
 *
 * **既知の簡略化**: 文字列リテラル内に行コメントやブロックコメントの開始記号と同じ文字並びが
 * 現れるケースは考慮しない単純な走査であり、字句解析器ではない（例: `"https://example.com"`
 * という文字列リテラルがあると、その行の残りが誤って行コメント扱いになりうる）。本プロジェクトの
 * 対象ファイル（`domain/`・`ai/`・`services/`・`features/`）にはそのようなリテラルを含む行が
 * 存在しないことを確認済み（`ai/model/ModelDownloader.kt`はURL文字列を持つが、T-AIISO-6の
 * ADR-0044許可リストにより判定対象から除外されるため無関係）。誤検出・見逃しがないことは
 * [IsolationGuardSupportTest]（T-AIISO-8・自己診断テスト）で直接検証する。
 */
internal object IsolationGuardSupport {

    /**
     * `app/src/main/java/com/actionstarter/`からの相対パス[relativePackagePath]配下の
     * ディレクトリを解決する。既存3ガードの`resolveXxxPackageDir`と同じ3段fallback方式
     * （working directoryがGradle実行コンテキストにより`app/`直下・リポジトリルート・その祖先の
     * いずれにもなり得るため）。解決不能なら「参照がない」という誤ったGreen判定
     * （パス解決失敗によるサイレントな偽陽性）を防ぐため、例外で即座に失敗させる。
     */
    fun resolveMainPackageDir(relativePackagePath: String): File {
        val relative = "src/main/java/com/actionstarter/$relativePackagePath"

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
            "パッケージディレクトリが見つかりません。相対パス '$relative' を作業ディレクトリ " +
                "'${System.getProperty("user.dir")}' およびその祖先から探索しましたが解決できませんでした。"
        )
    }

    /** [dir]配下の`.kt`ファイルを**再帰的に**列挙する（`walkTopDown()`使用、穴B対処と同型）。 */
    fun listKotlinFilesRecursively(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * [text]から行コメントとブロックコメント（KDocを含む）を除去し、コード実体のみの
     * テキストを返す。クラスKDoc「既知の簡略化」参照。
     */
    fun stripComments(text: String): String {
        val result = StringBuilder(text.length)
        var index = 0
        var inBlockComment = false
        while (index < text.length) {
            if (inBlockComment) {
                val blockEnd = text.indexOf("*/", index)
                if (blockEnd == -1) break
                index = blockEnd + 2
                inBlockComment = false
                continue
            }
            when {
                text.startsWith("//", index) -> {
                    val lineEnd = text.indexOf('\n', index)
                    index = if (lineEnd == -1) text.length else lineEnd
                }
                text.startsWith("/*", index) -> {
                    inBlockComment = true
                    index += 2
                }
                else -> {
                    result.append(text[index])
                    index += 1
                }
            }
        }
        return result.toString()
    }

    /**
     * [files]のうち、コメント除去後のコード本体が[bannedSubstrings]のいずれかを含むファイルを
     * 返す。[allowedFileNames]に名前が一致するファイルは判定対象から除外する
     * （T-AIISO-6のADR-0044許可リスト、単一ファイル名の完全一致）。
     */
    fun findFilesReferencingForbiddenCode(
        files: List<File>,
        bannedSubstrings: List<String>,
        allowedFileNames: Set<String> = emptySet()
    ): List<File> =
        files
            .filterNot { it.name in allowedFileNames }
            .filter { file ->
                val code = stripComments(file.readText())
                bannedSubstrings.any { code.contains(it) }
            }
}
