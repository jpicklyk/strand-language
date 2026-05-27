package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.ErrorVerbosity
import org.strand.core.EvaluationLimits
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Primitive

/**
 * Q-042: integration coverage for the [ErrorVerbosity] enum and its
 * effect on `InterpretError.IoFailure` materialisation. Scenarios 6 and
 * 7 from § 7 of `proposals/implemented/credential-isolation.md`:
 *
 *  6. `ErrorVerbosity.Full` skips scrubbing — the agent-visible detail
 *     is the original interpolated text, including any registered
 *     credential value that appeared in it.
 *  7. `ErrorVerbosity.RedactedWithKindOnly` strips the detail entirely;
 *     the agent sees only the structured `kind` discriminator.
 *
 * The default `ErrorVerbosity.Redacted` path is covered by the per-
 * provider tests (where a 401 echo body containing the API key is
 * verified to be scrubbed) and by [CredentialScrubberTest].
 *
 * Tests construct a minimal `ForeignNode` graph that calls a throwing
 * builtin — the builtin's IoFailure is propagated through the
 * interpreter's translateIoFailure path and surfaces as
 * `InterpretError.IoFailure` with verbosity-dependent `detail`.
 */
class ErrorVerbosityTest {

    private val savedCredentialProvider = Builtins.credentialProvider

    @BeforeEach
    fun setUp() {
        CredentialScrubber.resetForTesting()
        // Pre-register a credential so the runtime IoFailure has
        // something to scrub when verbosity == Redacted.
        CredentialScrubber.register(Credential(
            "sk-verbosity-test-1234abcd",
            "anthropic",
            "api_key",
        ))
    }

    @AfterEach
    fun tearDown() {
        Builtins.credentialProvider = savedCredentialProvider
        CredentialScrubber.resetForTesting()
    }

    // -- Scenario 6: Full skips scrubbing --

    @Test
    fun `ErrorVerbosity_Full surfaces the raw unscrubbed detail`() {
        val ie = runProgramExpectingIoFailure(
            verbosity = ErrorVerbosity.Full,
            ioFailure = IoFailure(
                "upstream-echo",
                "status 401: invalid key sk-verbosity-test-1234abcd",
            ),
        )
        assertEquals("upstream-echo", ie.kind)
        // Full reads `unscrubbedDetail` → the credential is present
        // verbatim.
        assertTrue("sk-verbosity-test-1234abcd" in ie.detail,
            "Full should NOT scrub the credential value")
        assertEquals(
            "status 401: invalid key sk-verbosity-test-1234abcd",
            ie.detail,
        )
    }

    // -- Scenario 7: RedactedWithKindOnly strips detail entirely --

    @Test
    fun `ErrorVerbosity_RedactedWithKindOnly strips detail entirely`() {
        val ie = runProgramExpectingIoFailure(
            verbosity = ErrorVerbosity.RedactedWithKindOnly,
            ioFailure = IoFailure(
                "upstream-echo",
                "status 401: invalid key sk-verbosity-test-1234abcd",
            ),
        )
        assertEquals("upstream-echo", ie.kind)
        assertEquals("(detail suppressed)", ie.detail)
        // The raw credential is unreachable.
        assertFalse("sk-verbosity-test-1234abcd" in ie.detail)
    }

    // -- Default path: Redacted (sanity check) --

    @Test
    fun `default ErrorVerbosity_Redacted scrubs the registered credential`() {
        val ie = runProgramExpectingIoFailure(
            verbosity = ErrorVerbosity.Redacted,
            ioFailure = IoFailure(
                "upstream-echo",
                "status 401: invalid key sk-verbosity-test-1234abcd",
            ),
        )
        assertEquals("upstream-echo", ie.kind)
        assertEquals(
            "status 401: invalid key [REDACTED:anthropic:api_key]",
            ie.detail,
        )
        assertFalse("sk-verbosity-test-1234abcd" in ie.detail)
    }

    @Test
    fun `EvaluationLimits PERMISSIVE preserves Redacted (does not relax verbosity)`() {
        // PERMISSIVE relaxes resource caps but NOT credential leakage —
        // a benchmark interacting with credentials should not leak.
        assertEquals(ErrorVerbosity.Redacted, EvaluationLimits.PERMISSIVE.errorVerbosity)
    }

    @Test
    fun `EvaluationLimits DEFAULTS sets Redacted`() {
        assertEquals(ErrorVerbosity.Redacted, EvaluationLimits.DEFAULTS.errorVerbosity)
    }

    // -- Test infrastructure: minimal program that calls a throwing builtin --

    /**
     * Build a minimal verified graph whose root Application calls a
     * test-only ForeignNode that throws the supplied [IoFailure]. Run
     * it under the given [verbosity] and return the materialised
     * [InterpretError.IoFailure] (asserts non-null and the right
     * subtype).
     *
     * The test-only ForeignNode is wired through a `ForeignDispatcher`
     * to avoid registering a permanent builtin in [Builtins.lookup] —
     * the dispatcher intercepts the call site directly.
     */
    private fun runProgramExpectingIoFailure(
        verbosity: ErrorVerbosity,
        ioFailure: IoFailure,
    ): InterpretError.IoFailure {
        // Build the graph entirely in memory: a root Application of a
        // 0-arg ForeignNode "test:throw" returning Unit (signature ()
        // -> Unit).
        val store = NodeStore()

        // Build the FunctionType `() -> Unit`.
        val unitTypeId = store.add(Node.PrimitiveType(kind = Primitive.Unit))
        val fnTypeId = store.add(Node.FunctionType(
            parameters = emptyList(),
            result = unitTypeId,
        ))

        // ForeignNode "test:throw" with no effects.
        val foreignId = store.add(Node.ForeignNode(
            target = "test:throw",
            foreignType = fnTypeId,
            effects = emptyList(),
        ))

        // Application of the ForeignNode with no arguments.
        val rootId = store.add(Node.Application(
            function = foreignId,
            arguments = emptyList(),
        ))

        // Build a foreign dispatcher that intercepts our test target
        // and throws the supplied IoFailure.
        val dispatcher = object : ForeignDispatcher {
            override fun dispatch(target: String, args: List<Value>): Value? {
                if (target == "test:throw") throw ioFailure
                return null
            }
        }

        val interpreter = Interpreter(store, emptyMap(), dispatcher)
        val ex = assertThrows<InterpretException> {
            interpreter.eval(
                root = rootId,
                capabilities = CapabilitySet.EMPTY,
                limits = EvaluationLimits.DEFAULTS.copy(errorVerbosity = verbosity),
            )
        }
        val err = ex.error
        assertTrue(err is InterpretError.IoFailure, "expected IoFailure, got $err")
        return err as InterpretError.IoFailure
    }
}
