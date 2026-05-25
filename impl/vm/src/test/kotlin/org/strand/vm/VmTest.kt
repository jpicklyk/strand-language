package org.strand.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.strand.bytecode.LoweringNotImplemented
import org.strand.bytecode.Lowerer
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Layer 1 equivalence test for the bytecode VM (Q-017 step 1).
 *
 * For each program in this test's fixture set, the test:
 *   1. Ingests the JSON, finalizes it, verifies.
 *   2. Runs the tree-walking [Interpreter] over the verified store.
 *   3. Lowers the same store to a [ChunkTable] via [Lowerer].
 *   4. Runs the [Vm] over the chunks.
 *   5. Asserts both produced equal [Value]s.
 *
 * Programs that use Layer 3+ features (effects, foreign nodes, match,
 * fixpoint, products, sums, state machines) are excluded from this slice
 * — they hit either [LoweringNotImplemented] in the lowerer or
 * [VmOpcodeNotImplemented] in the VM, which is the correct behavior:
 * the test surface tells us what's missing as those layers reach the VM.
 *
 * The Layer 1 fixtures here mirror the corpus's pure-computation
 * programs (01-11). The :corpus module hosts a richer
 * VmCorpusEquivalenceTest that walks the actual corpus once all layers
 * are wired.
 */
class VmTest {

    @Test
    fun `int literal returns the same value as the interpreter`() {
        assertVmEquivalentToInterpreter(
            """{"version":1, "root":"n", "nodes":{"n":{"type":"IntLit","value":42}}}"""
        )
    }

    @Test
    fun `bool literal returns the same value as the interpreter`() {
        assertVmEquivalentToInterpreter(
            """{"version":1, "root":"b", "nodes":{"b":{"type":"BoolLit","value":true}}}"""
        )
    }

    @Test
    fun `string literal returns the same value as the interpreter`() {
        assertVmEquivalentToInterpreter(
            """{"version":1, "root":"s", "nodes":{"s":{"type":"StringLit","value":"hello"}}}"""
        )
    }

    @Test
    fun `let returns its body value`() {
        // let x = 7 in x
        assertVmEquivalentToInterpreter(
            """
            {
              "version": 1,
              "root": "letNode",
              "nodes": {
                "intT": { "type": "PrimitiveType", "kind": "Int" },
                "seven": { "type": "IntLit", "value": 7 },
                "letNode": { "type": "Let", "name": "x", "value": "seven", "body": "xRef" },
                "xRef": { "type": "VarRef", "binder": "letNode" }
              }
            }
            """.trimIndent()
        )
    }

    @Test
    fun `polymorphic identity at Int returns the argument value`() {
        // (Λa. λx:a.x) [Int] (42) → 42
        assertVmEquivalentToInterpreter(
            """
            {
              "version": 1,
              "root": "app",
              "nodes": {
                "T_a":     { "type": "TypeParameter", "name": "a" },
                "intT":    { "type": "PrimitiveType", "kind": "Int" },
                "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
                "x_use":   { "type": "VarRef", "binder": "x" },
                "idInner": { "type": "Lambda", "parameters": ["x"], "body": "x_use" },
                "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
                "arg":     { "type": "IntLit", "value": 42 },
                "app":     { "type": "Application", "function": "id", "arguments": ["arg"], "typeArguments": ["intT"] }
              }
            }
            """.trimIndent()
        )
    }

    @Test
    fun `lambda captures outer let-bound value`() {
        // let y = 7 in ((λx:Int. y) 1) → 7
        assertVmEquivalentToInterpreter(
            """
            {
              "version": 1,
              "root": "outer",
              "nodes": {
                "intT": { "type": "PrimitiveType", "kind": "Int" },
                "seven": { "type": "IntLit", "value": 7 },
                "yLet": { "type": "Let", "name": "y", "value": "seven", "body": "appInner" },
                "outer": { "type": "Let", "name": "_top", "value": "yLet", "body": "yLet_ref" },
                "yLet_ref": { "type": "VarRef", "binder": "outer" },
                "x": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
                "yRefInLam": { "type": "VarRef", "binder": "yLet" },
                "lam": { "type": "Lambda", "parameters": ["x"], "body": "yRefInLam" },
                "one": { "type": "IntLit", "value": 1 },
                "appInner": { "type": "Application", "function": "lam", "arguments": ["one"] }
              }
            }
            """.trimIndent()
        )
    }

