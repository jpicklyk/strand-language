package org.strand.authoring

/**
 * Layer A parser. Reads a compact-text Strand authoring document into a
 * [LayerADocument] suitable for [DagJsonEmitter]. The parser is generic
 * — it tokenizes each line and recognizes lexical forms (bare token,
 * quoted string, integer, float, boolean, list, null) without consulting
 * per-code schemas. Per-code arg-shape validation happens at emit time.
 *
 * Lexical rules (see [LayerAGrammar] for the full grammar prose):
 *
 *  * One node per line. Lines whose first non-whitespace char is `#`
 *    are comments. Blank lines are ignored.
 *  * Header: the first non-blank, non-comment line must be `@v=<int>
 *    root=<author-id>`.
 *  * Tokens are space-separated except inside `[...]` lists or `"..."`
 *    strings, where whitespace is significant only as a separator
 *    inside the bracket / string body.
 *  * Integer: `-?[0-9]+`. Float: `-?[0-9]+\.[0-9]+`. Bool: `true`/`false`.
 *    Null: `_`. String: `"..."` with `\"`, `\\`, `\n`, `\t`.
 *  * Bare identifiers: `[A-Za-z_][A-Za-z0-9_]*`.
 *
 * Errors are accumulated across the whole document so the user sees the
 * full list, not just the first failure. The parser still throws
 * [AuthoringException] at end-of-document if any error was recorded.
 */
object LayerAParser {

    /**
     * Parse [text] into a [LayerADocument]. Throws [AuthoringException]
     * on any lexical or per-line syntax errors.
     */
    fun parse(text: String): LayerADocument {
        val errors = mutableListOf<AuthoringError>()
        val lines = text.split('\n')

        // Locate the header line (first non-blank, non-comment line).
        var headerLineIdx = -1
        for ((i, raw) in lines.withIndex()) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            headerLineIdx = i
            break
        }
        if (headerLineIdx == -1) {
            throw AuthoringException(listOf(
                AuthoringError.HeaderError(line = 1, detail = "empty document — expected '@v=1 root=<id>' header")
            ))
        }
        val (version, rootId) = parseHeader(lines[headerLineIdx], headerLineIdx + 1, errors)

        val nodes = mutableListOf<NodeDecl>()
        val seenIds = HashSet<String>()
        for (i in (headerLineIdx + 1) until lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val decl = parseNodeLine(trimmed, i + 1, errors) ?: continue
            if (!seenIds.add(decl.id)) {
                errors += AuthoringError.DuplicateNodeId(line = decl.line, id = decl.id)
                continue
            }
            nodes += decl
        }

        // Validate root is declared.
        if (rootId != null && rootId !in seenIds) {
            errors += AuthoringError.UnknownRoot(line = headerLineIdx + 1, rootId = rootId)
        }

