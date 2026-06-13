package org.strand.authoring.familiar

import org.strand.authoring.AuthoringError
import org.strand.authoring.AuthoringException
import org.strand.authoring.familiar.FamiliarLexer.Tok
import org.strand.authoring.familiar.FamiliarLexer.TokKind

/**
 * Q-061 Layer F — recursive-descent parser for the familiar dialect.
 *
 * Two diagnostic channels per the proposal:
 *
 *  * **Syntax errors** ([AuthoringError.TokenError]) for malformed
 *    dialect constructs.
 *  * **Dialect violations** ([AuthoringError.DialectViolation]) for
 *    everything real TypeScript has that the dialect deliberately
 *    lacks (proposal § 3): `let`/`var`/reassignment, loops, classes,
 *    `if`/ternary, untyped or `any` parameters, `async`, `import`,
 *    exceptions, operators, template literals, generics. Each carries
 *    a hint naming the dialect construct to use instead.
 *
 * The parser accumulates errors across items (sync points: the next
 * top-level keyword; inside blocks: the next statement) so an agent
 * sees multiple problems per attempt, then throws a single
 * [AuthoringException].
 */
internal object FamiliarParser {

    fun parse(text: String): FProgram {
        val lexed = FamiliarLexer.lex(text)
        val p = P(lexed.tokens, lexed.errors.toMutableList())
        val items = mutableListOf<FItem>()
        while (!p.at(TokKind.EOF)) {
            val before = p.pos
            try {
                items += p.parseItem() ?: continue
            } catch (e: ParseAbort) {
                p.syncToTopLevel()
            }
            if (p.pos == before) {
                // Defensive: never loop without progress.
                p.advance()
            }
        }
        if (p.errors.isNotEmpty()) throw AuthoringException(p.errors)
        return FProgram(items)
    }

    private class ParseAbort : RuntimeException()

    private val TOP_LEVEL_KEYWORDS = setOf("use", "type", "declare", "const", "function")

    /** Statement-position keywords that are dialect violations, with hints. */
    private val STATEMENT_VIOLATIONS: Map<String, Pair<String, String>> = mapOf(
        "let" to ("`let` bindings" to "bind values with `const`; there is no reassignment"),
        "var" to ("`var` bindings" to "bind values with `const`; there is no reassignment"),
        "if" to ("`if`/`else`" to
            "branch with a match expression: match (cond) { true => ..., false => ... }"),
        "else" to ("`if`/`else`" to
            "branch with a match expression: match (cond) { true => ..., false => ... }"),
        "switch" to ("`switch`" to "branch with a match expression"),
        "for" to ("loops" to
            "iterate with recursion or the higher-order List builtins (List.Map, List.Filter, List.Fold)"),
        "while" to ("loops" to
            "iterate with recursion or the higher-order List builtins (List.Map, List.Filter, List.Fold)"),
        "do" to ("loops" to
            "iterate with recursion or the higher-order List builtins (List.Map, List.Filter, List.Fold)"),
        "class" to ("classes" to
            "model data with `type` aliases (products and tagged unions) and behavior with functions"),
        "interface" to ("`interface` declarations" to "declare shapes with `type` aliases"),
        "enum" to ("`enum` declarations" to
            "declare a tagged union: type E = { tag: \"A\" } | { tag: \"B\" }"),
        "new" to ("`new` / constructors" to "construct values with object literals"),
        "import" to ("`import` statements" to
            "the only imports are `use prelude.{...}` and `use \"b3:<hash>\" as name`"),
        "export" to ("`export` statements" to "every top-level declaration is visible; `main` is the root"),
        "require" to ("`require`" to
            "the only imports are `use prelude.{...}` and `use \"b3:<hash>\" as name`"),
        "async" to ("`async`" to "effects are declared with `uses` clauses; there is no async surface"),
        "await" to ("`await`" to "effects are declared with `uses` clauses; there is no async surface"),
        "try" to ("`try`/`catch` exceptions" to
            "wrap the fallible expression in attempt { ... } and match on Ok(v) / Err(e)"),
        "catch" to ("`try`/`catch` exceptions" to
            "wrap the fallible expression in attempt { ... } and match on Ok(v) / Err(e)"),
        "throw" to ("`throw`" to
            "there are no exceptions; fallible calls are consumed with attempt { ... } and match"),
        "finally" to ("`try`/`finally`" to
            "there are no exceptions; fallible calls are consumed with attempt { ... } and match"),
    )

