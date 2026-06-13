package org.strand.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.NetPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.SandboxViolationKind
import org.strand.interpreter.StaticCredentialProvider
import org.strand.interpreter.Value
import java.util.concurrent.CyclicBarrier

/**
 * Q-054 follow-up ACCEPTANCE TEST: two (or more) [StrandRuntime] instances
 * executing effectful programs *genuinely concurrently* in one JVM under
 * different [HostPolicy] values, with no cross-contamination. This is the
 * property the whole follow-up exists to provide — the property the facade
 * (Q-054 step 1) could not provide because it installed the policy onto
 * process-global singletons for the duration of each run.
 *
 * Each test forces real overlap: the two runs are launched as parallel
 * coroutines on [Dispatchers.Default] and rendezvous on a [CyclicBarrier]
 * *inside* the effectful builtin, so both threads are provably executing the
 * effectful path simultaneously when the policy-sourced value is read. A
 * sequential implementation (or a shared-singleton install) would either
 * deadlock on the barrier or read the wrong tenant's value.
 */
class ConcurrentMultiTenantIsolationTest {

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    private fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    private fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    // ----------------------------------------------------------------------
    // 1. Different FixedClock values, read concurrently — each program sees
    //    its own clock. The barrier inside Time.Now's clock guarantees both
    //    threads are inside the builtin at the same instant.
    // ----------------------------------------------------------------------

    /** A Clock whose nowMillis() blocks on a shared barrier before returning [time], forcing overlap. */
    private class BarrierClock(private val time: Long, private val barrier: CyclicBarrier) : Builtins.Clock {
        override fun nowMillis(): Long {
            // Both tenants' Time.Now calls rendezvous here: neither returns
            // until both have entered the effectful builtin. If the two runs
            // shared a single installed clock, one tenant would read the
            // other's time; if they ran sequentially, this would deadlock.
            barrier.await()
            return time
        }
        override fun sleep(millis: Long) = Unit
    }

    @Test
    fun `two concurrent runs each read their own clock`() {
        val prog = image(TIME_NOW)
        val timeFx = nameMap(TIME_NOW).getValue("timeFx")
        val caps = CapabilitySet.ofCategories(setOf(timeFx))
        val barrier = CyclicBarrier(2)

        val rtA = StrandRuntime(HostPolicy.OPEN.copy(clock = BarrierClock(1_000L, barrier)))
        val rtB = StrandRuntime(HostPolicy.OPEN.copy(clock = BarrierClock(2_000L, barrier)))

        val (a, b) = runBlocking {
            withContext(Dispatchers.Default) {
                val da = async { rtA.run(prog, caps) }
                val db = async { rtB.run(prog, caps) }
                awaitAll(da, db)
            }
        }

        val va = (assertInstanceOf(RunOutcome.Ok::class.java, a)).value
        val vb = (assertInstanceOf(RunOutcome.Ok::class.java, b)).value
        // Each run read ITS OWN clock despite executing Time.Now concurrently.
        assertEquals(Value.IntV(1_000L), va)
        assertEquals(Value.IntV(2_000L), vb)
    }

    // ----------------------------------------------------------------------
    // 2. SECURE vs OPEN sandbox, concurrently — the SECURE tenant rejects an
    //    fs-escape that the OPEN tenant permits, with both inside Fs.Write at
    //    the same time. The barrier sits in a custom name resolver-free seam:
    //    we rendezvous via a clock the write program also touches is awkward,
    //    so we instead force overlap with a barrier in a no-op nondeterministic
    //    builtin the program calls before the write.
    // ----------------------------------------------------------------------

