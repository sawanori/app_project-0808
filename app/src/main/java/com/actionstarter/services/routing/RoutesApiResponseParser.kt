package com.actionstarter.services.routing

import java.time.Duration
import kotlin.math.roundToLong

/**
 * L1純粋関数（F24、計画書§6.1・§7.2 P3-P6）。ComputeRoutesレスポンスJSON（`String`）→
 * `Duration`。`routes`配列が空、または`routes`キー自体が存在しない（P3-C9参照）なら
 * [RoutingException.NoRoute]、不正JSON・フィールド欠損なら[RoutingException.MalformedResponse]
 * を送出する（0秒へ潰さない、エラー＆レスキューマップ#20）。
 *
 * **P3-C9実測確定内容（2026-08-09、Fable 5がcurlで本番同一FieldMask`routes.duration`を
 * 使用し実測）**: 東京タワー(35.6586,139.7454)→明治神宮(35.6595,139.7005)間のTRANSITは
 * HTTP 200・body`{}`（`routes`キー自体が省略される）。Google Routes APIは日本の公共交通
 * データを提供しておらず、有効経路0件時は`"routes":[]`ではなく`routes`キー自体が省略される
 * （proto3 JSON既定のフィールド省略規則）。P3-C8fixまでは「JSONオブジェクトだがroutesキーが
 * 無い」場合を誤って[RoutingException.MalformedResponse]に分類していた（第3の欠陥として
 * P3-C8fixで発見・申し送り）。本サイクルで[RoutingException.NoRoute]へ修正した。rootが
 * オブジェクトでない（配列・文字列等）場合は、レスポンス形状そのものが想定と異なるため
 * 従来どおり[RoutingException.MalformedResponse]のまま維持する。
 *
 * **P3-P6実測確定内容（2026-08-09、出典は
 * […/reference/rest/v2/TopLevel/computeRoutes](
 * https://developers.google.com/maps/documentation/routes/reference/rest/v2/TopLevel/computeRoutes)）**:
 * `routes[].duration`は文字列型で、秒数＋末尾`"s"`のDuration形式（ドキュメント記載例は
 * `"3.5s"`）。計画書§9.2 T-ROUTEPARSE-1が想定する`"1234s"`（整数秒）はこの形式の
 * サブセットとして妥当だが、**ドキュメント上の例は小数秒を含む**ため、パーサ実装
 * （P3-C4）は小数点を含む`"3.5s"`のようなケースも想定した解析にする必要がある
 * （整数秒専用の単純`substring`実装では丸め・切り捨て方針を別途決める必要がある。
 * P3-C2でtest-writerへ申し送り）。
 *
 * P3-P4（Robolectric上でのorg.json動作）の実測結果は本サイクルのprobe実測ログ参照。
 *
 * **P3-C4実装メモ**: `org.json`は使わない。本パーサはプレーンJUnit（`src/test`、
 * `@RunWith`指定なし）から直接呼ばれるが、P3-P4（Robolectric上でのorg.json動作可否）は
 * 計画書§16で「未検証」のまま残っており、かつこのテストクラスはRobolectricすら使わない
 * ためAndroidスタブ実装（`Stub!`例外）を踏む。したがって依存追加ゼロ（§88）の方針に従い、
 * このファイル内に最小限のJSON構文解析（[MinimalJson]、object/array/string/number/
 * boolean/nullのみ対応）を自前実装し、`routes[0].duration`のみを取り出す。
 */
object RoutesApiResponseParser {
    fun parse(responseBody: String): Duration {
        val root = try {
            MinimalJson.parse(responseBody)
        } catch (e: MinimalJson.JsonSyntaxException) {
            throw RoutingException.MalformedResponse(e)
        }

        // P3-C9実測確定（Fable 5、curl実測2026-08-09）: Google Routes APIは有効経路0件の場合、
        // `"routes":[]`ではなくトップレベルの`routes`キー自体を省略する（proto3 JSON既定の
        // フィールド省略規則。実例: 日本国内のTRANSITはAPIが公共交通データを提供しないため
        // 常にこの形状になる）。したがって「JSONオブジェクトだがroutesキーが無い」は
        // NoRouteへマップする（P3-C8fixまではMalformedResponseに誤分類していた欠陥の修正）。
        // root自体がオブジェクトでない（配列・文字列等）場合は、レスポンス形状そのものが
        // 想定と異なるためMalformedResponseのまま維持する。
        val rootObj = root as? MinimalJson.Value.Obj
            ?: throw RoutingException.MalformedResponse(
                IllegalStateException("response JSON root is not an object")
            )

        val routesField = rootObj.members["routes"]
            ?: throw RoutingException.NoRoute()

        val routes = routesField as? MinimalJson.Value.Arr
            ?: throw RoutingException.MalformedResponse(
                IllegalStateException("response JSON \"routes\" field is not an array")
            )

        if (routes.items.isEmpty()) {
            throw RoutingException.NoRoute()
        }

        val firstRoute = routes.items.first() as? MinimalJson.Value.Obj
            ?: throw RoutingException.MalformedResponse(
                IllegalStateException("routes[0] is not a JSON object")
            )

        val durationField = firstRoute.members["duration"] as? MinimalJson.Value.Str
            ?: throw RoutingException.MalformedResponse(
                IllegalStateException("routes[0].duration is missing or not a string")
            )

        return parseDurationSeconds(durationField.value)
    }

