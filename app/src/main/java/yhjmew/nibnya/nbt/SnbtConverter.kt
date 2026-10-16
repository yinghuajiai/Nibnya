package yhjmew.nibnya.nbt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * SNBT（Stringified NBT）与内部 JSON 表示之间的转换。
 *
 * 内部表示：每个标签是 `{t:类型ID, v:值}`，List 额外带 `itemType`。
 * SNBT 表示（基岩版 / Java 版通用文本形式）：
 * ```
 * byte 5b    short 5s    int 5    long 5L    float 5.0f    double 5.0d
 * string "text"
 * byteArray [B;1b,2b]   intArray [I;1,2]   longArray [L;1L,2L]
 * list [1,2,3] / [{"k":v}]      compound {k:v,k2:v2}
 * ```
 */
object SnbtConverter {

    // 类型 ID（与 BedrockParser 一致）
    private const val T_BYTE = 1
    private const val T_SHORT = 2
    private const val T_INT = 3
    private const val T_LONG = 4
    private const val T_FLOAT = 5
    private const val T_DOUBLE = 6
    private const val T_BYTE_ARRAY = 7
    private const val T_STRING = 8
    private const val T_LIST = 9
    private const val T_COMPOUND = 10
    private const val T_INT_ARRAY = 11
    private const val T_LONG_ARRAY = 12

    // ============================================
    // 输出：内部 JSON -> SNBT
    // ============================================

