package org.strand.authoring.familiar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.authoring.Authoring

/**
 * Q-061 Layer F lowering: the familiar dialect compiles through the
 * existing Elaborator + DagJsonEmitter pipeline to the expected
 * canonical dag-json shapes. Cross-surface hash equality against the
 * evaluation tasks' Layer A references lives in `:corpus`
 * (FamiliarSurfaceHashEqualityTest); this file asserts the lowered
 * node structure directly.
 */
class FamiliarLoweringTest {

    private fun compileNodes(text: String): Pair<JsonObject, Authoring.CompileResult> {
        val result = Authoring.compile(text, Authoring.Surface.FAMILIAR)
        val json = Json.parseToJsonElement(result.dagJson).jsonObject
        return json["nodes"]!!.jsonObject to result
    }

    private fun JsonObject.nodesOfType(nodes: JsonObject, type: String): List<Pair<String, JsonObject>> =
        nodes.entries.map { it.key to it.value.jsonObject }
            .filter { it.second["type"]?.jsonPrimitive?.content == type }

    // ------------------------------------------------------------------
    // The worked example (proposal § 4.2)
    // ------------------------------------------------------------------

    private val workedExample = """
        use prelude.{add}

        declare function fsWrite(path: String): Int from "strand-builtin:Filesystem.Write" uses Filesystem.Write(path)

        function main(): Int uses Filesystem.Write("/tmp/strand-eval.log") {
          const n = fsWrite("/tmp/strand-eval.log")
          return add(n, 1)
        }
    """.trimIndent()