    /** Type-position names that are dialect violations, with hints. */
    private val TYPE_NAME_VIOLATIONS: Map<String, Pair<String, String>> = mapOf(
        "any" to ("`any`" to "every annotation is a concrete type: Int, Float, String, Bool, Unit, Bytes, or a declared alias"),
        "unknown" to ("`unknown`" to "every annotation is a concrete type"),
        "number" to ("the `number` type" to "use Int or Float"),
        "string" to ("the `string` type" to "use String"),
        "boolean" to ("the `boolean` type" to "use Bool"),
        "void" to ("the `void` type" to "use Unit"),
        "undefined" to ("`undefined`" to "use Unit, or model absence with a tagged union"),
        "null" to ("`null`" to "model absence with a tagged union (e.g. Some/None)"),
        "object" to ("the `object` type" to "declare the shape with a `type` alias"),
        "never" to ("the `never` type" to "every annotation is a concrete type"),
        "Promise" to ("`Promise`" to "effects are declared with `uses` clauses; there is no async surface"),
    )

    /** Binary/unary operators, with the prelude call to use instead. */
    private val OPERATOR_HINTS: Map<String, String> = mapOf(
        "+" to "call add(a, b) for Int, fAdd(a, b) for Float, or concat(a, b) for String",
        "-" to "call sub(a, b) for Int or fSub(a, b) for Float",
        "*" to "call mul(a, b) for Int or fMul(a, b) for Float",
        "/" to "call div(a, b) for Int or fDiv(a, b) for Float",
        "%" to "call mod(a, b) (truncated) or mmod(a, b) (mathematical)",
        "==" to "call eqInt / eqStr / eqBool / fEq per the operand type",
        "===" to "call eqInt / eqStr / eqBool / fEq per the operand type",
        "!=" to "call not(eqInt(a, b)) (or the eq builtin matching the operand type)",
        "!==" to "call not(eqInt(a, b)) (or the eq builtin matching the operand type)",
        "<" to "call lt(a, b) for Int or fLt(a, b) for Float",
        "<=" to "call le(a, b) for Int or fLe(a, b) for Float",
        ">" to "call gt(a, b) for Int or fGt(a, b) for Float",
        ">=" to "call ge(a, b) for Int or fGe(a, b) for Float",
        "&&" to "call and(a, b)",
        "||" to "call or(a, b)",
        "!" to "call not(a)",
        "?" to "branch with a match expression: match (cond) { true => ..., false => ... }",
        "->" to "function types are written (T) => R with a fat arrow",
    )

    private class P(val tokens: List<Tok>, val errors: MutableList<AuthoringError>) {
        var pos = 0

        fun cur(): Tok = tokens[pos]
        fun peek(offset: Int = 1): Tok = tokens[(pos + offset).coerceAtMost(tokens.size - 1)]
        fun at(kind: TokKind): Boolean = cur().kind == kind
        fun atIdent(text: String): Boolean = cur().kind == TokKind.IDENT && cur().text == text
        fun advance(): Tok = cur().also { if (pos < tokens.size - 1) pos++ }

        fun error(line: Int, detail: String) {
            errors += AuthoringError.TokenError(line = line, detail = detail)
        }

        fun violation(line: Int, construct: String, hint: String) {
            errors += AuthoringError.DialectViolation(line = line, construct = construct, hint = hint)
        }