    @Test
    fun `Layer 4 ForeignNode dispatch produces same result as interpreter for Int Add`() {
        // Int.Add(2, 3) → 5
        assertVmEquivalentToInterpreter(
            """
            {
              "version": 1,
              "root": "app",
              "nodes": {
                "intT": { "type": "PrimitiveType", "kind": "Int" },
                "addT": { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
                "addFn": { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
                "two": { "type": "IntLit", "value": 2 },
                "three": { "type": "IntLit", "value": 3 },
                "app": { "type": "Application", "function": "addFn", "arguments": ["two", "three"] }
              }
            }
            """.trimIndent()
        )
    }

    @Test
    fun `Match dispatches to the first matching case`() {
        // Match scrutinee=0 against [literal 0 → 1, wildcard → 99].
        // Expected: literal 0 matches; result is 1.
        assertVmEquivalentToInterpreter(
            """
            {
              "version": 1,
              "root": "m",
              "nodes": {
                "intT": { "type": "PrimitiveType", "kind": "Int" },
                "scrut": { "type": "IntLit", "value": 0 },
                "lit0": { "type": "IntLit", "value": 0 },
                "p0": { "type": "Pattern", "kind": "literal", "patternType": "intT", "literal": "lit0" },
                "body0": { "type": "IntLit", "value": 1 },
                "c0": { "type": "MatchCase", "pattern": "p0", "body": "body0" },
                "pw": { "type": "Pattern", "kind": "wildcard", "patternType": "intT" },
                "body1": { "type": "IntLit", "value": 99 },
                "c1": { "type": "MatchCase", "pattern": "pw", "body": "body1" },
                "m": { "type": "Match", "scrutinee": "scrut", "cases": ["c0", "c1"] }
              }
            }
            """.trimIndent()
        )
    }

    @Test
    fun `Match falls through literal to wildcard when scrutinee differs`() {
        // Match scrutinee=42 against [literal 0 → 1, wildcard → 99].
        // Expected: literal 0 fails; wildcard matches; result is 99.
        assertVmEquivalentToInterpreter(
            """
            {
              "version": 1,
              "root": "m",
              "nodes": {
                "intT": { "type": "PrimitiveType", "kind": "Int" },
                "scrut": { "type": "IntLit", "value": 42 },
                "lit0": { "type": "IntLit", "value": 0 },
                "p0": { "type": "Pattern", "kind": "literal", "patternType": "intT", "literal": "lit0" },
                "body0": { "type": "IntLit", "value": 1 },
                "c0": { "type": "MatchCase", "pattern": "p0", "body": "body0" },
                "pw": { "type": "Pattern", "kind": "wildcard", "patternType": "intT" },
                "body1": { "type": "IntLit", "value": 99 },
                "c1": { "type": "MatchCase", "pattern": "pw", "body": "body1" },
                "m": { "type": "Match", "scrutinee": "scrut", "cases": ["c0", "c1"] }
              }
            }
            """.trimIndent()
        )
    }

    private fun assertVmEquivalentToInterpreter(json: String) {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verifyResult = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        check(verifyResult is VerifyResult.Ok) { "fixture failed verify: $verifyResult" }

        val interpreterValue = Interpreter(finalized.store, finalized.hashToNodeId)
            .eval(finalized.root)
        val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(finalized.root)
        val vmValue = Vm(table).run()

        assertEquals(interpreterValue, vmValue) {
            "VM produced ${vmValue} but interpreter produced ${interpreterValue}"
        }
    }
}
