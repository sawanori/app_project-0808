package com.actionstarter.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T-I18N-1／2／3（計画書§11.2 F10）。`res/values/strings.xml`（en）と
 * `res/values-ja/strings.xml`（ja）のキー集合・フォーマット引数個数・非空文字列の
 * パリティを純JVMでXMLパースして検証する（Robolectric不要。i18n基盤、仕様§7/§8、ADR-0009）。
 *
 * ファイルパスはテスト実行時のworking directoryからの相対パスで解決を試みるが、
 * 実行コンテキストによってworking directoryが`app/`（Gradleの`:app:testDebugUnitTest`
 * 既定）だったりリポジトリルートだったりし得るため、[resolveStringsXml]で複数候補を
 * 順に試し、最後に親ディレクトリを遡る fallback を行う。
 *
 * 現状のstrings.xmlは`app_name`／`hello_smoke`の2キーのみで、en/ja間で完全にパリティが
 * 取れている（キー一致・フォーマット引数0個で一致・両方非空）。そのため本ファイルの
 * 3テストは、現時点で実行すると検証すべき既存の不整合が実在しないためGreen（成功）に
 * なる見込みが高い。パリティ違反を人為的に作るために`app/src/main`のリソースを書き換える
 * ことは本タスクの制約（本番コード変更禁止）に反するため行っていない。C4以降で画面実装
 * のためにstrings.xmlへキーが追加された際、en/ja間のパリティが崩れれば本テストが検出し
 * Redになる回帰防止テストとして機能する。
 */
class StringResourceParityTest {

    private data class StringResource(val key: String, val value: String)

    private fun resolveStringsXml(qualifierSuffix: String): File {
        val relative = "src/main/res/values$qualifierSuffix/strings.xml"

        val direct = File(relative)
        if (direct.isFile) return direct

        val fromRepoRoot = File("app", relative)
        if (fromRepoRoot.isFile) return fromRepoRoot

        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/$relative")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }

        error(
            "strings.xml not found for qualifier '$qualifierSuffix'. " +
                "Tried relative path '$relative' from working directory " +
                "'${System.getProperty("user.dir")}' and its ancestors."
        )
    }

    private fun parseStrings(file: File): List<StringResource> {
        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")

        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            val key = element.getAttribute("name")
            val value = element.textContent ?: ""
            StringResource(key = key, value = value)
        }
    }

    // %1$s／%2$d のような位置指定フォーマット引数の最大インデックスを数える。
    // %% はリテラルの%（エスケープ）なので引数としては数えない。
    private fun formatArgCount(value: String): Int {
        val positionalArgRegex = Regex("""(?<!%)%(\d+)\$""")
        return positionalArgRegex.findAll(value)
            .map { it.groupValues[1].toInt() }
            .maxOrNull()
            ?: 0
    }

    private val enStrings by lazy { parseStrings(resolveStringsXml("")).associateBy { it.key } }
    private val jaStrings by lazy { parseStrings(resolveStringsXml("-ja")).associateBy { it.key } }

    // T-I18N-1: en/jaのstring resourceキー集合が完全一致しない場合、差分キー名を出力してテストが失敗する
    @Test
    fun stringKeys_matchExactlyBetweenEnAndJa() {
        val enOnly = enStrings.keys - jaStrings.keys
        val jaOnly = jaStrings.keys - enStrings.keys

        assertTrue(
            "en/jaでキー集合が一致しません。enのみに存在=$enOnly, jaのみに存在=$jaOnly",
            enOnly.isEmpty() && jaOnly.isEmpty()
        )
    }

    // T-I18N-2: フォーマット引数の個数が一致しない場合テストが失敗する
    @Test
    fun formatArgCounts_matchForEveryCommonKey() {
        val commonKeys = enStrings.keys intersect jaStrings.keys
        val mismatches = commonKeys.filter { key ->
            formatArgCount(enStrings.getValue(key).value) != formatArgCount(jaStrings.getValue(key).value)
        }

        assertTrue(
            "フォーマット引数の個数がen/jaで一致しないキー: $mismatches",
            mismatches.isEmpty()
        )
    }

    // T-I18N-3: 空文字列のリソースがあるとテストが失敗する
    @Test
    fun noStringResource_isEmpty_inEitherLocale() {
        val emptyInEn = enStrings.values.filter { it.value.isEmpty() }.map { it.key }
        val emptyInJa = jaStrings.values.filter { it.value.isEmpty() }.map { it.key }

        assertTrue(
            "空文字列のリソースがあります。en=$emptyInEn, ja=$emptyInJa",
            emptyInEn.isEmpty() && emptyInJa.isEmpty()
        )
    }
}