    /**
     * P3-P6実測確定（本ファイルKDoc）: `"1234s"`（整数秒）・`"3.5s"`（小数秒、ドキュメント
     * 記載例）の両形式に対応する。ミリ秒精度で四捨五入して[Duration]へ変換する
     * （T-ROUTEPARSE-1、`Duration.ofMillis`はナノ秒切り捨てではなく厳密なミリ秒表現）。
     */
    private fun parseDurationSeconds(raw: String): Duration {
        if (!raw.endsWith("s")) {
            throw RoutingException.MalformedResponse(
                IllegalStateException("duration value does not end with \"s\"")
            )
        }
        val secondsText = raw.substring(0, raw.length - 1)
        val seconds = secondsText.toDoubleOrNull()
            ?: throw RoutingException.MalformedResponse(
                IllegalStateException("duration value is not numeric")
            )
        val millis = (seconds * 1000.0).roundToLong()
        return Duration.ofMillis(millis)
    }
}

/**
 * 依存ゼロの最小JSONパーサ（本ファイル専用、private）。RFC 8259のサブセット
 * （object/array/string/number/true/false/null）を再帰下降で解析する。エスケープは
 * 標準的な`\" \\ \/ \b \f \n \r \t \uXXXX`のみ対応。構文エラーは
 * [MinimalJson.JsonSyntaxException]として送出し、呼び出し側（[RoutesApiResponseParser]）が
 * [RoutingException.MalformedResponse]へ変換する（不正JSONを握り潰さない、T-ROUTEPARSE-3）。
 */
private object MinimalJson {
    sealed class Value {
        data class Obj(val members: Map<String, Value>) : Value()
        data class Arr(val items: List<Value>) : Value()
        data class Str(val value: String) : Value()
        data class Num(val value: Double) : Value()
        data class Bool(val value: Boolean) : Value()
        object Null : Value()
    }

    class JsonSyntaxException(message: String) : Exception(message)

    fun parse(input: String): Value {
        val parser = Parser(input)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw JsonSyntaxException("Unexpected trailing content at position ${parser.position}")
        }
        return value
    }

    private class Parser(private val input: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= input.length

        fun skipWhitespace() {
            while (position < input.length && input[position].isWhitespace()) position++
        }

        fun parseValue(): Value {
            skipWhitespace()
            if (atEnd()) throw JsonSyntaxException("Unexpected end of input")
            return when (input[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Value.Str(parseStringLiteral())
                't' -> { expectLiteral("true"); Value.Bool(true) }
                'f' -> { expectLiteral("false"); Value.Bool(false) }
                'n' -> { expectLiteral("null"); Value.Null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Value.Obj {
            expect('{')
            val members = LinkedHashMap<String, Value>()
            skipWhitespace()
            if (!atEnd() && input[position] == '}') {
                position++
                return Value.Obj(members)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || input[position] != '"') {
                    throw JsonSyntaxException("Expected string key at position $position")
                }
                val key = parseStringLiteral()
                skipWhitespace()
                expect(':')
                members[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw JsonSyntaxException("Unterminated object at position $position")
                when (input[position]) {
                    ',' -> { position++ }
                    '}' -> { position++; return Value.Obj(members) }
                    else -> throw JsonSyntaxException("Expected ',' or '}' at position $position")
                }
            }
        }

        private fun parseArray(): Value.Arr {
            expect('[')
            val items = mutableListOf<Value>()
            skipWhitespace()
            if (!atEnd() && input[position] == ']') {
                position++
                return Value.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw JsonSyntaxException("Unterminated array at position $position")
                when (input[position]) {
                    ',' -> { position++ }
                    ']' -> { position++; return Value.Arr(items) }
                    else -> throw JsonSyntaxException("Expected ',' or ']' at position $position")
                }
            }
        }

        private fun parseStringLiteral(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonSyntaxException("Unterminated string literal")
                val c = input[position]
                position++
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> sb.append(parseEscapeSequence())
                    else -> sb.append(c)
                }
            }
        }

        private fun parseEscapeSequence(): Char {
            if (atEnd()) throw JsonSyntaxException("Unterminated escape sequence")
            val esc = input[position]
            position++
            return when (esc) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (position + 4 > input.length) throw JsonSyntaxException("Invalid unicode escape")
                    val hex = input.substring(position, position + 4)
                    val code = hex.toIntOrNull(16) ?: throw JsonSyntaxException("Invalid unicode escape: $hex")
                    position += 4
                    code.toChar()
                }
                else -> throw JsonSyntaxException("Invalid escape character: \\$esc")
            }
        }

        private fun parseNumber(): Value.Num {
            val start = position
            if (!atEnd() && input[position] == '-') position++
            if (atEnd() || !input[position].isDigit()) {
                throw JsonSyntaxException("Invalid number at position $position")
            }
            while (!atEnd() && input[position].isDigit()) position++
            if (!atEnd() && input[position] == '.') {
                position++
                if (atEnd() || !input[position].isDigit()) {
                    throw JsonSyntaxException("Invalid number fraction at position $position")
                }
                while (!atEnd() && input[position].isDigit()) position++
            }
            if (!atEnd() && (input[position] == 'e' || input[position] == 'E')) {
                position++
                if (!atEnd() && (input[position] == '+' || input[position] == '-')) position++
                if (atEnd() || !input[position].isDigit()) {
                    throw JsonSyntaxException("Invalid number exponent at position $position")
                }
                while (!atEnd() && input[position].isDigit()) position++
            }
            val text = input.substring(start, position)
            val value = text.toDoubleOrNull() ?: throw JsonSyntaxException("Invalid number literal: $text")
            return Value.Num(value)
        }

        private fun expectLiteral(literal: String) {
            if (position + literal.length > input.length || input.substring(position, position + literal.length) != literal) {
                throw JsonSyntaxException("Expected literal '$literal' at position $position")
            }
            position += literal.length
        }

        private fun expect(c: Char) {
            if (atEnd() || input[position] != c) {
                throw JsonSyntaxException("Expected '$c' at position $position")
            }
            position++
        }
    }
}
