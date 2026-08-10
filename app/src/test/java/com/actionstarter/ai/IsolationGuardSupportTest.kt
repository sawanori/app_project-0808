package com.actionstarter.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T-AIISO-8（計画書§12.7・§9.2穴B、`docs/plans/phase7-local-llm-foundation.md`§14 P7-C7）。
 * [IsolationGuardSupport]（P7-C7新設の共有走査ユーティリティ、T-AIISO-4/5/6/7/9が共通で利用
 * する）自身のメタテスト。
 *
 * **本テストの位置づけ**: 計画書のT-AIISO-8は「一時ディレクトリに禁止語を含むダミー.ktを
 * サブディレクトリ付きで作り、検出器が再帰的にそれを検出することを確認（穴Bが本当に塞がった
 * ことの証明）」と定義する。既存3ガード（T-BPE-28／T-BRE-32／T-NOTIF-9）の穴B対処
 * （`listFiles{}`→`walkTopDown()`）は各ファイル内へ直接インライン適用したため、それ自体を
 * 外部からユニットテストする独立した口はない。しかし3ガードが採用した修正は本ファイルが検証
 * する[IsolationGuardSupport.listKotlinFilesRecursively]と**同一のKotlin標準ライブラリ関数
 * （`File.walkTopDown()`）・同一の述語（`isFile && extension == "kt"`）**であるため、本メタ
 * テストが同関数の再帰性を直接証明することは、3ガードの穴B対処が機能することの根拠としても
 * 妥当である。
 *
 * **P7-C7で追加した自己診断（計画書が明示しない拡張）**: T-AIISO-6／T-AIISO-9はコメント除去
 * 付き部分文字列マッチという新しい検出方式を採る（[IsolationGuardSupport]クラスKDoc・
 * ADR-0060）。この方式の正しさ（コメント内言及を誤検出しない・実コードは検出する・許可リストが
 * 機能する）を直接検証しておかないと「なぜT-AIISO-6／T-AIISO-9がai/の既存KDoc言及を誤検出
 * しないのか」を裏付けるものが無くなるため、追加で自己診断テストを設けた。
 */
class IsolationGuardSupportTest {

    private fun tempDir(): File =
        Files.createTempDirectory("isolation-guard-support-test").toFile().apply { deleteOnExit() }

    // T-AIISO-8: エッジ - サブディレクトリ配下のダミー.ktファイルも再帰的に検出できる
    // （穴Bが本当に塞がったことの証明）
    @Test
    fun tAiIso8_listKotlinFilesRecursively_findsFilesInNestedSubdirectories() {
        val root = tempDir()
        val nestedDir = File(root, "outer/inner").apply { mkdirs() }
        val nestedFile = File(nestedDir, "Nested.kt").apply {
            writeText("package com.example\n\nclass Nested\n")
        }
        val topLevelFile = File(root, "TopLevel.kt").apply {
            writeText("package com.example\n\nclass TopLevel\n")
        }

        val found = IsolationGuardSupport.listKotlinFilesRecursively(root)

        assertTrue(
            "walkTopDown()ベースの再帰列挙がサブディレクトリ配下のファイルを検出できていません" +
                "(T-AIISO-8・穴B対処の証明): ${found.map { it.path }}",
            found.any { it.absolutePath == nestedFile.absolutePath }
        )
        assertEquals(
            "直下1件・サブディレクトリ配下1件の計2件を検出するべきです: ${found.map { it.path }}",
            setOf(topLevelFile.absolutePath, nestedFile.absolutePath),
            found.map { it.absolutePath }.toSet()
        )
    }

    // T-AIISO-8関連 - サブディレクトリ配下の禁止語を含むダミーファイルもfindFilesReferencingForbiddenCode
    // が検出する（検出器全体としての再帰性の証明）
    @Test
    fun tAiIso8_findFilesReferencingForbiddenCode_detectsViolationInNestedSubdirectory() {
        val root = tempDir()
        val nestedDir = File(root, "nested/deeper").apply { mkdirs() }
        val violatingFile = File(nestedDir, "Violator.kt").apply {
            writeText(
                "package com.example\n" +
                    "import com.google.ai.edge.litertlm.Engine\n" +
                    "class Violator\n"
            )
        }

        val files = IsolationGuardSupport.listKotlinFilesRecursively(root)
        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = files,
            bannedSubstrings = listOf("com.google.ai.edge.litertlm")
        )