    /**
     * 入口：把任意内部元素转成 SNBT。
     * - 若带 `t` 字段，视为标签包装
     * - 否则视为裸 compound（例如根数据）
     */
    fun toSnbt(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return "null"
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            return if (obj.has("t")) wrapperToSnbt(obj) else compoundToSnbt(obj)
        }
        // 裸数组按 list 处理
        if (element.isJsonArray) return listToSnbt(element.asJsonArray, detectItemType(element.asJsonArray))
        return element.toString()
    }

    private fun wrapperToSnbt(w: JsonObject): String {
        val t = w.get("t")?.asInt ?: T_COMPOUND
        val v = w.get("v") ?: return "null"
        return when (t) {
            T_BYTE -> "${v.asInt}b"
            T_SHORT -> "${v.asInt}s"
            T_INT -> v.asInt.toString()
            T_LONG -> "${v.asLong}L"
            T_FLOAT -> "${v.asFloat}f"
            T_DOUBLE -> "${v.asDouble}d"
            T_STRING -> quote(v.asString)
            T_BYTE_ARRAY -> typedArrayToSnbt("B", v, "b")
            T_INT_ARRAY -> typedArrayToSnbt("I", v, "")
            T_LONG_ARRAY -> typedArrayToSnbt("L", v, "L")
            T_LIST -> listToSnbt(
                if (v.isJsonArray) v.asJsonArray else JsonArray(),
                w.get("itemType")?.asInt ?: T_COMPOUND
            )
            T_COMPOUND -> compoundToSnbt(if (v.isJsonObject) v.asJsonObject else JsonObject())
            else -> v.toString()
        }
    }

    private fun typedArrayToSnbt(prefix: String, v: JsonElement, suffix: String): String {
        val arr = if (v.isJsonArray) v.asJsonArray else JsonArray()
        val sb = StringBuilder("[$prefix;")
        for (i in 0 until arr.size()) {
            if (i > 0) sb.append(',')
            sb.append(arr.get(i).asInt).append(suffix)
        }
        sb.append(']')
        return sb.toString()
    }

    private fun compoundToSnbt(obj: JsonObject): String {
        if (obj.size() == 0) return "{}"
        val sb = StringBuilder("{")
        // 按 key 排序，输出稳定、便于对比
        val keys = obj.keySet().sorted()
        for ((idx, k) in keys.withIndex()) {
            if (idx > 0) sb.append(',')
            sb.append(quoteKey(k)).append(':')
            sb.append(toSnbt(obj.get(k)))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun listToSnbt(arr: JsonArray, itemType: Int): String {
        if (arr.size() == 0) return "[]"
        val sb = StringBuilder("[")
        for (i in 0 until arr.size()) {
            if (i > 0) sb.append(',')
            val e = arr.get(i)
            // 列表元素是裸值，需要按 itemType 还原类型后缀
            sb.append(rawToListElementSnbt(e, itemType))
        }
        sb.append(']')
        return sb.toString()
    }

    private fun rawToListElementSnbt(e: JsonElement, itemType: Int): String = when (itemType) {
        T_BYTE -> "${e.asInt}b"
        T_SHORT -> "${e.asInt}s"
        T_INT -> e.asInt.toString()
        T_LONG -> "${e.asLong}L"
        T_FLOAT -> "${e.asFloat}f"
        T_DOUBLE -> "${e.asDouble}d"
        T_STRING -> quote(if (e.isJsonPrimitive) e.asString else e.toString())
        T_BYTE_ARRAY, T_INT_ARRAY, T_LONG_ARRAY -> {
            val t = if (itemType == T_BYTE_ARRAY) "B" else if (itemType == T_INT_ARRAY) "I" else "L"
            val suffix = if (itemType == T_BYTE_ARRAY) "b" else if (itemType == T_LONG_ARRAY) "L" else ""
            typedArrayToSnbt(t, e, suffix)
        }
        T_LIST -> listToSnbt(
            if (e.isJsonArray) e.asJsonArray else JsonArray(),
            detectItemType(if (e.isJsonArray) e.asJsonArray else JsonArray())
        )
        T_COMPOUND -> compoundToSnbt(if (e.isJsonObject) e.asJsonObject else JsonObject())
        else -> e.toString()
    }

    /** 根据列表首元素推断 itemType（裸值无法自带类型信息，只能猜） */
    private fun detectItemType(arr: JsonArray): Int {
        if (arr.size() == 0) return T_COMPOUND
        return when (val f = arr.get(0)) {
            is JsonObject -> {
                // 裸 compound（无 t）视为 compound；带 t 说明是包装过的标量列表
                if (f.has("t")) f.get("t").asInt else T_COMPOUND
            }
            is JsonArray -> T_LIST
            is JsonPrimitive -> if (f.isString) T_STRING else T_INT
            else -> T_COMPOUND
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** key 仅在必要时加引号（含特殊字符时） */
    private fun quoteKey(k: String): String {
        val needQuote = k.isEmpty() || k.any { !it.isLetterOrDigit() && it != '_' && it != '-' && it != '.' && it != '+' }
        return if (needQuote) quote(k) else k
    }

    // ============================================
    // 输入：SNBT -> 内部 JSON（返回标签包装 {t,v}）
    // ============================================

    fun parseSnbt(text: String): JsonElement? {
        return try {
            val p = Parser(text)
            val v = p.parseValue()
            p.skipWs()
            if (!p.eof()) null else v   // 有多余内容视为失败
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 智能解析：先按内部 JSON 试，再按 SNBT 试。
     * 用于「粘贴」时自动识别来源格式。
     */
    fun parseAny(text: String): JsonElement? {
        val t = text.trim()
        if (t.isEmpty()) return null
        // 1) 内部 JSON（标签包装 / 裸 compound）
        try {
            val el = JsonParser.parseString(t)
            if (el.isJsonObject) {
                val o = el.asJsonObject
                // 带 t 的包装，或值是包装的裸 compound，都认为是内部格式
                if (o.has("t")) return el
                if (o.size() > 0 && o.entrySet().all { it.value.isJsonObject && it.value.asJsonObject.has("t") }) {
                    return el
                }
            }
        } catch (_: Exception) {
        }
        // 2) SNBT
        return parseSnbt(t)
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun eof(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun expect(c: Char) {
            skipWs()
            if (i >= s.length || s[i] != c) throw IllegalArgumentException("期望 '$c' 于 $i")
            i++
        }

        /** 解析一个值，返回标签包装 {t,v} */
        fun parseValue(): JsonElement {
            skipWs()
            return when (peek()) {
                '{' -> parseCompound()
                '[' -> parseArrayOrList()
                '"', '\'' -> {
                    val str = parseQuoted()
                    wrapper(T_STRING, JsonPrimitive(str))
                }
                else -> parseScalar()
            }
        }

        private fun parseCompound(): JsonObject {
            expect('{')
            val obj = JsonObject()
            skipWs()
            if (peek() == '}') { i++; return wrapper(T_COMPOUND, obj) }
            while (true) {
                skipWs()
                val key = parseKey()
                expect(':')
                val v = parseValue()
                obj.add(key, v)
                skipWs()
                when (peek()) {
                    ',' -> { i++ }
                    '}' -> { i++; break }
                    else -> throw IllegalArgumentException("compound 中出现意外字符")
                }
            }
            return wrapper(T_COMPOUND, obj)
        }

        private fun parseKey(): String {
            skipWs()
            return when (peek()) {
                '"', '\'' -> parseQuoted()
                else -> {
                    val start = i
                    while (i < s.length && s[i] != ':' && s[i] != ',' && s[i] != '}') i++
                    s.substring(start, i).trim()
                }
            }
        }

        private fun parseQuoted(): String {
            val q = peek()
            i++ // 跳过开引号
            val sb = StringBuilder()
            while (i < s.length && s[i] != q) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        '\'' -> sb.append('\'')
                        else -> sb.append(e)
                    }
                } else sb.append(c)
                i++
            }
            if (i < s.length) i++ // 跳过闭引号
            return sb.toString()
        }

        /** 标量：判断后缀决定类型 */
        private fun parseScalar(): JsonObject {
            skipWs()
            val start = i
            while (i < s.length && s[i] !in charArrayOf(',', '}', ']', ':', ' ', '\t', '\n', '\r')) i++
            val raw = s.substring(start, i).trim()
            if (raw.isEmpty()) throw IllegalArgumentException("空标量")

            val last = raw.last()
            return when {
                raw.equals("true", true) -> wrapper(T_BYTE, JsonPrimitive(1))
                raw.equals("false", true) -> wrapper(T_BYTE, JsonPrimitive(0))
                last == 'b' || last == 'B' ->
                    wrapper(T_BYTE, JsonPrimitive(raw.dropLast(1).toDouble().toInt()))
                last == 's' || last == 'S' ->
                    wrapper(T_SHORT, JsonPrimitive(raw.dropLast(1).toDouble().toInt()))
                last == 'l' || last == 'L' ->
                    wrapper(T_LONG, JsonPrimitive(raw.dropLast(1).toDouble().toLong()))
                last == 'f' || last == 'F' ->
                    wrapper(T_FLOAT, JsonPrimitive(raw.dropLast(1).toFloat()))
                last == 'd' || last == 'D' ->
                    wrapper(T_DOUBLE, JsonPrimitive(raw.dropLast(1).toDouble()))
                raw.contains('.') || raw.contains('e') || raw.contains('E') ->
                    wrapper(T_DOUBLE, JsonPrimitive(raw.toDouble()))
                else -> wrapper(T_INT, JsonPrimitive(raw.toIntOrNull() ?: 0))
            }
        }

        private fun parseArrayOrList(): JsonObject {
            expect('[')
            skipWs()
            // 类型化数组：[B;...] [I;...] [L;...]
            val t = peek()
            if ((t == 'B' || t == 'b' || t == 'I' || t == 'i' || t == 'L' || t == 'l')
                && i + 1 < s.length && s[i + 1] == ';'
            ) {
                i += 2 // 跳过 "X;"
                val arr = JsonArray()
                skipWs()
                if (peek() == ']') { i++; return wrapperOfArray(t, arr) }
                while (true) {
                    val v = parseValue()
                    arr.add(v.asJsonObject.get("v"))
                    skipWs()
                    when (peek()) {
                        ',' -> { i++ }
                        ']' -> { i++; break }
                        else -> throw IllegalArgumentException("数组中出现意外字符")
                    }
                }
                return wrapperOfArray(t, arr)
            }

            // 普通列表
            val arr = JsonArray()
            var itemType = -1
            skipWs()
            if (peek() == ']') {
                i++
                val w = wrapper(T_LIST, arr)
                w.addProperty("itemType", T_COMPOUND)   // 空列表：与新建 List 保持一致
                return w
            }
            while (true) {
                val v = parseValue().asJsonObject
                val et = v.get("t")?.asInt ?: T_COMPOUND
                if (itemType < 0) itemType = et
                arr.add(v.get("v"))
                skipWs()
                when (peek()) {
                    ',' -> { i++ }
                    ']' -> { i++; break }
                    else -> throw IllegalArgumentException("列表中出现意外字符")
                }
            }
            val w = wrapper(T_LIST, arr)
            w.addProperty("itemType", if (itemType < 0) T_COMPOUND else itemType)
            return w
        }

        private fun wrapperOfArray(prefix: Char, arr: JsonArray): JsonObject {
            val type = when (prefix) {
                'B', 'b' -> T_BYTE_ARRAY
                'I', 'i' -> T_INT_ARRAY
                else -> T_LONG_ARRAY
            }
            return wrapper(type, arr)
        }

        private fun wrapper(type: Int, value: JsonElement): JsonObject {
            val w = JsonObject()
            w.addProperty("t", type)
            w.add("v", value)
            return w
        }
    }
}
