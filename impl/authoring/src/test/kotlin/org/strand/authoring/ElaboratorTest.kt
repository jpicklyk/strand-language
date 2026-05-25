package org.strand.authoring

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [Elaborator] — Layer C slice (Q-034 step 1):
 *
 *  * §5.3 case (3): effect-closure inference on Lambda (fills in
 *    `Lambda.effects` when absent).
 *  * §5.3 case (4): effectInstances defaulting on Application (fills
 *    in `Application.effectInstances` when absent and the document
 *    has exactly one EffectDecl per category on the callee).
 *
 * Each test parses a small Layer A document, runs the elaborator, and
 * asserts the resulting node's args reflect the expected defaulting (or
 * deliberate non-defaulting in the gap cases).
 */
class ElaboratorTest {

    @Test
    fun `Lambda with no effects gets the body's foreign-call effects filled in`() {
        val text = """
            @v=1 root=lam
            intT PRM Int
            timeFx EFC "Time.Now"
            nowT FNT [] intT
            now FN "strand-builtin:Time.Now" nowT [timeFx]
            callNow APP now []
            lam LAM [] callNow
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val lamNode = elaborated.nodes.first { it.id == "lam" }
        // The original LAM had 2 args ([params], body); elaboration should
        // add a third arg (the effects list).
        assertEquals(3, lamNode.args.size) {
            "elaborator should have filled in the LAM's effects arg; got args=${lamNode.args}"
        }
        val effectsArg = lamNode.args[2] as Arg.Listing
        val effectRefs = effectsArg.items.map { (it as Arg.Bare).text }
        assertEquals(listOf("timeFx"), effectRefs)
    }

    @Test
    fun `Lambda with no effects and pure body is left unchanged`() {
        val text = """
            @v=1 root=lam
            intT PRM Int
            x PRC "x" intT
            xRef VAR x
            lam LAM [x] xRef
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val lamNode = elaborated.nodes.first { it.id == "lam" }
        // Pure body: inferred closure is empty; LAM stays as 2-arg.
        assertEquals(2, lamNode.args.size) {
            "pure Lambda's args should be unchanged; got ${lamNode.args}"
        }
    }

    @Test
    fun `Lambda with explicit effects is left unchanged by elaboration`() {
        val text = """
            @v=1 root=lam
            intT PRM Int
            timeFx EFC "Time.Now"
            nowT FNT [] intT
            now FN "strand-builtin:Time.Now" nowT [timeFx]
            callNow APP now []
            lam LAM [] callNow [timeFx]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val lamNode = elaborated.nodes.first { it.id == "lam" }
        // Explicit effects: elaborator must not touch it.
        assertEquals(3, lamNode.args.size)
        val effectsArg = lamNode.args[2] as Arg.Listing
        assertEquals(listOf("timeFx"), effectsArg.items.map { (it as Arg.Bare).text })
    }

    @Test
    fun `compileWithElaboration emits the inferred effects in the dag-json`() {
        val text = """
            @v=1 root=app
            intT PRM Int
            timeFx EFC "Time.Now"
            nowT FNT [] intT
            now FN "strand-builtin:Time.Now" nowT [timeFx]
            callNow APP now []
            myLam LAM [] callNow
            app APP myLam []
        """.trimIndent()

        val jsonText = Authoring.compileToDagJson(text)
        // Cheap structural check: the resulting JSON must contain a Lambda
        // with effects = ["timeFx"]. We parse the JSON and inspect.
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(jsonText) as JsonObject
        val nodes = obj["nodes"] as JsonObject
        val lamObj = nodes["myLam"] as JsonObject
        val effects = lamObj["effects"] as JsonArray
        assertEquals(1, effects.size)
        assertEquals("timeFx", (effects[0] as JsonPrimitive).content)
    }

    // -- §5.3 case (4) — Application.effectInstances defaulting --

    @Test
    fun `Application with no effectInstances and unique EffectDecl per category gets defaulted`() {
        // Foreign function `write` declares one effect category (fsWriteFx).
        // The document has exactly one EffectDecl (writeDecl) whose
        // effectType is fsWriteFx. The Application of `write` omits its
        // effectInstances; elaboration should fill in [writeDecl] (and
        // pad with empty typeArguments first to preserve positional
        // ordering).
        val text = """
            @v=1 root=callWrite
            intT PRM Int
            strT PRM String
            fsWriteFx EFC "Filesystem.Write" [strT]
            writeT FNT [strT] intT [fsWriteFx]
            write FN "strand-builtin:Filesystem.Write" writeT [fsWriteFx]
            path STR "/tmp/log"
            writeDecl EFD fsWriteFx [path]
            callWrite APP write [path]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val appNode = elaborated.nodes.first { it.id == "callWrite" }
        // Originally args = [write, [path]]. After elaboration the APP
        // must have all four positional slots filled: function, arguments,
        // typeArguments=[], effectInstances=[writeDecl].
        assertEquals(4, appNode.args.size) {
            "elaborator should have filled in effectInstances; got args=${appNode.args}"
        }
        val typeArgsList = appNode.args[2] as Arg.Listing
        assertTrue(typeArgsList.items.isEmpty()) {
            "typeArguments slot must be the empty list placeholder; got ${typeArgsList.items}"
        }
        val effectInstancesList = appNode.args[3] as Arg.Listing
        val effectInstanceRefs = effectInstancesList.items.map { (it as Arg.Bare).text }
        assertEquals(listOf("writeDecl"), effectInstanceRefs)
    }

