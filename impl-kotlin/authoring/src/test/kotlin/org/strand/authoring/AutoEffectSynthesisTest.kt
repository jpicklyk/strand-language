package org.strand.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-060 M-4 slice b — opt-in `@auto` effect synthesis.
 *
 * The gate: an `@auto` application compiles to a canonical graph
 * byte-identical to its explicit-EffectDecl counterpart (synthesized
 * declarations ARE the explicit declarations, structurally). The
 * Handler-interaction pin lives in `AutoEffectsHandlerEquivalenceTest`
 * (`:corpus`, where the interpreter is on the classpath).
 */
class AutoEffectSynthesisTest {

    // ------------------------------------------------------------------
    // @auto == explicit (the file-write shape)
    // ------------------------------------------------------------------

    @Test
    fun `auto on a reserved effectful builtin equals the explicit EffectDecl form`() {
        val auto = """
            @v=1 root=w
            p STR "/safe/log"
            d BYT "64617461"
            w APP fsWrite [p d] [] @auto
        """.trimIndent()
        val explicit = """
            @v=1 root=w
            p STR "/safe/log"
            d BYT "64617461"
            wd EFD writeFx [p]
            w APP fsWrite [p d] [] [wd]
        """.trimIndent()
        assertSameCanonicalGraph(auto, explicit, "fsWrite @auto")
    }

    @Test
    fun `auto directly after the arguments list defaults typeArguments to empty`() {
        val auto = """
            @v=1 root=w
            p STR "/safe/log"
            d BYT "64617461"
            w APP fsWrite [p d] @auto
        """.trimIndent()
        val explicit = """
            @v=1 root=w
            p STR "/safe/log"
            d BYT "64617461"
            wd EFD writeFx [p]
            w APP fsWrite [p d] [] [wd]
        """.trimIndent()
        assertSameCanonicalGraph(auto, explicit, "fsWrite @auto (slot-2 spelling)")
    }

    @Test
    fun `auto on a pure callee synthesizes the empty effect-instances list`() {
        val auto = """
            @v=1 root=c
            c APP add [1 2] [] @auto
        """.trimIndent()
        val explicit = """
            @v=1 root=c
            c APP add [1 2]
        """.trimIndent()
        assertSameCanonicalGraph(auto, explicit, "pure callee @auto")
    }

    @Test
    fun `auto on a multi-category callee synthesizes one EffectDecl per category in order`() {
        // netConnect declares connectFx (host, port) with the Q-039
        // projection [ArgRef(0), ArgRef(1)].
        val auto = """
            @v=1 root=s
            host STR "example.com"
            port ILT 8080
            s APP netConnect [host port] [] @auto
        """.trimIndent()
        val explicit = """
            @v=1 root=s
            host STR "example.com"
            port ILT 8080
            cd EFD connectFx [host port]
            s APP netConnect [host port] [] [cd]
        """.trimIndent()
        assertSameCanonicalGraph(auto, explicit, "netConnect @auto")
    }

    @Test
    fun `auto reads projections from a document-declared FN's projection DSL`() {
        val auto = """
            @v=1 root=w
            myFx EFC "Filesystem.Write" [stringT]
            wT FNT [stringT bytesT] intT
            wFn FN "strand-builtin:Fs.Write" wT [myFx] "myFx:0"
            p STR "/safe/log"
            d BYT "64617461"
            w APP wFn [p d] [] @auto
        """.trimIndent()
        val explicit = """
            @v=1 root=w
            myFx EFC "Filesystem.Write" [stringT]
            wT FNT [stringT bytesT] intT
            wFn FN "strand-builtin:Fs.Write" wT [myFx] "myFx:0"
            p STR "/safe/log"
            d BYT "64617461"
            wd EFD myFx [p]
            w APP wFn [p d] [] [wd]
        """.trimIndent()
        assertSameCanonicalGraph(auto, explicit, "declared-FN DSL @auto")
    }

