package org.strand.corpus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.bytecode.Lowerer
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Value
import org.strand.runtime.EventCodec
import org.strand.runtime.MachineGroup
import org.strand.runtime.StateMachineRuntime
import org.strand.runtime.TransitionDispatcher
import org.strand.runtime.TransitionDispatcherFactory
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import org.strand.vm.Vm

/**
 * Q-017 step 1 Track A.4 follow-up: async multi-machine dispatch through
 * the VM via the [TransitionDispatcherFactory] hook on [MachineGroup].
 *
 * For each corpus async program — single-machine (46, 57), multi-input
 * (47), multi-machine supervisor (48), tagged-output (49) — the test
 * runs `StateMachineRuntime.runGroup` twice:
 *
 *  1. Default group (no dispatcher factory) → interpreter-backed actor
 *     dispatch. Reference behavior.
 *  2. Group with [VmTransitionDispatcherFactory] → VM-backed actor
 *     dispatch. Same actor loop, same channels, same output decoding,
 *     just with `Vm.applyClosure` standing in for `interpreter.applyCallable`.
 *
 * The test drains every external output channel from both runs into
 * sorted multisets and asserts they're equal. Multiset-equality (not
 * sequence-equality) because async runs intentionally have nondeterministic
 * interleaving between producers (Q-009 default); the SET of values
 * produced is invariant across dispatch engines, even when the ORDER
 * isn't.
 *
 * Recorded-event sequences are NOT compared — they depend on the actor's
 * `select` choice over multiple input channels, which varies per run
 * regardless of dispatcher. Final instance states ARE compared (when the
 * group has exactly one machine — supervisor patterns with multiple
 * instances have nondeterministic per-instance terminal states).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VmAsyncMachineEquivalenceTest {

    private data class AsyncCase(val baseName: String, val singleInstance: Boolean)

    private val cases = listOf(
        AsyncCase("46-async-single-machine-counter", singleInstance = true),
        AsyncCase("47-async-multi-input-merge", singleInstance = true),
        AsyncCase("48-async-supervisor-one-for-one", singleInstance = false),
        AsyncCase("49-async-tagged-output-list", singleInstance = true),
        AsyncCase("57-dropoldest-overflow", singleInstance = true),
    )

    @TestFactory
    fun vmAndInterpreterAgreeForAsyncMachines(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.baseName) {
            val programText = loadResource("/corpus/${case.baseName}.json")
            val eventsText = loadResource("/corpus/${case.baseName}.events.json")

            val (interpOutputs, interpFinal) = runGroupViaDispatcher(
                programText, eventsText, dispatcherFactory = null, single = case.singleInstance,
            )
            val (vmOutputs, vmFinal) = runGroupViaDispatcher(
                programText, eventsText,
                dispatcherFactory = { store, h2n -> VmTransitionDispatcherFactory(store, h2n) },
                single = case.singleInstance,
            )

            // Per-stream multiset equality on emitted outputs.
            assertEquals(interpOutputs.keys, vmOutputs.keys) {
                "${case.baseName}: differing external-output streams"
            }
            for ((streamName, interpList) in interpOutputs) {
                val vmList = vmOutputs.getValue(streamName)
                assertEquals(
                    interpList.sortedBy { it.toString() },
                    vmList.sortedBy { it.toString() },
                    "${case.baseName}: stream '$streamName' multiset differs " +
                        "(interp=$interpList, vm=$vmList)",
                )
            }
            if (case.singleInstance) {
                assertEquals(interpFinal, vmFinal) {
                    "${case.baseName}: single-instance final state differs"
                }
            }
        }
    }

    /**
     * Drive a corpus async program once through `runGroup` and collect
     * (per-stream emitted-values, single-instance final state). When
     * [dispatcherFactory] is non-null, the group's [MachineGroup.dispatcherFactory]
     * is set to its result — VM-backed dispatch. Otherwise the default
     * interpreter-backed dispatch runs.
     */
    private fun runGroupViaDispatcher(
        programText: String,
        eventsText: String,
        dispatcherFactory: ((NodeStore, Map<Hash, NodeId>) -> TransitionDispatcherFactory)?,
        single: Boolean,
    ): Pair<Map<String, List<Value>>, Value?> {
        val ingest = JsonIngest.parse(programText)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verifyResult = Verifier(finalized.store, finalized.hashToNodeId)
            .verify(finalized.root)
        assertTrue(verifyResult is VerifyResult.Ok) { "verifier failed: $verifyResult" }

        val machineIds = finalized.store.entries()
            .filter { it.second is Node.StateMachine }
            .map { it.first }
        val effectCategoryIds = finalized.store.entries()
            .filter { it.second is Node.EffectCategory }
            .map { it.first }
            .toSet()
        val caps = CapabilitySet.ofCategories(effectCategoryIds)
        val factory = dispatcherFactory?.invoke(finalized.store, finalized.hashToNodeId)
        val group = MachineGroup(
            store = finalized.store,
            hashToNodeId = finalized.hashToNodeId,
            machines = machineIds,
            capabilities = caps,
            dispatcherFactory = factory,
        )

        // Both the routed-event format (each entry has "stream") and the
        // flat-event format (each entry is just an EventCodec payload) are
        // accepted. For flat events we synthesize the single external input
        // stream's NodeId — corpus 46/57 use this shape because they were
        // originally written as sync machines and only adopted the async
        // runtime later.
        val routedEvents = parseEvents(eventsText, finalized.store)
        val resolvedRouted = routedEvents.map { (name, payload) ->
            val streamId = ingest.nameMap[name]
                ?: run {
                    // Flat-event fallback: name == "" → use the single
                    // external input stream's NodeId.
                    val externalInputs = finalized.store.entries()
                        .filter {
                            val n = it.second
                            n is Node.EventStream && n.streamKind == org.strand.core.StreamKind.External
                        }
                        .map { it.first }
                    externalInputs.singleOrNull()
                        ?: error("flat-event format requires a single external input stream; got $externalInputs")
                }
            streamId to payload
        }
        val nameById = ingest.nameMap.entries.associate { (n, id) -> id to n }

        val perStreamOutputs = mutableMapOf<String, MutableList<Value>>()
        var finalState: Value? = null

        kotlinx.coroutines.runBlocking {
            val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
            val handle = runtime.runGroup(group, this)
            for ((sid, payload) in resolvedRouted) {
                handle.externalInputs.getValue(sid).send(payload)
            }
            for (ch in handle.externalInputs.values) ch.close()
            coroutineScope {
                for ((sid, ch) in handle.externalOutputs) {
                    val name = nameById[sid] ?: "<unnamed:$sid>"
                    launch {
                        val sink = perStreamOutputs.getOrPut(name) { mutableListOf() }
                        for (value in ch) sink += value
                    }
                }
            }
            handle.await()
            if (single) {
                finalState = handle.allInstances.values.singleOrNull()?.currentState
            }
        }

        return perStreamOutputs to finalState
    }

    /**
     * Parse an events JSON file. Returns (streamName-or-empty, payload)
     * pairs. The routed format names a stream per event; the flat format
     * doesn't — the caller resolves the empty stream-name to the single
     * external input stream's NodeId. [store] is unused but accepted so
     * callers can route resolution any way they like.
     */
    private fun parseEvents(text: String, store: NodeStore): List<Pair<String, Value>> {
        val parser = Json { ignoreUnknownKeys = true }
        val root = parser.parseToJsonElement(text) as JsonObject
        val arr = root["events"] as JsonArray
        return arr.mapIndexed { i, elt ->
            val obj = elt as JsonObject
            val streamName = obj["stream"]?.jsonPrimitive?.contentOrNull ?: ""
            val payload = EventCodec.decodeValue(obj, ctx = "events[$i]")
            streamName to payload
        }
    }

    private fun loadResource(resource: String): String =
        VmAsyncMachineEquivalenceTest::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.readText()
            ?: error("missing resource $resource")
}