    @Test
    fun `Application with no effectInstances but callee has zero effects is unchanged`() {
        // Pure callee — no effects declared, nothing to default.
        val text = """
            @v=1 root=callId
            intT PRM Int
            idT FNT [intT] intT
            x PRC "x" intT
            xRef VAR x
            id LAM [x] xRef
            zero ILT 0
            callId APP id [zero]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val appNode = elaborated.nodes.first { it.id == "callId" }
        // Pure callee: the APP should remain 2-arg.
        assertEquals(2, appNode.args.size) {
            "elaborator should not have touched an APP whose callee has no effects; got ${appNode.args}"
        }
    }

    @Test
    fun `Application with explicit effectInstances is left unchanged by elaboration`() {
        // The APP already supplies effectInstances; elaboration must not
        // overwrite or re-default. Round-trip integrity for hand-authored
        // refinement-aware call sites.
        val text = """
            @v=1 root=callWrite
            intT PRM Int
            strT PRM String
            fsWriteFx EFC "Filesystem.Write" [strT]
            writeT FNT [strT] intT [fsWriteFx]
            write FN "strand-builtin:Filesystem.Write" writeT [fsWriteFx]
            path STR "/tmp/log"
            writeDecl EFD fsWriteFx [path]
            callWrite APP write [path] [] [writeDecl]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val appNode = elaborated.nodes.first { it.id == "callWrite" }
        // 4-arg APP unchanged.
        assertEquals(4, appNode.args.size)
        val effectInstancesList = appNode.args[3] as Arg.Listing
        assertEquals(listOf("writeDecl"),
            effectInstancesList.items.map { (it as Arg.Bare).text })
    }

    @Test
    fun `Application with ambiguous EffectDecls is left unchanged - gap`() {
        // Two EffectDecls for the same category — defaulting is ambiguous;
        // the proposal's spec calls for an ElaborationGap. The current
        // slice surfaces gaps implicitly by leaving the APP untouched
        // (the verifier accepts empty effectInstances when categories
        // are present — the pre-Q-031 back-compat path).
        val text = """
            @v=1 root=callWrite
            intT PRM Int
            strT PRM String
            fsWriteFx EFC "Filesystem.Write" [strT]
            writeT FNT [strT] intT [fsWriteFx]
            write FN "strand-builtin:Filesystem.Write" writeT [fsWriteFx]
            pathA STR "/tmp/a"
            pathB STR "/tmp/b"
            declA EFD fsWriteFx [pathA]
            declB EFD fsWriteFx [pathB]
            callWrite APP write [pathA]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val appNode = elaborated.nodes.first { it.id == "callWrite" }
        // 2 EffectDecls match -> ambiguous -> APP left untouched (2 args).
        assertEquals(2, appNode.args.size) {
            "elaborator must not default when multiple EffectDecls match; got ${appNode.args}"
        }
    }

    @Test
    fun `Application with no matching EffectDecl is left unchanged - gap`() {
        // Callee declares an effect, but no EffectDecl in the document
        // references that category. Defaulting fails; APP left untouched.
        val text = """
            @v=1 root=callWrite
            intT PRM Int
            strT PRM String
            fsWriteFx EFC "Filesystem.Write" [strT]
            writeT FNT [strT] intT [fsWriteFx]
            write FN "strand-builtin:Filesystem.Write" writeT [fsWriteFx]
            path STR "/tmp/log"
            callWrite APP write [path]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val appNode = elaborated.nodes.first { it.id == "callWrite" }
        // 0 EffectDecls match -> nothing to default -> APP unchanged.
        assertEquals(2, appNode.args.size) {
            "elaborator must not default when no EffectDecl matches; got ${appNode.args}"
        }
    }