    @Test
    fun `auto composes with an implicit registry-builtin callee`() {
        // Fs.List is table-covered (slice a) and carries readFx with the
        // ArgRef(0) projection; @auto consumes the synthesized FN's
        // projection in the same fixed-point loop.
        val auto = """
            @v=1 root=entries
            dir STR "/safe"
            entries APP Fs.List [dir] @auto
        """.trimIndent()
        val explicit = """
            @v=1 root=entries
            sSelf RS
            sHeadInner PRF "head" stringT
            sTailInner PRF "tail" sSelf
            sConsInner PRD [sHeadInner sTailInner]
            sConsCase SCS "Cons" sConsInner
            sNilCase SCS "Nil" _
            sSum SUM [sConsCase sNilCase]
            strListT RT sSum
            listDirT FNT [stringT] strListT
            listDirFn FN "strand-builtin:Fs.List" listDirT [readFx] "readFx:0"
            dir STR "/safe"
            ld EFD readFx [dir]
            entries APP listDirFn [dir] [] [ld]
        """.trimIndent()
        assertSameCanonicalGraph(auto, explicit, "Fs.List @auto")
    }

    // ------------------------------------------------------------------
    // Gap behavior (never guess)
    // ------------------------------------------------------------------

    @Test
    fun `auto gaps when a parameterized category has no projection`() {
        val text = """
            @v=1 root=w
            myFx EFC "Filesystem.Write" [stringT]
            wT FNT [stringT bytesT] intT
            wFn FN "strand-builtin:Fs.Write" wT [myFx]
            p STR "/safe/log"
            d BYT "64617461"
            w APP wFn [p d] [] @auto
        """.trimIndent()
        val thrown = runCatching { Authoring.compile(text) }.exceptionOrNull()
        val gaps = when (thrown) {
            is AuthoringException -> thrown.elaborationGaps
            null -> Authoring.compile(text).elaborationGaps
            else -> throw thrown
        }
        val gap = gaps.single { it.inferenceCase == "@auto effect-declaration synthesis" }
        assertTrue("'myFx'" in gap.reason && "explicit EffectDecl" in gap.reason) {
            "gap must name the category and the fix: ${gap.reason}"
        }
    }

    @Test
    fun `leftover auto marker surfaces a targeted arg-shape error`() {
        // The callee cannot be resolved at all, so the marker survives to
        // emission; the emitter's EFFECT_LIST slot rejects it with a
        // targeted message instead of a generic list mismatch.
        val text = """
            @v=1 root=w
            w APP nosuchfn [1 2] [] @auto
        """.trimIndent()
        val thrown = runCatching { Authoring.compile(text) }.exceptionOrNull()
        assertTrue(thrown is AuthoringException) { "expected an emit-time failure, got $thrown" }
        val message = (thrown as AuthoringException).errors.joinToString("\n") { it.detail }
        assertTrue("@auto" in message) { "error must mention the unresolved marker: $message" }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun assertSameCanonicalGraph(auto: String, explicit: String, label: String) {
        val autoJson = Authoring.compileToDagJson(auto)
        val explicitJson = Authoring.compileToDagJson(explicit)
        val autoFinal = run {
            val ingest = JsonIngest.parse(autoJson)
            Hasher(ingest.rawStore).finalize(ingest.root)
        }
        val explicitFinal = run {
            val ingest = JsonIngest.parse(explicitJson)
            Hasher(ingest.rawStore).finalize(ingest.root)
        }
        val autoHash = autoFinal.nodeIdToHash.getValue(autoFinal.root)
        val explicitHash = explicitFinal.nodeIdToHash.getValue(explicitFinal.root)
        assertEquals(explicitHash, autoHash) {
            "$label: @auto root hash $autoHash differs from the explicit form's $explicitHash\n" +
                "@auto dag-json:\n$autoJson\nexplicit dag-json:\n$explicitJson"
        }
        val autoVerify = Verifier(autoFinal.store, autoFinal.hashToNodeId).verify(autoFinal.root)
        val explicitVerify = Verifier(explicitFinal.store, explicitFinal.hashToNodeId)
            .verify(explicitFinal.root)
        assertTrue(explicitVerify is VerifyResult.Ok) {
            "$label: explicit form failed verification: $explicitVerify"
        }
        assertTrue(autoVerify is VerifyResult.Ok) {
            "$label: @auto form failed verification: $autoVerify"
        }
    }
}

