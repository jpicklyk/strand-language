package org.strand.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-060 M-4 slice a — registry-wide implicit builtins.
 *
 * The acceptance gate: a program using a bare dotted registry name in
 * callee position compiles to a canonical graph byte-identical (root
 * hash) to its hand-declared FNT + FN counterpart; an underdetermined
 * instantiation produces an [ElaborationGap] naming the annotation
 * needed and never guesses.
 */
class ImplicitBuiltinExpansionTest {

    // ------------------------------------------------------------------
    // Byte-identity: bare name == hand-declared counterpart
    // ------------------------------------------------------------------

    private val intListTower = """
        selfRef RS
        headInner PRF "head" intT
        tailInner PRF "tail" selfRef
        consInner PRD [headInner tailInner]
        consCase SCS "Cons" consInner
        nilCase SCS "Nil" _
        listSum SUM [consCase nilCase]
        listT RT listSum
        headOuter PRF "head" intT
        tailOuter PRF "tail" listT
        consOuter PRD [headOuter tailOuter]
        nilV SV listT "Nil" _
        five SV listT "Cons" (PV consOuter [head=5 tail=nilV])
        list SV listT "Cons" (PV consOuter [head=3 tail=five])
    """.trimIndent()

    @Test
    fun `List Map bare name compiles byte-identical to the explicit form`() {
        val bare = """
            @v=1 root=mapped
            $intListTower
            double LAM [x:intT] (APP mul [x 2])
            mapped APP List.Map [list double]
        """.trimIndent()
        val explicit = """
            @v=1 root=mapped
            $intListTower
            double LAM [x:intT] (APP mul [x 2])
            fnT FNT [intT] intT
            mapT FNT [listT fnT] listT
            mapFn FN "strand-builtin:List.Map" mapT
            mapped APP mapFn [list double]
        """.trimIndent()
        assertSameCanonicalGraph(bare, explicit, "List.Map")
    }

    @Test
    fun `List Map annotates an unannotated lambda parameter from the signature`() {
        // The lambda's parameter type A is bound from the list argument;
        // the expansion pushes `x:intT` into the compact entry so the
        // emitted graph matches the annotated form.
        val bare = """
            @v=1 root=mapped
            $intListTower
            double LAM [x] (APP mul [x 2])
            mapped APP List.Map [list double]
        """.trimIndent()
        val explicit = """
            @v=1 root=mapped
            $intListTower
            double LAM [x:intT] (APP mul [x 2])
            fnT FNT [intT] intT
            mapT FNT [listT fnT] listT
            mapFn FN "strand-builtin:List.Map" mapT
            mapped APP mapFn [list double]
        """.trimIndent()
        assertSameCanonicalGraph(bare, explicit, "List.Map (lambda annotation push)")
    }

    @Test
    fun `List Fold bare name with nested lambda compiles byte-identical to the explicit form`() {
        val bare = """
            @v=1 root=total
            $intListTower
            total APP List.Fold [list 0 (LAM [acc x] (APP add [acc x]))]
        """.trimIndent()
        val explicit = """
            @v=1 root=total
            $intListTower
            foldFnT FNT [intT intT] intT
            foldT FNT [listT intT foldFnT] intT
            foldFn FN "strand-builtin:List.Fold" foldT
            total APP foldFn [list 0 (LAM [acc:intT x:intT] (APP add [acc x]))]
        """.trimIndent()
        assertSameCanonicalGraph(bare, explicit, "List.Fold")
    }

    @Test
    fun `Set Union pipeline compiles byte-identical to the explicit form`() {
        val bare = """
            @v=1 root=u
            e1 APP Set.Empty []
            s1 APP Set.Add [e1 1]
            s2 APP Set.Add [e1 2]
            u APP Set.Union [s1 s2]
        """.trimIndent()
        val explicit = """
            @v=1 root=u
            emptyT FNT [] bytesT
            emptyFn FN "strand-builtin:Set.Empty" emptyT
            addT FNT [bytesT intT] bytesT
            addFn FN "strand-builtin:Set.Add" addT
            unionT FNT [bytesT bytesT] bytesT
            unionFn FN "strand-builtin:Set.Union" unionT
            e1 APP emptyFn []
            s1 APP addFn [e1 1]
            s2 APP addFn [e1 2]
            u APP unionFn [s1 s2]
        """.trimIndent()
        assertSameCanonicalGraph(bare, explicit, "Set.Union")
    }

