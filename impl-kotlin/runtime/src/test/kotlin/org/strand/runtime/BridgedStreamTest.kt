package org.strand.runtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyError
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import java.net.ServerSocket

/**
 * Q-046 actor-runtime bridge for IO-backed external event streams. Covers the
 * verifier well-formedness rules over a `source`-bound EventStream, the
 * group-start capability-coverage soundness gate, and an end-to-end bridged
 * drain over a loopback socket with deterministic per-instance replay.
 *
 * The bridged machine is a Bytes accumulator: its input stream is an
 * `External` `Bytes` stream whose `source` is an `Application` of
 * `Net.Connect`; each delivered chunk is appended to the state via
 * `Bytes.Concat`, so the final state equals the concatenation of every chunk
 * the feeder delivered.
 */
class BridgedStreamTest {

    private val savedSandbox = Builtins.sandboxPolicy

    @BeforeEach
    fun setUp() {
        // The feeder opens a loopback socket via Net.Connect; OPEN_DEFAULT
        // permits 127.0.0.1 (the CLI's SECURE_DEFAULT would block it).
        Builtins.sandboxPolicy = SandboxPolicy.OPEN_DEFAULT
    }

    @AfterEach
    fun tearDown() {
        Builtins.sandboxPolicy = savedSandbox
    }

    // ---- verifier well-formedness rules ---------------------------------

    @Test
    fun `source-bound external Bytes stream from a Net_Connect opener verifies`() {
        val ok = verify(program(EXTERNAL_SOURCED)) as VerifyResult.Ok
        // The machine type-checks; the bridge binding is admitted.
        org.junit.jupiter.api.Assertions.assertTrue(ok.rootType != null)
    }

    @Test
    fun `source on a non-external stream is rejected`() {
        assertRejected<VerifyError.StreamSourceOnNonExternal>(
            program("""
                "inStr": { "type": "EventStream", "eventType": "bytesT", "streamKind": "internal", "source": "openApp" }
            """.trimIndent()),
        )
    }

    @Test
    fun `source-bound stream whose eventType is not Bytes is rejected`() {
        assertRejected<VerifyError.StreamSourceTypeMismatch>(
            program("""
                "inStr": { "type": "EventStream", "eventType": "intT", "streamKind": "external", "source": "openApp" }
            """.trimIndent()),
        )
    }

    @Test
    fun `source-bound byte stream with a drop overflow policy is rejected`() {
        assertRejected<VerifyError.ByteStreamSourceRequiresBlockProducer>(
            program("""
                "inStr": { "type": "EventStream", "eventType": "bytesT", "streamKind": "external", "overflowPolicy": "DropNewest", "source": "openApp" }
            """.trimIndent()),
        )
    }

    @Test
    fun `source that is not an Application of an opener is rejected`() {
        // `emptyStr` is a StringLit (also used by the initial state), not an
        // Application of a registered opener.
        assertRejected<VerifyError.StreamSourceNotAnOpener>(
            program("""
                "inStr": { "type": "EventStream", "eventType": "bytesT", "streamKind": "external", "source": "emptyStr" }
            """.trimIndent()),
        )
    }

    @Test
    fun `opener that does not declare its semantic effect is rejected`() {
        // A Net.Connect ForeignNode that declares no effects: the bridge would
        // have no transport effect to absorb into the group closure.
        assertRejected<VerifyError.StreamSourceEffectMismatch>(
            program("""
                "netConnectFnNoFx": { "type": "ForeignNode", "target": "strand-builtin:Net.Connect", "foreignType": "connectT" },
                "openAppNoFx": { "type": "Application", "function": "netConnectFnNoFx", "arguments": ["hostLit", "portLit"] },
                "inStr": { "type": "EventStream", "eventType": "bytesT", "streamKind": "external", "source": "openAppNoFx" }
            """.trimIndent()),
        )
    }

    // ---- runtime soundness gate -----------------------------------------

