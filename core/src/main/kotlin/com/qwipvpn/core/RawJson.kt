package com.qwipvpn.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Structural edits retain untouched provider text, including opaque numbers. */
object RawJson {
    fun quote(value: String): String = Json.encodeToString(value)
    fun array(elements: List<String>) = elements.joinToString(",", "[", "]")
    fun obj(vararg fields: Pair<String, String>) = fields.joinToString(",", "{", "}") { quote(it.first) + ":" + it.second }
    fun members(text: String): LinkedHashMap<String, String> {
        val scan = Scanner(text)
        return scan.objectFields().also { scan.finish() }
    }
    fun elements(text: String): List<String> {
        val scan = Scanner(text)
        return scan.arrayItems().also { scan.finish() }
    }
    fun setting(text: String, key: String, value: String): String {
        validate(value)
        return members(text).apply { this[key] = value }.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + it.value }
    }
    fun validate(text: String) { Scanner(text).apply { value(0); finish() } }

    private class Scanner(val text: String) {
        var pos = 0
        fun space() { while (pos < text.length && text[pos] in " \r\n\t") pos++ }
        fun take(c: Char) { space(); require(pos < text.length && text[pos++] == c) { "Invalid JSON" } }
        fun finish() { space(); require(pos == text.length) { "Trailing JSON" } }
        fun string(): String {
            space(); val start = pos
            require(pos < text.length && text[pos++] == '"')
            while (pos < text.length) {
                val c = text[pos++]
                if (c == '"') return Json.parseToJsonElement(text.substring(start, pos)).jsonPrimitive.content
                require(c >= ' ')
                if (c == '\\') { require(pos < text.length); pos++ }
            }
            error("Unterminated JSON string")
        }
        fun objectFields(depth: Int = 0): LinkedHashMap<String, String> {
            require(depth <= 128); take('{'); space()
            val fields = linkedMapOf<String, String>()
            if (pos < text.length && text[pos] == '}') { pos++; return fields }
            while (true) {
                val key = string(); require(key !in fields) { "Duplicate JSON key" }; take(':'); space()
                val start = pos; value(depth + 1); fields[key] = text.substring(start, pos)
                space(); require(pos < text.length)
                when (text[pos++]) { '}' -> return fields; ',' -> Unit; else -> error("Invalid JSON object") }
            }
        }
        fun arrayItems(depth: Int = 0): List<String> {
            require(depth <= 128); take('['); space(); val items = mutableListOf<String>()
            if (pos < text.length && text[pos] == ']') { pos++; return items }
            while (true) {
                space(); val start = pos; value(depth + 1); items += text.substring(start, pos)
                space(); require(pos < text.length)
                when (text[pos++]) { ']' -> return items; ',' -> Unit; else -> error("Invalid JSON array") }
            }
        }
        fun value(depth: Int) {
            require(depth <= 128); space(); require(pos < text.length)
            when (text[pos]) {
                '{' -> objectFields(depth)
                '[' -> arrayItems(depth)
                '"' -> string()
                else -> {
                    val start = pos
                    while (pos < text.length && text[pos] !in ",]} \n\r\t") pos++
                    val token = text.substring(start, pos)
                    require(token in listOf("null", "true", "false") || NUMBER.matches(token)) { "Invalid JSON value" }
                }
            }
        }
    }
    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}
