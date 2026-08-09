package com.actionstarter.services.location

/**
 * L1純粋関数（F23、計画書§6.1・§7.1・§9.2 T-GEONORM-1〜4）。geocode前の目的地文字列を
 * 正規化する。ロケール固有語のハードコード禁止（仕様§6）。
 *
 * **計画書未指定のシグネチャ補完**: 計画書§5には本ファイルの型シグネチャが与えられて
 * いない（`services/routing/`の`RoutesApiRequestBuilder`等と同様、ファイル存在と役割
 * （§6.1表）・期待挙動（§9.2テストケース）のみが定義済み）。T-GEONORM-2/3が
 * 「空文字列／空白のみ→Geocoderを呼ばない」「URIスキーム付き文字列→Geocoderを呼ばず
 * 早期返却」という2種の早期リターン分岐を要求するため、戻り値を[NormalizedLocationName]
 * （sealed interface）としこれらを型で区別できるようにした。
 *
 * P3-C3で実装済み（T-GEONORM-1〜4）: ①`trim()`後に空文字列なら[NormalizedLocationName.Invalid]、
 * ②`scheme://`形式（RFC 3986のURIスキーム、`ALPHA *(ALPHA / DIGIT / "+" / "-" / ".")`）で
 * 始まるなら[NormalizedLocationName.UriScheme]、③それ以外は連続空白を単一半角スペースへ
 * 畳み込み[NormalizedLocationName.Normalized]で返す。非ラテン文字はいずれの正規表現にも
 * マッチしないため無変更で通過する（T-GEONORM-4）。ロケール固有語の判定は行わない（§6）。
 */
object LocationNameNormalizer {
    /** RFC 3986 `scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` + `"://"`。 */
    private val URI_SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

    /** 連続する空白文字（半角空白・タブ・改行等、[Char.isWhitespace]相当）の畳み込み用。 */
    private val WHITESPACE_RUN = Regex("\\s+")

    fun normalize(input: String): NormalizedLocationName {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return NormalizedLocationName.Invalid
        }
        if (URI_SCHEME_PREFIX.containsMatchIn(trimmed)) {
            return NormalizedLocationName.UriScheme
        }
        return NormalizedLocationName.Normalized(trimmed.replace(WHITESPACE_RUN, " "))
    }
}

/** [LocationNameNormalizer.normalize]の結果（T-GEONORM-2/3の早期リターン分岐を型で表現）。 */
sealed interface NormalizedLocationName {
    /** trim・連続空白畳み込み後の非空文字列。 */
    data class Normalized(val value: String) : NormalizedLocationName

    /** 空文字列／空白のみ（T-GEONORM-2）。[GeocodeFailureReason.INVALID_INPUT]へ写像する。 */
    data object Invalid : NormalizedLocationName

    /** `https://...`等のURIスキーム付き文字列（T-GEONORM-3）。[GeocodeResult.NoMatch]相当として早期返却する。 */
    data object UriScheme : NormalizedLocationName
}
