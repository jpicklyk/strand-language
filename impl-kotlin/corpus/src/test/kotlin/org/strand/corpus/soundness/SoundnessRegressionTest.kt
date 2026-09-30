package org.strand.corpus.soundness

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.hashing.Hasher
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.runtime.ProgramImage
import org.strand.runtime.StrandRuntime
import org.strand.verifier.ProgramAnalysis
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * Permanent regressions for the failures [EffectClosureSoundnessFuzzTest]
 * found, one test per distinct failure class, each a hand-minimized form of
 * the shrunk program the campaign reported.
 *
 * A failure whose fix is a rejection reachable from dag-json also has a
 * `corpus/negative/` entry (59 to 64); the tests here cover the rest, where
 * the fix changes what an admitted program's closure is or how a backend
 * runs it. Each admitted program is put back through every property of
 * [SoundnessHarness], so a regression in any of them fails here first with
 * a program small enough to read.
 */
class SoundnessRegressionTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun install() = FuzzHost.install()

        @JvmStatic
        @AfterAll
        fun uninstall() = FuzzHost.uninstall()

        /** Types, categories and stand-in bindings shared by every program below. */
        private val COMMON = """
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "strT":  { "type": "PrimitiveType", "kind": "String" },
            "boolT": { "type": "PrimitiveType", "kind": "Bool" },
            "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
            "catA":  { "type": "EffectCategory", "categoryName": "Fuzz.A", "parameters": [] },
            "catB":  { "type": "EffectCategory", "categoryName": "Fuzz.B", "parameters": [] },
            "catP":  { "type": "EffectCategory", "categoryName": "Fuzz.P", "parameters": ["intT"] },
            "catR":  { "type": "EffectCategory", "categoryName": "Fuzz.R", "parameters": ["strT", "intT"] },
            "iiT":   { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "iiAT":  { "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["catA"] },
            "ssT":   { "type": "FunctionType", "parameters": ["strT"], "result": "strT" },
            "stdA":  { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.FA", "foreignType": "iiT", "effects": ["catA"] },
            "stdAonType": { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.FA", "foreignType": "iiAT" },
            "stdB":  { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.FB", "foreignType": "iiT", "effects": ["catB"] },
            "stdP":  { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.FP", "foreignType": "iiT", "effects": ["catP"] },
            "stdPp": { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.FPp", "foreignType": "iiT", "effects": ["catP"],
                       "effectProjections": [ { "category": "catP", "sources": [ { "kind": "ArgRef", "index": 0 } ] } ] },
            "stdR":  { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.FR", "foreignType": "iiT", "effects": ["catR"] },
            "stdSA": { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.SA", "foreignType": "ssT", "effects": ["catA"] },
            "i0": { "type": "IntLit", "value": 0 },
            "i1": { "type": "IntLit", "value": 1 },
            "i8": { "type": "IntLit", "value": 8 },
            "sA": { "type": "StringLit", "value": "a" },
            "toolSchema": { "type": "Schema", "schemaName": "ToolParam", "valueType": "intT", "invariants": [] },
            "callToolT": { "type": "FunctionType", "parameters": ["bytesT", "intT"], "result": "intT" },
            "callTool": { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.CallTool", "foreignType": "callToolT" }
        """.trimIndent()

        /** The list type and `List.Map` at `(Int) -> Int ! {Fuzz.A}` callbacks. */
        private val LISTS = """
            "listSelf": { "type": "RecursiveSelf" },
            "headIn": { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tailIn": { "type": "ProductTypeField", "name": "tail", "fieldType": "listSelf" },
            "consIn": { "type": "ProductType", "fields": ["headIn", "tailIn"] },
            "consCase": { "type": "SumTypeCase", "name": "Cons", "caseType": "consIn" },
            "nilCase": { "type": "SumTypeCase", "name": "Nil" },
            "listBody": { "type": "SumType", "cases": ["consCase", "nilCase"] },
            "listT": { "type": "RecursiveType", "body": "listBody" },
            "headOut": { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tailOut": { "type": "ProductTypeField", "name": "tail", "fieldType": "listT" },
            "consOut": { "type": "ProductType", "fields": ["headOut", "tailOut"] },
            "nil": { "type": "SumValue", "ofType": "listT", "caseName": "Nil" },
            "hd": { "type": "ProductFieldValue", "fieldName": "head", "value": "i1" },
            "tl": { "type": "ProductFieldValue", "fieldName": "tail", "value": "nil" },
            "cell": { "type": "ProductValue", "ofType": "consOut", "fields": ["hd", "tl"] },
            "oneElem": { "type": "SumValue", "ofType": "listT", "caseName": "Cons", "payload": "cell" },
            "mapT": { "type": "FunctionType", "parameters": ["listT", "iiAT"], "result": "listT" },
            "map": { "type": "ForeignNode", "target": "strand-builtin:List.Map", "foreignType": "mapT" }
        """.trimIndent()

        /** Schema 0 (`x > 3`) and schema 1 (`x < 7`) over Int, with a stand-in consumer for each. */
        private val SCHEMAS = (0..1).joinToString(",\n") { k ->
            val cmp = if (k == 0) "Int.Gt" else "Int.Lt"
            val bound = if (k == 0) 3 else 7
            """
            "invX$k": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "invXRef$k": { "type": "VarRef", "binder": "invX$k" },
            "invBound$k": { "type": "IntLit", "value": $bound },
            "cmpT$k": { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "cmp$k": { "type": "ForeignNode", "target": "strand-builtin:$cmp", "foreignType": "cmpT$k" },
            "invBody$k": { "type": "Application", "function": "cmp$k", "arguments": ["invXRef$k", "invBound$k"] },
            "invLam$k": { "type": "Lambda", "parameters": ["invX$k"], "body": "invBody$k" },
            "inv$k": { "type": "Invariant", "invariantName": "bound$k", "targetSchema": "schema$k", "body": "invLam$k" },
            "schema$k": { "type": "Schema", "schemaName": "S$k", "valueType": "intT", "invariants": ["inv$k"] },
            "consumeT$k": { "type": "FunctionType", "parameters": ["schema$k"], "result": "intT" },
            "consume$k": { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.Consume$k", "foreignType": "consumeT$k" }
            """.trimIndent()
        }

        private fun program(root: String, nodes: String, extra: String = ""): String {
            val parts = listOf(COMMON, extra, nodes.trimIndent()).filter { it.isNotBlank() }
            return "{ \"version\": 1, \"root\": \"$root\", \"nodes\": {\n${parts.joinToString(",\n")}\n} }"
        }
    }

    private val harness = SoundnessHarness()

    private fun case(json: String, rootTy: Ty, grants: List<GrantSpec>, features: Set<String> = emptySet()) =
        GenCase(json, Mode.Expression, rootTy, emptyMap(), grants, features, 0)

    /** Every property holds for [json] under the standard grants plus [grants]. */
    private fun assertSound(
        json: String,
        rootTy: Ty = Ty.IntT,
        grants: List<GrantSpec> = emptyList(),
        features: Set<String> = emptySet(),
    ): CaseOutcome.Checked {
        val outcome = harness.check(case(json, rootTy, grants, features))
        assertTrue(outcome is CaseOutcome.Checked) { "expected the program to be admitted, got $outcome" }
        outcome as CaseOutcome.Checked
        assertTrue(outcome.violations.isEmpty()) { "violations: ${outcome.violations.joinToString("\n")}" }
        return outcome
    }

    private fun assertRejected(json: String, stage: String, family: String) {
        assertEquals(CaseOutcome.Rejected(stage, family), harness.check(case(json, Ty.IntT, emptyList())))
    }

    /** The category names in the program's total closure. */
    private fun totalClosure(json: String): Set<String> {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "expected the program to verify, got $verify" }
        return ProgramAnalysis(finalized.store, verify as VerifyResult.Ok, finalized.root).totalClosure()
            .mapTo(sortedSetOf()) { (finalized.store.get(it) as Node.EffectCategory).categoryName }
    }

    private fun refined(cat: Cat, vararg slots: Slot) =
        GrantSpec("refined", mapOf(cat to listOf(PatternSpec(sentinel = false, slots = slots.toList()))))

    // ------------------------------------------------------------------
    // S1: effects performed outside the surfaced closure
    // ------------------------------------------------------------------

    /**
     * S1. An EffectDecl parameter that performs an effect ran at the call
     * site while the Application's closure held only the callee's row: the
     * program surfaced `{Fuzz.R}` and performed `Fuzz.A`. Now rejected
     * (negative corpus 59).
     */
    @Test
    fun `an effectful EffectDecl parameter is rejected`() {
        val json = program("call", """
            "paramApp": { "type": "Application", "function": "stdA", "arguments": ["i0"] },
            "decl": { "type": "EffectDecl", "effectType": "catR", "parameters": ["sA", "paramApp"] },
            "call": { "type": "Application", "function": "stdR", "arguments": ["i0"], "effectInstances": ["decl"] }
        """)
        assertRejected(json, "verify", "EffectDeclParameterNotPure")
    }

    /**
     * S4. A projected binding's instance restates the argument node. With an
     * effectful argument the VM evaluated that node twice (argument, then
     * parameter) and performed its effect twice; the interpreter, which
     * synthesizes a projected callee's instances, performed it once. The
     * restated parameter is now subject to the purity rule.
     */
    @Test
    fun `a projected call restating an effectful argument is rejected`() {
        val json = program("call", """
            "arg": { "type": "Application", "function": "stdA", "arguments": ["i0"] },
            "decl": { "type": "EffectDecl", "effectType": "catP", "parameters": ["arg"] },
            "call": { "type": "Application", "function": "stdPp", "arguments": ["arg"], "effectInstances": ["decl"] }
        """)
        assertRejected(json, "verify", "EffectDeclParameterNotPure")
    }

    /**
     * S4. A parameter with an empty closure can still perform effects
     * through a higher-order builtin's callback; the purity rule covers the
     * latent reach as well.
     */
    @Test
    fun `an EffectDecl parameter that reaches an effect through a callback is rejected`() {
        val json = program("call", """
            "foldCbT": { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT", "effects": ["catA"] },
            "foldT": { "type": "FunctionType", "parameters": ["listT", "intT", "foldCbT"], "result": "intT" },
            "fold": { "type": "ForeignNode", "target": "strand-builtin:List.Fold", "foreignType": "foldT" },
            "acc": { "type": "ParameterDecl", "name": "acc", "paramType": "intT" },
            "elem": { "type": "ParameterDecl", "name": "elem", "paramType": "intT" },
            "elemRef": { "type": "VarRef", "binder": "elem" },
            "cbBody": { "type": "Application", "function": "stdA", "arguments": ["elemRef"] },
            "cb": { "type": "Lambda", "parameters": ["acc", "elem"], "body": "cbBody", "effects": ["catA"] },
            "folded": { "type": "Application", "function": "fold", "arguments": ["oneElem", "i0", "cb"] },
            "decl": { "type": "EffectDecl", "effectType": "catP", "parameters": ["folded"] },
            "call": { "type": "Application", "function": "stdP", "arguments": ["i0"], "effectInstances": ["decl"] }
        """, LISTS)
        assertRejected(json, "verify", "EffectDeclParameterNotPure")
    }

    /**
     * S1. A Handler subtracted its intercept from the body's closure even
     * when the category came from a nested Handler whose handle is a
     * ForeignNode. That handle fires at the intercepted call itself, not
     * through an Application, so nothing intercepts it: the program surfaced
     * `{Fuzz.B}` and performed `Fuzz.A`.
     */
    @Test
    fun `a nested foreign handler's row is not subtracted by an enclosing Handler`() {
        val json = program("outer", """
            "call": { "type": "Application", "function": "stdB", "arguments": ["i0"] },
            "inner": { "type": "Handler", "intercept": "catB", "handle": "stdA", "body": "call" },
            "outer": { "type": "Handler", "intercept": "catA", "handle": "stdB", "body": "inner" }
        """)
        assertEquals(setOf("Fuzz.A", "Fuzz.B"), totalClosure(json))
        assertSound(json)
    }

    /** The companion case: a nested Lambda handle's calls are still intercepted, so the subtraction stands. */
    @Test
    fun `a nested Lambda handler's row is still subtracted`() {
        val json = program("outer", """
            "x": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef": { "type": "VarRef", "binder": "x" },
            "viaA": { "type": "Application", "function": "stdA", "arguments": ["xRef"] },
            "lamHandle": { "type": "Lambda", "parameters": ["x"], "body": "viaA", "effects": ["catA"] },
            "call": { "type": "Application", "function": "stdB", "arguments": ["i0"] },
            "inner": { "type": "Handler", "intercept": "catB", "handle": "lamHandle", "body": "call" },
            "y": { "type": "ParameterDecl", "name": "y", "paramType": "intT" },
            "mock": { "type": "Lambda", "parameters": ["y"], "body": "i1" },
            "outer": { "type": "Handler", "intercept": "catA", "handle": "mock", "body": "inner" }
        """)
        assertEquals(emptySet<String>(), totalClosure(json))
        assertSound(json)
    }

    /**
     * S1. A ToolDef's closure was empty although evaluating it evaluates the
     * implementation expression. Here that expression projects a function
     * out of a record whose other field performs `Fuzz.A`: the program
     * surfaced the empty closure and performed `Fuzz.A`.
     */
    @Test
    fun `a ToolDef's closure includes its implementation expression`() {
        val json = program("call", """
            "viaA": { "type": "Application", "function": "stdA", "arguments": ["i0"] },
            "x": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "impl": { "type": "Lambda", "parameters": ["x"], "body": "i0" },
            "aT": { "type": "ProductTypeField", "name": "a", "fieldType": "intT" },
            "fT": { "type": "ProductTypeField", "name": "f", "fieldType": "iiT" },
            "recT": { "type": "ProductType", "fields": ["aT", "fT"] },
            "aV": { "type": "ProductFieldValue", "fieldName": "a", "value": "viaA" },
            "fV": { "type": "ProductFieldValue", "fieldName": "f", "value": "impl" },
            "rec": { "type": "ProductValue", "ofType": "recT", "fields": ["aV", "fV"] },
            "getF": { "type": "ProductFieldGet", "target": "rec", "fieldName": "f" },
            "tool": { "type": "ToolDef", "name": "t", "description": "d", "parameterSchema": "toolSchema", "implementation": "getF" },
            "call": { "type": "Application", "function": "callTool", "arguments": ["tool", "i0"] }
        """)
        assertEquals(setOf("Fuzz.A"), totalClosure(json))
        assertSound(json)
    }

    // ------------------------------------------------------------------
    // A1: the audit log as a complete record
    // ------------------------------------------------------------------

    /**
     * A1. A dispatch with no EffectDecl and no projection (every call of a
     * parameterless category written without an instance) performed its
     * effect and left no audit record, on either backend.
     */
    @Test
    fun `an uninstantiated dispatch is audited`() {
        assertSound(program("call", """
            "call": { "type": "Application", "function": "stdA", "arguments": ["i0"] }
        """))
    }

    // ------------------------------------------------------------------
    // S2 / S4: capability confinement and parity on the VM
    // ------------------------------------------------------------------

    /**
     * S2. The VM lowered only `ForeignNode.effects`, so a row declared on
     * the FunctionType alone was invisible to its capability check and the
     * effect ran under an empty grant.
     */
    @Test
    fun `an effect declared only on the foreignType is confined on the VM`() {
        assertSound(program("call", """
            "call": { "type": "Application", "function": "stdAonType", "arguments": ["i0"] }
        """))
    }

    /**
     * S4 (known gap a). A parameterized category dispatched with no instance
     * needs an unrefined grant; the VM let it run under a refined one.
     */
    @Test
    fun `an uninstantiated parameterized dispatch needs an unrefined grant on both backends`() {
        assertSound(
            program("call", """
                "call": { "type": "Application", "function": "stdP", "arguments": ["i0"] }
            """),
            grants = listOf(refined(Cat.P, Slot.IntC(0)), refined(Cat.P, Slot.Wild)),
        )
    }

    /**
     * S2, with a real file. The program declares `Filesystem.Write` with no
     * parameter and binds `Fs.Write` without a projection. A host grant
     * refined to path `b` was keyed to a category with nothing to match, the
     * uninstantiated dispatch of a parameterless category was always
     * covered, and the file `a` was written. The refinement for a registry
     * I/O builtin is now the argument it acts on
     * (`BuiltinEffectTable.resourceProjections`).
     */
    @Test
    fun `a parameterless category declaration does not defeat a path-refined grant`() {
        val outcome = assertSound(
            program("write", """
                "catW": { "type": "EffectCategory", "categoryName": "Filesystem.Write" },
                "writeT": { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
                "fsWrite": { "type": "ForeignNode", "target": "strand-builtin:Fs.Write", "foreignType": "writeT", "effects": ["catW"] },
                "payload": { "type": "BytesLit", "value": "00" },
                "write": { "type": "Application", "function": "fsWrite", "arguments": ["sA", "payload"] }
            """),
            grants = listOf(refined(Cat.W, Slot.StrC("b")), refined(Cat.W, Slot.StrC("a"))),
            features = setOf("fs-write"),
        )
        assertTrue(outcome.performed > 0) { "the write must run under the grants that cover it" }
    }

    /**
     * S2, with a real file. The same bypass with the category declared
     * properly: an unprojected binding took its refinement from the call
     * site's EffectDecl, which declared path `b` while the argument was `a`.
     */
    @Test
    fun `an authored instance does not redirect the path a write is checked against`() {
        assertSound(
            program("write", """
                "catW": { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
                "writeT": { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
                "fsWrite": { "type": "ForeignNode", "target": "strand-builtin:Fs.Write", "foreignType": "writeT", "effects": ["catW"] },
                "payload": { "type": "BytesLit", "value": "00" },
                "declared": { "type": "StringLit", "value": "b" },
                "decl": { "type": "EffectDecl", "effectType": "catW", "parameters": ["declared"] },
                "write": { "type": "Application", "function": "fsWrite", "arguments": ["sA", "payload"], "effectInstances": ["decl"] }
            """),
            grants = listOf(refined(Cat.W, Slot.StrC("b")), refined(Cat.W, Slot.StrC("a"))),
            features = setOf("fs-write"),
        )
    }

    /**
     * A1 and S2. A higher-order builtin was treated as a propagating site
     * for its whole row, on the ground that its row is its callbacks'. One
     * that performs an effect itself (a provider binding calls out and also
     * runs tool implementations) therefore ran that effect unrecorded when
     * the call carried no EffectDecl, and a refined grant covered it. Its
     * own effects are now performed at its dispatch, on both backends.
     */
    @Test
    fun `a higher-order builtin's own effect is audited and needs an unrefined grant`() {
        val outcome = assertSound(
            program("call", """
                "x": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
                "cb": { "type": "Lambda", "parameters": ["x"], "body": "i0", "effects": [] },
                "hopT": { "type": "FunctionType", "parameters": ["intT", "iiT"], "result": "intT", "effects": ["catP"] },
                "hop": { "type": "ForeignNode", "target": "strand-builtin:Test.Fuzz.HoP", "foreignType": "hopT", "effects": ["catP"] },
                "call": { "type": "Application", "function": "hop", "arguments": ["i0", "cb"] }
            """),
            grants = listOf(refined(Cat.P, Slot.IntC(0)), refined(Cat.P, Slot.Wild)),
        )
        assertTrue(outcome.vmSupported)
        assertTrue(outcome.performed > 0)
        assertTrue(outcome.denials > 0) { "the refined grant must deny the uninstantiated dispatch" }
    }

    /**
     * S4. At a call a Handler intercepts, the interpreter skipped the call's
     * effect-instance parameters while the VM, which takes them as operands
     * of the call, evaluated them. They are effect-free but not total: here
     * the parameter divides by zero, so the interpreter returned the mock's
     * value and the VM stopped on a contract violation. Both now evaluate
     * them. Found by reading the VM's schema-obligation lowering, where an
     * obligation inside such a parameter would have fired on one backend.
     */
    @Test
    fun `an intercepted call evaluates its effect-instance parameters on both backends`() {
        val json = program("handled", """
            "divT": { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "div": { "type": "ForeignNode", "target": "strand-builtin:Int.Div", "foreignType": "divT" },
            "boom": { "type": "Application", "function": "div", "arguments": ["i1", "i0"] },
            "decl": { "type": "EffectDecl", "effectType": "catP", "parameters": ["boom"] },
            "call": { "type": "Application", "function": "stdP", "arguments": ["i1"], "effectInstances": ["decl"] },
            "y": { "type": "ParameterDecl", "name": "y", "paramType": "intT" },
            "mock": { "type": "Lambda", "parameters": ["y"], "body": "i8" },
            "handled": { "type": "Handler", "intercept": "catP", "handle": "mock", "body": "call" }
        """)
        val outcome = assertSound(json)
        assertTrue(outcome.vmSupported)
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val image = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val ex = org.junit.jupiter.api.assertThrows<InterpretException> {
            StrandRuntime(FuzzHost.policy()).run(image)
        }
        assertTrue(ex.error is InterpretError.BuiltinContractViolation) { "got ${ex.error}" }
    }

    /**
     * S5. The VM had no dispatch for higher-order builtins: `List.Map`
     * raised a raw `IllegalStateException`, so an effectful callback could
     * not be checked on the VM at all.
     */
    @Test
    fun `a higher-order builtin with an effectful callback runs on the VM`() {
        val outcome = assertSound(
            program("mapped", """
                "x": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
                "xRef": { "type": "VarRef", "binder": "x" },
                "cbBody": { "type": "Application", "function": "stdA", "arguments": ["xRef"] },
                "cb": { "type": "Lambda", "parameters": ["x"], "body": "cbBody", "effects": ["catA"] },
                "mapped": { "type": "Application", "function": "map", "arguments": ["oneElem", "cb"] }
            """, LISTS),
            rootTy = Ty.ListInt,
        )
        assertTrue(outcome.vmSupported)
        assertTrue(outcome.performed > 0)
    }

    /**
     * S4. The interpreter checked a Lambda call's capabilities before
     * evaluating its arguments and the VM after, so under a grant that
     * denied the call the two disagreed on which effects had already run
     * and on which category the denial named.
     */
    @Test
    fun `a denied Lambda call has evaluated its arguments on both backends`() {
        assertSound(
            program("call", """
                "x": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
                "needsB": { "type": "Lambda", "parameters": ["x"], "body": "i0", "effects": ["catB"] },
                "arg": { "type": "Application", "function": "stdA", "arguments": ["i0"] },
                "call": { "type": "Application", "function": "needsB", "arguments": ["arg"] }
            """),
            grants = listOf(GrantSpec("only-A", mapOf(Cat.A to listOf(PatternSpec(true, emptyList()))))),
        )
    }

    // ------------------------------------------------------------------
    // S6: schema confinement
    // ------------------------------------------------------------------

    /**
     * S6. The arrow rule compared parameters with the symmetric schema
     * relaxation, so a function with a `Schema<Int>` parameter was accepted
     * where `(Int) -> Int` was expected and then called with unchecked
     * values. Now rejected (negative corpus 60).
     */
    @Test
    fun `a schema-parameter function is not an (Int) to Int callback`() {
        val json = program("call", """
            "cb": { "type": "ParameterDecl", "name": "cb", "paramType": "iiT" },
            "cbRef": { "type": "VarRef", "binder": "cb" },
            "inner": { "type": "Application", "function": "cbRef", "arguments": ["i0"] },
            "applyToZero": { "type": "Lambda", "parameters": ["cb"], "body": "inner" },
            "call": { "type": "Application", "function": "applyToZero", "arguments": ["consume0"] }
        """, SCHEMAS)
        assertRejected(json, "verify", "ParameterTypeMismatch")
    }

    /**
     * S6. Equirecursive comparison stripped a schema wrapper from both
     * sides, so a `Schema1<Int>` value was accepted at a `Schema0<Int>`
     * parameter with no obligation recorded. Now rejected (negative corpus 61).
     */
    @Test
    fun `a value of one schema does not flow into another schema's parameter`() {
        val json = program("call", """
            "p": { "type": "ParameterDecl", "name": "p", "paramType": "schema1" },
            "pRef": { "type": "VarRef", "binder": "p" },
            "inner": { "type": "Application", "function": "consume0", "arguments": ["pRef"] },
            "relabel": { "type": "Lambda", "parameters": ["p"], "body": "inner" },
            "call": { "type": "Application", "function": "relabel", "arguments": ["i1"] }
        """, SCHEMAS)
        assertRejected(json, "verify", "ParameterTypeMismatch")
    }

    /**
     * S6 (known gap b). One shared literal reaching a `Schema0` position and
     * a `Schema1` position kept only the obligation recorded last; 8
     * violates schema 1 and was admitted when schema 0 was recorded second.
     */
    @Test
    fun `one shared node carries both schema obligations`() {
        val json = program("sum", """
            "first": { "type": "Application", "function": "consume1", "arguments": ["i8"] },
            "second": { "type": "Application", "function": "consume0", "arguments": ["i8"] },
            "addT": { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add": { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "sum": { "type": "Application", "function": "add", "arguments": ["first", "second"] }
        """, SCHEMAS)
        assertRejected(json, "schema", "SchemaInvariantViolation")
    }

    // ------------------------------------------------------------------
    // T1 / S5: handler interception the verifier did not check
    // ------------------------------------------------------------------

    /**
     * T1. A Handler around a tool-invoking call was not checked against the
     * calls inside the tool's implementation, so a `(String) -> String`
     * handler received an Int. The walk now follows a ToolDef's
     * implementation (negative corpus 62).
     */
    @Test
    fun `a Handler is checked against the calls inside a tool implementation`() {
        val json = program("handled", """
            "x": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef": { "type": "VarRef", "binder": "x" },
            "implBody": { "type": "Application", "function": "stdB", "arguments": ["xRef"] },
            "impl": { "type": "Lambda", "parameters": ["x"], "body": "implBody", "effects": ["catB"] },
            "tool": { "type": "ToolDef", "name": "t", "description": "d", "parameterSchema": "toolSchema", "implementation": "impl" },
            "call": { "type": "Application", "function": "callTool", "arguments": ["tool", "i0"] },
            "handled": { "type": "Handler", "intercept": "catB", "handle": "stdSA", "body": "call" }
        """)
        assertRejected(json, "verify", "HandlerSignatureMismatch")
    }

    /**
     * T1 / S5. Handlers are dynamically scoped: the outer Handler's handle
     * runs inside the inner Handler's extent, where its `Fuzz.P` call is
     * intercepted by the inner `(Int) -> Int` handler although that call
     * takes a String. No lexical walk of the inner Handler sees the call.
     * The interpreter handed the handler a String (a contract violation or a
     * mistyped result downstream); the VM, given a mistyped callee, raised a
     * raw `IllegalStateException`. Both now stop with
     * `UnverifiedInterception`.
     */
    @Test
    fun `an interception the verifier did not check is refused at runtime`() {
        val json = program("outer", """
            "s": { "type": "ParameterDecl", "name": "s", "paramType": "strT" },
            "takesString": { "type": "Lambda", "parameters": ["s"], "body": "i0", "effects": ["catP"] },
            "x": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "viaP": { "type": "Application", "function": "takesString", "arguments": ["sA"] },
            "outerHandle": { "type": "Lambda", "parameters": ["x"], "body": "viaP", "effects": ["catP"] },
            "y": { "type": "ParameterDecl", "name": "y", "paramType": "intT" },
            "yRef": { "type": "VarRef", "binder": "y" },
            "useArg": { "type": "Application", "function": "stdA", "arguments": ["yRef"] },
            "innerHandle": { "type": "Lambda", "parameters": ["y"], "body": "useArg", "effects": ["catA"] },
            "call": { "type": "Application", "function": "stdR", "arguments": ["i0"] },
            "inner": { "type": "Handler", "intercept": "catP", "handle": "innerHandle", "body": "call" },
            "outer": { "type": "Handler", "intercept": "catR", "handle": "outerHandle", "body": "inner" }
        """)
        assertSound(json)

        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val image = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val runtime = StrandRuntime(FuzzHost.policy())
        val categories = Cat.entries.mapNotNull { ingest.nameMap[it.nodeId] }.toSet()
        FuzzLog.reset()
        val error = try {
            runtime.run(image, org.strand.interpreter.CapabilitySet.ofCategories(categories))
            null
        } catch (e: InterpretException) {
            e.error
        }
        assertTrue(error is InterpretError.UnverifiedInterception) { "expected UnverifiedInterception, got $error" }
        error as InterpretError.UnverifiedInterception
        assertEquals(ingest.nameMap.getValue("viaP"), error.at)
        assertEquals(ingest.nameMap.getValue("inner"), error.handler)
    }
}