        fun expect(kind: TokKind, what: String): Tok {
            if (cur().kind != kind) {
                error(cur().line, "expected $what but found ${cur()}")
                throw ParseAbort()
            }
            return advance()
        }

        fun expectIdent(what: String): Tok {
            if (cur().kind != TokKind.IDENT) {
                error(cur().line, "expected $what but found ${cur()}")
                throw ParseAbort()
            }
            return advance()
        }

        fun skipSemis() {
            while (at(TokKind.SEMI)) advance()
        }

        fun syncToTopLevel() {
            while (!at(TokKind.EOF)) {
                // Stop at the next declaration keyword OR the next
                // rejectable construct, so several violations in one
                // document are all reported (scenario-10 accumulation).
                if (cur().kind == TokKind.IDENT &&
                    (cur().text in TOP_LEVEL_KEYWORDS || cur().text in STATEMENT_VIOLATIONS)
                ) return
                advance()
            }
        }

        // --------------------------------------------------------------
        // Items
        // --------------------------------------------------------------

        fun parseItem(): FItem? {
            skipSemis()
            if (at(TokKind.EOF)) return null
            val t = cur()
            if (t.kind != TokKind.IDENT) {
                error(t.line, "expected a top-level declaration (use / type / declare / const / function) but found $t")
                throw ParseAbort()
            }
            STATEMENT_VIOLATIONS[t.text]?.let { (construct, hint) ->
                violation(t.line, construct, hint)
                advance() // past the violating keyword so recovery makes progress
                throw ParseAbort()
            }
            return when (t.text) {
                "use" -> parseUse()
                "type" -> parseTypeAlias()
                "declare" -> parseDeclare()
                "const" -> parseConst()
                "function" -> parseFunction()
                else -> {
                    // `x = e` at top level is a reassignment habit.
                    if (peek().kind == TokKind.EQ) {
                        violation(t.line, "reassignment", "bind a new name with `const`; bindings are immutable")
                    } else {
                        error(t.line, "expected a top-level declaration (use / type / declare / const / function) but found $t")
                    }
                    throw ParseAbort()
                }
            }
        }

        fun parseUse(): FItem {
            val useTok = advance() // `use`
            if (at(TokKind.STRING)) {
                val hashTok = advance()
                if (!atIdent("as")) {
                    error(cur().line, "a hash import is `use \"b3:<hash>\" as <name>` — expected `as`")
                    throw ParseAbort()
                }
                advance() // `as`
                val alias = expectIdent("the imported name")
                skipSemis()
                val raw = hashTok.text
                if (!raw.startsWith("b3:")) {
                    violation(hashTok.line, "arbitrary imports",
                        "the only imports are `use prelude.{...}` and `use \"b3:<hash>\" as name`")
                    throw ParseAbort()
                }
                val hex = raw.removePrefix("b3:")
                if (hex.length != 66 || hex.any { it !in "0123456789abcdef" }) {
                    error(hashTok.line, "a hash import target is \"b3:\" + 66 lowercase hex chars " +
                        "(the BLAKE3-256 multihash); got \"${raw.take(40)}\"")
                    throw ParseAbort()
                }
                return FUseHash(hashHex = hex, alias = alias.text, line = useTok.line)
            }
            if (!atIdent("prelude")) {
                violation(useTok.line, "arbitrary imports",
                    "the only imports are `use prelude.{...}` and `use \"b3:<hash>\" as name`")
                throw ParseAbort()
            }
            advance() // `prelude`
            expect(TokKind.DOT, "'.' after `prelude`")
            val names = mutableListOf<String>()
            if (at(TokKind.LBRACE)) {
                advance()
                while (!at(TokKind.RBRACE)) {
                    names += expectIdent("a prelude name").text
                    if (at(TokKind.COMMA)) advance() else break
                }
                expect(TokKind.RBRACE, "'}' closing the prelude import list")
            } else {
                names += expectIdent("a prelude name").text
            }
            skipSemis()
            return FUsePrelude(names = names, line = useTok.line)
        }