        if (errors.isNotEmpty()) throw AuthoringException(errors)
        return LayerADocument(
            version = version,
            rootId = rootId!!,  // guaranteed non-null when errors empty
            nodes = nodes,
        )
    }

    /**
     * Parse the header line `@v=<int> root=<author-id>`. Both fields are
     * required and order-sensitive (v first, then root). Returns
     * (version, rootId-or-null). On any header error, records an
     * [AuthoringError.HeaderError] and returns sentinels (-1, null)
     * which the caller treats as "header invalid, downstream gates on
     * errors-empty anyway".
     */
    private fun parseHeader(
        line: String,
        lineNum: Int,
        errors: MutableList<AuthoringError>,
    ): Pair<Int, String?> {
        val tokens = tokenizeLine(line, lineNum, errors)
        if (tokens.size != 2) {
            errors += AuthoringError.HeaderError(
                line = lineNum,
                detail = "expected '@v=<int> root=<id>' (two tokens) but got ${tokens.size}",
            )
            return -1 to null
        }
        val first = (tokens[0] as? Arg.Bare)?.text
        val second = (tokens[1] as? Arg.Bare)?.text
        if (first == null || !first.startsWith("@v=")) {
            errors += AuthoringError.HeaderError(
                line = lineNum,
                detail = "expected first token to be '@v=<int>' (e.g., '@v=1'); got '$first'",
            )
            return -1 to null
        }
        val versionStr = first.removePrefix("@v=")
        val version = versionStr.toIntOrNull()
        if (version == null) {
            errors += AuthoringError.HeaderError(
                line = lineNum,
                detail = "header version '$versionStr' is not a valid integer",
            )
            return -1 to null
        }
        if (second == null || !second.startsWith("root=")) {
            errors += AuthoringError.HeaderError(
                line = lineNum,
                detail = "expected second token to be 'root=<id>'; got '$second'",
            )
            return -1 to null
        }
        val root = second.removePrefix("root=")
        if (root.isEmpty() || !isBareIdentifier(root)) {
            errors += AuthoringError.HeaderError(
                line = lineNum,
                detail = "root id '$root' is not a valid author identifier",
            )
            return -1 to null
        }
        return version to root
    }

    /**
     * Parse one node line. Returns null on error (errors are recorded
     * in [errors] and the line is skipped).
     */
    private fun parseNodeLine(
        line: String,
        lineNum: Int,
        errors: MutableList<AuthoringError>,
    ): NodeDecl? {
        val tokens = tokenizeLine(line, lineNum, errors)
        if (tokens.size < 2) {
            errors += AuthoringError.TokenError(
                line = lineNum,
                detail = "node line needs at least an id and a code (e.g., 'x VAR y')",
            )
            return null
        }
        val idArg = tokens[0]
        if (idArg !is Arg.Bare) {
            errors += AuthoringError.TokenError(
                line = lineNum,
                detail = "node id (first token) must be a bare identifier; got ${idArg::class.simpleName}",
            )
            return null
        }
        if (!isBareIdentifier(idArg.text)) {
            errors += AuthoringError.TokenError(
                line = lineNum,
                detail = "node id '${idArg.text}' is not a valid author identifier",
            )
            return null
        }
        val codeArg = tokens[1]
        if (codeArg !is Arg.Bare) {
            errors += AuthoringError.TokenError(
                line = lineNum,
                detail = "node code (second token) must be a bare identifier; got ${codeArg::class.simpleName}",
            )
            return null
        }
        return NodeDecl(
            id = idArg.text,
            code = codeArg.text,
            args = tokens.drop(2),
            line = lineNum,
        )
    }

    /**
     * Lex a single line into [Arg] tokens. Handles bracketed lists,
     * quoted strings, signed numerics, booleans, null, and bare tokens.
     * Records [AuthoringError.TokenError] on lexical issues but tries
     * to keep going to surface multiple errors per line.
     */
    internal fun tokenizeLine(
        line: String,
        lineNum: Int,
        errors: MutableList<AuthoringError>,
    ): List<Arg> {
        val out = mutableListOf<Arg>()
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> { i++ }
                c == '"' -> {
                    val (str, end) = readQuotedString(line, i, lineNum, errors) ?: return out
                    out += Arg.Str(str)
                    i = end
                }
                c == '[' -> {
                    val (list, end) = readList(line, i, lineNum, errors) ?: return out
                    out += list
                    i = end
                }
                c == '_' && (i + 1 == n || !isBareChar(line[i + 1])) -> {
                    out += Arg.Null
                    i++
                }
                c == '-' || c.isDigit() -> {
                    val (arg, end) = readNumeric(line, i, lineNum, errors) ?: return out
                    out += arg
                    i = end
                }
                isBareStartChar(c) -> {
                    val (arg, end) = readBare(line, i)
                    out += arg
                    i = end
                }
                else -> {
                    errors += AuthoringError.TokenError(
                        line = lineNum,
                        detail = "unexpected character '${c}' at column ${i + 1}",
                    )
                    return out
                }
            }
        }
        return out
    }

    private fun readQuotedString(
        line: String,
        start: Int,
        lineNum: Int,
        errors: MutableList<AuthoringError>,
    ): Pair<String, Int>? {
        // start points at opening "
        val sb = StringBuilder()
        var i = start + 1
        while (i < line.length) {
            val c = line[i]
            when (c) {
                '"' -> return sb.toString() to (i + 1)
                '\\' -> {
                    if (i + 1 >= line.length) {
                        errors += AuthoringError.TokenError(
                            line = lineNum,
                            detail = "unterminated escape at end of line in string starting at column ${start + 1}",
                        )
                        return null
                    }
                    when (val esc = line[i + 1]) {
                        '"', '\\' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> {
                            errors += AuthoringError.TokenError(
                                line = lineNum,
                                detail = "unknown escape '\\$esc' at column ${i + 1}",
                            )
                            sb.append(esc)
                        }
                    }
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        errors += AuthoringError.TokenError(
            line = lineNum,
            detail = "unterminated string starting at column ${start + 1}",
        )
        return null
    }

    private fun readList(
        line: String,
        start: Int,
        lineNum: Int,
        errors: MutableList<AuthoringError>,
    ): Pair<Arg.Listing, Int>? {
        // start points at opening [
        var i = start + 1
        val items = mutableListOf<Arg>()
        while (i < line.length) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == ']' -> return Arg.Listing(items) to (i + 1)
                c == '[' -> {
                    // Nested lists are not part of the first slice's grammar
                    // (every list-bearing field takes a flat list of refs).
                    // Record the error but continue past the inner bracket
                    // so the outer tokenizer can recover.
                    errors += AuthoringError.TokenError(
                        line = lineNum,
                        detail = "nested lists are not supported in the first Layer A slice (column ${i + 1})",
                    )
                    val (inner, end) = readList(line, i, lineNum, errors) ?: return null
                    items += inner
                    i = end
                }
                c == '"' -> {
                    val (str, end) = readQuotedString(line, i, lineNum, errors) ?: return null
                    items += Arg.Str(str)
                    i = end
                }
                c == '_' && (i + 1 == line.length || !isBareChar(line[i + 1])) -> {
                    items += Arg.Null
                    i++
                }
                c == '-' || c.isDigit() -> {
                    val (arg, end) = readNumeric(line, i, lineNum, errors) ?: return null
                    items += arg
                    i = end
                }
                isBareStartChar(c) -> {
                    val (arg, end) = readBare(line, i)
                    items += arg
                    i = end
                }
                else -> {
                    errors += AuthoringError.TokenError(
                        line = lineNum,
                        detail = "unexpected character '${c}' inside list at column ${i + 1}",
                    )
                    return null
                }
            }
        }
        errors += AuthoringError.TokenError(
            line = lineNum,
            detail = "unterminated list starting at column ${start + 1}",
        )
        return null
    }

    private fun readNumeric(
        line: String,
        start: Int,
        lineNum: Int,
        errors: MutableList<AuthoringError>,
    ): Pair<Arg, Int>? {
        var i = start
        if (line[i] == '-') i++
        if (i >= line.length || !line[i].isDigit()) {
            errors += AuthoringError.TokenError(
                line = lineNum,
                detail = "expected digit after '-' at column ${start + 1}",
            )
            return null
        }
        while (i < line.length && line[i].isDigit()) i++
        if (i < line.length && line[i] == '.') {
            i++
            while (i < line.length && line[i].isDigit()) i++
            val text = line.substring(start, i)
            val value = text.toDoubleOrNull()
            if (value == null) {
                errors += AuthoringError.TokenError(
                    line = lineNum,
                    detail = "malformed float '$text' at column ${start + 1}",
                )
                return null
            }
            return Arg.FloatL(value) to i
        }
        val text = line.substring(start, i)
        val value = text.toLongOrNull()
        if (value == null) {
            errors += AuthoringError.TokenError(
                line = lineNum,
                detail = "malformed integer '$text' at column ${start + 1}",
            )
            return null
        }
        return Arg.IntL(value) to i
    }

    private fun readBare(line: String, start: Int): Pair<Arg, Int> {
        var i = start
        while (i < line.length && isBareChar(line[i])) i++
        val text = line.substring(start, i)
        return when (text) {
            "true" -> Arg.BoolL(true) to i
            "false" -> Arg.BoolL(false) to i
            else -> Arg.Bare(text) to i
        }
    }

    /**
     * Bare-identifier characters allow `=` to support header tokens like
     * `@v=1` and `root=foo` to lex as single tokens; otherwise the bare-
     * char set is alphanumeric + underscore (standard identifier).
     */
    private fun isBareChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '@' || c == '='

    /** First-character predicate for bare tokens. Digits are excluded so numerics dispatch first. */
    private fun isBareStartChar(c: Char): Boolean =
        c.isLetter() || c == '_' || c == '@'

    /** True if [text] is a pure author-id identifier (no `=`/`@` allowed at use sites). */
    private fun isBareIdentifier(text: String): Boolean {
        if (text.isEmpty()) return false
        if (!(text[0].isLetter() || text[0] == '_')) return false
        return text.all { it.isLetterOrDigit() || it == '_' }
    }
}
