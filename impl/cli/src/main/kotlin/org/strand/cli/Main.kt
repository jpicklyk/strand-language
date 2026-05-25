package org.strand.cli

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.strand.authoring.Authoring
import org.strand.authoring.AuthoringException
import org.strand.authoring.ConstraintGrammar
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Interpreter
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value
import org.strand.runtime.EventCodec
import org.strand.runtime.MachineGroup
import org.strand.runtime.RuntimeMetrics
import org.strand.runtime.StateMachineRuntime
import org.strand.runtime.Trace
import org.strand.runtime.TraceStep
import org.strand.schema.SchemaChecker
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import java.io.File
import kotlin.system.exitProcess

/**
 * Parse, finalize, and return the canonical [FinalizedProgram] alongside
 * the ingest result. The ingest is preserved (rather than just the
 * finalized form) so commands that need the author-id-to-NodeId map
 * (`strand group`'s routed events) can resolve user-named streams.
 */
private fun loadFinalizedWithIngest(text: String): Pair<JsonIngest.IngestResult, FinalizedProgram> {
    val ingest = JsonIngest.parse(text)
    val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
    return ingest to finalized
}

/**
 * Parse, finalize, and return the canonical [FinalizedProgram]. Every
 * command after the JSON-parse step operates on the finalized form — the
 * verifier and interpreter need the canonical [NodeStore] (with
 * Hash-bearing NodeRefs) plus the `hashToNodeId` reverse map.
 */
private fun loadFinalized(text: String): FinalizedProgram =
    loadFinalizedWithIngest(text).second

/**
 * Build a permissive [CapabilitySet] that grants wildcard patterns for every
 * EffectCategory NodeId reachable in the verified store. Used by the
 * `--grant-all` CLI flag so capability-requiring corpus programs can run
 * end-to-end from the command line without a policy file. This is demo /
 * dev-mode only — production deployments build CapabilitySets from policy.
 */
private fun grantAllCapabilities(finalized: FinalizedProgram): CapabilitySet {
    val categories: Set<NodeId> = finalized.store.entries()
        .filter { it.second is Node.EffectCategory }
        .map { it.first }
        .toSet()
    return CapabilitySet.ofCategories(categories)
}

/**
 * CLI for the Strand reference implementation.
 *
 * Usage:
 *   strand verify  <file.json>
 *   strand run     <file.json>
 *   strand machine <file.json> --events <events.json>
 *   strand group   <file.json> --events <events.json>
 *   strand author  <file.layer-a> [--emit-json]
 *   strand grammar
 *
 * `verify` ingests and type-checks; `run` additionally evaluates pure
 * programs (Layer 1–5); `machine` drives a single StateMachine over a JSON
 * event list (Layer 6 step 1, synchronous fold); `group` drives every
 * StateMachine reachable in the program as a MachineGroup over a routed
 * event list (Layer 6 step 2, per-machine coroutine actors); `author`
 * compiles a Layer A authoring-format file (Q-034 step 1) to canonical
 * dag-json (always elaborating absent annotations) and runs the verifier
 * — with `--emit-json` it prints the generated JSON instead of running
 * the pipeline; `grammar` emits the Layer B GBNF constraint grammar.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        exitProcess(2)
    }
    when (val command = args[0]) {
        "verify", "run" -> runVerifyOrEval(command, args)
        "machine" -> runMachine(args)
        "group" -> runGroup(args)
        "author" -> runAuthor(args)
        "grammar" -> runGrammar(args)
        else -> {
            usage()
            exitProcess(2)
        }
    }
}

/**
 * Q-034 step 1 Layer B: emit the constraint grammar (GBNF) that describes
 * exactly the well-formed Layer A documents. The grammar is consumable by
 * `llama.cpp --grammar-file`, Outlines, LMQL, and any other GBNF-aware
 * decoder. Lexical / syntactic correctness only; semantic correctness
 * (reference validity, id uniqueness) is out of GBNF's expressive range
 * and not enforced by the emitted grammar.
 */
private fun runGrammar(args: Array<String>) {
    if (args.size > 1) {
        usage()
        exitProcess(2)
    }
    print(ConstraintGrammar.emitGbnf())
}