        fun parseTypeAlias(): FItem {
            val typeTok = advance() // `type`
            val name = expectIdent("the type alias name")
            if (cur().kind == TokKind.OP && cur().text == "<") {
                violation(cur().line, "generic type declarations", "step-1 aliases are concrete; write the concrete shape")
                throw ParseAbort()
            }
            expect(TokKind.EQ, "'=' after the type alias name")
            if (!at(TokKind.LBRACE)) {
                violation(cur().line, "non-shape type aliases",
                    "a `type` alias is a product { a: T, ... } or a tagged union { tag: \"A\", ... } | { tag: \"B\" }")
                throw ParseAbort()
            }
            val shapes = mutableListOf<FShape>()
            shapes += parseShape()
            while (at(TokKind.PIPE)) {
                advance()
                shapes += parseShape()
            }
            skipSemis()
            val tagged = shapes.count { it.tag != null }
            if (tagged != 0 && tagged != shapes.size) {
                error(typeTok.line, "type alias '${name.text}' mixes tagged and untagged shapes — " +
                    "either every case carries tag: \"...\" (a union) or there is a single untagged product")
                throw ParseAbort()
            }
            if (tagged == 0 && shapes.size > 1) {
                error(typeTok.line, "type alias '${name.text}' is a union of untagged shapes — " +
                    "every union case needs a tag: \"...\" discriminant")
                throw ParseAbort()
            }
            return FTypeAlias(name = name.text, shapes = shapes, line = typeTok.line)
        }

        fun parseShape(): FShape {
            val open = expect(TokKind.LBRACE, "'{' opening a shape")
            var tag: String? = null
            val fields = mutableListOf<FField>()
            while (!at(TokKind.RBRACE)) {
                val fieldName = expectIdent("a field name")
                expect(TokKind.COLON, "':' after field name '${fieldName.text}'")
                if (fieldName.text == "tag" && at(TokKind.STRING)) {
                    tag = advance().text
                } else if (at(TokKind.STRING)) {
                    violation(cur().line, "string-literal types",
                        "only the `tag` field carries a string-literal discriminant")
                    throw ParseAbort()
                } else {
                    fields += FField(name = fieldName.text, type = parseTypeRef(), line = fieldName.line)
                }
                if (at(TokKind.COMMA)) advance() else break
            }
            expect(TokKind.RBRACE, "'}' closing the shape")
            return FShape(tag = tag, fields = fields, line = open.line)
        }

        fun parseTypeRef(): FTypeRef {
            if (at(TokKind.LPAREN)) {
                val open = advance()
                val params = mutableListOf<FTypeRef>()
                while (!at(TokKind.RPAREN)) {
                    params += parseTypeRef()
                    if (at(TokKind.COMMA)) advance() else break
                }
                expect(TokKind.RPAREN, "')' closing the function-type parameter list")
                expect(TokKind.ARROW, "'=>' in a function type")
                val result = parseTypeRef()
                return FFnType(params = params, result = result, line = open.line)
            }
            if (at(TokKind.LBRACE)) {
                violation(cur().line, "inline object types",
                    "declare the shape once with a `type` alias and reference it by name")
                throw ParseAbort()
            }
            val t = expectIdent("a type")
            TYPE_NAME_VIOLATIONS[t.text]?.let { (construct, hint) ->
                violation(t.line, construct, hint)
                throw ParseAbort()
            }
            if (cur().kind == TokKind.OP && cur().text == "<") {
                violation(cur().line, "generic type application",
                    "step-1 types are concrete; write the concrete type")
                throw ParseAbort()
            }
            return FNamedType(name = t.text, line = t.line)
        }