    @Test
    fun `concurrent SECURE and OPEN runs isolate an fs-escape`(
        @org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path,
    ) {
        val barrier = CyclicBarrier(2)
        // A test builtin both programs call first; it blocks on the barrier so
        // the two runs are provably overlapping when each reaches Fs.Write.
        Builtins.installTestBuiltin(
            "strand-builtin:Test.Rendezvous",
            effectful = false,
            determinism = Builtins.Determinism.Deterministic,
            fn = Builtins.Fn { _, args -> barrier.await(); args[0] },
        )

        val prog = image(RENDEZVOUS_THEN_WRITE)
        val writeFx = nameMap(RENDEZVOUS_THEN_WRITE).getValue("writeFx")
        val caps = CapabilitySet(
            mapOf(
                writeFx to listOf(
                    CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("../escape.txt")))),
                ),
            ),
        )

        // SECURE: workspace rooted at tmp, escape denied.
        val secureRt = StrandRuntime(
            HostPolicy.OPEN.copy(
                sandbox = SandboxPolicy(
                    fs = FsPolicy(workspaceRoot = tmp, escape = EscapePolicy.Deny),
                    net = NetPolicy(defaultDeny = false),
                ),
            ),
        )
        // OPEN: escape allowed, workspace one level down so ../escape.txt lands
        // inside the temp dir (cleaned automatically).
        val openWs = tmp.resolve("ws").also { java.nio.file.Files.createDirectories(it) }
        val openRt = StrandRuntime(
            HostPolicy.OPEN.copy(
                sandbox = SandboxPolicy(
                    fs = FsPolicy(workspaceRoot = openWs, escape = EscapePolicy.Allow),
                    net = NetPolicy(defaultDeny = false),
                ),
            ),
        )

        val (secureResult, openResult) = runBlocking {
            withContext(Dispatchers.Default) {
                val ds = async { runCatching { secureRt.run(prog, caps) } }
                val dop = async { runCatching { openRt.run(prog, caps) } }
                awaitAll(ds, dop)
            }
        }

        // The SECURE tenant rejected the escape, concurrently with the OPEN
        // tenant permitting it.
        val secureEx = secureResult.exceptionOrNull() as? InterpretException
            ?: error("SECURE run should have thrown; got ${secureResult.getOrNull()}")
        val sv = secureEx.error as? InterpretError.SandboxViolation
            ?: error("expected SandboxViolation under SECURE, got ${secureEx.error}")
        assertEquals(SandboxViolationKind.FsPathEscape, sv.kind)

        // The OPEN tenant succeeded; the write landed inside the temp dir.
        val openOk = assertInstanceOf(RunOutcome.Ok::class.java, openResult.getOrThrow())
        assertEquals(Value.IntV(4L), openOk.value)  // 4 bytes ("deadbeef" hex = 4 bytes)
        assertTrue(java.nio.file.Files.exists(tmp.resolve("escape.txt")))
    }

    // ----------------------------------------------------------------------
    // 3. Different credential sets, concurrently — tenant A's credential never
    //    appears in tenant B's scrubbed error text (each context carries its
    //    own scrubber, populated only by the credentials its own provider
    //    resolved).
    // ----------------------------------------------------------------------

    @Test
    fun `concurrent runs do not let one tenant's credential leak into the other's scrubbed error`() {
        val barrier = CyclicBarrier(2)
        // A test builtin that: resolves its tenant's api_key through the active
        // context's credentialProvider (registering ONLY that key into the
        // context's scrubber), rendezvouses with the other tenant, then throws
        // an IoFailure whose text embeds BOTH tenants' keys as fixed probe
        // literals. The interpreter scrubs the detail through the per-context
        // scrubber: the probe equal to THIS tenant's resolved key must be
        // redacted; the probe equal to the OTHER tenant's key — which this
        // context's scrubber never saw — must survive verbatim. That asymmetry
        // (same error text, different redaction per tenant) is the isolation
        // proof. A shared/global scrubber would redact both probes for both
        // tenants.
        Builtins.installTestBuiltin(
            "strand-builtin:Test.LeakBoth",
            effectful = true,
            determinism = Builtins.Determinism.Stateful,
            fn = Builtins.Fn { ctx, _ ->
                // Resolve THIS tenant's key — registers it into ctx.scrubber.
                ctx.credentialProvider.resolve("anthropic", "api_key")
                    ?: error("test: no api_key configured")
                barrier.await()
                // Both tenants throw the SAME text containing BOTH keys.
                throw org.strand.interpreter.IoFailure(
                    "tenant-leak",
                    "upstream rejected probeA=$A_KEY probeB=$B_KEY",
                )
            },
        )

        val prog = image(CALL_LEAK)
        val leakFx = nameMap(CALL_LEAK).getValue("leakFx")
        val caps = CapabilitySet.ofCategories(setOf(leakFx))

        val rtA = StrandRuntime(HostPolicy.OPEN.copy(
            credentialProvider = StaticCredentialProvider(mapOf("anthropic" to A_KEY)),
        ))
        val rtB = StrandRuntime(HostPolicy.OPEN.copy(
            credentialProvider = StaticCredentialProvider(mapOf("anthropic" to B_KEY)),
        ))

        val (ra, rb) = runBlocking {
            withContext(Dispatchers.Default) {
                val da = async { runCatching { rtA.run(prog, caps) } }
                val db = async { runCatching { rtB.run(prog, caps) } }
                awaitAll(da, db)
            }
        }

        val detailA = ((ra.exceptionOrNull() as InterpretException).error as InterpretError.IoFailure).detail
        val detailB = ((rb.exceptionOrNull() as InterpretException).error as InterpretError.IoFailure).detail

        // Tenant A redacts A_KEY (its own, registered in A's scrubber) but NOT
        // B_KEY (which A's scrubber never saw) — even though the error text is
        // identical and the two runs overlapped.
        assertFalse(A_KEY in detailA, "tenant A's own key must be redacted in A's error: $detailA")
        assertTrue(
            B_KEY in detailA,
            "tenant B's credential must NOT be scrubbed by tenant A's scrubber: $detailA",
        )

        // Symmetric: tenant B redacts B_KEY but leaves A_KEY verbatim.
        assertFalse(B_KEY in detailB, "tenant B's own key must be redacted in B's error: $detailB")
        assertTrue(
            A_KEY in detailB,
            "tenant A's credential must NOT be scrubbed by tenant B's scrubber: $detailB",
        )
    }

    companion object {
        private const val A_KEY = "sk-ant-tenant-A-aaaaaaaaaaaaaaaa"
        private const val B_KEY = "sk-ant-tenant-B-bbbbbbbbbbbbbbbb"

        // Time.Now under granted Time.Now capability.
        private val TIME_NOW = """
        {
          "version": 1, "root": "app",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT" },
            "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now", "foreignType": "nowT", "effects": ["timeFx"] },
            "app":    { "type": "Application", "function": "now", "arguments": [] }
          }
        }
        """.trimIndent()

        // Call Test.Rendezvous(path) to force overlap, then Fs.Write(path, payload).
        // Test.Rendezvous returns its arg unchanged so the write sees the path.
        private val RENDEZVOUS_THEN_WRITE = """
        {
          "version": 1, "root": "writeApp",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "strT":      { "type": "PrimitiveType", "kind": "String" },
            "bytesT":    { "type": "PrimitiveType", "kind": "Bytes" },
            "writeFx":   { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
            "rvT":       { "type": "FunctionType", "parameters": ["strT"], "result": "strT" },
            "rendezvous":{ "type": "ForeignNode", "target": "strand-builtin:Test.Rendezvous", "foreignType": "rvT" },
            "writeT":    { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
            "writeFn":   { "type": "ForeignNode", "target": "strand-builtin:Fs.Write", "foreignType": "writeT",
                           "effects": ["writeFx"],
                           "effectProjections": [ { "category": "writeFx", "sources": [ { "kind": "ArgRef", "index": 0 } ] } ] },
            "escapePath": { "type": "StringLit", "value": "../escape.txt" },
            "syncedPath": { "type": "Application", "function": "rendezvous", "arguments": ["escapePath"] },
            "payload":   { "type": "BytesLit", "value": "deadbeef" },
            "writeApp":  { "type": "Application", "function": "writeFn", "arguments": ["syncedPath", "payload"] }
          }
        }
        """.trimIndent()

        // Call Test.LeakBoth() under a granted effect category.
        private val CALL_LEAK = """
        {
          "version": 1, "root": "app",
          "nodes": {
            "strT":   { "type": "PrimitiveType", "kind": "String" },
            "leakFx": { "type": "EffectCategory", "categoryName": "Test.Leak" },
            "leakT":  { "type": "FunctionType", "parameters": [], "result": "strT" },
            "leak":   { "type": "ForeignNode", "target": "strand-builtin:Test.LeakBoth", "foreignType": "leakT", "effects": ["leakFx"] },
            "app":    { "type": "Application", "function": "leak", "arguments": [] }
          }
        }
        """.trimIndent()
    }
}