private fun runVerifyOrEval(command: String, args: Array<String>) {
    if (args.size < 2) {
        usage()
        exitProcess(2)
    }
    val path = args[1]
    val flags = args.drop(2).toSet()
    val grantAll = "--grant-all" in flags
    val unknown = flags - setOf("--grant-all")
    if (unknown.isNotEmpty()) {
        System.err.println("unknown flags: ${unknown.joinToString(", ")}")
        usage()
        exitProcess(2)
    }
    val text = File(path).readText()
    val finalized = loadFinalized(text)
    val verifier = Verifier(finalized.store, finalized.hashToNodeId)
    when (val result = verifier.verify(finalized.root)) {
        is VerifyResult.Failed -> {
            System.err.println("verification failed:")
            for (e in result.errors) System.err.println("  $e")
            exitProcess(1)
        }
        is VerifyResult.Ok -> {
            println("type: ${result.rootType}")
            // Layer 7 step 1: after type-checking, run the SchemaChecker to
            // evaluate any pure-expression invariants on statically-known
            // values. Violations halt; deferred diagnostics are surfaced
            // (informational, per the proposal's default disposition).
            if (!runSchemaCheck(finalized, result)) exitProcess(1)
            if (command == "run") {
                try {
                    val interp = Interpreter(finalized.store, finalized.hashToNodeId)
                    val value = if (grantAll) {
                        interp.eval(finalized.root, capabilities = grantAllCapabilities(finalized))
                    } else {
                        interp.eval(finalized.root)
                    }
                    println("value: $value")
                } catch (e: InterpretException) {
                    System.err.println("interpretation failed: ${e.error}")
                    exitProcess(1)
                }
            }
        }
    }
}

/**
 * Run the Layer 7 step 1 [SchemaChecker] over a verified graph. Prints
 * deferred diagnostics to stderr (informational); returns false (and
 * prints violations) when any pure-expression invariant fails over a
 * statically-known value. Returns true when the schema-check passes or no
 * Schema-typed positions exist in the graph.
 */
private fun runSchemaCheck(finalized: FinalizedProgram, verifyResult: VerifyResult.Ok): Boolean {
    val schemaResult = SchemaChecker(finalized.store, finalized.hashToNodeId, verifyResult).check()
    for (deferred in schemaResult.deferred) {
        System.err.println("schema-check deferred: $deferred")
    }
    if (schemaResult.violations.isNotEmpty()) {
        System.err.println("schema-check failed:")
        for (v in schemaResult.violations) System.err.println("  $v")
        return false
    }
    return true
}

private fun runMachine(args: Array<String>) {
    if (args.size < 4 || args[2] != "--events") {
        usage()
        exitProcess(2)
    }
    val programPath = args[1]
    val eventsPath = args[3]
    val flags = args.drop(4).toSet()
    val grantAll = "--grant-all" in flags
    val unknown = flags - setOf("--grant-all")
    if (unknown.isNotEmpty()) {
        System.err.println("unknown flags: ${unknown.joinToString(", ")}")
        usage()
        exitProcess(2)
    }

    val programText = File(programPath).readText()
    val finalized = loadFinalized(programText)
    val verifier = Verifier(finalized.store, finalized.hashToNodeId)
    when (val result = verifier.verify(finalized.root)) {
        is VerifyResult.Failed -> {
            System.err.println("verification failed:")
            for (e in result.errors) System.err.println("  $e")
            exitProcess(1)
        }
        is VerifyResult.Ok -> {
            // Layer 7 step 1: SchemaChecker also runs for `strand machine`,
            // matching `verify` / `run` semantics so a malformed Schema-bearing
            // value halts before any state-machine evaluation occurs.
            if (!runSchemaCheck(finalized, result)) exitProcess(1)
            val eventsText = File(eventsPath).readText()
            val events = EventCodec.parseEventList(eventsText)
            val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
            try {
                val caps = if (grantAll) grantAllCapabilities(finalized) else CapabilitySet.EMPTY
                val trace = runtime.runMachine(finalized.root, events, caps)
                printTrace(trace)
            } catch (e: InterpretException) {
                System.err.println("machine evaluation failed: ${e.error}")
                exitProcess(1)
            }
        }
    }
}

/**
 * Pretty-print a [Trace] in a single readable form. The format is structured
 * but human-friendly — one line per step plus a final halt record. We do
 * not emit JSON here because the trace is informational; a structured codec
 * (for snapshot / replay) is step 3 work, not step 1.
 */
private fun printTrace(trace: Trace) {
    println("trace (${trace.steps.size} step${if (trace.steps.size == 1) "" else "s"}):")
    for ((i, step) in trace.steps.withIndex()) {
        val outputsStr = if (step.outputs.isEmpty()) {
            ""
        } else {
            "  outputs=${step.outputs}"
        }
        println("  [$i] event=${step.event}  ${formatStateTransition(step.before, step.after)}$outputsStr")
    }
    println("halt: reason=${trace.final.reason}  final=${trace.final.finalState}")
}

private fun formatStateTransition(before: Value, after: Value): String =
    if (before == after) "state=$before (unchanged)" else "state: $before -> $after"

