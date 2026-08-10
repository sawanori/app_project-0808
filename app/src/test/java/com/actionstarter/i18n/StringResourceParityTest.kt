package com.actionstarter.i18n

import org.junit.Assert.assertEquals
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
 * **P11-C2でKDoc是正（T-P11P-4）**: 本ファイルのKDoc冒頭は元々、Phase 0時点のキー数の少なさに
 * 言及する記述のまま陳腐化していた。実際には画面実装の進行に伴いキー数は増え続けており、
 * P11-C1時点（`execution_placeholder_step_title`／`travel_time_manual_apply_button`の削除、
 * `notification_open_settings_button`／`accessibility_warning_announcement`／
 * `recovery_option_selected_state_description`の追加後）でen/jaとも105キーだった
 * （`grep -c '<string name=' app/src/main/res/values{,-ja}/strings.xml`で実測確認可能）。
 * **P7-C6（F97 Settings画面）でさらに21キー追加し、en/jaとも126キーとなった**（T-SET-7、
 * [tSet7_settingsNewKeys_existInBothLocales]参照）。
 * T-I18N-1〜3は元々キー数に依存しない汎用アサーション（差分/フォーマット引数/空文字の
 * いずれもキー集合全体を走査する設計）であり、キー数の増減そのものによってはRedにならない。
 *
 * **§12 S-4裁定（P11-C2/C3、T-P11P-3）**: en/ja値が完全一致するキー（未翻訳の疑い）を
 * 検出する`nonAllowlistedKeys_haveDistinctValuesBetweenEnAndJa`を追加した。意図的な一致は
 * [INTENTIONAL_SAME_VALUE_KEYS]（テストコード内の明示定数リスト、各エントリに理由コメント
 * 必須）で許容する。新規に意図的一致キーを追加する場合は、本リストへの追加理由を
 * `docs/plans/phase11-i18n-a11y.md`§10の完了記録へ1行（キー名・理由・追加日）残す運用とする
 * （§7.6）。
 *
 * **`<string-array>`／`<plurals>`スコープ外の申し送り（T-P11P-5）**: [parseStrings]は
 * `getElementsByTagName("string")`固定であり、`<string-array>`／`<plurals>`要素は一切
 * 走査しない。現時点（P11-C2実測）で両ロケールとも0件のため実害はないが、将来これらの
 * 要素が追加された場合は本パーサの拡張が必要になる（`tP11p5_...`が0件の前提を機械的に
 * 固定し、要素が追加された瞬間にRed化することでこの申し送りの失念を防ぐ）。
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

    /**
     * §12 S-4裁定・§7.6準拠。en/ja値の完全一致検出（[tP11p3_nonAllowlistedKeys_haveDistinctValuesBetweenEnAndJa]）
     * から除外する意図的一致キー。各エントリに理由コメントを必須とする。
     */
    private val INTENTIONAL_SAME_VALUE_KEYS = setOf(
        "app_name", // 固有名詞のため翻訳しない
        "manual_event_picker_confirm_button" // "OK"は国際的に共通の慣用表現
    )

    /**
     * [resolveStringsXml]と同じ「working directoryが`app/`だったりリポジトリルートだったりし
     * 得る」制約に対応する汎用パス解決（T-P11S-4／T-P11S-7／T-P11P-4の各grep構造ガードが使う）。
     * [relativeFromApp]は`app/`ディレクトリからの相対パス。
     */
    private fun resolveFromRepo(relativeFromApp: String): File {
        val direct = File(relativeFromApp)
        if (direct.exists()) return direct

        val fromRepoRoot = File("app", relativeFromApp)
        if (fromRepoRoot.exists()) return fromRepoRoot

        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/$relativeFromApp")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }

        error(
            "'$relativeFromApp' not found from working directory " +
                "'${System.getProperty("user.dir")}' and its ancestors."
        )
    }

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

    // ==================================================================================
    // P11-C2: T-P11S-1/2/4/7・T-P11P-1〜5（計画書§8.5・§8.6、F83/F84）
    // ==================================================================================
    //
    // T-P11S-1/2/4/7はP11-C1（scaffold）で既にstrings.xmlの削除・ExecutionViewModel.ktの
    // KDoc是正を完了しているため、born-green（既存の正しい状態を固定する回帰ガードとして
    // 記録する。§8.5には「grep構造ガード」と明記されており新規実装ロジックを要さない）。
    //
    // 計画書§6.2の footprint 表は本ファイルへ「T-P11S-1/2/4」の3件のみを明記しており、
    // T-P11S-7（ExecutionViewModel.ktのKDocダングリング参照グレップ）の割当先ファイルは
    // 明記されていない。T-P11S-4と同じ「grep構造ガード」区分・同じ実装手法（app/src/main配下の
    // テキスト走査）であるため、本ファイル（i18n配下のgrep系構造ガード集約先）へ追加する
    // ことが最も一貫すると判断した（実装時の判断、完了報告で開示）。

    // T-P11S-1: 正常系 - execution_placeholder_step_titleがstrings.xml（en/ja）に存在しない。
    // resolveStepTitle（features/common/StepTitle.kt）のelse分岐（step_title_fallback）へ
    // P4-C6で構造的に置換済みの死蔵リソースだった（docs/plans/phase4-basic-engine.md参照）。
    @Test
    fun tP11s1_executionPlaceholderStepTitleKey_absentFromBothLocales() {
        assertTrue(
            "execution_placeholder_step_title must not exist in values/strings.xml (en)",
            "execution_placeholder_step_title" !in enStrings
        )
        assertTrue(
            "execution_placeholder_step_title must not exist in values-ja/strings.xml (ja)",
            "execution_placeholder_step_title" !in jaStrings
        )
    }

    // T-P11S-2: 正常系 - travel_time_manual_apply_buttonがstrings.xml（en/ja）に存在しない。
    // P3-C5で確定した即時反映設計（TravelTimeInputにApplyボタン自体が存在しない）と両立
    // しない死蔵リソースだった。
    @Test
    fun tP11s2_travelTimeManualApplyButtonKey_absentFromBothLocales() {
        assertTrue(
            "travel_time_manual_apply_button must not exist in values/strings.xml (en)",
            "travel_time_manual_apply_button" !in enStrings
        )
        assertTrue(
            "travel_time_manual_apply_button must not exist in values-ja/strings.xml (ja)",
            "travel_time_manual_apply_button" !in jaStrings
        )
    }

    // T-P11S-4: エッジケース - 削除した2キー（execution_placeholder_step_title／
    // travel_time_manual_apply_button）へのソースコード参照が app/src/main 全体で0件である
    // （コメント/KDoc含むテキスト走査）。
    @Test
    fun tP11s4_deletedKeys_haveZeroReferencesAnywhereInMainSource() {
        val mainSrcRoot = resolveFromRepo("src/main")
        val deletedKeys = listOf("execution_placeholder_step_title", "travel_time_manual_apply_button")

        val offendingFiles = mutableMapOf<String, MutableList<String>>()
        mainSrcRoot.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val text = file.readText()
                deletedKeys.forEach { key ->
                    if (text.contains(key)) {
                        offendingFiles.getOrPut(key) { mutableListOf() }.add(file.path)
                    }
                }
            }

        assertTrue(
            "Deleted string keys still referenced in app/src/main: $offendingFiles",
            offendingFiles.isEmpty()
        )
    }

    // T-P11S-7: エッジケース - ExecutionViewModel.ktのKDocが削除済みリソース
    // （execution_placeholder_step_title）へのダングリング参照を含まない。T-P11S-4の
    // 全体走査に加え、本ファイル単体を明示的にピン留めする独立の回帰ガード。
    @Test
    fun tP11s7_executionViewModelKDoc_hasNoDanglingReferenceToDeletedPlaceholderKey() {
        val executionViewModelFile = resolveFromRepo(
            "src/main/java/com/actionstarter/features/execution/ExecutionViewModel.kt"
        )
        val text = executionViewModelFile.readText()

        assertTrue(
            "ExecutionViewModel.kt must not reference the deleted execution_placeholder_step_title " +
                "resource in its KDoc (dangling reference)",
            !text.contains("execution_placeholder_step_title")
        )
    }

    // T-P11P-1: 正常系 - 既存T-I18N-1〜3が104キー(Phase 0基準)から105キー（Phase 11純増1件 =
    // 104 - 2削除 + 3新規）へ変化した時点でもGreenのまま。stringKeys_matchExactlyBetweenEnAndJa
    // (T-I18N-1)自体はセット照合のみで絶対数を固定しないため、本テストはキー総数を明示的に
    // ピン留めする独立の回帰ガードとして追加する（片方のロケールだけキー追加を忘れる、
    // 削除を二重に行う等のミスを検出する）。
    //
    // **P7-C6追補（T-SET-7、F97 Settings画面）**: 105 + 21（Settings画面新規21キー、
    // [tSet7_settingsNewKeys_existInBothLocales]の一覧と対応）= 126へ更新した。
    @Test
    fun tP11p1_keyCount_reflectsPhase11NetChange_andBothLocalesStayInSync() {
        assertEquals("en key count after P7-C6 (126 = 105 + 21 Settings keys)", 126, enStrings.size)
        assertEquals("ja key count after P7-C6 (126 = 105 + 21 Settings keys)", 126, jaStrings.size)
    }

    // T-SET-7（計画書§12.6、P7-C6・F97）: 正常系 - Settings画面の新規21キーがen/ja両方に存在する
    // （既存tP11p2と同型のパリティ回帰ガード）。
    @Test
    fun tSet7_settingsNewKeys_existInBothLocales() {
        val settingsNewKeys = listOf(
            "settings_title",
            "settings_back_button_label",
            "settings_ai_toggle_label",
            "settings_ai_toggle_description",
            "settings_ai_unsupported_ram_reason",
            "settings_ai_unsupported_abi_reason",
            "settings_model_section_title",
            "settings_model_name_label",
            "settings_model_status_not_installed",
            "settings_model_status_downloading_format",
            "settings_model_status_installed",
            "settings_model_status_failed_insufficient_storage",
            "settings_model_status_failed_verification",
            "settings_model_status_failed_generic",
            "settings_download_button",
            "settings_retry_button",
            "settings_delete_button",
            "settings_capacity_required_format",
            "settings_capacity_available_format",
            "settings_capacity_insufficient_warning",
            "settings_open_button_label"
        )

        assertEquals("P7-C6 Settings screen must add exactly 21 new keys", 21, settingsNewKeys.size)
        for (key in settingsNewKeys) {
            assertTrue("P7-C6 new key '$key' missing from en strings.xml", key in enStrings)
            assertTrue("P7-C6 new key '$key' missing from ja strings.xml", key in jaStrings)
        }
    }

    // T-P11P-2: 正常系 - 本Phaseで新規追加した全キー（設定ボタン・contentDescription用文言）が
    // en/ja両方に存在しパリティが崩れない。
    @Test
    fun tP11p2_phase11NewKeys_existInBothLocales() {
        val phase11NewKeys = listOf(
            "notification_open_settings_button",
            "accessibility_warning_announcement",
            "recovery_option_selected_state_description"
        )

        for (key in phase11NewKeys) {
            assertTrue("Phase 11 new key '$key' missing from en strings.xml", key in enStrings)
            assertTrue("Phase 11 new key '$key' missing from ja strings.xml", key in jaStrings)
        }
    }

    // T-P11P-3: エッジケース - allowlist（INTENTIONAL_SAME_VALUE_KEYS、各エントリに意図的一致の
    // 理由コメント必須）を除き、en/ja値が完全一致するキーが存在しないことを検証する
    // （§12 S-4裁定・§7.6）。
    @Test
    fun tP11p3_nonAllowlistedKeys_haveDistinctValuesBetweenEnAndJa() {
        val commonKeys = enStrings.keys intersect jaStrings.keys
        val unexpectedMatches = commonKeys.filter { key ->
            key !in INTENTIONAL_SAME_VALUE_KEYS && enStrings.getValue(key).value == jaStrings.getValue(key).value
        }

        assertTrue(
            "en/ja values unexpectedly identical (add to INTENTIONAL_SAME_VALUE_KEYS with a reason " +
                "comment if intentional, otherwise translate): $unexpectedMatches",
            unexpectedMatches.isEmpty()
        )
    }

    // T-P11P-4: 正常系 - 本ファイルのクラスKDoc冒頭記述が、Phase 0時点のキー数の少なさに触れる
    // 陳腐化した文言から更新されている（自己参照的な構造ガード。T-P11S-4/7と同じテキスト走査
    // 手法を本ファイル自身へ適用する。判定文字列自体は[tP11p4_classKDoc_noLongerClaimsOnlyTwoKeysExist]
    // 内に閉じ、このコメントを含む本メソッド周辺の説明文では判定対象の文字列を再掲しない）。
    @Test
    fun tP11p4_classKDoc_noLongerClaimsOnlyTwoKeysExist() {
        val selfFile = resolveFromRepo("src/test/java/com/actionstarter/i18n/StringResourceParityTest.kt")
        val text = selfFile.readText()
        // 検索語をリテラル結合で分割する（"2" + "キーのみ"）: この判定コード自身が
        // app/src/mainならぬsrc/test配下の本ファイルを読むため、判定文字列をそのまま1つの
        // リテラルとして書くと、この行自身がヒットしてしまう自己言及（quine）問題を生む。
        val staleClaimFragment = "2" + "キーのみ"

        assertTrue(
            "StringResourceParityTest.kt's class KDoc must not still claim strings.xml has only " +
                "app_name/hello_smoke (stale since Phase 0; the suite now covers ${enStrings.size} keys)",
            !text.contains(staleClaimFragment)
        )
    }

    // T-P11P-5: エッジケース - <string-array>／<plurals>が両ロケールとも0件であることの前提を
    // 機械検証する（parseStringsはgetElementsByTagName("string")固定であり、これらの要素が
    // 追加されても静かに無視する。将来追加時にパーサ拡張が必要になる旨の申し送りをクラス
    // KDocに明記した上で、0件の前提自体をここで固定する）。
    @Test
    fun tP11p5_stringArrayAndPluralsElements_areAbsentFromBothLocales() {
        val factory = DocumentBuilderFactory.newInstance()
        val enDocument = factory.newDocumentBuilder().parse(resolveStringsXml(""))
        val jaDocument = factory.newDocumentBuilder().parse(resolveStringsXml("-ja"))

        assertEquals(
            "en values/strings.xml must have zero <string-array> elements (see class KDoc)",
            0,
            enDocument.getElementsByTagName("string-array").length
        )
        assertEquals(
            "en values/strings.xml must have zero <plurals> elements (see class KDoc)",
            0,
            enDocument.getElementsByTagName("plurals").length
        )
        assertEquals(
            "ja values-ja/strings.xml must have zero <string-array> elements (see class KDoc)",
            0,
            jaDocument.getElementsByTagName("string-array").length
        )
        assertEquals(
            "ja values-ja/strings.xml must have zero <plurals> elements (see class KDoc)",
            0,
            jaDocument.getElementsByTagName("plurals").length
        )
    }
}
