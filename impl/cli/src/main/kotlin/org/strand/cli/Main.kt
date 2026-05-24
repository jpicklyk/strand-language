package org.strand.cli

import org.strand.core.JsonIngest
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.interpreter.Interpreter
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value
import org.strand.runtime.EventCodec
import org.strand.runtime.StateMachineRuntime
import org.strand.runtime.Trace
import org.strand.runtime.TraceStep
import org.strand.schema.SchemaChecker
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import java.io.File
import kotlin.system.exitProcess

/**
 * Parse, finalize, and return the canonical [FinalizedProgram]. Every
 * command after the JSON-parse step operates on the finalized form — the
 * verifier and interpreter need the canonical [NodeStore] (with
 * Hash-bearing NodeRefs) plus the `hashToNodeId` reverse map.
 */
private fun loadFinalized(text: String): FinalizedProgram {
    val ingest = JsonIngest.parse(text)
    return Hasher(ingest.rawStore).finalize(ingest.root)
}

/**
 * CLI for the Strand reference implementation.
 *
 * Usage:
 *   strand verify  <file.json>
 *   strand run     <file.json>
 *   strand machine <file.json> --events <events.json>
 *
 * `verify` ingests and type-checks; `run` additionally evaluates pure
 * programs (Layer 1–5); `machine` drives a StateMachine over a JSON event
 * list (Layer 6 step 1).
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        exitProcess(2)
    }
    when (val command = args[0]) {
        "verify", "run" -> runVerifyOrEval(command, args)
        "machine" -> runMachine(args)
        else -> {
            usage()
            exitProcess(2)
        }
    }
}

private fun runVerifyOrEval(command: String, args: Array<String>) {
    if (args.size != 2) {
        usage()
        exitProcess(2)
    }
    val path = args[1]
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
                    val value = Interpreter(finalized.store, finalized.hashToNodeId).eval(finalized.root)
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
                val trace = runtime.runMachine(finalized.root, events)
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

private fun usage() {
    System.err.println("usage:")
    System.err.println("  strand verify  <file.json>")
    System.err.println("  strand run     <file.json>")
    System.err.println("  strand machine <file.json> --events <events.json>")
}
