package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.hashing.LocalProgramResolver
import org.strand.hashing.federated
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-043 step 3a — cross-store composition, end-to-end over real corpus files.
 *
 * corpus 76 ships a library (`lib.json` exporting `inc = (x: Int) -> x + 1`)
 * and an application (`app.json`) that references `inc` by its content hash via
 * the `targetHash` NodeRef ingest form and computes `inc(inc(40))`. Wiring the
 * library as a `LocalProgramResolver` peer, the application verifies and runs
 * to 42 — the resolver fetches and re-bases `inc` (a Lambda with a bound
 * ParameterDecl and a builtin call) into the app store, and the repeated
 * reference resolves once and is reused.
 */
class CorpusFederationTest {

    private fun load(resource: String): String =
        CorpusFederationTest::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.readText()
            ?: error("missing resource $resource")

    private fun finalize(resource: String): FinalizedProgram {
        val ingest = JsonIngest.parse(load(resource))
        return Hasher(ingest.rawStore).finalize(ingest.root)
    }

    @Test
    fun `corpus 76 - app composes a function exported by a peer library`() {
        val lib = finalize("/corpus/76-multi-store-composition/lib.json")
        val incHash = lib.nodeIdToHash.getValue(lib.root)

        val app = finalize("/corpus/76-multi-store-composition/app.json")
            .federated(LocalProgramResolver(lib))

        val verify = Verifier(app.store, app.hashToNodeId, app::fetchAndAdmit).verify(app.root)
        assertTrue(verify is VerifyResult.Ok,
            "app must verify against the peer library; lib inc export hash = $incHash; got $verify")

        val result = Interpreter(app.store, app.hashToNodeId, resolveTarget = app::fetchAndAdmit)
            .eval(app.root)
        assertEquals(Value.IntV(42), result, "inc(inc(40)) across the store boundary = 42")
    }
}
