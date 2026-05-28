package org.strand.hashing

import org.strand.core.Hash
import org.strand.core.HashFunction

/**
 * Layer 2 step 3 — the off-graph name discovery layer.
 *
 * A [NameRegistry] is a flat mapping from human-chosen names to content
 * hashes. The registry is *not* part of the canonical graph — it does not
 * affect any hash, does not appear in the verifier's input, and is read
 * exclusively by tooling (the CLI `strand registry` subcommands and the
 * authoring layer's name-to-hash resolution).
 *
 * Registry entries typically point at [Node.ModuleManifest] hashes ("one
 * entry per published bundle") but the format does not constrain this —
 * entries may point at any node hash. Multi-version naming is registry-
 * internal convention (`json-parser-v1`, `json-parser-v2`); the format is
 * single-version-per-name.
 *
 * The on-disk JSON format:
 *
 * ```json
 * {
 *   "version": 1,
 *   "entries": {
 *     "stdlib/json-parser": "<base32-multihash>",
 *     "stdlib/markdown-render": "<base32-multihash>",
 *     "app/main": "<base32-multihash>"
 *   }
 * }
 * ```
 *
 * Hashes are encoded as the lowercase hex string of the multi-hash bytes
 * (matching [Hash.toString]) — sufficient for the reference implementation;
 * the proposal calls for base32 with multibase prefix as a future
 * tooling-layer improvement.
 *
 * Q-043 proposal § 4.6 documents the role and rationale. The registry is
 * the off-graph counterpart to N-046 ModuleManifest's in-graph grouping.
 */
data class NameRegistry(val entries: Map<String, Hash>) {

    /** Resolves [name] to its bound hash, or null if unknown. */
    fun resolve(name: String): Hash? = entries[name]

    /** Returns a new registry with one entry added or updated. */
    fun put(name: String, hash: Hash): NameRegistry =
        copy(entries = entries + (name to hash))

    /** Returns a new registry with [name] removed. */
    fun remove(name: String): NameRegistry =
        copy(entries = entries - name)

    /** Returns the names in sorted order — stable for diff-friendly output. */
    fun names(): List<String> = entries.keys.sorted()

    companion object {
        /** An empty registry. */
        val EMPTY: NameRegistry = NameRegistry(emptyMap())

        /**
         * Parses a registry from its on-disk JSON representation. Throws
         * [IllegalArgumentException] if the JSON is malformed or carries
         * an unsupported `version` field.
         */
        fun fromJson(json: String): NameRegistry {
            val parsed = SimpleJson.parse(json) as? Map<*, *>
                ?: throw IllegalArgumentException("NameRegistry JSON must be a JSON object")
            val version = (parsed["version"] as? Number)?.toInt()
                ?: throw IllegalArgumentException("NameRegistry JSON missing 'version' field")
            require(version == 1) { "NameRegistry version $version not supported (this implementation reads version 1)" }
            val rawEntries = parsed["entries"] as? Map<*, *>
                ?: throw IllegalArgumentException("NameRegistry JSON missing 'entries' field")
            val entries = rawEntries.entries.associate { (k, v) ->
                val name = (k as? String) ?: throw IllegalArgumentException("NameRegistry entry key must be a String, got ${k?.let { it::class.simpleName } ?: "null"}")
                val hex = (v as? String) ?: throw IllegalArgumentException("NameRegistry entry value for '$name' must be a String, got ${v?.let { it::class.simpleName } ?: "null"}")
                name to parseHexHash(hex)
            }
            return NameRegistry(entries)
        }

        /** Serializes this registry to its on-disk JSON representation. */
        fun toJson(registry: NameRegistry): String {
            val sortedEntries = registry.entries.toSortedMap()
            val entriesJson = sortedEntries.entries.joinToString(separator = ",\n  ") { (name, hash) ->
                "\"${escapeJsonString(name)}\": \"$hash\""
            }
            return """{
              |  "version": 1,
              |  "entries": {
              |  $entriesJson
              |  }
              |}""".trimMargin()
        }

        private fun parseHexHash(hex: String): Hash {
            require(hex.length % 2 == 0) { "Hash hex must have even length: '$hex'" }
            val bytes = ByteArray(hex.length / 2) { i ->
                val high = Character.digit(hex[i * 2], 16)
                val low = Character.digit(hex[i * 2 + 1], 16)
                require(high >= 0 && low >= 0) { "Invalid hex char at position ${i * 2} of '$hex'" }
                ((high shl 4) or low).toByte()
            }
            return Hash(bytes)
        }

        private fun escapeJsonString(s: String): String =
            s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
    }
}

/**
 * Minimal JSON parser sufficient for [NameRegistry] round-trip. The
 * authoring module already depends on kotlinx-serialization-json; the
 * hashing module deliberately stays free of that dependency to keep the
 * core/hashing dependency surface minimal. The registry format is a flat
 * object-of-strings, so a hand-rolled parser is right-sized.
 */
internal object SimpleJson {
    fun parse(json: String): Any? {
        val parser = Parser(json)
        val value = parser.parseValue()
        parser.expectEnd()
        return value
    }

    private class Parser(private val src: String) {
        private var pos: Int = 0

        fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '"' -> parseString()
                'n' -> parseNull()
                't', 'f' -> parseBoolean()
                in '0'..'9', '-' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected character at position $pos: '${peek()}'")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val out = linkedMapOf<String, Any?>()
            if (peek() == '}') {
                advance()
                return out
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                out[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> { advance(); continue }
                    '}' -> { advance(); return out }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at position $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\' && pos < src.length) {
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        else -> throw IllegalArgumentException("Unsupported escape '\\$esc' at position ${pos - 1}")
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string starting near position $pos")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek() == '-') advance()
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) advance()
            val text = src.substring(start, pos)
            return if ('.' in text) text.toDouble() else text.toLong()
        }

        private fun parseBoolean(): Boolean = when {
            src.regionMatches(pos, "true", 0, 4) -> { pos += 4; true }
            src.regionMatches(pos, "false", 0, 5) -> { pos += 5; false }
            else -> throw IllegalArgumentException("Expected true/false at position $pos")
        }

        private fun parseNull(): Any? {
            if (src.regionMatches(pos, "null", 0, 4)) { pos += 4; return null }
            throw IllegalArgumentException("Expected null at position $pos")
        }

        private fun peek(): Char = if (pos < src.length) src[pos] else ' '
        private fun advance() { pos++ }
        private fun expect(c: Char) {
            if (pos >= src.length || src[pos] != c) {
                throw IllegalArgumentException("Expected '$c' at position $pos")
            }
            pos++
        }
        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
        fun expectEnd() {
            skipWhitespace()
            if (pos < src.length) {
                throw IllegalArgumentException("Trailing content at position $pos")
            }
        }
    }
}