    @Test
    fun `Json Parse bare name compiles byte-identical to the explicit blessed tower`() {
        val bare = """
            @v=1 root=parsed
            input STR "{\"a\": 1}"
            parsed APP Json.Parse [input]
        """.trimIndent()
        // The hand-declared counterpart: the corpus-66 JsonValueFull tower
        // wrapped in the canonical Option sum.
        val explicit = """
            @v=1 root=parsed
            recSelf RS
            arrConsHead PRF "head" recSelf
            arrConsTail PRF "tail" recSelf
            arrConsProd PRD [arrConsHead arrConsTail]
            objConsKey PRF "key" stringT
            objConsValue PRF "value" recSelf
            objConsTail PRF "tail" recSelf
            objConsProd PRD [objConsKey objConsValue objConsTail]
            jsonNullCase SCS "JsonNull" _
            jsonBoolCase SCS "JsonBool" boolT
            jsonNumberCase SCS "JsonNumber" intT
            jsonStringCase SCS "JsonString" stringT
            jsonArrConsCase SCS "JsonArrayCons" arrConsProd
            jsonArrNilCase SCS "JsonArrayNil" _
            jsonObjConsCase SCS "JsonObjectCons" objConsProd
            jsonObjNilCase SCS "JsonObjectNil" _
            jsonValueBody SUM [jsonNullCase jsonBoolCase jsonNumberCase jsonStringCase jsonArrConsCase jsonArrNilCase jsonObjConsCase jsonObjNilCase]
            jsonValueT RT jsonValueBody
            someCase SCS "Some" jsonValueT
            noneCase SCS "None" _
            optT SUM [someCase noneCase]
            parseT FNT [stringT] optT
            parseFn FN "strand-builtin:Json.Parse" parseT
            input STR "{\"a\": 1}"
            parsed APP parseFn [input]
        """.trimIndent()
        assertSameCanonicalGraph(bare, explicit, "Json.Parse")
    }

    @Test
    fun `Map Merge with annotated conflict lambda compiles byte-identical to the explicit form`() {
        val bare = """
            @v=1 root=m
            e APP Map.Empty []
            m1 APP Map.Put [e "a" 1]
            m2 APP Map.Put [e "b" 2]
            m APP Map.Merge [m1 m2 (LAM [v1:intT v2:intT] (APP add [v1 v2]))]
        """.trimIndent()
        val explicit = """
            @v=1 root=m
            emptyT FNT [] bytesT
            emptyFn FN "strand-builtin:Map.Empty" emptyT
            putT FNT [bytesT stringT intT] bytesT
            putFn FN "strand-builtin:Map.Put" putT
            conflictT FNT [intT intT] intT
            mergeT FNT [bytesT bytesT conflictT] bytesT
            mergeFn FN "strand-builtin:Map.Merge" mergeT
            e APP emptyFn []
            m1 APP putFn [e "a" 1]
            m2 APP putFn [e "b" 2]
            m APP mergeFn [m1 m2 (LAM [v1:intT v2:intT] (APP add [v1 v2]))]
        """.trimIndent()
        assertSameCanonicalGraph(bare, explicit, "Map.Merge")
    }

