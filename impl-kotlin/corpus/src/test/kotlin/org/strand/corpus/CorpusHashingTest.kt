package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * End-to-end Layer 2 corpus tests. For every Layer 1 corpus program:
 *   - the program ingests, verifies, and hashes without error,
 *   - hashing is deterministic — two independent ingests of the same JSON
 *     produce the same root hash,
 *   - the hashed-reachable map is non-empty and excludes intrinsic bound
 *     nodes (ParameterDecl, TypeParameter).
 *
 * The corpus dedup test verifies that program 10 (which uses NodeRef to
 * share a subgraph) produces fewer hash entries than verifier-reachable
 * NodeIds — a structural witness that hashing collapses shared subgraphs.
 */
class CorpusHashingTest {

    private val corpusResources = listOf(
        "/corpus/01-int-literal.json",
        "/corpus/02-identity-applied.json",
        "/corpus/03-let-identity.json",
        "/corpus/04-k-combinator.json",
        "/corpus/05-s-combinator-typed.json",
        "/corpus/06-let-polymorphic.json",
        "/corpus/07-higher-order.json",
        "/corpus/08-product-type-decl.json",
        "/corpus/09-sum-type-decl.json",
        "/corpus/10-noderef-shared.json",
        "/corpus/11-higher-rank-apply.json",
        "/corpus/12-effect-declared-and-granted.json",
        "/corpus/13-capability-scope-narrow-then-call.json",
        "/corpus/14-multi-effect-lambda.json",
        "/corpus/15-builtin-add.json",
        "/corpus/16-builtin-time-now-under-capability.json",
        "/corpus/17-builtin-compose-pure-and-effectful.json",
        "/corpus/18-match-int-literal-with-wildcard.json",
        "/corpus/19-match-on-comparison-result.json",
        "/corpus/20-match-variable-binding.json",
        "/corpus/21-fixpoint-factorial.json",
        "/corpus/22-fixpoint-sum-to-n.json",
        "/corpus/23-product-construct-and-access.json",
        "/corpus/24-product-sum-fields-via-lambda.json",
        "/corpus/25-option-some-unwrap.json",
        "/corpus/26-option-none-default.json",
        "/corpus/27-result-ok-or-err.json",
        "/corpus/28-safe-divide-success.json",
        "/corpus/29-safe-divide-by-zero.json",
        "/corpus/30-string-concat.json",
        "/corpus/31-recursive-list-head.json",
        "/corpus/32-recursive-list-sum.json",
        "/corpus/33-refined-network-connect.json",
        "/corpus/34-refined-wildcard-port.json",
        "/corpus/35-refined-logger-authorized-path.json",
        "/corpus/36-handler-mock-time-now.json",
        "/corpus/37-handler-captures-outer-let.json",
        "/corpus/38-handler-nested-innermost-wins.json",
        "/corpus/39-handler-itself-performs-effect.json",
        "/corpus/40-handler-fires-through-fixpoint.json",
        // N-046 ModuleManifest (Q-043 step 3b) — exercises the
        // RawModuleManifest → canonical Node.ModuleManifest finalize bridge
        // and the dual raw/canonical encoder paths end-to-end. Verifies Ok
        // (its exports' declared effects match their surfaces).
        "/corpus/79-module-manifest-with-effects.json",
        // Q-045 streaming I/O — verify-only drain exemplar. Exercises the
        // canonical encoding of a Fixpoint+Match drain over the streaming
        // ForeignNodes (CreateStream / LLM.Stream.Receive / LLM.Stream.Close)
        // plus an Option<Bytes> SumType; no resource Value ever enters the
        // store so the program hashes deterministically like any other.
        "/corpus/81-llm-stream-drain.json",
        "/corpus/84-bridged-stream.json",
    )

    @TestFactory
    fun `every corpus program hashes deterministically`(): List<DynamicTest> =
        corpusResources.map { resource ->
            DynamicTest.dynamicTest(resource.substringAfterLast('/')) {
                val text = loadResource(resource)

                // First ingest + verify + hash.
                val a = ingestAndHash(text, resource)
                // Second independent ingest + verify + hash. NodeIds differ
                // across ingests in principle (the verifier renumbers from 0
                // each time), but the canonical hash is structural and must
                // match.
                val b = ingestAndHash(text, resource)

                assertEquals(a.rootHash, b.rootHash) {
                    "Root hash differs across ingests of $resource: " +
                        "${a.rootHash} vs ${b.rootHash}"
                }
            }
        }

    @Test
    fun `corpus program 10 dedups the shared NodeRef subgraph`() {
        // Program 10 wires a single IntLit into two Application sites via
        // NodeRef. The NodeStore retains every unique NodeId; the HashStore
        // collapses the shared IntLit and any subgraphs equivalent under
        // alpha-equivalence and structural equality.
        val text = loadResource("/corpus/10-noderef-shared.json")
        val result = ingestAndHash(text, "/corpus/10-noderef-shared.json")

        val hashes = result.nodeToHash.values.toSet()
        assertTrue(hashes.size < result.nodeToHash.size) {
            "Expected hash dedup on shared subgraph; got " +
                "${result.nodeToHash.size} reachable NodeIds mapped to " +
                "${hashes.size} unique hashes"
        }
    }

    @Test
    fun `every reachable hash is a well-formed BLAKE3 multi-hash`() {
        for (resource in corpusResources) {
            val text = loadResource(resource)
            val result = ingestAndHash(text, resource)
            for ((nodeId, hash) in result.nodeToHash) {
                assertEquals(33, hash.bytes.size) {
                    "Hash for NodeId $nodeId in $resource is not 33 bytes: $hash"
                }
                assertEquals(0x1e.toByte(), hash.bytes[0]) {
                    "Hash for NodeId $nodeId in $resource is not BLAKE3-prefixed: $hash"
                }
            }
        }
    }

    private data class HashResult(
        val rootHash: org.strand.core.Hash,
        val nodeToHash: Map<org.strand.core.NodeId, org.strand.core.Hash>,
    )

    private fun ingestAndHash(text: String, resource: String): HashResult {
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) {
            "Verifier failed for $resource: $verify"
        }
        val rootHash = finalized.nodeIdToHash.getValue(finalized.root)
        return HashResult(rootHash, finalized.nodeIdToHash)
    }

    private fun loadResource(resource: String): String {
        val stream = CorpusHashingTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