        fun parseParams(context: String): List<FParam> {
            expect(TokKind.LPAREN, "'(' opening the parameter list")
            val params = mutableListOf<FParam>()
            while (!at(TokKind.RPAREN)) {
                val name = expectIdent("a parameter name")
                if (!at(TokKind.COLON)) {
                    violation(name.line, "untyped parameters",
                        "parameter '${name.text}' of $context needs a type annotation, e.g. (${name.text}: Int)")
                    throw ParseAbort()
                }
                advance() // ':'
                val type = parseTypeRef()
                params += FParam(name = name.text, type = type, line = name.line)
                if (at(TokKind.COMMA)) advance() else break
            }
            expect(TokKind.RPAREN, "')' closing the parameter list")
            return params
        }

        fun parseUsesClause(): List<FUsesEntry> {
            if (!atIdent("uses")) return emptyList()
            advance()
            val entries = mutableListOf<FUsesEntry>()
            while (true) {
                val first = expectIdent("an effect category name (e.g. Filesystem.Write)")
                val sb = StringBuilder(first.text)
                while (at(TokKind.DOT)) {
                    advance()
                    sb.append('.').append(expectIdent("the effect category name segment").text)
                }
                var args: List<FExpr>? = null
                if (at(TokKind.LPAREN)) {
                    advance()
                    val list = mutableListOf<FExpr>()
                    while (!at(TokKind.RPAREN)) {
                        list += parseUsesArg()
                        if (at(TokKind.COMMA)) advance() else break
                    }
                    expect(TokKind.RPAREN, "')' closing the effect instance arguments")
                    args = list
                }
                entries += FUsesEntry(category = sb.toString(), args = args, line = first.line)
                if (at(TokKind.COMMA)) advance() else break
            }
            return entries
        }

        fun parseUsesArg(): FExpr {
            val t = cur()
            return when (t.kind) {
                TokKind.STRING -> FStringLit(advance().text, t.line)
                TokKind.INT -> FIntLit(advance().text.toLong(), t.line)
                TokKind.FLOAT -> FFloatLit(advance().text.toDouble(), t.line)
                TokKind.IDENT -> when (t.text) {
                    "true" -> FBoolLit(true, advance().line)
                    "false" -> FBoolLit(false, advance().line)
                    else -> FIdent(advance().text, t.line)
                }
                TokKind.OP -> {
                    if (t.text == "-" && peek().kind == TokKind.INT) {
                        advance()
                        FIntLit(-advance().text.toLong(), t.line)
                    } else {
                        error(t.line, "a uses-clause argument is a literal or a parameter name; found $t")
                        throw ParseAbort()
                    }
                }
                else -> {
                    error(t.line, "a uses-clause argument is a literal or a parameter name; found $t")
                    throw ParseAbort()
                }
            }
        }

        fun parseDeclare(): FItem {
            val declTok = advance() // `declare`
            if (!atIdent("function")) {
                error(cur().line, "only `declare function` ambient declarations are supported")
                throw ParseAbort()
            }
            advance() // `function`
            val name = expectIdent("the declared function name")
            val params = parseParams("declare function '${name.text}'")
            if (!at(TokKind.COLON)) {
                violation(name.line, "missing return annotations",
                    "declare function '${name.text}' needs a return type, e.g. (): Int")
                throw ParseAbort()
            }
            advance()
            val result = parseTypeRef()
            if (!atIdent("from")) {
                error(cur().line, "a declare function names its foreign target: " +
                    "`from \"strand-builtin:...\"` — expected `from`")
                throw ParseAbort()
            }
            advance() // `from`
            val target = expect(TokKind.STRING, "the foreign target string").text
            val uses = parseUsesClause()
            skipSemis()
            return FDeclareFn(
                name = name.text, params = params, result = result,
                target = target, uses = uses, line = declTok.line,
            )
        }

