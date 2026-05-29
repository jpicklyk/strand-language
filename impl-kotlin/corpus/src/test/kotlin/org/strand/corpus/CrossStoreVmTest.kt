package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.strand.bytecode.Lowerer
import org.strand.core.JsonIngest
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.hashing.LocalProgramResolver
import org.strand.hashing.federated
import org.strand.interpreter.Value
import org.strand.vm.Vm

/**
 * Q-043 step 3a — the bytecode Lowerer + VM across a store boundary.
 *
 * Lowering precedes execution, so a cross-store NodeRef target must be fetched
 * and admitted into the shared store *before* its sub-chunk can be lowered. The
 * [Lowerer]'s `resolveTarget` callback (wired to `FederatedProgram::fetchAndAdmit`)
 * closes that gap. corpus 76 (`app.json` references `inc` by content hash and
 * computes `inc(inc(40))`) is the fixture; here it runs through the VM rather than
 * the tree-walking interpreter that [CorpusFederationTest] exercises.
 */
class CrossStoreVmTest {

    private fun load(resource: String): String =
        CrossStoreVmTest::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.readText()
            ?: error("missing resource $resource")

    private fun finalize(resource: String): FinalizedProgram {
        val ingest = JsonIngest.parse(load(resource))
        return Hasher(ingest.rawStore).finalize(ingest.root)
    }

    @Test
    fun `corpus 76 - the Lowerer fetches a cross-store target and the VM runs it`() {
        val lib = finalize("/corpus/76-multi-store-composition/lib.json")
        // Fresh federation, deliberately NOT pre-verified: the Lowerer itself
        // must fetch + admit `inc` through the resolver before it can lower the
        // target chunk. (The verifier would otherwise admit it first, masking
        // the Lowerer's own resolveTarget path.)
        val app = finalize("/corpus/76-multi-store-composition/app.json")
            .federated(LocalProgramResolver(lib))

        val table = Lowerer(app.store, app.hashToNodeId, app::fetchAndAdmit).lower(app.root)
        val value = Vm(table).run()
        assertEquals(Value.IntV(42), value, "inc(inc(40)) lowered + run on the VM across the store boundary = 42")
    }

    @Test
    fun `corpus 76 - without a resolver the Lowerer cannot resolve the cross-store target`() {
        // Single-store path preserved bit-for-bit: a `targetHash` NodeRef whose
        // target is held only by a peer (no resolveTarget callback) is the
        // original hard error rather than a silent miss.
        val app = finalize("/corpus/76-multi-store-composition/app.json")
        assertThrows(IllegalStateException::class.java) {
            Lowerer(app.store, app.hashToNodeId).lower(app.root)
        }
    }
}
