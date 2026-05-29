package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.FederatedProgram
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.hashing.LocalProgramResolver
import org.strand.hashing.federated
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-043 step 3a — the state-machine runtime across a store boundary.
 *
 * A peer library exports `inc = (x: Int) -> x + 1`; the application is a
 * StateMachine whose transition function `(s, _) -> {state: inc(s), outputs: {}}`
 * references `inc` purely by content hash (the `targetHash` NodeRef form). With
 * the resolver wired into [StateMachineRuntime] (threaded into the default
 * interpreter used by the synchronous `runMachine` fold and into the per-actor
 * interpreters the async `runGroup` path builds via `RuntimeContext`), the
 * machine fetches + re-bases `inc` on first transition and counts 0 → 1 → 2 → 3
 * over three Unit events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossStoreRuntimeTest {

    private fun finalize(json: String): FinalizedProgram {
        val ingest = JsonIngest.parse(json)
        return Hasher(ingest.rawStore).finalize(ingest.root)
    }

    /** Build the federated application machine against the `inc` peer library. */
    private fun federatedMachine(): FederatedProgram {
        val lib = finalize(LIB)
        val incHash = lib.nodeIdToHash.getValue(lib.root)
        val app = finalize(APP_MACHINE.replace("\$INC_HASH", incHash.toString()))
        return app.federated(LocalProgramResolver(lib))
    }

    @Test
    fun `sync runMachine resolves a cross-store transition helper`() {
        val app = federatedMachine()
        // Sanity: the federated machine verifies (the verifier fetches + types
        // the cross-store inc before the runtime ever runs).
        val verify = Verifier(app.store, app.hashToNodeId, app::fetchAndAdmit).verify(app.root)
        assertTrue(verify is VerifyResult.Ok) { "federated machine must verify; got $verify" }

        val runtime = StateMachineRuntime(app.store, app.hashToNodeId, app::fetchAndAdmit)
        val trace = runtime.runMachine(app.root, listOf(Value.UnitV, Value.UnitV, Value.UnitV))
        assertEquals(Value.IntV(3), trace.final.finalState, "inc applied three times from 0 across the store boundary = 3")
    }

    @Test
    fun `sync runMachine without a resolver cannot resolve the cross-store helper`() {
        // No resolveTarget callback: the transition's NodeRef has no local
        // target, so the first transition fails — single-store behaviour.
        val app = federatedMachine()
        val runtime = StateMachineRuntime(app.store, app.hashToNodeId)
        assertThrows(InterpretException::class.java) {
            runtime.runMachine(app.root, listOf(Value.UnitV))
        }
    }

    @Test
    fun `async runGroup resolves a cross-store transition helper per actor`() = runTest {
        val app = federatedMachine()
        val runtime = StateMachineRuntime(app.store, app.hashToNodeId, app::fetchAndAdmit)
        val group = MachineGroup(
            store = app.store,
            hashToNodeId = app.hashToNodeId,
            machines = listOf(app.root),
        )
        val handle = runtime.runGroup(group, this)

        val input = handle.externalInputs.values.single()
        repeat(3) { input.send(Value.UnitV) }
        input.close()
        handle.await()

        val instance = handle.instances.values.single()
        assertEquals(Value.IntV(3), instance.currentState,
            "the per-actor interpreter must fetch + re-base inc across the store boundary")
        assertTrue(instance.halted)
    }

    companion object {
        // The `inc` library — identical in shape to corpus 76's lib.json.
        private val LIB = """
        {
          "version": 1,
          "root": "inc",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "addT":   { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "addFn":  { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "x":      { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":   { "type": "VarRef", "binder": "x" },
            "one":    { "type": "IntLit", "value": 1 },
            "addApp": { "type": "Application", "function": "addFn", "arguments": ["xRef", "one"] },
            "inc":    { "type": "Lambda", "parameters": ["x"], "body": "addApp" }
          }
        }
        """.trimIndent()

        // A counter machine whose transition increments the state by calling the
        // cross-store `inc` (a `targetHash` NodeRef). State Int from 0; Unit
        // events; no output streams. `${'$'}INC_HASH` is interpolated with the
        // peer library's `inc` content hash before ingest.
        private val APP_MACHINE = """
        {
          "version": 1,
          "root": "m",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "unitT":   { "type": "PrimitiveType", "kind": "Unit" },
            "emptyT":  { "type": "ProductType", "fields": [] },
            "sft":     { "type": "ProductTypeField", "name": "state",   "fieldType": "intT" },
            "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },
            "incRef":  { "type": "NodeRef", "targetHash": "${'$'}INC_HASH" },
            "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "intT" },
            "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "sRef":    { "type": "VarRef", "binder": "sP" },
            "incApp":  { "type": "Application", "function": "incRef", "arguments": ["sRef"] },
            "sV":      { "type": "ProductFieldValue", "fieldName": "state",   "value": "incApp" },
            "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "lam":     { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "init":    { "type": "IntLit", "value": 0 },
            "stream":  { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
            "receiveFx": { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "m": {
              "type": "StateMachine",
              "transitionFn": "lam",
              "initialState": "init",
              "inputStreams": ["stream"],
              "outputStreams": [],
              "effects": ["receiveFx"]
            }
          }
        }
        """.trimIndent()
    }
}