    @Test
    fun `bridged stream with revoked Network_Receive is rejected at group start`() {
        val finalized = finalize(program(EXTERNAL_SOURCED, port = 9))
        assertTrue(Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root) is VerifyResult.Ok)
        // Grant every effect category EXCEPT Network.Receive.
        val group = buildGroup(finalized, grantReceive = false)
        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val ex = assertThrows<MachineGroupValidationError.ExternalStreamSourceEffectUncovered> {
            runBlocking { runtime.runGroup(group, this) }
        }
        assertTrue("Network.Receive" in ex.missing, "expected Network.Receive in ${ex.missing}")
        // The gate fires before any socket is opened (port 9 is never dialed).
    }

    // ---- end-to-end bridged drain + replay ------------------------------

    @Test
    fun `bridged socket stream drains into the machine and replays deterministically`() {
        val server = ServerSocket(0)
        val payload = "hello-bridge-world".toByteArray(Charsets.UTF_8)
        val writer = Thread {
            server.accept().use { client ->
                client.getOutputStream().apply { write(payload); flush() }
                // Returning closes the socket → the reader sees EOF.
            }
        }
        try {
            writer.start()
            val finalized = finalize(program(EXTERNAL_SOURCED, port = server.localPort))
            assertTrue(Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root) is VerifyResult.Ok)
            val group = buildGroup(finalized, grantReceive = true)
            val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)

            val recorded: List<Value>
            val liveFinal: Value
            runBlocking {
                val handle = runtime.runGroup(group, this)
                handle.await()
                val instance = handle.allInstances.values.single()
                liveFinal = instance.currentState
                recorded = instance.recordedEvents() ?: error("recording expected")
            }

            // The machine accumulated every delivered chunk into the payload.
            assertArrayEquals(payload, (liveFinal as Value.BytesV).v)
            assertTrue(recorded.isNotEmpty(), "feeder must have delivered at least one chunk")

            // Replay: folding the recorded events through the synchronous
            // runMachine reproduces the same final state with zero live IO.
            val replay = runtime.runMachine(finalized.root, recorded, CapabilitySet.EMPTY)
            assertArrayEquals(payload, (replay.final.finalState as Value.BytesV).v)
        } finally {
            server.close()
            writer.join(2000)
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun verify(json: String): VerifyResult {
        val finalized = finalize(json)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    private fun finalize(json: String): org.strand.hashing.FinalizedProgram {
        val ingest = JsonIngest.parse(json)
        return Hasher(ingest.rawStore).finalize(ingest.root)
    }

    private inline fun <reified E : VerifyError> assertRejected(json: String) {
        val f = verify(json) as? VerifyResult.Failed
            ?: org.junit.jupiter.api.Assertions.fail("expected verification to fail")
        assertTrue(f.errors.any { it is E }, "expected ${E::class.simpleName}, got ${f.errors}")
    }

    private fun buildGroup(
        finalized: org.strand.hashing.FinalizedProgram,
        grantReceive: Boolean,
    ): MachineGroup {
        val catIds = finalized.store.entries()
            .filter { (_, n) ->
                n is Node.EffectCategory && (grantReceive || n.categoryName != "Network.Receive")
            }
            .map { it.first }
            .toSet()
        val machineIds = finalized.store.entries()
            .filter { it.second is Node.StateMachine }
            .map { it.first }
        return MachineGroup(
            store = finalized.store,
            hashToNodeId = finalized.hashToNodeId,
            machines = machineIds,
            capabilities = CapabilitySet.ofCategories(catIds),
            nodeIdToHash = finalized.nodeIdToHash,
        )
    }

    companion object {

        /** The default `inStr`: an External Bytes stream backed by `openApp`. */
        private val EXTERNAL_SOURCED = """
            "inStr": { "type": "EventStream", "eventType": "bytesT", "streamKind": "external", "source": "openApp" }
        """.trimIndent()

        /**
         * A Bytes-accumulator state machine consuming a `source`-bound external
         * stream. [streamAndSourceNodes] supplies the `inStr` node (and any
         * extra source/opener nodes it references); [port] is baked into the
         * `Net.Connect` opener's port literal.
         */
        private fun program(streamAndSourceNodes: String, port: Int = 1): String = """
        {
          "version": 1,
          "root": "m",
          "nodes": {
            "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "strT":   { "type": "PrimitiveType", "kind": "String" },
            "emptyT": { "type": "ProductType", "fields": [] },
            "sft":    { "type": "ProductTypeField", "name": "state",   "fieldType": "bytesT" },
            "oft":    { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":   { "type": "ProductType", "fields": ["sft", "oft"] },
            "concatT":  { "type": "FunctionType", "parameters": ["bytesT", "bytesT"], "result": "bytesT" },
            "concatFn": { "type": "ForeignNode", "target": "strand-builtin:Bytes.Concat", "foreignType": "concatT" },
            "sP":     { "type": "ParameterDecl", "name": "s", "paramType": "bytesT" },
            "eP":     { "type": "ParameterDecl", "name": "e", "paramType": "bytesT" },
            "sRef":   { "type": "VarRef", "binder": "sP" },
            "eRef":   { "type": "VarRef", "binder": "eP" },
            "cat":    { "type": "Application", "function": "concatFn", "arguments": ["sRef", "eRef"] },
            "sV":     { "type": "ProductFieldValue", "fieldName": "state",   "value": "cat" },
            "emptyV": { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":     { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result": { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "emptyStr":   { "type": "StringLit", "value": "" },
            "fromUtf8T":  { "type": "FunctionType", "parameters": ["strT"], "result": "bytesT" },
            "fromUtf8Fn": { "type": "ForeignNode", "target": "strand-builtin:Bytes.FromUtf8", "foreignType": "fromUtf8T" },
            "init":   { "type": "Application", "function": "fromUtf8Fn", "arguments": ["emptyStr"] },
            "hostLit": { "type": "StringLit", "value": "127.0.0.1" },
            "portLit": { "type": "IntLit", "value": $port },
            "connectT": { "type": "FunctionType", "parameters": ["strT", "intT"], "result": "intT" },
            "netConnectCat":  { "type": "EffectCategory", "categoryName": "Network.Connect" },
            "netConnectFn":   { "type": "ForeignNode", "target": "strand-builtin:Net.Connect", "foreignType": "connectT", "effects": ["netConnectCat"] },
            "netConnectDecl": { "type": "EffectDecl", "effectType": "netConnectCat" },
            "openApp": { "type": "Application", "function": "netConnectFn", "arguments": ["hostLit", "portLit"], "effectInstances": ["netConnectDecl"] },
            "receiveFx":     { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "netReceiveCat": { "type": "EffectCategory", "categoryName": "Network.Receive" },
            $streamAndSourceNodes,
            "m": {
              "type": "StateMachine",
              "transitionFn": "lam",
              "initialState": "init",
              "inputStreams": ["inStr"],
              "outputStreams": [],
              "effects": ["receiveFx", "netReceiveCat"]
            }
          }
        }
        """.trimIndent()
    }
}