    @Test
    fun `Application with explicit typeArguments but no effectInstances gets effectInstances appended without padding`() {
        // The APP already has the typeArguments slot filled (empty list,
        // but explicitly present); elaboration appends the
        // effectInstances slot without inserting an extra typeArguments
        // placeholder. This guards the 3-arg vs 2-arg branch.
        val text = """
            @v=1 root=callWrite
            intT PRM Int
            strT PRM String
            fsWriteFx EFC "Filesystem.Write" [strT]
            writeT FNT [strT] intT [fsWriteFx]
            write FN "strand-builtin:Filesystem.Write" writeT [fsWriteFx]
            path STR "/tmp/log"
            writeDecl EFD fsWriteFx [path]
            callWrite APP write [path] []
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val appNode = elaborated.nodes.first { it.id == "callWrite" }
        assertEquals(4, appNode.args.size) {
            "elaborator must extend a 3-arg APP to 4 args without doubling typeArguments; got ${appNode.args}"
        }
        // Existing empty typeArguments slot preserved verbatim.
        val typeArgsList = appNode.args[2] as Arg.Listing
        assertTrue(typeArgsList.items.isEmpty())
        val effectInstancesList = appNode.args[3] as Arg.Listing
        assertEquals(listOf("writeDecl"),
            effectInstancesList.items.map { (it as Arg.Bare).text })
    }

    @Test
    fun `compileWithElaboration emits defaulted effectInstances in the dag-json`() {
        // End-to-end check via the public Authoring facade: a Layer A
        // program with an effects-bearing call but no effectInstances
        // compiles to dag-json whose Application has the inferred
        // effectInstances list populated.
        val text = """
            @v=1 root=app
            intT PRM Int
            strT PRM String
            fsWriteFx EFC "Filesystem.Write" [strT]
            writeT FNT [strT] intT [fsWriteFx]
            write FN "strand-builtin:Filesystem.Write" writeT [fsWriteFx]
            path STR "/tmp/log"
            writeDecl EFD fsWriteFx [path]
            app APP write [path]
        """.trimIndent()

        val jsonText = Authoring.compileToDagJson(text)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(jsonText) as JsonObject
        val nodes = obj["nodes"] as JsonObject
        val appObj = nodes["app"] as JsonObject
        val effectInstances = appObj["effectInstances"] as JsonArray
        assertEquals(1, effectInstances.size)
        assertEquals("writeDecl", (effectInstances[0] as JsonPrimitive).content)
    }

    @Test
    fun `defaulting traces VarRef-bound callable through Let`() {
        // The APP's callee is a VarRef to a Let-bound name whose value is
        // an effects-bearing ForeignNode. The elaborator's effectsOf
        // walker must trace through the Let binder (VarRef → Let → its
        // `value` field) to discover the declared effects, then default
        // the unique-match EffectDecl. (VarRef binder fields reference
        // the binder *node* by id — for a Let binding that's the Let
        // node itself, matching `id_use VAR outer` in corpus 03.)
        val text = """
            @v=1 root=letChain
            intT PRM Int
            strT PRM String
            fsWriteFx EFC "Filesystem.Write" [strT]
            writeT FNT [strT] intT [fsWriteFx]
            write FN "strand-builtin:Filesystem.Write" writeT [fsWriteFx]
            path STR "/tmp/log"
            pathDecl EFD fsWriteFx [path]
            wRef VAR letChain
            callViaLet APP wRef [path]
            letChain LET "w" write callViaLet
        """.trimIndent()
        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val appNode = elaborated.nodes.first { it.id == "callViaLet" }
        // The callee `wRef` -> binder `letChain` (the Let node) -> Let's
        // value `write` (a ForeignNode declaring [fsWriteFx]). Exactly
        // one EffectDecl (pathDecl) covers fsWriteFx -> defaulting fires.
        assertEquals(4, appNode.args.size) {
            "defaulting must trace through Let to discover callee effects; got ${appNode.args}"
        }
        val effectInstancesList = appNode.args[3] as Arg.Listing
        assertEquals(listOf("pathDecl"),
            effectInstancesList.items.map { (it as Arg.Bare).text })
    }

    // -- Tests preserved from the case-(3) effects-closure slice --

    @Test
    fun `Match with effectful body propagates effects through the case bodies`() {
        // A Lambda whose body Matches on a Bool and one of the cases calls
        // an effectful builtin. The Lambda's closure should include the
        // effects from any case body.
        val text = """
            @v=1 root=lam
            intT PRM Int
            boolT PRM Bool
            timeFx EFC "Time.Now"
            nowT FNT [] intT
            now FN "strand-builtin:Time.Now" nowT [timeFx]
            callNow APP now []
            zero ILT 0
            patTrue PLT boolT trueLit
            trueLit BLT true
            caseTrue MC patTrue callNow
            patFalse PLT boolT falseLit
            falseLit BLT false
            caseFalse MC patFalse zero
            b PRC "b" boolT
            bRef VAR b
            m MAT bRef [caseTrue caseFalse]
            lam LAM [b] m
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val lamNode = elaborated.nodes.first { it.id == "lam" }
        assertTrue(lamNode.args.size == 3) {
            "elaborator should have filled in effects; got ${lamNode.args}"
        }
        val effectRefs = (lamNode.args[2] as Arg.Listing).items.map { (it as Arg.Bare).text }
        assertEquals(listOf("timeFx"), effectRefs)
    }

