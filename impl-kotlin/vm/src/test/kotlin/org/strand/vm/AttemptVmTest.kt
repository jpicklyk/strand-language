package org.strand.vm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.bytecode.Lowerer
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.interpreter.ResourceTable
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * N-047 Attempt (Q-048) bytecode-VM scenarios. The VM's ATTEMPT_PUSH/POP +
 * unwind protocol must agree with the tree-walking interpreter for the
 * IoFailure-catching shape (the payload excludes NodeIds, so both backends
 * construct identical Err values), and must let uncatchable errors propagate.
 *
 * The schema-catch divergence (the VM does not enforce schema invariants, so a
 * program catching SchemaInvariantViolation is interpreter-only) is pinned by
 * the corpus-level guard test, not here.
 */
class AttemptVmTest {

    @AfterEach
    fun cleanup() {
        ResourceTable.resetForTest()
    }

    private data class Loaded(
        val store: org.strand.core.NodeStore,
        val root: org.strand.core.NodeId,
        val names: Map<String, org.strand.core.NodeId>,
        val hashToNodeId: Map<org.strand.core.Hash, org.strand.core.NodeId>,
        val effectIds: Set<Int>,
    )

    private fun load(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        check(verify is VerifyResult.Ok) { "fixture failed verify: $verify" }
        val effectIds = finalized.store.entries().asSequence()
            .filter { it.second is Node.EffectCategory }
            .map { it.first.value }
            .toSet()
        return Loaded(finalized.store, finalized.root, ingest.nameMap, finalized.hashToNodeId, effectIds)
    }

    @Test
    fun `TRY over a pure expression is VM-equivalent (Ok passthrough)`() {
        val l = load("""{
          "version": 1, "root": "att",
          "nodes": {
            "lit": { "type": "IntLit", "value": 42 },
            "att": { "type": "Attempt", "body": "lit" }
          }
        }""")
        val interp = Interpreter(l.store, l.hashToNodeId).eval(l.root)
        val table = Lowerer(l.store, l.hashToNodeId).lower(l.root)
        val vm = Vm(table).run()
        assertEquals(interp, vm)
        assertEquals(Value.SumV("Ok", Value.IntV(42)), vm)
    }

    private val fsReadAttemptJson = """{
      "version": 1, "root": "att",
      "nodes": {
        "stringT":  { "type": "PrimitiveType", "kind": "String" },
        "bytesT":   { "type": "PrimitiveType", "kind": "Bytes" },
        "readFx":   { "type": "EffectCategory", "categoryName": "Filesystem.Read" },
        "fsReadT":  { "type": "FunctionType", "parameters": ["stringT"], "result": "bytesT", "effects": ["readFx"] },
        "fsRead":   { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "fsReadT", "effects": ["readFx"] },
        "pathS":    { "type": "StringLit", "value": "strand-nonexistent-dir-xyz/missing.json" },
        "readApp":  { "type": "Application", "function": "fsRead", "arguments": ["pathS"] },
        "att":      { "type": "Attempt", "body": "readApp" }
      }
    }"""

    @Test
    fun `TRY over a failing Fs Read is VM-equivalent (Err with filesystem-read kind)`() {
        val l = load(fsReadAttemptJson)
        val interp = Interpreter(l.store, l.hashToNodeId)
            .eval(l.root, capabilities = CapabilitySet.ofCategories(
                l.names.values.filter { l.store.getOrNull(it) is Node.EffectCategory }.toSet()
            ))
        val table = Lowerer(l.store, l.hashToNodeId).lower(l.root)
        val vm = Vm(table).run(initialCaps = l.effectIds)

        // Both backends produce Err({kind = "filesystem-read", detail = ...}).
        val vmSum = vm as Value.SumV
        assertEquals("Err", vmSum.case)
        val vmPayload = vmSum.payload as Value.ProductV
        assertEquals(Value.StringV("filesystem-read"), vmPayload.fields["kind"])

        // The VM and interpreter must agree exactly — the Err payload excludes
        // NodeIds, so the detail strings match (both come from the same
        // Builtins IoFailure construction under the same verbosity gate).
        assertEquals(interp, vm)
    }

    @Test
    fun `CapabilityViolation inside TRY propagates uncaught in the VM`() {
        val l = load(fsReadAttemptJson)
        val table = Lowerer(l.store, l.hashToNodeId).lower(l.root)
        // No capabilities granted: the effectful call is denied. Q-064
        // unified the VM's denial shape with the interpreter's at the
        // InterpretError boundary; the error is uncatchable and must
        // escape TRY.
        val ex = org.junit.jupiter.api.assertThrows<InterpretException> {
            Vm(table).run(initialCaps = emptySet())
        }
        val err = ex.error as InterpretError.CapabilityViolation
        val readFx = l.names.getValue("readFx")
        assertEquals(setOf(readFx), err.missing)
        // The VM-side DenialReport is honest about its coarseness: the
        // category renders as the NodeId (no store to resolve the name),
        // requested is null (category-only checking — no refinement
        // parameters fabricated), held is empty, no denying NodeId.
        assertEquals(readFx.toString(), err.report.category)
        assertEquals(null, err.report.requested)
        assertEquals(emptyList<String>(), err.report.held)
        assertEquals(null, err.report.node)
        assertEquals(org.strand.interpreter.DenialPhase.Expression, err.report.phase)
    }
}