        fun parseConst(): FConst {
            val constTok = advance() // `const`
            val name = expectIdent("the const name")
            var annotation: FTypeRef? = null
            if (at(TokKind.COLON)) {
                advance()
                annotation = parseTypeRef()
            }
            expect(TokKind.EQ, "'=' in the const binding")
            val value = parseExpr()
            skipSemis()
            return FConst(name = name.text, annotation = annotation, value = value, line = constTok.line)
        }

        fun parseFunction(): FItem {
            val fnTok = advance() // `function`
            val name = expectIdent("the function name")
            if (cur().kind == TokKind.OP && cur().text == "<") {
                violation(cur().line, "generic functions", "step-1 functions take concrete types")
                throw ParseAbort()
            }
            val params = parseParams("function '${name.text}'")
            if (!at(TokKind.COLON)) {
                violation(name.line, "missing return annotations",
                    "function '${name.text}' needs a return type, e.g. (): Int")
                throw ParseAbort()
            }
            advance()
            val result = parseTypeRef()
            val uses = parseUsesClause()
            val body = parseBlock("function '${name.text}'")
            return FFunction(
                name = name.text, params = params, result = result,
                uses = uses, body = body, line = fnTok.line,
            )
        }

        fun parseBlock(context: String): FBlock {
            val open = expect(TokKind.LBRACE, "'{' opening the body of $context")
            val consts = mutableListOf<FConst>()
            var result: FExpr? = null
            while (!at(TokKind.RBRACE) && !at(TokKind.EOF)) {
                skipSemis()
                if (at(TokKind.RBRACE)) break
                val t = cur()
                if (t.kind != TokKind.IDENT) {
                    error(t.line, "expected a statement (`const` or `return`) but found $t")
                    throw ParseAbort()
                }
                if (result != null) {
                    error(t.line, "unreachable statement after `return` in $context")
                    throw ParseAbort()
                }
                STATEMENT_VIOLATIONS[t.text]?.let { (construct, hint) ->
                    violation(t.line, construct, hint)
                    advance() // past the violating keyword so recovery makes progress
                    throw ParseAbort()
                }
                when (t.text) {
                    "const" -> consts += parseConst()
                    "return" -> {
                        advance()
                        result = parseExpr()
                        skipSemis()
                    }
                    else -> {
                        if (peek().kind == TokKind.EQ) {
                            violation(t.line, "reassignment",
                                "bind a new name with `const`; bindings are immutable")
                        } else {
                            error(t.line, "expected `const` or `return` in $context but found $t " +
                                "(every body is a const chain ending in a single return)")
                        }
                        throw ParseAbort()
                    }
                }
            }
            expect(TokKind.RBRACE, "'}' closing the body of $context")
            if (result == null) {
                error(open.line, "the body of $context never returns — end it with `return <expr>`")
                throw ParseAbort()
            }
            return FBlock(consts = consts, result = result, line = open.line)
        }

        // --------------------------------------------------------------
        // Expressions
        // --------------------------------------------------------------

        fun parseExpr(): FExpr {
            val e = parsePostfix(parsePrimary())
            // A binary operator after a complete expression is a dialect
            // violation with a targeted hint.
            if (at(TokKind.OP)) {
                val op = cur()
                val hint = OPERATOR_HINTS[op.text] ?: "operators lower through prelude calls"
                violation(op.line, "the `${op.text}` operator", hint)
                throw ParseAbort()
            }
            if (at(TokKind.EQ)) {
                violation(cur().line, "reassignment", "bind a new name with `const`; bindings are immutable")
                throw ParseAbort()
            }
            return e
        }

        fun parsePostfix(base: FExpr): FExpr {
            var e = base
            while (true) {
                when {
                    at(TokKind.DOT) -> {
                        advance()
                        val field = expectIdent("a field name after '.'")
                        e = FFieldGet(target = e, field = field.text, line = field.line)
                    }
                    at(TokKind.LPAREN) -> {
                        val open = advance()
                        val args = mutableListOf<FExpr>()
                        while (!at(TokKind.RPAREN)) {
                            args += parseExpr()
                            if (at(TokKind.COMMA)) advance() else break
                        }
                        expect(TokKind.RPAREN, "')' closing the argument list")
                        e = FCall(callee = e, args = args, line = open.line)
                    }
                    else -> return e
                }
            }
        }

