package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.authoring.Authoring
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-060 M-4 slice b — the `@auto` / Handler interaction pin.
 *
 * Semantics pinned here: synthesized EffectDecls behave identically to
 * the equivalent explicit declarations at a Handler-intercepted call
 * site. This holds by construction — N-043 Handler interception keys on
 * the callee's DECLARED effect categories (the handler replaces the
 * effectful call wholesale), never on the call site's effect-instances
 * list, and the `@auto` synthesis produces EffectDecls structurally
 * identical to the explicit ones — so the two forms compile to the same
 * canonical graph (byte identity) and therefore evaluate identically.
 * Both halves are asserted: hash equality AND interpreter-result
 * equality under an empty capability context (the handler covers the
 * intercepted category, so no Filesystem.Write grant is needed and no
 * file is touched).
 */
class AutoEffectsHandlerEquivalenceTest {

    private val autoForm = """
        @v=1 root=h
        p STR "/safe/log"
        d BYT "64617461"
        w APP fsWrite [p d] [] @auto
        mock LAM [mp:stringT md:bytesT] 42
        h H writeFx mock w
    """.trimIndent()

    private val explicitForm = """
        @v=1 root=h
        p STR "/safe/log"
        d BYT "64617461"
        wd EFD writeFx [p]
        w APP fsWrite [p d] [] [wd]
        mock LAM [mp:stringT md:bytesT] 42
        h H writeFx mock w
    """.trimIndent()

    @Test
    fun `auto under Handler interception compiles byte-identical to the explicit form`() {
        val autoFinal = finalize(autoForm)
        val explicitFinal = finalize(explicitForm)
        assertEquals(
            explicitFinal.nodeIdToHash.getValue(explicitFinal.root),
            autoFinal.nodeIdToHash.getValue(autoFinal.root),
        ) { "@auto under a Handler must hash identically to the explicit-EffectDecl form" }
    }

    @Test
    fun `auto under Handler interception evaluates identically to the explicit form`() {
        val autoResult = evaluate(autoForm)
        val explicitResult = evaluate(explicitForm)
        assertEquals(explicitResult, autoResult) {
            "@auto and explicit forms must produce the same value under Handler interception"
        }
        // The handler replaces the fsWrite call wholesale; its handle
        // returns 42, which is the program's value. No capability was
        // granted and no filesystem write happened.
        assertEquals(Value.IntV(42), autoResult)
    }

    private fun finalize(layerA: String): org.strand.hashing.FinalizedProgram {
        val json = Authoring.compileToDagJson(layerA)
        val ingest = JsonIngest.parse(json)
        return Hasher(ingest.rawStore).finalize(ingest.root)
    }

    private fun evaluate(layerA: String): Value {
        val finalized = finalize(layerA)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "verification failed: $verify" }
        return Interpreter(finalized.store, finalized.hashToNodeId)
            .eval(finalized.root, emptySet<NodeId>())
    }
}