    @Test
    fun `Http Request bare name carries effects and projections byte-identical to the explicit DSL form`() {
        val headerTower = """
            hSelf RS
            hNameInner PRF "name" stringT
            hValueInner PRF "value" stringT
            hEntry PRD [hNameInner hValueInner]
            hHeadInner PRF "head" hEntry
            hTailInner PRF "tail" hSelf
            hConsInner PRD [hHeadInner hTailInner]
            hConsCase SCS "Cons" hConsInner
            hNilCase SCS "Nil" _
            hSum SUM [hConsCase hNilCase]
            hListT RT hSum
            noHeaders SV hListT "Nil" _
            host STR "example.com"
            port ILT 443
            scheme STR "https"
            path STR "/data"
            method STR "GET"
            body BYT ""
        """.trimIndent()
        val bare = """
            @v=1 root=resp
            $headerTower
            resp APP Http.Request [host port scheme path method noHeaders body]
        """.trimIndent()
        val explicit = """
            @v=1 root=resp
            $headerTower
            statusF PRF "status" intT
            bodyF PRF "body" bytesT
            headersF PRF "headers" hListT
            respT PRD [statusF bodyF headersF]
            reqT FNT [stringT intT stringT stringT stringT hListT bytesT] respT
            reqFn FN "strand-builtin:Http.Request" reqT [connectFx netSendFx netRecvFx] "connectFx:0,1;netSendFx:;netRecvFx:"
            resp APP reqFn [host port scheme path method noHeaders body]
        """.trimIndent()
        assertSameCanonicalGraph(bare, explicit, "Http.Request")
    }

    @Test
    fun `Fs List bare name carries readFx and its ArgRef projection`() {
        val bare = """
            @v=1 root=entries
            dir STR "/safe"
            entries APP Fs.List [dir]
        """.trimIndent()
        val explicit = """
            @v=1 root=entries
            sSelf RS
            sHeadInner PRF "head" stringT
            sTailInner PRF "tail" sSelf
            sConsInner PRD [sHeadInner sTailInner]
            sConsCase SCS "Cons" sConsInner
            sNilCase SCS "Nil" _
            sSum SUM [sConsCase sNilCase]
            strListT RT sSum
            listDirT FNT [stringT] strListT
            listDirFn FN "strand-builtin:Fs.List" listDirT [readFx] "readFx:0"
            dir STR "/safe"
            entries APP listDirFn [dir]
        """.trimIndent()
        assertSameCanonicalGraph(bare, explicit, "Fs.List")
    }

    // ------------------------------------------------------------------
    // Underdetermined instantiations gap (never guess)
    // ------------------------------------------------------------------

    @Test
    fun `List Empty without context produces an ElaborationGap naming the result variable`() {
        val text = """
            @v=1 root=e
            e APP List.Empty []
        """.trimIndent()
        val result = Authoring.compile(text)
        val gap = result.elaborationGaps.single {
            it.inferenceCase == "implicit registry-builtin expansion"
        }
        assertTrue("'A'" in gap.reason) { "gap must name the unbound variable: ${gap.reason}" }
        assertTrue("explicit FNT + FN" in gap.reason) { "gap must name the fix: ${gap.reason}" }
        // The unresolved reference fails downstream ingest, which is what
        // surfaces the gap note to the driver.
        assertTrue(runCatching { JsonIngest.parse(result.dagJson) }.isFailure) {
            "an unexpanded dotted callee must not silently ingest"
        }
    }

    @Test
    fun `Map Get produces an ElaborationGap for the undetermined value type`() {
        val text = """
            @v=1 root=v
            e APP Map.Empty []
            m APP Map.Put [e "a" 1]
            k STR "a"
            v APP Map.Get [m k]
        """.trimIndent()
        val gaps = Authoring.compile(text).elaborationGaps
        val gap = gaps.single { it.inferenceCase == "implicit registry-builtin expansion" }
        assertEquals("v", gap.nodeId)
        assertTrue("'V'" in gap.reason) { "gap must name the unbound variable: ${gap.reason}" }
    }

    @Test
    fun `unannotated Map Merge conflict lambda gaps with the parameter to annotate`() {
        val text = """
            @v=1 root=m
            e APP Map.Empty []
            m APP Map.Merge [e e (LAM [v1 v2] (APP add [v1 v2]))]
        """.trimIndent()
        val gaps = Authoring.compile(text).elaborationGaps
        val gap = gaps.single { it.inferenceCase == "implicit registry-builtin expansion" }
        assertTrue("'v1'" in gap.reason && "'V'" in gap.reason) {
            "gap must name the lambda parameter and the variable: ${gap.reason}"
        }
    }