    @Test
    fun `Application of polymorphic identity with elided typeArguments gets Int inferred`() {
        // corpus 02-identity-applied translated to Layer A with the
        // `[intT]` typeArguments list elided from the APP line. Elaboration
        // resolves `id` to a TAB, finds the single TPM `T_a` referenced
        // directly by parameter `x`, types the IntLit arg as `intT`, and
        // builds the typeArguments list.
        val text = """
            @v=1 root=app
            T_a TPM "a"
            intT PRM Int
            x PRC "x" T_a
            x_use VAR x
            idInner LAM [x] x_use
            id TAB [T_a] idInner
            arg ILT 42
            app APP id [arg]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val appNode = elaborated.nodes.first { it.id == "app" }
        // Original APP had 2 args; elaboration should add typeArguments
        // (the third positional slot).
        assertEquals(3, appNode.args.size) {
            "elaborator should have inferred typeArguments; got args=${appNode.args}"
        }
        val typeArgs = (appNode.args[2] as Arg.Listing).items.map { (it as Arg.Bare).text }
        assertEquals(listOf("intT"), typeArgs)
    }

    @Test
    fun `Lambda paramType is inferred from the single call-site value-arg type`() {
        // A LAM whose PRC has no paramType. Elaborator finds the APP that
        // calls this LAM and infers paramType from the corresponding
        // value-arg's type (an IntLit → intT).
        val text = """
            @v=1 root=app
            intT PRM Int
            x PRC "x"
            x_use VAR x
            lam LAM [x] x_use
            arg ILT 42
            app APP lam [arg]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val prcNode = elaborated.nodes.first { it.id == "x" }
        // Original PRC had 1 arg (name only); elaboration fills in the
        // paramType slot.
        assertEquals(2, prcNode.args.size) {
            "elaborator should have inferred paramType; got args=${prcNode.args}"
        }
        val paramType = (prcNode.args[1] as Arg.Bare).text
        assertEquals("intT", paramType)
    }

