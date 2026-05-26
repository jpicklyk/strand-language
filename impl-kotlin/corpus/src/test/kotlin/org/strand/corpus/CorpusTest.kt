package org.strand.corpus

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * Drives the Layer 1 seed corpus end-to-end:
 *  - every program ingests cleanly,
 *  - every program verifies without errors,
 *  - programs marked "runnable" evaluate to the expected value.
 *
 * Corpus 16 and 17 exercise the Time.Now builtin and assert against
 * [Builtins.FIXED_REPLAY_TIMESTAMP]. To keep that deterministic after
 * Layer 4 step 2's "Time.Now reads from the active clock" change, this
 * suite installs [Builtins.FixedClock] in @BeforeAll and restores
 * [Builtins.SystemClock] in @AfterAll.
 */
class CorpusTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun installFixedClock() {
            Builtins.clock = Builtins.FixedClock(Builtins.FIXED_REPLAY_TIMESTAMP)
        }
        @JvmStatic
        @AfterAll
        fun restoreSystemClock() {
            Builtins.clock = Builtins.SystemClock
        }
    }

    private data class Case(
        val resource: String,
        val expected: Value?,
        val notes: String = ""
    )

    private val cases = listOf(
        Case("/corpus/01-int-literal.json", Value.IntV(42)),
        Case("/corpus/02-identity-applied.json", Value.IntV(42)),
        Case("/corpus/03-let-identity.json", Value.BoolV(true)),
        Case("/corpus/04-k-combinator.json", Value.IntV(7)),
        Case("/corpus/05-s-combinator-typed.json", null, "verify-only; S is type-only here"),
        Case("/corpus/06-let-polymorphic.json", Value.BoolV(true)),
        Case("/corpus/07-higher-order.json", Value.IntV(99)),
        Case("/corpus/08-product-type-decl.json", null, "verify-only; no product constructor in Layer 1"),
        Case("/corpus/09-sum-type-decl.json", null, "verify-only; no sum constructor in Layer 1"),
        Case("/corpus/10-noderef-shared.json", Value.IntV(123)),
        Case("/corpus/11-higher-rank-apply.json", Value.IntV(7)),
        // Layer 3: effects and capabilities
        Case("/corpus/12-effect-declared-and-granted.json", Value.IntV(7),
            "Lambda declares Time.Now; runs under a context granting Time.Now"),
        Case("/corpus/13-capability-scope-narrow-then-call.json", Value.IntV(7),
            "CapabilityScope retains Time.Now while narrowing away other capabilities"),
        Case("/corpus/14-multi-effect-lambda.json", Value.IntV(9),
            "Lambda declares three effects; over-declaration is allowed"),
        // Layer 4 step 1: ForeignNode + builtins
        Case("/corpus/15-builtin-add.json", Value.IntV(5),
            "Pure builtin Int.Add via ForeignNode; no capabilities required"),
        Case("/corpus/16-builtin-time-now-under-capability.json", Value.IntV(Builtins.FIXED_REPLAY_TIMESTAMP),
            "Effectful builtin Time.Now under granted Time.Now capability"),
        Case("/corpus/17-builtin-compose-pure-and-effectful.json", Value.IntV(Builtins.FIXED_REPLAY_TIMESTAMP + 1),
            "Compose pure Int.Add with effectful Time.Now; sums the timestamp with 1"),
        // Layer 5 step 1: Match + patterns
        Case("/corpus/18-match-int-literal-with-wildcard.json", Value.IntV(100),
            "Match an Int against a literal 0; wildcard fallback. Scrutinee is 0, so first case wins."),
        Case("/corpus/19-match-on-comparison-result.json", Value.IntV(1),
            "Match on the Bool result of Int.Lt(3, 5); BoolLit(true) → 1, BoolLit(false) → 0."),
        Case("/corpus/20-match-variable-binding.json", Value.IntV(17),
            "Match with a variable pattern binding the scrutinee; body computes n + 10. Scrutinee is 7, so result is 17."),
        // Layer 5 step 2: Fixpoint (recursion)
        Case("/corpus/21-fixpoint-factorial.json", Value.IntV(120),
            "Factorial via Fixpoint+Match. fact(0)=1; fact(n)=n*fact(n-1). Applied to 5 → 120."),
        Case("/corpus/22-fixpoint-sum-to-n.json", Value.IntV(55),
            "Sum 1..N via Fixpoint+Match. sum(n)=0 if n<=0 else n+sum(n-1). Applied to 10 → 55."),
        // Layer 5 step 3a: product values
        Case("/corpus/23-product-construct-and-access.json", Value.IntV(4),
            "Construct a {x:3, y:4} product and read field y. Smallest end-to-end product example."),
        Case("/corpus/24-product-sum-fields-via-lambda.json", Value.IntV(42),
            "A Lambda over a product type reads two fields and adds them. Demonstrates products flowing through Application boundaries."),
        // Layer 5 step 3b: sum values + constructor patterns
        Case("/corpus/25-option-some-unwrap.json", Value.IntV(42),
            "Option-like sum: construct Some(42), Match with constructor pattern binding the payload, return it."),
        Case("/corpus/26-option-none-default.json", Value.IntV(-1),
            "Option-like sum: construct None, Match with both Some(n) and None cases, return the None fallback."),
        Case("/corpus/27-result-ok-or-err.json", Value.IntV(107),
            "Result-like sum with Ok:Int / Err:String. Ok(7) matched via constructor pattern, body computes n+100. Err case unused."),
        // Combination programs (post-Layer-5 polish): exercise the language's full vocabulary
        Case("/corpus/28-safe-divide-success.json", Value.IntV(5),
            "safe-divide: Lambda returning Option<Int>; divide(10, 2) → Some(5), unwrapped by Match to 5."),
        Case("/corpus/29-safe-divide-by-zero.json", Value.IntV(-1),
            "safe-divide: Lambda returning Option<Int>; divide(10, 0) → None, unwrapped by Match to fallback -1."),
        Case("/corpus/30-string-concat.json", Value.StringV("Hello, Strand!"),
            "String.Concat builtin: concatenate two string literals. Smallest demo of the String operations."),
        // Recursive types (N-041 + N-042) — first programs over recursive sum types
        Case("/corpus/31-recursive-list-head.json", Value.IntV(7),
            "Construct a 1-element linked list Cons(7, Nil) over a recursive sum type and extract the head via Match + ProductFieldGet."),
        Case("/corpus/32-recursive-list-sum.json", Value.IntV(6),
            "Recursive sum over a linked list of 1..3 via Fixpoint + Match. Demonstrates the full vocabulary in one program: recursive types, sum constructors, product field access, pattern matching, and recursion."),
        // Q-031: refinement-lattice capability matching (Layer 3 step 2).
        // These programs supply Application.effectInstances and run under
        // a structured CapabilitySet via the `refinedCapabilitiesFor` map
        // below. The builtin targets (Filesystem.Write, Network.Connect)
        // are stub registrations in the in-process Builtins table — they
        // return stable sentinel Ints so the call dispatch is observable
        // without actually performing I/O.
        Case("/corpus/33-refined-network-connect.json", Value.IntV(1),
            "Q-031: Network.Connect(api.example.com, 443) under a Concrete refinement grant {host: \"api.example.com\", port: 443}. Demonstrates the simplest positive refinement match: the EffectDecl's evaluated parameters equal the granted Concrete slots."),
        Case("/corpus/34-refined-wildcard-port.json", Value.IntV(1),
            "Q-031: Network.Connect(api.example.com, 8080) under a wildcard-port grant {host: \"api.example.com\", port: *}. Demonstrates per-slot wildcards: a Wildcard slot accepts any value, a Concrete slot constrains by value equality. Connects on any port the host policy allows."),
        Case("/corpus/35-refined-logger-authorized-path.json", Value.IntV(0),
            "Q-031: a logger Lambda that writes to a caller-supplied path. The outer call passes \"/var/log/app.log\" — the path the granted Filesystem.Write{path: \"/var/log/app.log\"} authorizes — and the inner write succeeds. Together with the negative case (caller passing \"/etc/passwd\", covered by InterpreterTest's confused-deputy scenario 9), demonstrates the parameter-tagged capability defense in operation."),
        // Q-030: no-continuation effect handlers (Layer 3 step 3).
        // These programs run under EMPTY capability context — the handler
        // intercepts the effectful call and supplies a return value, so
        // the surrounding context does not need the intercepted effect.
        // Program 39 is the exception: the handler itself performs
        // Filesystem.Write, which the surrounding context must grant.
        Case("/corpus/36-handler-mock-time-now.json", Value.IntV(1700000000),
            "Q-030 scenario 1: a Handler over Time.Now mocks the now() builtin to return a fixed timestamp. Program runs under EMPTY capabilities — the handler's closure subtraction removes Time.Now from the body's effective requirements. Smallest end-to-end Handler demo."),
        Case("/corpus/37-handler-captures-outer-let.json", Value.IntV(1700000000),
            "Q-030 scenario 2: a Handler whose `handle` Lambda reads a value bound by an outer Let. Confirms the handler's lexical environment is captured at Handler-entry, so values in scope at that point flow into the handler's evaluation."),
        Case("/corpus/38-handler-nested-innermost-wins.json", Value.IntV(2),
            "Q-030 scenario 3: two Handlers for the same effect category nested inside each other; the innermost intercepts. The body calls now(); the inner handler returns 2 and the outer handler (which would have returned 1) is shadowed."),
        Case("/corpus/39-handler-itself-performs-effect.json", Value.IntV(0),
            "Q-030 scenario 4 (under {Filesystem.Write}): the Handler intercepts Time.Now; its `handle` Lambda itself writes to a file via the Filesystem.Write builtin. The closure-subtraction rule plus closure-union means the surrounding context needs Filesystem.Write but NOT Time.Now — exactly what the proposal § 6.3 algebra states."),
        Case("/corpus/40-handler-fires-through-fixpoint.json", Value.IntV(4),
            "Q-030 scenario 6: a recursive function over Fixpoint that wraps every now() call in a freshly-installed Handler returning 1. Regression test for handler threading across Fixpoint self-references. f(3) returns 1+1+1+1 = 4 (one handler firing at each n=3,2,1,0)."),

        // Phase 4 #10 — blessed Option<T> convention with real
        // builtin-produced values. These corpus programs are
        // canonical exemplars of the Option pattern that all the
        // Layer 4 step 2 builtins use for their fallible-parse
        // results (String.ParseInt, Bytes.ParseUtf8, etc.).
        Case("/corpus/64-option-parseint-unwrap.json", Value.IntV(42L),
            "Phase 4 #10: ParseInt(\"42\") -> Some(42), unwrapped by Match to 42. Demonstrates the canonical Option<Int> shape (SumV \"Some\" / \"None\") that all the new String.* / Bytes.* / Process.* builtins use for fallible-parse / fallible-lookup results."),
        Case("/corpus/65-option-parseint-fallback.json", Value.IntV(-1L),
            "Phase 4 #10: ParseInt(\"not a number\") -> None, unwrapped to fallback -1. Pair with corpus 64 for the canonical Option-with-default pattern."),

        // Slice 3 of stdlib expansion round 2 — nested-μ JsonValue
        // exercising the new RecursiveSelf depth field. corpus 66
        // builds a [1, 2] array literal using the full six-case
        // JsonValueFull schema (corpus 54 stays flat for backward
        // compat). The identity Lambda returns the array value
        // unchanged; the schemaClaim evaluates to the JsonArray
        // SumV with a Cons-Nil chain of JsonNumber payloads.
        Case(
            "/corpus/66-json-value-nested.json",
            // [1, 2] encoded as JsonArrayCons(JsonNumber(1),
            //                     JsonArrayCons(JsonNumber(2),
            //                       JsonArrayNil))
            Value.SumV(
                "JsonArrayCons",
                Value.ProductV(mapOf(
                    "head" to Value.SumV("JsonNumber", Value.IntV(1L)),
                    "tail" to Value.SumV(
                        "JsonArrayCons",
                        Value.ProductV(mapOf(
                            "head" to Value.SumV("JsonNumber", Value.IntV(2L)),
                            "tail" to Value.SumV("JsonArrayNil", null),
                        )),
                    ),
                )),
            ),
            "Slice 3: post-blocker JsonValue with spliced JsonArrayCons/JsonArrayNil and JsonObjectCons/JsonObjectNil variants inside a single RecursiveType. The depth-field extension is sound but doesn't compose with value construction across nested μ; the spliced-variants approach keeps the type self-contained while still expressing arrays and objects. arrayValue [JsonNumber(1), JsonNumber(2)] round-trips through the identity Lambda unchanged.",
        ),
        // Q-038 Phase 1 — Pinecone vector-store builtin graph shape.
        // Verify-only: the program demonstrates the structural pattern
        // an agent emits for a Pinecone upsert + query workflow under
        // Vector.Read{provider: "pinecone", store: "main"} + Vector.Write
        // capabilities. Runtime execution requires mocked HTTP transport
        // and a registered Resource handle; that path is exercised
        // end-to-end by BuiltinsPineconeTest in the interpreter module.
        Case("/corpus/67-vector-pinecone-upsert-query.json", null,
            "Q-038 Phase 1: verify-only structural exemplar for Pinecone upsert + query. Open declares both Vector.Read and Vector.Write; Upsert declares only Vector.Write; Query declares only Vector.Read. provider parameter pinned to literal \"pinecone\", store parameter to literal \"main\". The runtime end-to-end roundtrip lives in BuiltinsPineconeTest with mocked HTTP transport."),
    )

    /**
     * For Layer 3 programs that declare effects, the interpreter needs a
     * capability context. This map specifies the EffectCategory NodeId
     * lookup keys (by author id) per program. Pre-Q-031 programs use
     * category-only matching via [CapabilitySet.ofCategories].
     */
    private val capabilitiesFor: Map<String, List<String>> = mapOf(
        "/corpus/12-effect-declared-and-granted.json" to listOf("timeFx"),
        "/corpus/13-capability-scope-narrow-then-call.json" to listOf("timeFx", "netFx"),
        "/corpus/14-multi-effect-lambda.json" to listOf("timeFx", "netFx", "fsFx"),
        "/corpus/16-builtin-time-now-under-capability.json" to listOf("timeFx"),
        "/corpus/17-builtin-compose-pure-and-effectful.json" to listOf("timeFx"),
        // Q-030 program 39 needs the handler's own effect (Filesystem.Write)
        // granted; the intercepted Time.Now is consumed by the Handler.
        "/corpus/39-handler-itself-performs-effect.json" to listOf("fsWriteFx"),
    )

    /**
     * Q-031 programs use refinement-bearing capabilities. Each entry is a
     * builder that consumes the program's author-id → NodeId map and
     * returns a [CapabilitySet] with concrete patterns. This map takes
     * precedence over [capabilitiesFor] when both are present (none of
     * the listed programs are also in [capabilitiesFor], by design).
     */
    private val refinedCapabilitiesFor: Map<String, (Map<String, NodeId>) -> CapabilitySet> = mapOf(
        "/corpus/33-refined-network-connect.json" to { names ->
            CapabilitySet(mapOf(
                names.getValue("netConnFx") to listOf(CapabilityPattern(listOf(
                    CapabilityArgument.Concrete(Value.StringV("api.example.com")),
                    CapabilityArgument.Concrete(Value.IntV(443L)),
                )))
            ))
        },
        "/corpus/34-refined-wildcard-port.json" to { names ->
            CapabilitySet(mapOf(
                names.getValue("netConnFx") to listOf(CapabilityPattern(listOf(
                    CapabilityArgument.Concrete(Value.StringV("api.example.com")),
                    CapabilityArgument.Wildcard,
                )))
            ))
        },
        "/corpus/35-refined-logger-authorized-path.json" to { names ->
            CapabilitySet(mapOf(
                names.getValue("fsWriteFx") to listOf(CapabilityPattern(listOf(
                    CapabilityArgument.Concrete(Value.StringV("/var/log/app.log")),
                )))
            ))
        },
    )

    @TestFactory
    fun corpus(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.resource.substringAfterLast('/')) {
            val stream = CorpusTest::class.java.getResourceAsStream(case.resource)
                ?: error("missing resource ${case.resource}")
            val text = stream.bufferedReader().readText()
            val ingest = JsonIngest.parse(text)
            val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
            val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
            assertTrue(verify is VerifyResult.Ok, "verifier failed for ${case.resource}: $verify")
            if (case.expected != null) {
                val interpreter = Interpreter(finalized.store, finalized.hashToNodeId)
                val refinedBuilder = refinedCapabilitiesFor[case.resource]
                val v = if (refinedBuilder != null) {
                    interpreter.eval(finalized.root, refinedBuilder(ingest.nameMap))
                } else {
                    val capabilityNames = capabilitiesFor[case.resource].orEmpty()
                    val capabilities = capabilityNames
                        .map { ingest.nameMap[it] ?: error("unknown capability author id '$it' in ${case.resource}") }
                        .toSet()
                    interpreter.eval(finalized.root, capabilities)
                }
                assertEquals(case.expected, v, "wrong runtime value for ${case.resource}")
            }
        }
    }
}
