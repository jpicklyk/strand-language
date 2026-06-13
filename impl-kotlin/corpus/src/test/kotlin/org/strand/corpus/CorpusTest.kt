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

        // Q-069 precise-model migration — corpus 66 retired-and-replaced.
        // The former spliced JsonValueFull program is gone (the spliced
        // model has no remaining consumer now that Json.* speak the
        // precise model); the slot is a precise round-trip demonstrator.
        // It builds a genuine N-048 JsonArray [1, 2] (a real
        // List<JsonValue>, corpus 88's construction via
        // RecursiveProjection) and applies Json.Stringify to it,
        // evaluating to the JSON text "[1,2]" — the round-trip the
        // spliced model blocked and the output-by-construction demo
        // had to cut (W4). The first corpus program to exercise a JSON
        // builtin end-to-end on a constructed value.
        Case(
            "/corpus/66-json-roundtrip-via-builtins.json",
            Value.StringV("[1,2]"),
            "Q-069: build a precise N-048 JsonArray [1, 2] (a real List<JsonValue> via RecursiveProjection, corpus 88's construction) and Json.Stringify it to \"[1,2]\". Closes the build-via-N-048 -> stringify round-trip the corpus-66 splice blocked. Json.Stringify's (jsonValueT) -> String foreignType accepts the projTop-typed array value by the N-048 equirecursive (fold/unfold) value-flow relaxation.",
        ),
        // Q-037 Phase 1 — agent-native LLM ForeignNodes. Corpus 67 is
        // a verify-only demonstrator of the agent-as-state-machine
        // pattern: a StateMachine whose transition function calls
        // Anthropic.Messages.Create with a GenerateRequest ProductV,
        // pinning the LLM.Generate{provider, model} refinement at the
        // call site. Verify-only because the GenerateRequest payload
        // uses opaque Bytes placeholders for the messages and tools
        // lists (the full Strand-side product/sum types are agent-
        // chosen and out of scope for a single corpus demo).
        Case("/corpus/67-llm-state-machine-with-tool.json", null,
            "verify-only; state machine calling Anthropic.Messages.Create with an EffectDecl pinning provider='anthropic'"),
        // Q-038 Phase 1 — Pinecone vector-store builtin graph shape.
        // Verify-only: the program demonstrates the structural pattern
        // an agent emits for a Pinecone upsert + query workflow under
        // Vector.Read{provider: "pinecone", store: "main"} + Vector.Write
        // capabilities. Runtime execution requires mocked HTTP transport
        // and a registered Resource handle; that path is exercised
        // end-to-end by BuiltinsPineconeTest in the interpreter module.
        Case("/corpus/68-vector-pinecone-upsert-query.json", null,
            "Q-038 Phase 1: verify-only structural exemplar for Pinecone upsert + query. Open declares both Vector.Read and Vector.Write; Upsert declares only Vector.Write; Query declares only Vector.Read. provider parameter pinned to literal \"pinecone\", store parameter to literal \"main\". The runtime end-to-end roundtrip lives in BuiltinsPineconeTest with mocked HTTP transport."),
        // N-045 ResponseSchemaSpec — symmetric counterpart to corpus 67's
        // ToolDef demonstrator. A minimal verify-only program: build a
        // Schema for an `{answer: String}` product, wrap it in a
        // ResponseSchemaSpec node. The verifier projects the schema's
        // valueType to JSON Schema at admission and would raise
        // ResponseSchemaTypeUnsupported if the valueType contained
        // FunctionType / ForallType / unbound TypeParameter. The wrapper
        // evaluates to a runtime Value.ResponseSchemaSpecV that the
        // LLM.Generate builtin reads through Builtins.verifierNodeTypes
        // to project at dispatch time. Root type is Bytes (opaque-handle
        // convention shared with ToolDef / Resource / MapV).
        Case("/corpus/69-response-schema-spec.json", null,
            "verify-only; ResponseSchemaSpec wrapper around a Schema describing {answer: String} — the symmetric counterpart to corpus 67's ToolDef demonstrator. The wrapper carries the schema reference into a value position the LLM.Generate builtin's responseSchema field accepts; the verifier statically projects the schema's valueType to JSON Schema at admission."),
        // N-046 ModuleManifest (Q-043 step 3b). The root is a manifest
        // exporting a pure Int.identity Lambda (declaredEffects []) and an
        // effectful Fs.writeFile Lambda (declaredEffects [Filesystem.Write]).
        // The verifier certifies each export's declared effects exactly equal
        // its effect surface (the function's declared effect row, not the
        // always-empty Lambda closure). A manifest is a passive declaration:
        // it evaluates to Unit, so the run check exercises the interpreter's
        // passive-node path under empty capabilities.
        Case("/corpus/79-module-manifest-with-effects.json", Value.UnitV,
            "N-046 (Q-043 step 3b) accept case. A ModuleManifest root bundles a pure Int.identity export (declaredEffects []) and an effectful Fs.writeFile export (declaredEffects [Filesystem.Write]). Each export's declaredEffects exactly equals its effect surface, so the manifest is admitted; it evaluates to Unit."),
        // Q-045 streaming I/O — verify-only structural exemplar. The program
        // opens a streaming LLM completion (Anthropic.Messages.CreateStream,
        // declaring E-035 LLM.Generate{provider, model}), drains it with a
        // Fixpoint + Match over LLM.Stream.Receive's Option<Bytes>
        // (Some(chunk) → recurse appending via Bytes.Concat, None → the
        // accumulator), and closes the handle (LLM.Stream.Close) before
        // returning the concatenated Bytes. The drain Lambda declares E-004
        // Network.Receive — the load-bearing transport effect the proposal
        // settles on structural-safety grounds — so the program's effect
        // closure is {LLM.Generate, Network.Receive}. Verify-only: a real
        // run needs a streaming HTTP transport and a credential; the runtime
        // drain is exercised end-to-end by StreamingReceiveTest in the
        // interpreter module with an injected transport.
        Case("/corpus/81-llm-stream-drain.json", null,
            "Q-045 verify-only: streaming-LLM drain. CreateStream declares E-035 LLM.Generate{provider=\"anthropic\", model=\"claude-opus-4-8\"}; the Fixpoint+Match drain over LLM.Stream.Receive's Option<Bytes> declares E-004 Network.Receive on its Lambda. Root type is Bytes. The runtime drain is covered by StreamingReceiveTest with an injected chunk transport."),

        // Q-047 (Layer 7 step 2). A dynamic value (Int.Sub(5,3)) flows into
        // a PositiveInt schema parameter. The value is non-static, so the
        // verify-time SchemaChecker defers it (SchemaInvariantDeferred) and
        // the graph verifies; it evaluates to the underlying Int (2). Runtime
        // schema enforcement of the invariant is exercised in
        // CorpusRuntimeSchemaTest, which installs the verifier's obligations
        // on the interpreter (CorpusTest's interpreter has none, so here the
        // program simply runs to its value).
        Case("/corpus/82-runtime-schema-dynamic-pass.json", Value.IntV(2),
            "Q-047 runtime-schema pass case: identity-over-PositiveInt applied to a dynamic Int.Sub(5,3). The argument is non-static so step-1 defers the invariant; the value (2) satisfies PositiveInt. Evaluates to IntV(2). CorpusRuntimeSchemaTest drives the same program with runtime obligations installed to assert the invariant is enforced."),

        // Q-046 (actor-runtime bridge) — verify-only structural exemplar. A
        // Bytes-accumulator state machine consumes an `External` Bytes stream
        // whose `source` is an `Application` of `Net.Connect` (declaring
        // E-001 Network.Connect). The verifier admits the source binding (the
        // opener is a registered IO-open declaring its semantic effect, the
        // eventType is Bytes, the stream is External with default
        // BlockProducer). Running it would dial 127.0.0.1:9000; the end-to-end
        // bridged drain + replay is covered by BridgedStreamTest over a
        // loopback socket.
        Case("/corpus/84-bridged-stream.json", null,
            "Q-046 verify-only: a state machine consuming a source-bound External Bytes stream. The stream's `source` is an Application of Net.Connect declaring Network.Connect; the runtime feeder (BridgedStreamTest) drains the opened handle into the machine as Bytes events under the group's Network.Receive capability."),

        // N-047 Attempt (Q-048, proposals/error-recovery.md § 7). Three
        // scenarios: 85 Ok-passthrough (a TRY over a pure Int literal,
        // unwrapped by Match to the Ok payload), 86 Fs.Read fallback (a TRY
        // over a Fs.Read on a path that cannot exist; the catchable
        // filesystem-read IoFailure becomes Err and the Match yields the
        // fallback bytes), 87 retry-with-backoff (a Fixpoint counts down
        // attempts, sleeping 0ms between tries against a deterministically-
        // missing relative path; after the final attempt the give-up branch
        // returns the fallback). The expected values assert on the fallback /
        // Ok payload only — the platform-varying Err.detail string is never
        // embedded in an expected value (branch-on-kind, never detail).
        Case("/corpus/85-attempt-ok-passthrough.json", Value.IntV(42L),
            "N-047 Attempt Ok passthrough: TRY over IntLit(42) yields Ok(42); the Match unwraps it to 42. The canonical exemplar of the verifier-synthesized Result<T> = Ok(T) | Err({kind, detail}) sum that every Attempt result inhabits."),
        Case("/corpus/86-attempt-fs-read-fallback.json", Value.BytesV("{}".toByteArray()),
            "N-047 Attempt Fs.Read fallback: TRY over Fs.Read on a path that cannot exist; the catchable filesystem-read IoFailure becomes Err({kind: \"filesystem-read\", detail: ...}) and the Match takes the Err branch, yielding the default bytes {} (0x7b7d). Asserts on the fallback value only — Err.detail is platform-varying."),
        Case("/corpus/87-attempt-retry-with-backoff.json", Value.BytesV("{}".toByteArray()),
            "N-047 Attempt retry-with-backoff (the canonical agent program): a Fixpoint counts down from 2, on each Err Time.Sleep(0)s and recurses with a deterministically-missing relative path; after the final attempt the give-up branch returns the default bytes {}. Hermetic (relative path, 0ms backoff) so it runs fast and offline."),

        // N-048 RecursiveProjection (Q-053). The precise nested-μ shapes the
        // corpus-66 splice cannot type: each constructs a value through a
        // RecursiveProjection-typed ofType / paramType / pattern type, so the
        // inner list / entry-list / child-list — which has no standalone
        // meaning (its RecursiveSelf depth=1 reaches the outer μ) — is named
        // by projecting the closed outer μ rather than by a bare open inner μ.
        Case("/corpus/88-json-array-via-projection.json", Value.IntV(3),
            "N-048: a true JSON value with a real List<JsonValue> array [1, 2]. jsonValueT = μ jv. JsonNumber(Int) | JsonArray(μ list. Cons(head: RecursiveSelf 1, tail: RecursiveSelf 0) | Nil) — the array element type reaches the outer jv binder, so it is a list OF json values, the precision corpus 66's flat splice loses. A Fixpoint folds the inner list summing each element's number; [1,2] → 3. Every construction site and the fold's list paramType / pattern types name the inner list via RecursiveProjection(jsonValueT, [Case JsonArray, Unfold])."),
        Case("/corpus/89-json-object-via-projection.json", Value.IntV(1),
            "N-048: a true JSON object map {\"k\": 1} as a real entry list. jsonValueT = μ jv. JsonNumber(Int) | JsonObject(μ. Cons(head: {key: String, value: jv}, tail: <self>) | Nil) — a nested Product (the entry) inside a list inside the outer μ, every value reaching the outer jv. Matches the entries, reads the head entry's value field, and unwraps its JsonNumber to 1. Demonstrates the Product-inside-list-inside-μ shape resolving through projections (including a Field path step into the entry)."),
        Case("/corpus/90-ast-child-list-via-projection.json", Value.IntV(5),
            "N-048: an AST whose Node case carries a child LIST. astT = μ a. Lit(Int) | Node(μ. Cons(head: RecursiveSelf 1, tail: RecursiveSelf 0) | Nil) — the child-list element type is the outer ast, so a Node holds a list of asts. Two mutually-recursive Fixpoints (eval an ast; sum a child list) — the inner list fold lexically captures the ast evaluator — recursively sum the Lit leaves of Node([Lit(2), Lit(3)]) → 5. The child-list type is named via RecursiveProjection(astT, [Case Node, Unfold])."),
        Case("/corpus/91-element-tree-via-projection.json", Value.IntV(2),
            "N-048: an HTML/SVG-style element tree — the canonical tree-of-lists shape Q-026/Q-047 deferred. elT = μ el. Text(String) | Element({tag: String, children: μ. Cons(head: RecursiveSelf 1, tail: RecursiveSelf 0) | Nil}) — Element carries a tag plus a child list of elements. Counts the Text leaves of div([text \"a\", span([text \"b\"])]) → 2. The children list is reached by a three-step path RecursiveProjection(elT, [Case Element, Field children, Unfold]) — exercising all three selector kinds (Case, Field, Unfold) in one path."),
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
        // N-047 Attempt programs: the Fs.Read / Time.Sleep calls inside the
        // TRY body still need their effects granted — Attempt is transparent
        // to the capability check (the catchable failure happens AFTER the
        // call is authorized and attempted).
        "/corpus/86-attempt-fs-read-fallback.json" to listOf("readFx"),
        "/corpus/87-attempt-retry-with-backoff.json" to listOf("readFx", "sleepFx"),
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