/**
 * VM-backed [TransitionDispatcherFactory]. Each `build` call lowers the
 * machine's `transitionFn` into a chunk table, evaluates it once via
 * `Vm.evaluate` to obtain a VmClosure, then returns a dispatcher that
 * applies that closure to per-event `(state, event)` args. Capabilities
 * are converted from [CapabilitySet] (structured patterns) to the VM's
 * `Set<Int>` (EffectCategory NodeId .values) — the wildcard patterns
 * produced by `CapabilitySet.ofCategories` cover category presence,
 * which is what the VM checks.
 */
internal class VmTransitionDispatcherFactory(
    private val store: NodeStore,
    private val hashToNodeId: Map<Hash, NodeId>,
) : TransitionDispatcherFactory {
    override fun build(
        machineNode: Node.StateMachine,
        machineId: NodeId,
        capabilities: CapabilitySet,
    ): TransitionDispatcher {
        val table = Lowerer(store, hashToNodeId).lower(machineNode.transitionFn)
        val vm = Vm(table)
        val intCaps = capabilities.grants.keys.map { it.value }.toSet()
        val closure = vm.evaluate(intCaps)
        return VmTransitionDispatcher(vm, closure, intCaps)
    }
}

internal class VmTransitionDispatcher(
    private val vm: Vm,
    private val closure: Any,
    private val caps: Set<Int>,
) : TransitionDispatcher {
    override fun applyTransition(state: Value, event: Value): Value =
        vm.applyClosure(closure, listOf(state, event), caps)
}