        fun parsePrimary(): FExpr {
            val t = cur()
            when (t.kind) {
                TokKind.INT -> return FIntLit(advance().text.toLong(), t.line)
                TokKind.FLOAT -> return FFloatLit(advance().text.toDouble(), t.line)
                TokKind.STRING -> return FStringLit(advance().text, t.line)
                TokKind.LBRACE -> return parseObjectLit()
                TokKind.LPAREN -> return parseParenOrArrow()
                TokKind.OP -> {
                    if (t.text == "-" && peek().kind == TokKind.INT) {
                        advance()
                        return FIntLit(-advance().text.toLong(), t.line)
                    }
                    if (t.text == "-" && peek().kind == TokKind.FLOAT) {
                        advance()
                        return FFloatLit(-advance().text.toDouble(), t.line)
                    }
                    val hint = OPERATOR_HINTS[t.text] ?: "operators lower through prelude calls"
                    violation(t.line, "the `${t.text}` operator", hint)
                    throw ParseAbort()
                }
                TokKind.IDENT -> {
                    STATEMENT_VIOLATIONS[t.text]?.let { (construct, hint) ->
                        violation(t.line, construct, hint)
                        throw ParseAbort()
                    }
                    return when (t.text) {
                        "true" -> FBoolLit(true, advance().line)
                        "false" -> FBoolLit(false, advance().line)
                        "match" -> parseMatch()
                        "attempt" -> parseAttempt()
                        else -> FIdent(advance().text, t.line)
                    }
                }
                else -> {
                    error(t.line, "expected an expression but found $t")
                    throw ParseAbort()
                }
            }
        }

        /**
         * At '(' in expression position: an arrow function when the
         * parenthesized list is followed by `=>`, otherwise a
         * parenthesized expression. An arrow whose parameters lack
         * annotations is the scenario-4 rejection.
         */
        fun parseParenOrArrow(): FExpr {
            val open = cur()
            // Scan to the matching ')' to see whether '=>' follows.
            var depth = 0
            var j = pos
            while (j < tokens.size - 1) {
                when (tokens[j].kind) {
                    TokKind.LPAREN -> depth++
                    TokKind.RPAREN -> {
                        depth--
                        if (depth == 0) break
                    }
                    else -> {}
                }
                j++
            }
            val isArrow = j < tokens.size - 1 && tokens[j + 1].kind == TokKind.ARROW
            if (!isArrow) {
                advance() // '('
                val inner = parseExpr()
                expect(TokKind.RPAREN, "')' closing the parenthesized expression")
                return inner
            }
            val params = parseParams("the arrow function")
            expect(TokKind.ARROW, "'=>' in the arrow function")
            if (at(TokKind.LBRACE) && looksLikeBlockBody()) {
                violation(cur().line, "block-bodied arrow functions",
                    "an arrow body is a single expression; use a named `function` for const chains")
                throw ParseAbort()
            }
            val body = parseExpr()
            return FArrow(params = params, body = body, line = open.line)
        }

        /**
         * After an arrow's `=>`, distinguish `{ tag: ..., ... }` object
         * literals (allowed) from `{ const ... return ... }` statement
         * blocks (rejected with a hint). An object literal's first token
         * after '{' is `ident :` or '}'.
         */
        fun looksLikeBlockBody(): Boolean {
            val first = peek(1)
            if (first.kind == TokKind.RBRACE) return false
            if (first.kind != TokKind.IDENT) return false
            if (first.text == "const" || first.text == "return") return true
            return peek(2).kind != TokKind.COLON
        }

