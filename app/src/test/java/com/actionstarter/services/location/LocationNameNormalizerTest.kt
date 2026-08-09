package com.actionstarter.services.location

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-GEONORM-1〜4（計画書§9.2）。対象は[LocationNameNormalizer]（L1純粋関数）。
 * 現状（P3-C1契約scaffold）は[LocationNameNormalizer.normalize]の本文が
 * `TODO("P3-C3で実装")`のため、以下は全件`NotImplementedError`によりRedになる
 * （意図した失敗）。
 *
 * [LocationNameNormalizer]自体は[GeocoderSource]への参照を一切持たない（L1純粋関数）ため、
 * T-GEONORM-2/3の説明文にある「Geocoderを呼ばない」という性質は、本ファイルでは型構造に
 * よって自明に保証されており実行時アサーションを要しない（呼び出し側の
 * [AndroidGeocodingService]が[NormalizedLocationName.Invalid]／
 * [NormalizedLocationName.UriScheme]を見て早期returnする責務を持つ）。
 */
class LocationNameNormalizerTest {

    // T-GEONORM-1: 正常 - 前後空白trim・連続空白の畳み込み
    @Test
    fun normalize_withSurroundingAndRepeatedWhitespace_trimsAndCollapsesToSingleSpaces() {
        val result = LocationNameNormalizer.normalize("  Shibuya   Crossing  \n")

        assertEquals(NormalizedLocationName.Normalized("Shibuya Crossing"), result)
    }

    // T-GEONORM-2: 異常 - 空文字／空白のみ → INVALID_INPUT（Geocoderを呼ばない）
    @Test
    fun normalize_withEmptyOrWhitespaceOnlyInput_returnsInvalid() {
        assertEquals(NormalizedLocationName.Invalid, LocationNameNormalizer.normalize(""))
        assertEquals(NormalizedLocationName.Invalid, LocationNameNormalizer.normalize("   "))
        assertEquals(NormalizedLocationName.Invalid, LocationNameNormalizer.normalize("\t\n"))
    }

    // T-GEONORM-3: エッジ - https://... 等のURIスキーム付き文字列 → Geocoderを呼ばず
    // NoMatch相当で早期返却（§4.5境界）
    @Test
    fun normalize_withUriSchemeString_returnsUriScheme() {
        assertEquals(
            NormalizedLocationName.UriScheme,
            LocationNameNormalizer.normalize("https://zoom.us/j/1234567890")
        )
        assertEquals(
            NormalizedLocationName.UriScheme,
            LocationNameNormalizer.normalize("http://meet.google.com/abc-defg-hij")
        )
    }

    // T-GEONORM-4: エッジ - 非ラテン文字（日本語住所）が破壊・正規化除去されない（§6/§7）
    @Test
    fun normalize_withNonLatinJapaneseAddress_preservesCharactersUnchanged() {
        val input = "東京都渋谷区渋谷2-24-1"

        val result = LocationNameNormalizer.normalize(input)

        assertEquals(NormalizedLocationName.Normalized(input), result)
    }
}
