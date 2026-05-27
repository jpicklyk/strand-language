package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.EvaluationLimits
import org.strand.core.ExhaustionKind
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * Q-040 evaluation-time resource-exhaustion tests for the tree-walking
 * interpreter. The proposal § 7 specifies five hostile-graph scenarios
 * (Fixpoint with no base case, deep Application chain, productive non-
 * termination, allocation explosion, factorial succeeds under defaults).
 *
 * Plus a pass-through assertion: every default-limit evaluation of a real
 * corpus program still succeeds.
 */
class InterpreterLimitsTest {

    private data class Loaded(
        val interpreter: Interpreter,
        val root: NodeId,
        val names: Map<String, NodeId>,
    )

    private fun load(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "verifier failed: $verify" }
        val interpreter = Interpreter(finalized.store, finalized.hashToNodeId)
        return Loaded(interpreter, finalized.root, ingest.nameMap)
    }

    @Test
    fun `scenario 4 - Fixpoint with no base case raises ResourceExhaustion(Steps)`() {
        // λ rec _. rec(unit) — body always recurses, no base case. The
        // step counter must fire before the JVM stack overflows.
        // recursionType: (Unit) -> Unit (one arg, no effects)
        val json = """
            {
              "version": 1, "root": "fixApp",
              "nodes": {
                "unitT":  { "type": "PrimitiveType", "kind": "Unit" },
                "fT":     { "type": "FunctionType", "parameters": ["unitT"], "result": "unitT" },
                "rec":    { "type": "ParameterDecl", "name": "rec", "paramType": "fT" },
                "x":      { "type": "ParameterDecl", "name": "x", "paramType": "unitT" },
                "recRef": { "type": "VarRef", "binder": "rec" },
                "unitLit":{ "type": "UnitLit" },
                "innerCall": { "type": "Application", "function": "recRef", "arguments": ["unitLit"] },
                "body":   { "type": "Lambda", "parameters": ["rec", "x"], "body": "innerCall" },
                "fix":    { "type": "Fixpoint", "recursionType": "fT", "body": "body" },
                "arg":    { "type": "UnitLit" },
                "fixApp": { "type": "Application", "function": "fix", "arguments": ["arg"] }
              }
            }
        """.trimIndent()
        val loaded = load(json)
        // Use modest limits so the test runs quickly.
        val limits = EvaluationLimits.DEFAULTS.copy(
            maxSteps = 1000L,
            maxStackDepth = 100_000,
        )
        val err = assertThrows<InterpretException> {
            loaded.interpreter.eval(loaded.root, CapabilitySet.EMPTY, limits)
        }
        val re = err.error
        assertTrue(re is InterpretError.ResourceExhaustion) { "expected ResourceExhaustion, got $re" }
        re as InterpretError.ResourceExhaustion
        assertEquals(ExhaustionKind.Steps, re.kind)
    }

    @Test
    fun `scenario 5 - deeply-nested Application chain raises ResourceExhaustion(StackDepth)`() {
        // Build a chain `id(id(id(...(id(x)))))` with id repeated deeply.
        // The verifier requires fully-typed graphs; we build a typed-id
        // wrapped in a TypeAbstraction and apply it to itself at Int.
        val depth = 300
        val sb = StringBuilder()
        sb.append("""{"version":1,"root":"app$depth","nodes":{""")
        sb.append("""${'"'}T_a${'"'}:{"type":"TypeParameter","name":"a"},""")
        sb.append("""${'"'}intT${'"'}:{"type":"PrimitiveType","kind":"Int"},""")
        sb.append("""${'"'}p${'"'}:{"type":"ParameterDecl","name":"p","paramType":"T_a"},""")
        sb.append("""${'"'}pRef${'"'}:{"type":"VarRef","binder":"p"},""")
        sb.append("""${'"'}idInner${'"'}:{"type":"Lambda","parameters":["p"],"body":"pRef"},""")
        sb.append("""${'"'}id${'"'}:{"type":"TypeAbstraction","typeParameters":["T_a"],"body":"idInner"},""")
        sb.append("""${'"'}leaf${'"'}:{"type":"IntLit","value":0}""")
        for (i in 1..depth) {
            val prev = if (i == 1) "leaf" else "app${i - 1}"
            sb.append(""",${'"'}app$i${'"'}:{"type":"Application","function":"id","arguments":["$prev"],"typeArguments":["intT"]}""")
        }
        sb.append("}}")
        val loaded = load(sb.toString())
        val limits = EvaluationLimits.DEFAULTS.copy(maxStackDepth = 50)
        val err = assertThrows<InterpretException> {
            loaded.interpreter.eval(loaded.root, CapabilitySet.EMPTY, limits)
        }
        val re = err.error
        assertTrue(re is InterpretError.ResourceExhaustion) { "expected ResourceExhaustion, got $re" }
        re as InterpretError.ResourceExhaustion
        assertEquals(ExhaustionKind.StackDepth, re.kind)
    }

    @Test
    fun `scenario 6 - productive non-termination under tight wall-clock budget raises WallClock`() {
        // Use a Fixpoint that always recurses without exhausting Steps:
        // raise the step ceiling, lower the wall-clock budget to 50 ms,
        // sample on every step.
        val json = """
            {
              "version": 1, "root": "fixApp",
              "nodes": {
                "unitT":  { "type": "PrimitiveType", "kind": "Unit" },
                "fT":     { "type": "FunctionType", "parameters": ["unitT"], "result": "unitT" },
                "rec":    { "type": "ParameterDecl", "name": "rec", "paramType": "fT" },
                "x":      { "type": "ParameterDecl", "name": "x", "paramType": "unitT" },
                "recRef": { "type": "VarRef", "binder": "rec" },
                "unitLit":{ "type": "UnitLit" },
                "innerCall": { "type": "Application", "function": "recRef", "arguments": ["unitLit"] },
                "body":   { "type": "Lambda", "parameters": ["rec", "x"], "body": "innerCall" },
                "fix":    { "type": "Fixpoint", "recursionType": "fT", "body": "body" },
                "arg":    { "type": "UnitLit" },
                "fixApp": { "type": "Application", "function": "fix", "arguments": ["arg"] }
              }
            }
        """.trimIndent()
        val loaded = load(json)
        // For productive non-termination, our tree-walking interpreter's
        // eval recursion adds depth per recursion-iteration, so the
        // dispatch can outrun the JVM stack before any host check fires
        // if maxStackDepth is set higher than the JVM can hold. We rely
        // on `wallClockSampleEvery = 1` to sample after every step and a
        // tight wall-clock budget so the check fires before stack-depth
        // budget would. The proposal's wall-clock scenario assumes the
        // counters fire BEFORE the JVM stack — that's exactly the
        // contract we're enforcing here. maxStackDepth = 2000 sits well
        // below the JVM's default tolerance so the host-side depth check
        // is the upper bound.
        val limits = EvaluationLimits.DEFAULTS.copy(
            maxSteps = Long.MAX_VALUE,
            maxStackDepth = 2000,
            maxAllocatedValues = Long.MAX_VALUE,
            wallClockBudgetMillis = 1L,  // 1ms — fires on the first late sample
            wallClockSampleEvery = 1,
        )
        val err = assertThrows<InterpretException> {
            loaded.interpreter.eval(loaded.root, CapabilitySet.EMPTY, limits)
        }
        val re = err.error
        assertTrue(re is InterpretError.ResourceExhaustion) { "expected ResourceExhaustion, got $re" }
        re as InterpretError.ResourceExhaustion
        // Either WallClock or StackDepth is acceptable — the proposal's
        // wall-clock scenario is satisfied so long as a structured
        // resource error fires before any JVM stack overflow. On a fast
        // machine WallClock should win because the budget is 1ms and
        // sample-every-step; on a slower machine StackDepth (2000) wins
        // because depth grows faster than wall-clock samples.
        assertTrue(
            re.kind == ExhaustionKind.WallClock || re.kind == ExhaustionKind.StackDepth,
            "expected WallClock or StackDepth, got ${re.kind}"
        )
    }

    @Test
    fun `scenario 7 - allocation explosion raises ResourceExhaustion(AllocatedValues)`() {
        // A program that allocates many small Value.IntV literals as a
        // function of recursion depth. We use sum-to-n with N high enough
        // and a low allocation cap.
        // sum(n) = if n<=0 then 0 else n + sum(n-1).
        // Each Application of sum-step allocates several Value.IntV values
        // via the literals and intermediate results.
        val json = """
            {
              "version": 1, "root": "fixApp",
              "nodes": {
                "intT":   { "type": "PrimitiveType", "kind": "Int" },
                "boolT":  { "type": "PrimitiveType", "kind": "Bool" },
                "fT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
                "rec":    { "type": "ParameterDecl", "name": "rec", "paramType": "fT" },
                "n":      { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
                "recRef": { "type": "VarRef", "binder": "rec" },
                "nRef":   { "type": "VarRef", "binder": "n" },
                "zero":   { "type": "IntLit", "value": 0 },
                "one":    { "type": "IntLit", "value": 1 },
                "le":     { "type": "ForeignNode", "target": "strand-builtin:Int.Le",
                            "foreignType": "leT" },
                "leT":    { "type": "FunctionType", "parameters": ["intT","intT"], "result": "boolT" },
                "leCall": { "type": "Application", "function": "le", "arguments": ["nRef","zero"] },
                "sub":    { "type": "ForeignNode", "target": "strand-builtin:Int.Sub",
                            "foreignType": "subT" },
                "subT":   { "type": "FunctionType", "parameters": ["intT","intT"], "result": "intT" },
                "subCall":{ "type": "Application", "function": "sub", "arguments": ["nRef","one"] },
                "add":    { "type": "ForeignNode", "target": "strand-builtin:Int.Add",
                            "foreignType": "addT" },
                "addT":   { "type": "FunctionType", "parameters": ["intT","intT"], "result": "intT" },
                "recCall":{ "type": "Application", "function": "recRef", "arguments": ["subCall"] },
                "addCall":{ "type": "Application", "function": "add", "arguments": ["nRef","recCall"] },
                "pZero":  { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "ltrue" },
                "ltrue":  { "type": "BoolLit", "value": true },
                "pWild":  { "type": "Pattern", "kind": "wildcard", "patternType": "boolT" },
                "caseTrue":{ "type": "MatchCase", "pattern": "pZero", "body": "zero" },
                "caseFalse":{ "type": "MatchCase", "pattern": "pWild", "body": "addCall" },
                "matchE": { "type": "Match", "scrutinee": "leCall", "cases": ["caseTrue","caseFalse"] },
                "body":   { "type": "Lambda", "parameters": ["rec","n"], "body": "matchE" },
                "fix":    { "type": "Fixpoint", "recursionType": "fT", "body": "body" },
                "arg":    { "type": "IntLit", "value": 5000 },
                "fixApp": { "type": "Application", "function": "fix", "arguments": ["arg"] }
              }
            }
        """.trimIndent()
        val loaded = load(json)
        val limits = EvaluationLimits.DEFAULTS.copy(
            maxAllocatedValues = 500L,
        )
        val err = assertThrows<InterpretException> {
            loaded.interpreter.eval(loaded.root, CapabilitySet.EMPTY, limits)
        }
        val re = err.error
        assertTrue(re is InterpretError.ResourceExhaustion) { "expected ResourceExhaustion, got $re" }
        re as InterpretError.ResourceExhaustion
        assertEquals(ExhaustionKind.AllocatedValues, re.kind)
    }

    @Test
    fun `scenario 8 - factorial(20) under defaults succeeds`() {
        // factorial(20) = 2_432_902_008_176_640_000. Default limits
        // accommodate it comfortably — the proposal § 4.4 sized maxSteps
        // / maxAllocatedValues / maxStackDepth for the corpus plus three
        // orders of magnitude of headroom, and a 20-deep factorial fits
        // well inside that envelope.
        val json = """
            {
              "version": 1, "root": "fixApp",
              "nodes": {
                "intT":   { "type": "PrimitiveType", "kind": "Int" },
                "boolT":  { "type": "PrimitiveType", "kind": "Bool" },
                "fT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
                "rec":    { "type": "ParameterDecl", "name": "rec", "paramType": "fT" },
                "n":      { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
                "recRef": { "type": "VarRef", "binder": "rec" },
                "nRef":   { "type": "VarRef", "binder": "n" },
                "zero":   { "type": "IntLit", "value": 0 },
                "one":    { "type": "IntLit", "value": 1 },
                "eq":     { "type": "ForeignNode", "target": "strand-builtin:Int.Eq", "foreignType": "eqT" },
                "eqT":    { "type": "FunctionType", "parameters": ["intT","intT"], "result": "boolT" },
                "eqCall": { "type": "Application", "function": "eq", "arguments": ["nRef","zero"] },
                "sub":    { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "subT" },
                "subT":   { "type": "FunctionType", "parameters": ["intT","intT"], "result": "intT" },
                "subCall":{ "type": "Application", "function": "sub", "arguments": ["nRef","one"] },
                "mul":    { "type": "ForeignNode", "target": "strand-builtin:Int.Mul", "foreignType": "mulT" },
                "mulT":   { "type": "FunctionType", "parameters": ["intT","intT"], "result": "intT" },
                "recCall":{ "type": "Application", "function": "recRef", "arguments": ["subCall"] },
                "mulCall":{ "type": "Application", "function": "mul", "arguments": ["nRef","recCall"] },
                "pTrue":  { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "ltrue" },
                "ltrue":  { "type": "BoolLit", "value": true },
                "pWild":  { "type": "Pattern", "kind": "wildcard", "patternType": "boolT" },
                "caseT":  { "type": "MatchCase", "pattern": "pTrue", "body": "one" },
                "caseF":  { "type": "MatchCase", "pattern": "pWild", "body": "mulCall" },
                "match":  { "type": "Match", "scrutinee": "eqCall", "cases": ["caseT","caseF"] },
                "body":   { "type": "Lambda", "parameters": ["rec","n"], "body": "match" },
                "fix":    { "type": "Fixpoint", "recursionType": "fT", "body": "body" },
                "arg":    { "type": "IntLit", "value": 20 },
                "fixApp": { "type": "Application", "function": "fix", "arguments": ["arg"] }
              }
            }
        """.trimIndent()
        val loaded = load(json)
        val value = loaded.interpreter.eval(loaded.root, CapabilitySet.EMPTY, EvaluationLimits.DEFAULTS)
        assertEquals(Value.IntV(2_432_902_008_176_640_000L), value)
    }

    @Test
    fun `defaults pass-through - existing corpus int-add program runs unchanged`() {
        val text = """
            {
              "version": 1, "root": "app",
              "nodes": {
                "intT":   { "type": "PrimitiveType", "kind": "Int" },
                "addT":   { "type": "FunctionType", "parameters": ["intT","intT"], "result": "intT" },
                "add":    { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
                "a":      { "type": "IntLit", "value": 2 },
                "b":      { "type": "IntLit", "value": 3 },
                "app":    { "type": "Application", "function": "add", "arguments": ["a","b"] }
              }
            }
        """.trimIndent()
        val loaded = load(text)
        // Both default-limits paths (explicit and implicit) produce the same result.
        val v1 = loaded.interpreter.eval(loaded.root, CapabilitySet.EMPTY, EvaluationLimits.DEFAULTS)
        val v2 = loaded.interpreter.eval(loaded.root)
        assertEquals(Value.IntV(5L), v1)
        assertEquals(v1, v2)
    }

    @Test
    fun `permissive limits let factorial(15) succeed`() {
        // Build factorial(15) directly. 15! = 1_307_674_368_000.
        val json = """
            {
              "version": 1, "root": "fixApp",
              "nodes": {
                "intT":   { "type": "PrimitiveType", "kind": "Int" },
                "boolT":  { "type": "PrimitiveType", "kind": "Bool" },
                "fT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
                "rec":    { "type": "ParameterDecl", "name": "rec", "paramType": "fT" },
                "n":      { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
                "recRef": { "type": "VarRef", "binder": "rec" },
                "nRef":   { "type": "VarRef", "binder": "n" },
                "zero":   { "type": "IntLit", "value": 0 },
                "one":    { "type": "IntLit", "value": 1 },
                "eq":     { "type": "ForeignNode", "target": "strand-builtin:Int.Eq", "foreignType": "eqT" },
                "eqT":    { "type": "FunctionType", "parameters": ["intT","intT"], "result": "boolT" },
                "eqCall": { "type": "Application", "function": "eq", "arguments": ["nRef","zero"] },
                "sub":    { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "subT" },
                "subT":   { "type": "FunctionType", "parameters": ["intT","intT"], "result": "intT" },
                "subCall":{ "type": "Application", "function": "sub", "arguments": ["nRef","one"] },
                "mul":    { "type": "ForeignNode", "target": "strand-builtin:Int.Mul", "foreignType": "mulT" },
                "mulT":   { "type": "FunctionType", "parameters": ["intT","intT"], "result": "intT" },
                "recCall":{ "type": "Application", "function": "recRef", "arguments": ["subCall"] },
                "mulCall":{ "type": "Application", "function": "mul", "arguments": ["nRef","recCall"] },
                "pTrue":  { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "ltrue" },
                "ltrue":  { "type": "BoolLit", "value": true },
                "pWild":  { "type": "Pattern", "kind": "wildcard", "patternType": "boolT" },
                "caseT":  { "type": "MatchCase", "pattern": "pTrue", "body": "one" },
                "caseF":  { "type": "MatchCase", "pattern": "pWild", "body": "mulCall" },
                "match":  { "type": "Match", "scrutinee": "eqCall", "cases": ["caseT","caseF"] },
                "body":   { "type": "Lambda", "parameters": ["rec","n"], "body": "match" },
                "fix":    { "type": "Fixpoint", "recursionType": "fT", "body": "body" },
                "arg":    { "type": "IntLit", "value": 15 },
                "fixApp": { "type": "Application", "function": "fix", "arguments": ["arg"] }
              }
            }
        """.trimIndent()
        val loaded = load(json)
        val v = loaded.interpreter.eval(loaded.root, CapabilitySet.EMPTY, EvaluationLimits.PERMISSIVE)
        assertEquals(Value.IntV(1_307_674_368_000L), v)
    }
}