        fun parseObjectLit(): FExpr {
            val open = expect(TokKind.LBRACE, "'{' opening the object literal")
            var tag: String? = null
            val fields = mutableListOf<FObjField>()
            while (!at(TokKind.RBRACE)) {
                val name = expectIdent("a field name")
                expect(TokKind.COLON, "':' after field name '${name.text}'")
                if (name.text == "tag" && at(TokKind.STRING)) {
                    tag = advance().text
                } else {
                    fields += FObjField(name = name.text, value = parseExpr(), line = name.line)
                }
                if (at(TokKind.COMMA)) advance() else break
            }
            expect(TokKind.RBRACE, "'}' closing the object literal")
            return FObjectLit(tag = tag, fields = fields, line = open.line)
        }

        fun parseMatch(): FExpr {
            val matchTok = advance() // `match`
            expect(TokKind.LPAREN, "'(' around the match scrutinee")
            val scrutinee = parseExpr()
            expect(TokKind.RPAREN, "')' closing the match scrutinee")
            expect(TokKind.LBRACE, "'{' opening the match arms")
            val arms = mutableListOf<FArm>()
            while (!at(TokKind.RBRACE)) {
                val pattern = parsePattern()
                expect(TokKind.ARROW, "'=>' between the pattern and the arm body")
                val body = parseExpr()
                arms += FArm(pattern = pattern, body = body, line = pattern.line)
                if (at(TokKind.COMMA)) advance() else break
            }
            expect(TokKind.RBRACE, "'}' closing the match arms")
            if (arms.isEmpty()) {
                error(matchTok.line, "a match expression needs at least one arm")
                throw ParseAbort()
            }
            return FMatch(scrutinee = scrutinee, arms = arms, line = matchTok.line)
        }

        fun parsePattern(): FPattern {
            val t = cur()
            return when (t.kind) {
                TokKind.UNDERSCORE -> FWildPattern(advance().line)
                TokKind.INT -> FLitPattern(FIntLit(advance().text.toLong(), t.line), t.line)
                TokKind.STRING -> FLitPattern(FStringLit(advance().text, t.line), t.line)
                TokKind.OP -> {
                    if (t.text == "-" && peek().kind == TokKind.INT) {
                        advance()
                        FLitPattern(FIntLit(-advance().text.toLong(), t.line), t.line)
                    } else {
                        error(t.line, "expected a pattern but found $t")
                        throw ParseAbort()
                    }
                }
                TokKind.IDENT -> {
                    when (t.text) {
                        "true" -> return FLitPattern(FBoolLit(true, advance().line), t.line)
                        "false" -> return FLitPattern(FBoolLit(false, advance().line), t.line)
                    }
                    val name = advance()
                    if (at(TokKind.LPAREN)) {
                        advance()
                        val binderTok = cur()
                        val (binder, isWild) = when (binderTok.kind) {
                            TokKind.UNDERSCORE -> { advance(); null to true }
                            TokKind.IDENT -> advance().text to false
                            else -> {
                                error(binderTok.line, "a constructor pattern binds one name " +
                                    "(or `_`): ${name.text}(payload); found $binderTok")
                                throw ParseAbort()
                            }
                        }
                        expect(TokKind.RPAREN, "')' closing the constructor pattern")
                        FCtorPattern(ctor = name.text, binder = binder, binderIsWild = isWild, line = name.line)
                    } else {
                        FNamePattern(name = name.text, line = name.line)
                    }
                }
                else -> {
                    error(t.line, "expected a pattern but found $t")
                    throw ParseAbort()
                }
            }
        }

        fun parseAttempt(): FExpr {
            val attemptTok = advance() // `attempt`
            expect(TokKind.LBRACE, "'{' opening the attempt body")
            val body = parseExpr()
            expect(TokKind.RBRACE, "'}' closing the attempt body")
            return FAttempt(body = body, line = attemptTok.line)
        }
    }
}