/**
 * Layer 6 step 2: drive every StateMachine reachable from the program as
 * a [MachineGroup]. The events file uses the "routed" format — each entry
 * carries a `stream` field naming the external input EventStream by its
 * author id, plus the standard [EventCodec] payload encoding.
 *
 * Output streams are drained concurrently and each emission is printed as
 * it arrives, prefixed with the output stream's author id (so multi-output
 * programs are distinguishable in the trace). The CLI exits when every
 * actor has halted (all input channels closed and all events processed).
 */
private fun runGroup(args: Array<String>) {
    if (args.size < 4 || args[2] != "--events") {
        usage()
        exitProcess(2)
    }
    val programPath = args[1]
    val eventsPath = args[3]
    val flags = args.drop(4).toSet()
    val grantAll = "--grant-all" in flags
    val emitMetrics = "--metrics" in flags
    val unknown = flags - setOf("--grant-all", "--metrics")
    if (unknown.isNotEmpty()) {
        System.err.println("unknown flags: ${unknown.joinToString(", ")}")
        usage()
        exitProcess(2)
    }

    val programText = File(programPath).readText()
    val (ingest, finalized) = loadFinalizedWithIngest(programText)
    val verifier = Verifier(finalized.store, finalized.hashToNodeId)
    val verifyResult = verifier.verify(finalized.root)
    if (verifyResult is VerifyResult.Failed) {
        System.err.println("verification failed:")
        for (e in verifyResult.errors) System.err.println("  $e")
        exitProcess(1)
    }
    if (!runSchemaCheck(finalized, verifyResult as VerifyResult.Ok)) exitProcess(1)

    // Collect every StateMachine NodeId from the canonical store. The
    // group includes ALL reachable StateMachines, regardless of whether
    // they appear at the root or are buried inside a Let chain (the
    // multi-machine corpus 48 pattern).
    val machineIds: List<NodeId> = finalized.store.entries()
        .filter { it.second is Node.StateMachine }
        .map { it.first }
    if (machineIds.isEmpty()) {
        System.err.println("group: no StateMachine nodes found in $programPath")
        exitProcess(1)
    }

    // Parse the routed event list. The "stream" field names the external
    // input EventStream by author id; we resolve it to the NodeId via
    // the ingest's nameMap (which the canonical store has rewritten to
    // opaque NodeIds but the author names are preserved here for routing).
    val eventsText = File(eventsPath).readText()
    val routedEvents = parseRoutedEvents(eventsText)
    val resolvedRouted = routedEvents.map { (streamName, payload) ->
        val streamId = ingest.nameMap[streamName]
            ?: run {
                System.err.println(
                    "group: routed event names unknown stream '$streamName'; " +
                        "known names: ${ingest.nameMap.keys.sorted().joinToString(", ")}"
                )
                exitProcess(1)
            }
        streamId to payload
    }

    val group = MachineGroup(
        store = finalized.store,
        hashToNodeId = finalized.hashToNodeId,
        machines = machineIds,
        capabilities = if (grantAll) grantAllCapabilities(finalized) else CapabilitySet.EMPTY,
        recordInputs = false,  // CLI runs are not replay-determinism tests
    )

    // Inverse name map for nicer output labelling (NodeId → author name).
    val nameByNodeId: Map<NodeId, String> = ingest.nameMap.entries
        .associate { (name, id) -> id to name }

    try {
        runBlocking {
            val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
            val handle = runtime.runGroup(group, this)

            // Send routed events on their designated input streams, then
            // close all external inputs so the actors halt naturally.
            for ((streamId, payload) in resolvedRouted) {
                val channel = handle.externalInputs[streamId]
                    ?: error("group: stream $streamId is not an external input")
                channel.send(payload)
            }
            for (channel in handle.externalInputs.values) channel.close()

            // Drain output streams concurrently. Each emission is printed
            // as it arrives; the actors continue until their input
            // channels close and any pending transitions complete.
            coroutineScope {
                for ((streamId, channel) in handle.externalOutputs) {
                    val name = nameByNodeId[streamId] ?: "<unnamed:$streamId>"
                    launch {
                        for (value in channel) println("output $name: $value")
                    }
                }
            }
            handle.await()
            if (emitMetrics) printMetrics(handle.metrics(), nameByNodeId)
        }
    } catch (e: InterpretException) {
        System.err.println("group evaluation failed: ${e.error}")
        exitProcess(1)
    }
}

/**
 * Print a [RuntimeMetrics] snapshot in a human-readable form. Per-instance
 * counters are listed first, then per-stream. Stream NodeIds are labeled with
 * their author name when known.
 */