    @Test
    fun `worked example lowers to the corpus file-write shape`() {
        val (nodes, _) = compileNodes(workedExample)

        // The declared foreign node with the category row on FN and FNT.
        val fn = nodes["fsWrite"]!!.jsonObject
        assertEquals("ForeignNode", fn["type"]!!.jsonPrimitive.content)
        assertEquals("strand-builtin:Filesystem.Write", fn["target"]!!.jsonPrimitive.content)
        assertEquals(listOf("writeFx"), fn["effects"]!!.jsonArray.map { it.jsonPrimitive.content })
        val fnt = nodes[fn["foreignType"]!!.jsonPrimitive.content]!!.jsonObject
        assertEquals(listOf("stringT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("intT", fnt["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("writeFx"), fnt["effects"]!!.jsonArray.map { it.jsonPrimitive.content })

        // The application site carries an explicit EffectDecl whose
        // parameter IS the call's path argument node (the surface Q-039
        // projection from `uses Filesystem.Write(path)`).
        val writeCall = nodes.entries.map { it.key to it.value.jsonObject }
            .first { (_, n) ->
                n["type"]?.jsonPrimitive?.content == "Application" &&
                    n["function"]?.jsonPrimitive?.content == "fsWrite"
            }.second
        val pathArg = writeCall["arguments"]!!.jsonArray[0].jsonPrimitive.content
        val efdId = writeCall["effectInstances"]!!.jsonArray[0].jsonPrimitive.content
        val efd = nodes[efdId]!!.jsonObject
        assertEquals("writeFx", efd["effectType"]!!.jsonPrimitive.content)
        assertEquals(listOf(pathArg), efd["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("StringLit", nodes[pathArg]!!.jsonObject["type"]!!.jsonPrimitive.content)

        // `const n = ...` is a Let whose body is the add application.
        val let = nodes.entries.map { it.key to it.value.jsonObject }
            .first { it.second["type"]?.jsonPrimitive?.content == "Let" }.second
        assertEquals("n", let["name"]!!.jsonPrimitive.content)
        val addApp = nodes[let["body"]!!.jsonPrimitive.content]!!.jsonObject
        assertEquals("add", addApp["function"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sourceLines map nodes back to Layer F lines`() {
        val (_, result) = compileNodes(workedExample)
        // The declare sits on line 3; the const (the write call) on line 6.
        assertEquals(3, result.sourceLines["fsWrite"])
        assertTrue(result.sourceLines.values.contains(6)) {
            "expected a node mapped to the const line; got ${result.sourceLines}"
        }
    }

    // ------------------------------------------------------------------
    // Effects through the v5 machinery
    // ------------------------------------------------------------------

    @Test
    fun `prelude effectful call under a parameterized uses clause gets auto-synthesized EffectDecls`() {
        val (nodes, _) = compileNodes(
            """
            use prelude.{fsRead, bytesLen}

            function main(): Int uses Filesystem.Read("/etc/app.conf") {
              return bytesLen(fsRead("/etc/app.conf"))
            }
            """.trimIndent(),
        )
        val readCall = nodes.entries.map { it.key to it.value.jsonObject }
            .first { (_, n) ->
                n["type"]?.jsonPrimitive?.content == "Application" &&
                    n["function"]?.jsonPrimitive?.content == "fsRead"
            }.second
        // AutoEffectSynthesis (@auto) produced the EffectDecl from the
        // prelude fsRead Q-039 projection: parameters = [the path arg].
        val pathArg = readCall["arguments"]!!.jsonArray[0].jsonPrimitive.content
        val efdId = readCall["effectInstances"]!!.jsonArray[0].jsonPrimitive.content
        val efd = nodes[efdId]!!.jsonObject
        assertEquals("readFx", efd["effectType"]!!.jsonPrimitive.content)
        assertEquals(listOf(pathArg), efd["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `bare uses entry plants no effect instances`() {
        val (nodes, _) = compileNodes(
            """
            use prelude.{now, add}

            declare function logInfo(msg: String): Unit from "strand-builtin:Log.Info" uses Log.Write

            function main(): Int uses Time.Now {
              return add(now(), 1)
            }
            """.trimIndent(),
        )
        val nowCall = nodes.entries.map { it.key to it.value.jsonObject }
            .first { (_, n) ->
                n["type"]?.jsonPrimitive?.content == "Application" &&
                    n["function"]?.jsonPrimitive?.content == "now"
            }.second
        assertTrue(nowCall["effectInstances"] == null || nowCall["effectInstances"]!!.jsonArray.isEmpty()) {
            "a bare `uses Time.Now` must not plant EffectDecls; got $nowCall"
        }
    }

    @Test
    fun `function uses clause becomes the explicit Lambda effects row`() {
        val (nodes, _) = compileNodes(
            """
            declare function now(): Int from "strand-builtin:Time.Now" uses Time.Now
            declare function logInfo(msg: String): Unit from "strand-builtin:Log.Info" uses Log.Write
            use prelude.{mul, concat, intToStr}

            function auditedDouble(x: Int): Int uses Time.Now, Log.Write {
              const ignore = logInfo(concat("audit:", intToStr(now())))
              return mul(x, 2)
            }
            function main(): Int {
              return auditedDouble(21)
            }
            """.trimIndent(),
        )
        val lam = nodes["auditedDouble"]!!.jsonObject
        assertEquals("Lambda", lam["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("nowFx", "logFx"), lam["effects"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The helper call site carries no instances (the corpus shape).
        val helperCall = nodes.entries.map { it.value.jsonObject }
            .first {
                it["type"]?.jsonPrimitive?.content == "Application" &&
                    it["function"]?.jsonPrimitive?.content == "auditedDouble"
            }
        assertTrue(helperCall["effectInstances"] == null || helperCall["effectInstances"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `function without a uses clause gets an explicit empty effects row`() {
        val (nodes, _) = compileNodes(
            """
            declare function write(path: String): Int from "strand-builtin:Filesystem.Write" uses Filesystem.Write(path)
            use prelude.{add}

            function helper(): Int {
              return write("/tmp/x")
            }
            function main(): Int {
              return add(helper(), 1)
            }
            """.trimIndent(),
        )
        // The omission is preserved (effects: []) so the verifier reports
        // UncoveredEffects — scenario 5's premise. An ABSENT row would let
        // the elaborator infer [writeFx] and silently cover the omission.
        val lam = nodes["helper"]!!.jsonObject
        assertEquals(0, lam["effects"]!!.jsonArray.size) {
            "expected an explicit empty effects row; got $lam"
        }
    }

    // ------------------------------------------------------------------
    // Recursion via Fixpoint
    // ------------------------------------------------------------------

    @Test
    fun `named recursion lambda-lifts to Fixpoint`() {
        val (nodes, _) = compileNodes(
            """
            use prelude.{eqInt, sub, mul}

            function fact(n: Int): Int {
              return match (eqInt(n, 0)) {
                true => 1,
                false => mul(n, fact(sub(n, 1))),
              }
            }
            function main(): Int {
              return fact(5)
            }
            """.trimIndent(),
        )
        val fix = nodes["fact"]!!.jsonObject
        assertEquals("Fixpoint", fix["type"]!!.jsonPrimitive.content)
        val fnt = nodes[fix["recursionType"]!!.jsonPrimitive.content]!!.jsonObject
        assertEquals(listOf("intT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("intT", fnt["result"]!!.jsonPrimitive.content)
        val lam = nodes[fix["body"]!!.jsonPrimitive.content]!!.jsonObject
        val params = lam["parameters"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(2, params.size)
        // The recursion slot is the first Lambda parameter, typed by the FNT.
        val recSlot = nodes[params[0]]!!.jsonObject
        assertEquals(fix["recursionType"]!!.jsonPrimitive.content, recSlot["paramType"]!!.jsonPrimitive.content)
        // The self-call goes through a VarRef to the recursion slot.
        val selfCallVar = nodes.entries.map { it.value.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "VarRef" }
            .any { it["binder"]?.jsonPrimitive?.content == params[0] }
        assertTrue(selfCallVar) { "expected a VarRef to the recursion slot" }
    }

    // ------------------------------------------------------------------
    // Type aliases, sums, match
    // ------------------------------------------------------------------

    @Test
    fun `option-style union lowers to SumType with collapsed single-field payloads`() {
        val (nodes, _) = compileNodes(
            """
            type Option = { tag: "Some", v: Int } | { tag: "None" }

            function main(): Int {
              return match ({ tag: "Some", v: 42 }) {
                Some(n) => n,
                None => 0,
              }
            }
            """.trimIndent(),
        )
        val sum = nodes["Option"]!!.jsonObject
        assertEquals("SumType", sum["type"]!!.jsonPrimitive.content)
        val cases = sum["cases"]!!.jsonArray.map { nodes[it.jsonPrimitive.content]!!.jsonObject }
        assertEquals(listOf("Some", "None"), cases.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("intT", cases[0]["caseType"]!!.jsonPrimitive.content)

        val sv = nodes.entries.map { it.value.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "SumValue" }
        assertEquals("Some", sv["caseName"]!!.jsonPrimitive.content)
        assertEquals("Option", sv["ofType"]!!.jsonPrimitive.content)

        val pcn = nodes.entries.map { it.value.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "Pattern" }
            .first { it["caseName"]?.jsonPrimitive?.content == "Some" }
        assertEquals("Option", pcn["patternType"]!!.jsonPrimitive.content)
        val pvr = nodes[pcn["payloadPattern"]!!.jsonPrimitive.content]!!.jsonObject
        assertEquals("variable", pvr["kind"]!!.jsonPrimitive.content)
        assertEquals("intT", pvr["patternType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `recursive list alias produces the RT plus RS inner-outer product split`() {
        val (nodes, _) = compileNodes(
            """
            type List = { tag: "Cons", head: Int, tail: List } | { tag: "Nil" }
            use prelude.{add}

            function sum(xs: List): Int {
              return match (xs) {
                Cons(p) => add(p.head, sum(p.tail)),
                Nil => 0,
              }
            }
            function main(): Int {
              const nil = { tag: "Nil" }
              return sum({ tag: "Cons", head: 1, tail: { tag: "Cons", head: 2, tail: nil } })
            }
            """.trimIndent(),
        )
        val rt = nodes["List"]!!.jsonObject
        assertEquals("RecursiveType", rt["type"]!!.jsonPrimitive.content)
        val sum = nodes[rt["body"]!!.jsonPrimitive.content]!!.jsonObject
        assertEquals("SumType", sum["type"]!!.jsonPrimitive.content)

        // Inner product: tail -> RecursiveSelf. Outer product: tail -> RT.
        val consCase = sum["cases"]!!.jsonArray.map { nodes[it.jsonPrimitive.content]!!.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "Cons" }
        val innerProd = nodes[consCase["caseType"]!!.jsonPrimitive.content]!!.jsonObject
        val innerTail = innerProd["fields"]!!.jsonArray
            .map { nodes[it.jsonPrimitive.content]!!.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "tail" }
        assertEquals("RecursiveSelf",
            nodes[innerTail["fieldType"]!!.jsonPrimitive.content]!!.jsonObject["type"]!!.jsonPrimitive.content)

        // Construction site uses the OUTER product (tail -> List).
        val pv = nodes.entries.map { it.value.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "ProductValue" }
        val outerProd = nodes[pv["ofType"]!!.jsonPrimitive.content]!!.jsonObject
        val outerTail = outerProd["fields"]!!.jsonArray
            .map { nodes[it.jsonPrimitive.content]!!.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "tail" }
        assertEquals("List", outerTail["fieldType"]!!.jsonPrimitive.content)

        // The Cons pattern binder is typed by the outer product.
        val pcn = nodes.entries.map { it.value.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "Pattern" }
            .first { it["caseName"]?.jsonPrimitive?.content == "Cons" }
        assertEquals(pv["ofType"]!!.jsonPrimitive.content,
            nodes[pcn["payloadPattern"]!!.jsonPrimitive.content]!!.jsonObject["patternType"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // Attempt
    // ------------------------------------------------------------------

    @Test
    fun `attempt plus Ok-Err match lowers through TRY and the RES sugar`() {
        val (nodes, _) = compileNodes(
            """
            use prelude.{fsRead}

            function main(): Bytes {
              return match (attempt { fsRead("missing/config.json") }) {
                Ok(b) => b,
                Err(e) => fsRead("/dev/null"),
              }
            }
            """.trimIndent(),
        )
        val att = nodes.entries.map { it.value.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "Attempt" }
        val readApp = nodes[att["body"]!!.jsonPrimitive.content]!!.jsonObject
        assertEquals("fsRead", readApp["function"]!!.jsonPrimitive.content)

        // Ok/Err constructor patterns typed by the RES-expanded sum.
        val patterns = nodes.entries.map { it.value.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "Pattern" &&
                it["kind"]?.jsonPrimitive?.content == "constructor" }
        val ok = patterns.first { it["caseName"]!!.jsonPrimitive.content == "Ok" }
        val err = patterns.first { it["caseName"]!!.jsonPrimitive.content == "Err" }
        val resSum = nodes[ok["patternType"]!!.jsonPrimitive.content]!!.jsonObject
        assertEquals("SumType", resSum["type"]!!.jsonPrimitive.content)
        assertEquals(ok["patternType"], err["patternType"])
        // Ok binder is the body type; Err binder is the errPayloadT product.
        assertEquals("bytesT",
            nodes[ok["payloadPattern"]!!.jsonPrimitive.content]!!.jsonObject["patternType"]!!.jsonPrimitive.content)
        assertEquals("errPayloadT",
            nodes[err["payloadPattern"]!!.jsonPrimitive.content]!!.jsonObject["patternType"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // Imports and interop
    // ------------------------------------------------------------------

    @Test
    fun `hash import lowers to a targetHash NodeRef`() {
        val hex = "1e" + "ab".repeat(32)
        val (nodes, _) = compileNodes(
            """
            use "b3:$hex" as helper

            function main(): Int {
              return helper(40)
            }
            """.trimIndent(),
        )
        val nrf = nodes["helper"]!!.jsonObject
        assertEquals("NodeRef", nrf["type"]!!.jsonPrimitive.content)
        assertEquals(hex, nrf["targetHash"]!!.jsonPrimitive.content)
        assertTrue(nrf["target"] == null)
    }

    @Test
    fun `bare dotted registry call expands through ImplicitBuiltinExpansion`() {
        val (nodes, _) = compileNodes(
            """
            function main(): Int {
              return Math.Abs(-5)
            }
            """.trimIndent(),
        )
        val fn = nodes.entries.map { it.value.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "ForeignNode" }
        assertEquals("strand-builtin:Math.Abs", fn["target"]!!.jsonPrimitive.content)
    }

    @Test
    fun `arrow functions lower to pure lambdas`() {
        val (nodes, _) = compileNodes(
            """
            use prelude.{mul}

            function main(): Int {
              const double = (x: Int) => mul(x, 2)
              return double(21)
            }
            """.trimIndent(),
        )
        val lam = nodes.entries.map { it.value.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "Lambda" }
        assertEquals(0, lam["effects"]!!.jsonArray.size)
        val prc = nodes[lam["parameters"]!!.jsonArray[0].jsonPrimitive.content]!!.jsonObject
        assertEquals("intT", prc["paramType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `top-level consts are shared document nodes not Lets`() {
        val (nodes, result) = compileNodes(
            """
            use prelude.{add}
            const base = 40

            function main(): Int {
              return add(base, add(base, 2))
            }
            """.trimIndent(),
        )
        assertNotNull(result)
        // No Let in the document; the literal is referenced twice.
        assertTrue(nodes.values.none { it.jsonObject["type"]?.jsonPrimitive?.content == "Let" })
        val addApps = nodes.values.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "Application" }
        assertEquals(2, addApps.size)
    }

    // ------------------------------------------------------------------
    // Lowering-time diagnostics
    // ------------------------------------------------------------------

    @Test
    fun `unimported prelude name carries an import hint`() {
        val errors = try {
            Authoring.compile(
                """
                function main(): Int {
                  return add(1, 2)
                }
                """.trimIndent(),
                Authoring.Surface.FAMILIAR,
            )
            emptyList()
        } catch (e: org.strand.authoring.AuthoringException) {
            e.errors
        }
        assertTrue(errors.any { it.detail.contains("use prelude.{add}") }) {
            "expected an import hint; got $errors"
        }
    }

    @Test
    fun `missing main is reported`() {
        val errors = try {
            Authoring.compile("const x = 1", Authoring.Surface.FAMILIAR)
            emptyList()
        } catch (e: org.strand.authoring.AuthoringException) {
            e.errors
        }
        assertTrue(errors.any { it.detail.contains("function main") }) {
            "expected a missing-main diagnostic; got $errors"
        }
    }

    @Test
    fun `excluded registry names point at the declare form`() {
        val errors = try {
            Authoring.compile(
                """
                function main(): Int {
                  return Filesystem.Write("/tmp/x")
                }
                """.trimIndent(),
                Authoring.Surface.FAMILIAR,
            )
            emptyList()
        } catch (e: org.strand.authoring.AuthoringException) {
            e.errors
        }
        assertTrue(errors.any { it.detail.contains("declare function") }) {
            "expected a declare-form hint; got $errors"
        }
    }
}
