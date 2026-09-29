package org.strand.authoring

/**
 * Structured Layer A authoring error. Two phases produce these:
 *
 *  * [LayerAParser] — lexical / syntactic errors during tokenization
 *    or per-line parsing.
 *  * [DagJsonEmitter] — per-code arg-shape errors when the parsed line
 *    does not match the code's [LayerAGrammar] schema.
 *
 * The parser favors keeping going past a single error so the user sees
 * multiple problems at once; the emitter fails fast on the first shape
 * mismatch since a downstream JSON ingest would not be coherent with
 * partial output.
 */
sealed class AuthoringError {
    abstract val line: Int
    abstract val detail: String

    /**
     * 1-based column within the source line, when the producing phase knows
     * the offset (lexer and per-line parser errors). Null when only the line
     * is known (emitter-phase errors on already-tokenized args, dag-json
     * translation). Drivers print `line N, col M:` when present.
     */
    open val column: Int? get() = null

    companion object {
        /** `line N` or `line N, col M` (no trailing colon). */
        fun location(e: AuthoringError): String =
            if (e.column != null) "line ${e.line}, col ${e.column}" else "line ${e.line}"
    }

    data class UnknownCode(
        override val line: Int,
        val code: String,
        override val column: Int? = null,
    ) : AuthoringError() {
        override val detail: String
            get() = "unknown Layer A node code '$code' — see LayerAGrammar.codes for the supported set"
    }

    data class ArityMismatch(
        override val line: Int,
        val code: String,
        val expected: IntRange,
        val actual: Int,
        override val column: Int? = null,
    ) : AuthoringError() {
        override val detail: String
            get() = "code '$code' expects ${expected.first}..${expected.last} positional arguments but got $actual"
    }

    data class ArgShapeMismatch(
        override val line: Int,
        val code: String,
        val position: Int,
        val expectedKind: String,
        val actualKind: String,
        override val column: Int? = null,
    ) : AuthoringError() {
        override val detail: String
            get() = "code '$code' at position $position expected $expectedKind but got $actualKind"
    }

    data class TokenError(
        override val line: Int,
        override val detail: String,
        override val column: Int? = null,
    ) : AuthoringError()

    /**
     * Q-061 Layer F: a construct the familiar dialect deliberately lacks
     * (mutation, loops, classes, `any`, exceptions, arbitrary imports —
     * proposal § 3). The [hint] names the dialect construct to use
     * instead, so an agent writing real-TypeScript habits gets a
     * corrective rather than silent acceptance or a bare syntax error.
     */
    data class DialectViolation(
        override val line: Int,
        val construct: String,
        val hint: String,
    ) : AuthoringError() {
        override val detail: String
            get() = "the familiar dialect has no $construct — $hint"
    }

    data class HeaderError(
        override val line: Int,
        override val detail: String,
    ) : AuthoringError()

    data class DuplicateNodeId(
        override val line: Int,
        val id: String,
        override val column: Int? = null,
    ) : AuthoringError() {
        override val detail: String
            get() = "duplicate node id '$id'"
    }

    /**
     * A user-declared node id starts with `__`, the prefix reserved for
     * compiler-minted ids (`__lit<n>`, `__var<n>`, `__anon<line>`, `__if<n>_*`,
     * `__when<n>_*`, `__res<n>_*`, `__expr<n>`, ...). Accepting such ids would
     * let a user node collide with, or hijack (`@last` tracks `__anon*`), a
     * synthesized node.
     */
    data class ReservedNodeId(
        override val line: Int,
        val id: String,
        override val column: Int? = null,
    ) : AuthoringError() {
        override val detail: String
            get() = "node id '$id' is invalid: the '__' prefix is reserved for the compiler " +
                "(synthesized literals, variable references, anonymous nodes, sugar expansions)"
    }

    /**
     * The emitter minted a synthesized node id that already exists (as a user
     * node or an earlier synthesized node). Never expected once
     * [ReservedNodeId] is enforced at parse time; retained as a hard assertion
     * so a collision fails loudly instead of silently overwriting a node.
     */
    data class SynthesizedIdCollision(
        override val line: Int,
        val id: String,
    ) : AuthoringError() {
        override val detail: String
            get() = "internal error: compiler-synthesized node id '$id' collides with an existing node id " +
                "(ids with the '__' prefix are reserved for the compiler)"
    }

    /** A NaN or infinite float reached the renderer; Layer A has no literal for it. */
    data class UnrenderableFloat(
        override val line: Int,
        val value: String,
    ) : AuthoringError() {
        override val detail: String
            get() = "float value $value has no Layer A literal (only finite floats can be rendered)"
    }

    data class UnknownRoot(
        override val line: Int,
        val rootId: String,
    ) : AuthoringError() {
        override val detail: String
            get() = "root '$rootId' is not declared in the document"
    }

    /**
     * The [Elaborator]'s fixed-point inference loop did not reach a stable
     * document within its iteration bound. A converging document produces
     * a no-change pass and returns early; exhausting the bound while the
     * last pass still rewrites the document means the inference passes are
     * cycling (or a genuinely large document needs more iterations than
     * the defensive bound allows). Rather than emit a possibly
     * half-elaborated document silently, elaboration fails structurally so
     * the caller sees a definite non-convergence rather than a mysterious
     * downstream verifier error. Synthesized diagnostic (no source line).
     */
    data class ElaborationDidNotConverge(
        val iterations: Int,
    ) : AuthoringError() {
        override val line: Int
            get() = 0
        override val detail: String
            get() = "elaboration did not converge within $iterations fixed-point " +
                "iterations; the document may be too large for the iteration bound or " +
                "the inference passes are cycling"
    }
}

/**
 * Non-fatal authoring diagnostic. Unlike [AuthoringError] a warning never
 * blocks emission; drivers print it on the same channel as verifier warnings.
 */
sealed class AuthoringWarning {
    abstract val line: Int
    abstract val message: String

    /**
     * A user node id equals a prelude reserved name (`intT`, `ok`, ...). By the
     * "local declarations win" rule the user node silently replaces the prelude
     * node, and every reserved node that depends on it then resolves to the
     * user's declaration. Legal, but almost always unintended.
     */
    data class ShadowedReservedName(
        override val line: Int,
        val name: String,
    ) : AuthoringWarning() {
        override val message: String
            get() = "node id '$name' shadows the prelude reserved name '$name'; the local declaration " +
                "wins, and every reserved node that references '$name' now resolves to it"
    }
}

class AuthoringException(
    val errors: List<AuthoringError>,
    /**
     * Inference cases the [Elaborator] attempted but could not resolve
     * before the failure (Q-034 gap policy). Populated when the failure
     * occurred after elaboration ran (the [DagJsonEmitter] phase); empty
     * for parse-phase failures where elaboration never executed. Drivers
     * print these as `elaboration note:` lines alongside the errors so
     * the agent can see which omitted annotation caused the downstream
     * failure.
     */
    val elaborationGaps: List<ElaborationGap> = emptyList(),
) : RuntimeException("Layer A authoring failed:\n" + errors.joinToString("\n") { "  ${AuthoringError.location(it)}: ${it.detail}" })