private fun printMetrics(metrics: RuntimeMetrics, nameByNodeId: Map<NodeId, String>) {
    println("metrics:")
    println("  instances (${metrics.perInstance.size}):")
    for ((id, m) in metrics.perInstance) {
        println("    $id:")
        println("      eventsReceived=${m.eventsReceived}")
        println("      transitionsExecuted=${m.transitionsExecuted}")
        println("      lastTransitionLatencyNanos=${m.lastTransitionLatencyNanos}")
        println("      halted=${m.halted}")
        println("      currentState=${m.currentState}")
    }
    println("  streams (${metrics.perStream.size}):")
    for ((id, m) in metrics.perStream) {
        val name = nameByNodeId[id] ?: "<unnamed:$id>"
        println("    $name: overflowDrops=${m.overflowDrops}  closed=${m.closed}")
    }
}

/**
 * Parse a routed event list of the form
 * `{"events": [{"stream": "<name>", "tag": "<tag>", ...}, ...]}`. Each
 * entry's `stream` field names an external input EventStream by author
 * id; the remaining fields decode to a [Value] via the standard
 * [EventCodec] format. Returns the (stream name, decoded value) pairs in
 * the order they appear in the file.
 */
private fun parseRoutedEvents(text: String): List<Pair<String, Value>> {
    val parser = Json { ignoreUnknownKeys = true }
    val root = parser.parseToJsonElement(text) as? JsonObject
        ?: error("routed event list: top-level value must be an object")
    val events = root["events"] as? JsonArray
        ?: error("routed event list: missing or non-array 'events' field")
    return events.mapIndexed { i, elt ->
        val obj = elt as? JsonObject
            ?: error("routed event list: events[$i] must be an object")
        val streamName = obj["stream"]?.jsonPrimitive?.contentOrNull
            ?: error("routed event list: events[$i] missing 'stream' field")
        val payload = EventCodec.decodeValue(obj, ctx = "events[$i]")
        streamName to payload
    }
}

/**
 * Q-034 step 1: compile a Layer A authoring-format file to canonical
 * dag-json, ingest, finalize, verify. Elaboration (Layer C — fills in
 * absent Lambda effects, Application effectInstances / typeArguments,
 * Lambda paramType) runs unconditionally.
 *
 * Flags:
 *   `--emit-json`   print the compiled JSON to stdout, skip the verify
 *                   pipeline
 */
private fun runAuthor(args: Array<String>) {
    if (args.size < 2) {
        usage()
        exitProcess(2)
    }
    val path = args[1]
    val flags = args.drop(2).toSet()
    val emitOnly = "--emit-json" in flags
    val recognized = setOf("--emit-json")
    val unknown = flags - recognized
    if (unknown.isNotEmpty()) {
        System.err.println("unknown flags: ${unknown.joinToString(", ")}")
        usage()
        exitProcess(2)
    }
    val layerAText = File(path).readText()
    val dagJsonText = try {
        Authoring.compileToDagJson(layerAText)
    } catch (e: AuthoringException) {
        System.err.println("Layer A compilation failed:")
        for (err in e.errors) {
            System.err.println("  line ${err.line}: ${err.detail}")
        }
        exitProcess(1)
    }
    if (emitOnly) {
        println(dagJsonText)
        return
    }
    val finalized = loadFinalized(dagJsonText)
    val verifier = Verifier(finalized.store, finalized.hashToNodeId)
    when (val result = verifier.verify(finalized.root)) {
        is VerifyResult.Failed -> {
            System.err.println("verification failed for $path (after Layer A compile):")
            for (e in result.errors) System.err.println("  $e")
            exitProcess(1)
        }
        is VerifyResult.Ok -> {
            println("type: ${result.rootType}")
            if (!runSchemaCheck(finalized, result)) exitProcess(1)
        }
    }
}

private fun usage() {
    System.err.println("usage:")
    System.err.println("  strand verify  <file.json>")
    System.err.println("  strand run     <file.json> [--grant-all]")
    System.err.println("  strand machine <file.json> --events <events.json> [--grant-all]")
    System.err.println("  strand group   <file.json> --events <events.json> [--grant-all] [--metrics]")
    System.err.println("  strand author  <file.layer-a> [--emit-json]")
    System.err.println("  strand grammar                → emit Layer B constraint grammar (GBNF)")
    System.err.println()
    System.err.println("  --grant-all: auto-grant wildcard capabilities for every EffectCategory")
    System.err.println("               in the verified store (demo / dev-mode convenience; not for")
    System.err.println("               production use).")
    System.err.println("  --metrics:   after `strand group` completes, print a RuntimeMetrics snapshot")
    System.err.println("               (Layer 6 step 3 slice 3.4) showing per-instance and per-stream")
    System.err.println("               counters.")
}
