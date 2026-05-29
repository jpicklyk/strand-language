package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.hashing.LocalProgramResolver
import org.strand.hashing.NameRegistry
import org.strand.hashing.NodeResolver
import org.strand.hashing.NodeResolverIntegrityViolation
import org.strand.hashing.SubgraphFetch
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

    @Test
    fun `corpus 77 - the name registry resolves stdlib inc to corpus 76's inc hash`() {
        // corpus 77 is the off-graph discovery layer for corpus 76: a NameRegistry
        // JSON binding the human name "stdlib/inc" to the content hash of the inc
        // export. The binding must track the live hash, so the test recomputes it
        // from lib.json rather than trusting the literal in the registry file.
        val lib = finalize("/corpus/76-multi-store-composition/lib.json")
        val incHash = lib.nodeIdToHash.getValue(lib.root)

        val registry = NameRegistry.fromJson(load("/corpus/77-name-registry-resolution/registry.json"))
        assertEquals(incHash, registry.resolve("stdlib/inc"),
            "the registry entry must resolve to corpus 76's inc export hash")
        assertEquals(null, registry.resolve("stdlib/missing"), "an unbound name resolves to null")
    }

    @Test
    fun `corpus 78 - an adversarial resolver returning mismatched content is rejected`() {
        // corpus 78 is the integrity-violation case. app.json references inc by
        // content hash; a BadResolver claims that hash but returns IntLit(999).
        // The root-hash claim check passes (rootHash == requested), so the trust
        // comes entirely from the post-admission Merkle re-hash, which detects
        // that IntLit(999) does not hash to the requested hash and halts the
        // session. A *finalized* peer cannot lie this way — its content always
        // hashes to its own claimed hash — so the adversary is necessarily a
        // hand-built resolver rather than static corpus JSON; corpus 78 is
        // therefore exercised programmatically (see corpus/README.md).
        val app = finalize("/corpus/76-multi-store-composition/app.json")
        val liar = object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch =
                SubgraphFetch(
                    rootHash = hash,
                    rootId = NodeId(0),
                    nodes = mapOf(NodeId(0) to Node.IntLit(999)),
                    nodeIdToHash = mapOf(NodeId(0) to hash),
                )
        }
        val federated = app.federated(liar)
        assertThrows(NodeResolverIntegrityViolation::class.java) {
            Verifier(federated.store, federated.hashToNodeId, federated::fetchAndAdmit)
                .verify(federated.root)
        }
    }
}
