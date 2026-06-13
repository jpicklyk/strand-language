package org.strand.authoring.familiar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.strand.authoring.AuthoringError
import org.strand.authoring.AuthoringException

/**
 * Q-061 Layer F parser: the step-1 grammar parses, and everything the
 * dialect deliberately lacks (proposal § 3) is rejected with a
 * [AuthoringError.DialectViolation] carrying a hint. Covers the
 * proposal § 7 scenarios 3 (mutation rejection), 4 (untyped-parameter
 * rejection), and 10 (non-dialect TypeScript rejection).
 */
class FamiliarParserTest {

    private fun violations(text: String): List<AuthoringError.DialectViolation> = try {
        FamiliarParser.parse(text)
        fail("expected the parse of:\n$text\nto fail with dialect violations")
    } catch (e: AuthoringException) {
        val dialect = e.errors.filterIsInstance<AuthoringError.DialectViolation>()
        assertTrue(dialect.isNotEmpty()) {
            "expected at least one DialectViolation; got ${e.errors}"
        }
        dialect
    }

    private fun assertHint(text: String, constructFragment: String, hintFragment: String) {
        val v = violations(text)
        assertTrue(v.any { it.construct.contains(constructFragment) && it.hint.contains(hintFragment) }) {
            "expected a violation for '$constructFragment' hinting '$hintFragment'; got $v"
        }
    }

    // ------------------------------------------------------------------
    // The step-1 grammar parses
    // ------------------------------------------------------------------

    @Test
    fun `worked-example shape parses`() {
        val program = FamiliarParser.parse(
            """
            use prelude.{add}

            declare function write(path: String): Int from "strand-builtin:Filesystem.Write" uses Filesystem.Write(path)

            function main(): Int uses Filesystem.Write("/tmp/strand-eval.log") {
              return add(write("/tmp/strand-eval.log"), 1)
            }
            """.trimIndent(),
        )
        assertEquals(3, program.items.size)
        val use = program.items[0] as FUsePrelude
        assertEquals(listOf("add"), use.names)
        val decl = program.items[1] as FDeclareFn
        assertEquals("write", decl.name)
        assertEquals("strand-builtin:Filesystem.Write", decl.target)
        assertEquals(1, decl.uses.size)
        assertEquals("Filesystem.Write", decl.uses[0].category)
        assertEquals(listOf("path"), decl.uses[0].args?.map { (it as FIdent).name })
        val main = program.items[2] as FFunction
        assertEquals("main", main.name)
        assertEquals("Filesystem.Write", main.uses[0].category)
        assertEquals(1, main.uses[0].args?.size)
        val ret = main.body.result as FCall
        assertEquals("add", (ret.callee as FIdent).name)
    }

    @Test
    fun `type aliases parse as products and tagged unions`() {
        val program = FamiliarParser.parse(
            """
            type Point = { x: Int, y: Int }
            type List = { tag: "Cons", head: Int, tail: List } | { tag: "Nil" }
            function main(): Int {
              return 0
            }
            """.trimIndent(),
        )
        val point = program.items[0] as FTypeAlias
        assertTrue(point.isProduct)
        assertEquals(listOf("x", "y"), point.shapes[0].fields.map { it.name })
        val list = program.items[1] as FTypeAlias
        assertTrue(!list.isProduct)
        assertEquals(listOf("Cons", "Nil"), list.shapes.map { it.tag })
        assertEquals(listOf("head", "tail"), list.shapes[0].fields.map { it.name })
    }

    @Test
    fun `match attempt object literal and arrow parse`() {
        val program = FamiliarParser.parse(
            """
            type Option = { tag: "Some", v: Int } | { tag: "None" }
            const fallback = { tag: "None" }
            function main(): Int {
              const r = attempt { div(1, 0) }
              return match (r) {
                Ok(v) => v,
                Err(_) => 0,
              }
            }
            """.trimIndent(),
        )
        val main = program.items[2] as FFunction
        assertEquals(1, main.body.consts.size)
        assertTrue(main.body.consts[0].value is FAttempt)
        val match = main.body.result as FMatch
        assertEquals(2, match.arms.size)
        val ok = match.arms[0].pattern as FCtorPattern
        assertEquals("Ok", ok.ctor)
        assertEquals("v", ok.binder)
        val err = match.arms[1].pattern as FCtorPattern
        assertTrue(err.binderIsWild)
    }

    @Test
    fun `hash import and dotted builtin calls parse`() {
        val program = FamiliarParser.parse(
            """
            use "b3:1e${"ab".repeat(32)}" as helper
            function main(): Int {
              return Math.Abs(helper(-3))
            }
            """.trimIndent(),
        )
        val hashImport = program.items[0] as FUseHash
        assertEquals("helper", hashImport.alias)
        assertEquals(66, hashImport.hashHex.length)
        val call = (program.items[1] as FFunction).body.result as FCall
        val callee = call.callee as FFieldGet
        assertEquals("Abs", callee.field)
        assertEquals("Math", (callee.target as FIdent).name)
    }

    @Test
    fun `negative literals and float literals parse`() {
        val program = FamiliarParser.parse(
            """
            const x = -42
            const y = 3.5
            function main(): Int { return x }
            """.trimIndent(),
        )
        assertEquals(-42L, ((program.items[0] as FConst).value as FIntLit).value)
        assertEquals(3.5, ((program.items[1] as FConst).value as FFloatLit).value)
    }