    @Test
    fun `excluded builtin gaps with the documented exclusion reason`() {
        val text = """
            @v=1 root=w
            p STR "/tmp/x"
            d STR "data"
            w APP Filesystem.Write [p d]
        """.trimIndent()
        val gaps = Authoring.compile(text).elaborationGaps
        val gap = gaps.single { it.inferenceCase == "implicit registry-builtin expansion" }
        assertTrue("excluded" in gap.reason && "fsWrite" in gap.reason) {
            "exclusion gap must carry the documented reason: ${gap.reason}"
        }
    }

    @Test
    fun `unknown dotted name gaps with a spelling hint`() {
        val text = """
            @v=1 root=x
            x APP List.Mpa [y]
            y ILT 1
        """.trimIndent()
        val gaps = Authoring.compile(text).elaborationGaps
        val gap = gaps.single { it.inferenceCase == "implicit registry-builtin expansion" }
        assertTrue("no implicit signature" in gap.reason) { gap.reason }
    }

    // ------------------------------------------------------------------
    // Structural checks on the expanded output
    // ------------------------------------------------------------------

    @Test
    fun `expanded ForeignNode carries the registry target and synthesized FunctionType`() {
        val text = """
            @v=1 root=parts
            input STR "a,b,c"
            sep STR ","
            parts APP String.Split [input sep]
        """.trimIndent()
        val json = Authoring.compileToDagJson(text)
        assertTrue("\"strand-builtin:String.Split\"" in json) {
            "expanded output must reference the registry target: $json"
        }
        // Compiles, ingests, and verifies end to end.
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "String.Split expansion failed verification: $verify" }
    }

    @Test
    fun `synthesized categories appear for builtins without reserved effect names`() {
        val text = """
            @v=1 root=h
            port ILT 8080
            h APP Http.Listen [port]
        """.trimIndent()
        val json = Authoring.compileToDagJson(text)
        assertTrue("\"Network.Listen\"" in json) {
            "Http.Listen must synthesize the Network.Listen category: $json"
        }
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "Http.Listen expansion failed verification: $verify" }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun assertSameCanonicalGraph(bare: String, explicit: String, label: String) {
        val bareJson = Authoring.compileToDagJson(bare)
        val explicitJson = Authoring.compileToDagJson(explicit)

        val bareFinal = run {
            val ingest = JsonIngest.parse(bareJson)
            Hasher(ingest.rawStore).finalize(ingest.root)
        }
        val explicitFinal = run {
            val ingest = JsonIngest.parse(explicitJson)
            Hasher(ingest.rawStore).finalize(ingest.root)
        }
        val bareHash = bareFinal.nodeIdToHash.getValue(bareFinal.root)
        val explicitHash = explicitFinal.nodeIdToHash.getValue(explicitFinal.root)
        assertEquals(explicitHash, bareHash) {
            "$label: bare-name root hash $bareHash differs from the hand-declared " +
                "counterpart's $explicitHash\nbare dag-json:\n$bareJson\n" +
                "explicit dag-json:\n$explicitJson"
        }

        val bareVerify = Verifier(bareFinal.store, bareFinal.hashToNodeId).verify(bareFinal.root)
        val explicitVerify = Verifier(explicitFinal.store, explicitFinal.hashToNodeId)
            .verify(explicitFinal.root)
        assertTrue(explicitVerify is VerifyResult.Ok) {
            "$label: explicit form failed verification: $explicitVerify"
        }
        assertTrue(bareVerify is VerifyResult.Ok) {
            "$label: bare-name form failed verification: $bareVerify"
        }
        assertEquals(
            (explicitVerify as VerifyResult.Ok).rootType,
            (bareVerify as VerifyResult.Ok).rootType,
        ) { "$label: root types differ between bare and explicit forms" }
    }
}