    @Test
    fun `PRC with no paramType and no call sites is left unchanged - gap`() {
        // A LAM never applied: no call site means no type context. The
        // PRC stays without paramType; the canonical emit will produce
        // JSON missing paramType and the verifier will reject — the LLM
        // is told it must annotate.
        val text = """
            @v=1 root=lam
            x PRC "x"
            x_use VAR x
            lam LAM [x] x_use
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val prcNode = elaborated.nodes.first { it.id == "x" }
        assertEquals(1, prcNode.args.size) {
            "no call site → no inference; PRC must remain without paramType"
        }
    }

    @Test
    fun `Application with no PRM Int in scope falls back to reserved intT`() {
        // Layer A density v4: the implicit prelude (Slice 1) makes
        // `intT` a reserved name that resolves to the synthetic
        // PrimitiveType{kind=Int} node. Even when the document doesn't
        // declare `intT` explicitly, the elaborator infers IntLit's type
        // as `intT` so typeArguments inference succeeds — the synthesized
        // intT node appears in the emitted JSON via the implicit prelude.
        val text = """
            @v=1 root=app
            T_a TPM "a"
            strT PRM String
            x PRC "x" T_a
            x_use VAR x
            idInner LAM [x] x_use
            id TAB [T_a] idInner
            arg ILT 42
            app APP id [arg]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val appNode = elaborated.nodes.first { it.id == "app" }
        // With the implicit prelude, IntLit resolves to `intT` and
        // typeArguments is inferred.
        assertEquals(3, appNode.args.size) {
            "implicit prelude provides intT; typeArguments should be inferred"
        }
        val typeArgs = (appNode.args[2] as Arg.Listing).items.map { (it as Arg.Bare).text }
        assertEquals(listOf("intT"), typeArgs)
    }

    // ==================================================================
    // Layer A density v4 — deeper inference tests.
    // ==================================================================

    @Test
    fun `v4 recursion-slot PRC paramType is inferred from FIX recursionType`() {
        // A FIX whose body Lambda's first param `recurse` has no
        // paramType. The elaborator fills it from the FIX's
        // recursionType (`factT`).
        val text = """
            @v=1 root=fact
            factT FNT [intT] intT
            recurse PRC "recurse"
            n PRC "n" intT
            nRef VAR n
            recRef VAR recurse
            recCall APP recRef [nRef]
            bodyLam LAM [recurse n] recCall
            fact FIX factT bodyLam
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val recursePrc = elaborated.nodes.first { it.id == "recurse" }
        assertEquals(2, recursePrc.args.size) {
            "recurse PRC should have its paramType inferred from FIX recursionType"
        }
        val recParamType = (recursePrc.args[1] as Arg.Bare).text
        assertEquals("factT", recParamType)
    }

    @Test
    fun `v4 compact LAM recursion-slot param gets type suffix added`() {
        // A FIX whose body Lambda uses compact-form params and the
        // first param `recurse` has no type annotation. The elaborator
        // rewrites the LAM PARAM_LIST entry from "recurse" to
        // "recurse:factT".
        val text = """
            @v=1 root=fact
            factT FNT [intT] intT
            nRef VAR n
            recRef VAR recurse
            recCall APP recRef [nRef]
            bodyLam LAM [recurse n:intT] recCall
            fact FIX factT bodyLam
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val bodyLam = elaborated.nodes.first { it.id == "bodyLam" }
        val params = (bodyLam.args[0] as Arg.Listing).items
        val firstEntry = (params[0] as Arg.Bare).text
        assertEquals("recurse:factT", firstEntry) {
            "compact LAM's first entry should have :factT appended"
        }
    }