    // ------------------------------------------------------------------
    // Scenario 3 — mutation rejection
    // ------------------------------------------------------------------

    @Test
    fun `let binding is rejected with a const hint`() {
        assertHint(
            """
            function main(): Int {
              let x = 1
              return x
            }
            """.trimIndent(),
            "`let`", "const",
        )
    }

    @Test
    fun `reassignment statement is rejected`() {
        assertHint(
            """
            const x = 1
            function main(): Int {
              x = 2
              return x
            }
            """.trimIndent(),
            "reassignment", "immutable",
        )
    }

    @Test
    fun `var is rejected`() {
        assertHint("var x = 1", "`var`", "const")
    }

    // ------------------------------------------------------------------
    // Scenario 4 — untyped-parameter rejection
    // ------------------------------------------------------------------

    @Test
    fun `untyped function parameter is rejected`() {
        assertHint(
            """
            function f(x): Int { return x }
            """.trimIndent(),
            "untyped parameters", "type annotation",
        )
    }

    @Test
    fun `untyped arrow parameter is rejected`() {
        assertHint(
            """
            const f = (x) => x
            """.trimIndent(),
            "untyped parameters", "type annotation",
        )
    }

    @Test
    fun `any annotation is rejected`() {
        assertHint(
            """
            function f(x: any): Int { return x }
            """.trimIndent(),
            "`any`", "concrete type",
        )
    }

    @Test
    fun `missing return annotation is rejected`() {
        assertHint(
            """
            function f(x: Int) { return x }
            """.trimIndent(),
            "missing return annotations", "return type",
        )
    }

    // ------------------------------------------------------------------
    // Scenario 10 — non-dialect TypeScript rejection
    // ------------------------------------------------------------------

    @Test
    fun `class declarations are rejected`() {
        assertHint("class Foo { }", "classes", "type")
    }

    @Test
    fun `async functions are rejected`() {
        assertHint("async function f(): Int { return 1 }", "`async`", "uses")
    }

    @Test
    fun `import statements are rejected`() {
        assertHint("import { x } from \"./mod\"", "`import`", "use prelude")
    }

    @Test
    fun `template literals are rejected`() {
        assertHint(
            """
            const x = `hello`
            """.trimIndent(),
            "template literals", "concat",
        )
    }

    @Test
    fun `loops are rejected with a recursion hint`() {
        assertHint(
            """
            function main(): Int {
              for (const x of xs) { }
              return 0
            }
            """.trimIndent(),
            "loops", "List",
        )
    }

    @Test
    fun `if statements are rejected with a match hint`() {
        assertHint(
            """
            function main(): Int {
              if (true) { return 1 }
              return 0
            }
            """.trimIndent(),
            "`if`", "match",
        )
    }

    @Test
    fun `try catch is rejected with an attempt hint`() {
        assertHint(
            """
            function main(): Int {
              try { return 1 } catch (e) { return 0 }
              return 0
            }
            """.trimIndent(),
            "exceptions", "attempt",
        )
    }

    @Test
    fun `binary operators are rejected with prelude-call hints`() {
        assertHint(
            """
            function main(): Int {
              return 1 + 2
            }
            """.trimIndent(),
            "`+` operator", "add",
        )
    }

    @Test
    fun `ternary is rejected with a match hint`() {
        assertHint(
            """
            function main(): Int {
              return true ? 1 : 0
            }
            """.trimIndent(),
            "`?` operator", "match",
        )
    }

    @Test
    fun `lowercase typescript primitive names are rejected with the Strand name`() {
        assertHint("function f(x: number): Int { return x }", "`number` type", "Int or Float")
        assertHint("function f(x: string): Int { return 0 }", "`string` type", "String")
        assertHint("function f(x: boolean): Int { return 0 }", "`boolean` type", "Bool")
    }

    @Test
    fun `arbitrary string imports are rejected`() {
        assertHint("use \"./helpers\" as h\nfunction main(): Int { return 0 }", "", "b3:")
    }

    // ------------------------------------------------------------------
    // Error accumulation
    // ------------------------------------------------------------------

    @Test
    fun `multiple violations across items are all reported`() {
        val errors = try {
            FamiliarParser.parse(
                """
                let x = 1
                class Foo { }
                function main(): Int { return 0 }
                """.trimIndent(),
            )
            fail("expected a parse failure")
        } catch (e: AuthoringException) {
            e.errors.filterIsInstance<AuthoringError.DialectViolation>()
        }
        assertTrue(errors.size >= 2) { "expected two violations, got $errors" }
        assertEquals(listOf(1, 2), errors.map { it.line }.take(2))
    }

    @Test
    fun `lines are tracked for diagnostics`() {
        val errors = try {
            FamiliarParser.parse(
                """
                function main(): Int {
                  const a = 1
                  const b = a
                  b = 2
                  return b
                }
                """.trimIndent(),
            )
            fail("expected a parse failure")
        } catch (e: AuthoringException) {
            e.errors
        }
        assertTrue(errors.any { it.line == 4 }) { "expected a line-4 diagnostic, got $errors" }
    }
}
