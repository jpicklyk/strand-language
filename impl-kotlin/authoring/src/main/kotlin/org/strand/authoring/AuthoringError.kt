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

    data class UnknownCode(
        override val line: Int,
        val code: String,
    ) : AuthoringError() {
        override val detail: String
            get() = "unknown Layer A node code '$code' — see LayerAGrammar.codes for the supported set"
    }

    data class ArityMismatch(
        override val line: Int,
        val code: String,
        val expected: IntRange,
        val actual: Int,
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
    ) : AuthoringError() {
        override val detail: String
            get() = "code '$code' at position $position expected $expectedKind but got $actualKind"
    }

    data class TokenError(
        override val line: Int,
        override val detail: String,
    ) : AuthoringError()

    data class HeaderError(
        override val line: Int,
        override val detail: String,
    ) : AuthoringError()

    data class DuplicateNodeId(
        override val line: Int,
        val id: String,
    ) : AuthoringError() {
        override val detail: String
            get() = "duplicate node id '$id'"
    }

    data class UnknownRoot(
        override val line: Int,
        val rootId: String,
    ) : AuthoringError() {
        override val detail: String
            get() = "root '$rootId' is not declared in the document"
    }
}

class AuthoringException(val errors: List<AuthoringError>) :
    RuntimeException("Layer A authoring failed:\n" + errors.joinToString("\n") { "  line ${it.line}: ${it.detail}" })