        assertEquals(listOf(violatingFile.absolutePath), offenders.map { it.absolutePath })
    }

    // IsolationGuardSupport自体の精度確認: KDocコメント内でのみの言及は検出しない
    // （T-AIISO-6/9がai/の既存の正当なKDoc言及〔AIRecoveryResponse.kt等〕を誤検出しないことの
    // 設計上の根拠、ADR-0060）
    @Test
    fun stripComments_wordOnlyInsideKDocComment_isNotDetectedAsViolation() {
        val root = tempDir()
        File(root, "DocOnly.kt").writeText(
            "package com.example\n\n" +
                "/**\n" +
                " * このクラスはcom.google.ai.edge.litertlmを一切importしない\n" +
                " * （本文はKDocでの説明のみで実importではない）。\n" +
                " */\n" +
                "class DocOnly\n"
        )

        val files = IsolationGuardSupport.listKotlinFilesRecursively(root)
        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = files,
            bannedSubstrings = listOf("com.google.ai.edge.litertlm")
        )

        assertTrue(
            "KDocコメント内でのみ言及されたクラスが誤検出で違反扱いされています: " +
                offenders.map { it.path },
            offenders.isEmpty()
        )
    }

    // IsolationGuardSupport自体の精度確認: //行コメント内でのみの言及も検出しない
    @Test
    fun stripComments_wordOnlyInsideLineComment_isNotDetectedAsViolation() {
        val root = tempDir()
        File(root, "LineCommentOnly.kt").writeText(
            "package com.example\n\n" +
                "// com.google.ai.edge.litertlmは使わない\n" +
                "class LineCommentOnly\n"
        )

        val files = IsolationGuardSupport.listKotlinFilesRecursively(root)
        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = files,
            bannedSubstrings = listOf("com.google.ai.edge.litertlm")
        )

        assertTrue(
            "//行コメント内でのみ言及されたクラスが誤検出で違反扱いされています: " +
                offenders.map { it.path },
            offenders.isEmpty()
        )
    }

    // IsolationGuardSupport自体の精度確認: 実コード（import文）内の言及は検出する
    @Test
    fun stripComments_wordInActualImportStatement_isDetectedAsViolation() {
        val root = tempDir()
        val file = File(root, "RealImport.kt").apply {
            writeText(
                "package com.example\n" +
                    "import com.google.ai.edge.litertlm.Engine\n" +
                    "class RealImport\n"
            )
        }

        val files = IsolationGuardSupport.listKotlinFilesRecursively(root)
        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = files,
            bannedSubstrings = listOf("com.google.ai.edge.litertlm")
        )

        assertEquals(listOf(file.absolutePath), offenders.map { it.absolutePath })
    }

    // 許可リスト（ADR-0044型）の動作確認: 許可ファイル名は禁止コードを含んでいても除外される
    @Test
    fun findFilesReferencingForbiddenCode_allowedFileName_isExcludedEvenIfItContainsBannedCode() {
        val root = tempDir()
        File(root, "ModelDownloader.kt").writeText(
            "package com.example\n" +
                "import java.net.HttpURLConnection\n" +
                "class ModelDownloader\n"
        )

        val files = IsolationGuardSupport.listKotlinFilesRecursively(root)
        val offenders = IsolationGuardSupport.findFilesReferencingForbiddenCode(
            files = files,
            bannedSubstrings = listOf("java.net.", "HttpURLConnection"),
            allowedFileNames = setOf("ModelDownloader.kt")
        )

        assertTrue(
            "許可リストに含まれるファイル名は除外されるべきです: ${offenders.map { it.path }}",
            offenders.isEmpty()
        )
    }
}