    @Test
    fun `v4 FNT is synthesized when FIX recursionType is undeclared`() {
        // FIX references `factT` but no FNT decl exists. The elaborator
        // synthesizes one from the body LAM's signature. The body must
        // have an inferable return type that does NOT depend on the
        // recursive call (otherwise the self-reference creates a cycle
        // the bidirectional inference can't break). Here the body is a
        // simple Int literal — return type = intT.
        val text = """
            @v=1 root=fact
            one ILT 1
            bodyLam LAM [recurse n:intT] one
            fact FIX factT bodyLam
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val fntNode = elaborated.nodes.firstOrNull { it.id == "factT" && it.code == "FNT" }
        assertTrue(fntNode != null) {
            "elaborator should have synthesized an FNT for the undeclared factT"
        }
        val params = (fntNode!!.args[0] as Arg.Listing).items
        val result = (fntNode.args[1] as Arg.Bare).text
        assertEquals(listOf("intT"), params.map { (it as Arg.Bare).text })
        // Body returns the IntLit 1 directly; return type = intT (via
        // the implicit prelude's reserved-name fallback).
        assertEquals("intT", result)
    }

    @Test
    fun `v4 FNT synthesis succeeds when body is a Match with literal branches`() {
        // A more realistic factorial-like case where the body is an IF
        // (Match) whose branches return Int literals or APPs returning
        // Int. The recursive APP returns the FIX's recursionType result
        // (which is what we're inferring), so we use a non-recursive
        // path to anchor the type: the IF's then-branch is a literal 1.
        val text = """
            @v=1 root=fact
            nIsZero APP eqInt [n 0]
            mulBody APP mul [n 99]
            matchBody IF nIsZero 1 mulBody
            bodyLam LAM [recurse n:intT] matchBody
            fact FIX factT bodyLam
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val fntNode = elaborated.nodes.firstOrNull { it.id == "factT" && it.code == "FNT" }
        assertTrue(fntNode != null) {
            "elaborator should have synthesized an FNT (matchBody → intT via IF then-branch)"
        }
        val result = (fntNode!!.args[1] as Arg.Bare).text
        assertEquals("intT", result)
    }

    @Test
    fun `v4 SCS caseType is inferred from SumValue payload type`() {
        // SCS Some has caseType=`_`; an SV supplies an IntLit payload.
        // Elaborator infers caseType=intT.
        val text = """
            @v=1 root=someValue
            someCase SCS "Some" _
            noneCase SCS "None" _
            optionT SUM [someCase noneCase]
            someValue SV optionT "Some" 42
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val someCase = elaborated.nodes.first { it.id == "someCase" }
        val caseType = someCase.args[1]
        assertTrue(caseType is Arg.Bare) {
            "Some SCS caseType should be inferred to a reference; got $caseType"
        }
        assertEquals("intT", (caseType as Arg.Bare).text)
    }

    @Test
    fun `v4 compact LAM param without colon is inferred from APP arg use`() {
        // The LAM has a compact param `s` (no :type) and the body uses
        // `s` as the arg of `APP not [s]`. The elaborator looks up the
        // reserved-name `not` and finds its paramType[0] = boolT.
        val text = """
            @v=1 root=lam
            negate APP not [s]
            lam LAM [s] negate
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val lam = elaborated.nodes.first { it.id == "lam" }
        val params = (lam.args[0] as Arg.Listing).items
        val firstEntry = (params[0] as Arg.Bare).text
        assertEquals("s:boolT", firstEntry) {
            "compact param `s` should be inferred from `not`'s parameter type"
        }
    }

    @Test
    fun `v4 PRC paramType is inferred via Match scrutinee context`() {
        // A LAM whose param is the scrutinee of a Match. The first
        // case's patternType is the inferred paramType.
        val text = """
            @v=1 root=lam
            intT PRM Int
            boolT PRM Bool
            zero ILT 0
            one ILT 1
            patZero PLT intT zero
            caseZero MC patZero one
            n PRC "n"
            nRef VAR n
            m MAT nRef [caseZero]
            lam LAM [n] m
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val nPrc = elaborated.nodes.first { it.id == "n" }
        assertEquals(2, nPrc.args.size) {
            "n PRC should have paramType inferred from Match scrutinee context"
        }
        val paramType = (nPrc.args[1] as Arg.Bare).text
        assertEquals("intT", paramType)
    }

    @Test
    fun `v4 PRC paramType is inferred via PFV context`() {
        // A LAM whose param is the value of a PFV in a PV. The PFV's
        // name matches a PRF "x" in the PV's PRD. Inferred paramType is
        // the PRF's fieldType.
        val text = """
            @v=1 root=lam
            intT PRM Int
            xFld PRF "x" intT
            recordT PRD [xFld]
            x PRC "x"
            xRef VAR x
            xPfv PFV "x" xRef
            recordV PV recordT [xPfv]
            lam LAM [x] recordV
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val xPrc = elaborated.nodes.first { it.id == "x" && it.code == "PRC" }
        assertEquals(2, xPrc.args.size) {
            "x PRC should have paramType inferred from PFV context"
        }
        val paramType = (xPrc.args[1] as Arg.Bare).text
        assertEquals("intT", paramType)
    }
}
