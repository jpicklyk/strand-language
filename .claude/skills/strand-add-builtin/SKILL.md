---
name: strand-add-builtin
description: How to add a new builtin (a foreign function callable from Strand programs) to the reference implementation. Use this skill PROACTIVELY whenever the user mentions adding a new builtin, a new `strand-builtin:` target, a stdlib function (Math.*, String.*, Hash.*, List.*, Random.*, Fs.*, Net.*, Http.*, Process.*, Time.*, etc.), or wants to expose an existing JVM/host capability to Strand programs. Also triggers for phrasings like "add Bool.Xor", "wire up a logarithm function", "Strand needs a regex builtin", "I want agents to be able to read environment variables", "let's add Hash.Sha512", or any "add <function> to Strand"-shaped request where the function doesn't need new node-algebra structure. Spans three coordinated additions — the builtin registration, the Layer A implicit-prelude entries, and the agent-facing system-prompt documentation — plus unit tests. Skipping any of the three leaves the builtin partially shipped (callable from canonical dag-json but invisible to agents writing Layer A) which is the failure mode this skill exists to prevent.
---

# Adding a new builtin to Strand

A "builtin" is a host-language (JVM) function registered in `interpreter/Builtins.kt` under a `strand-builtin:Namespace.Name` target. Strand programs invoke it via a `ForeignNode` (Layer 4 step 1). The implementation slice is much smaller than a new node category — no spec docs, no canonical encoder changes, no verifier changes — but it touches three different audiences and historically all three are easy to miss.

## Why this is a skill and not just a checklist

One mistake produced this skill: the stdlib expansion round 2 (2026-05-26) shipped 28+ new builtins without implicit-prelude entries even though every one was monomorphic. Agents could only reach them via the verbose explicit `FNT` + `FRN` + `APP` form, multiplying token cost on every use. The corrective commit added the prelude entries the slice should have shipped with. This skill enforces the corrective: a builtin slice isn't done until all three coordinated additions land.

Three things make builtins easy to half-ship:

1. **The runtime-level work succeeds in isolation.** Adding the `Fn { ... }` entry in `Builtins.kt` plus a unit test passes the gradle suite. Nothing fails until an agent tries to use the new name in Layer A and gets `UnknownReservedName`.

2. **Layer A prelude entries depend on the builtin's *exact* signature shape.** Monomorphic builtins fit cleanly; polymorphic / Option-returning / agent-typed-payload ones don't, and the skill exists partly to make that fork explicit so partial shipping isn't accidental.

3. **The system prompt is the only way agents learn the new name.** A prelude entry that isn't documented in `evaluation/dynamic/prompts/strand-system.md` is invisible to fresh-context emissions.

## When to use this skill vs other approaches

- **Use this skill** when adding a new entry to `interpreter/Builtins.kt` — any `strand-builtin:` target, pure or effectful, monomorphic or polymorphic. Includes `Math.*`, `Hash.*`, `String.*`, `Bytes.*`, `Fs.*`, `Net.*`, `Process.*`, `Random.*`, `Json.*`, `Markdown.*`, and any future namespaces.
- **Don't use this skill** for:
  - Adding a new node category (a new sealed-class variant under `Node`) — use `strand-add-node` instead, since that involves spec docs, identifier registry, canonical encoder, verifier, all six modules.
  - Pure verifier-only checks with no new builtin (e.g., a new exhaustiveness rule) — fold into the relevant module directly.
  - Bug fixes in existing builtin behavior — direct edits without ceremony.
- **For research that hasn't been decided yet** (e.g., a regex engine choice, a Map<K,V> data structure design), use `strand-research-proposal` first, then come back and use this skill once the design is settled.

## The procedure (high level)

1. Orient: read the existing builtin registry and confirm the shape of what's being added
2. Decide: monomorphic or polymorphic/Option-returning/agent-typed?
3. Add the builtin in `interpreter/Builtins.kt` (and unit tests)
4. Add prelude entries in `authoring/LayerAGrammar.kt` (if monomorphic — otherwise document the exception)
5. Document the new name in `evaluation/dynamic/prompts/strand-system.md`
6. Run the full gradle test suite
7. Commit

## Step 1: Orient

Read these to confirm conventions and current state:

- [`impl-kotlin/CLAUDE.md`](../../../impl-kotlin/CLAUDE.md) § Code conventions § "When adding a new builtin" — the canonical three-step checklist this skill operationalizes.
- [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt`](../../../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) — scroll the existing entries to find the shape of pure / effectful / resource-handle-returning builtins. The recent stdlib expansion round 2 entries (Math.*, Hash.*, Random.*) are good monomorphic templates.
- [`impl-kotlin/authoring/src/main/kotlin/org/strand/authoring/LayerAGrammar.kt`](../../../impl-kotlin/authoring/src/main/kotlin/org/strand/authoring/LayerAGrammar.kt) § `reservedNodes` — find the `Stdlib expansion round 2` block to see the `FunctionType` + `ForeignNode` (+ effect category) shape the prelude expects.
- [`evaluation/dynamic/prompts/strand-system.md`](../../../evaluation/dynamic/prompts/strand-system.md) § "Implicit prelude" — see where the new entry will be documented for agents.

## Step 2: Decide — monomorphic, or one of the documented exceptions

The prelude can express **monomorphic** FunctionTypes only. Pure and effectful builtins both work, as long as the type is concrete (`(Int, Int) -> Int`, `(Bytes) -> Bytes`, etc.). For these, **all three steps** of the checklist apply.

The documented exceptions where step 4 (prelude entry) is **deliberately skipped**, with a comment in the commit explaining the reason:

- **Polymorphic builtins** — anything with a type parameter (`List.Map<A, B>`, `List.Length<T>`, `String.Split` returning `List<String>` where the list type depends on its element). The prelude has no polymorphic-FNT facility today.
- **Option-returning builtins** (`String.ParseInt → Option<Int>`, `Bytes.ParseHex → Option<Bytes>`, `Process.EnvVar → Option<String>`) — would need a blessed `Option<T>` in the prelude, which doesn't exist yet.
- **Agent-chosen payload types** (`Fs.Read → Bytes` *as written* fits, but `Http.Request → {status, body}` returns a product the agent might want to expand; `Json.Parse → Option<JsonValue>` is typed against a specific JsonValue schema). Use judgment — if the natural prelude FNT would constrain a real use case, skip with a note.

For exceptions, the commit message should say *why* the prelude entry is omitted, and the system prompt's "Round-2 builtins NOT in the prelude" section (or its successor) should list the new name.

## Step 3: Add the builtin in `interpreter/Builtins.kt`

Add the entry in the appropriate section of `Builtins.registry` (or `higherOrderRegistry` for builtins that take a Strand callable as one of their args — `List.Map` etc.).

**Q-065 determinism position (mandatory).** Every registration wraps its lambda in one of the determinism helpers: `det { }` for an effect-free, replay-deterministic builtin; `fx { }` for an effect-declaring (stateful) builtin; `nondet { }` for nondeterministic ones (the `Random.*` family); `detH { }` / `fxH { }` for higher-order. An effect-free entry registered without `det` fails registry construction at class-init, the chosen helper must agree with the prelude/table effect declarations (`DeterminismRegistryConsistencyTest` in `:corpus`), and a `det` entry whose inputs the audit's signature generators cannot derive needs a fixture in `BuiltinDeterminismAuditTest` (`:corpus`) or the totality check fails.

**Standard pattern (first-order, effect-free):**

```kotlin
"strand-builtin:Math.Sqrt" to det { args ->
    require(args.size == 1) { "Math.Sqrt expects 1 arg (f: Float), got ${args.size}" }
    Value.FloatV(kotlin.math.sqrt((args[0] as Value.FloatV).v))
},
```

**Higher-order pattern (callback into Strand):**

```kotlin
"strand-builtin:List.Map" to detH { args, apply ->
    require(args.size == 2) { "List.Map expects 2 args (list, fn), got ${args.size}" }
    val fn = args[1]
    // ... walk the Cons/Nil chain, calling apply.apply(fn, listOf(element)) per item ...
},
```

**Effectful builtin (Random.* / Time.* / Fs.* etc.):**

The effect category is declared at the `ForeignNode` *use site*, not in `Builtins.kt`. The builtin's body just executes — capability checking happens in `Interpreter.applyForeign` before the `Fn.invoke` is called. For builtins with replay-determinism concerns (Time, Random), inject the source via a `@Volatile var` on `Builtins` (`clock`, `random`) so tests can install a fixed/seeded variant in `@BeforeAll`.

**Failure handling:** runtime exceptions (`java.io.IOException`, `IllegalArgumentException` etc.) should be caught and rethrown as `IoFailure(kind, detail)`. The interpreter's `applyForeign` translates `IoFailure` to `InterpretError.IoFailure(at, kind, detail)` so agents see a structured error with the call-site `NodeId`. Pure builtins that can fail (divide-by-zero, sqrt of negative, etc.) follow per-builtin convention — `Math.Sqrt` returns NaN matching IEEE 754; `Int.Div` throws on zero.

**Unit tests** go in a focused `Builtins{Topic}Test.kt` under `interpreter/src/test/kotlin/org/strand/interpreter/`. The recent `BuiltinsMathTest`, `BuiltinsHashTest`, `BuiltinsRandomTest`, `BuiltinsListHoTest` are good templates. Cover:
- Happy path with representative inputs.
- Edge cases (empty input, boundary values).
- Error path if the builtin can fail.
- Determinism if the builtin uses injectable state (test with `@BeforeAll` installing a fixed source, restoring in `@AfterAll`).

Compile early: `./gradlew :interpreter:compileKotlin` after the entry is added, then run the new tests to confirm semantics.

## Step 4: Add prelude entries in `authoring/LayerAGrammar.kt` (monomorphic only)

If step 2 said "monomorphic", add two reserved-name entries (three if the builtin declares a new effect category that isn't already in the prelude):

```kotlin
// FunctionType signature — convention: <name>T
"sqrtT" to ReservedNodeSpec(
    jsonType = "FunctionType",
    refListFields = mapOf("parameters" to listOf("floatT")),
    refFields = mapOf("result" to "floatT"),
),

// ForeignNode callable — short reserved name
"sqrt" to ReservedNodeSpec(
    jsonType = "ForeignNode",
    stringFields = mapOf("target" to "strand-builtin:Math.Sqrt"),
    refFields = mapOf("foreignType" to "sqrtT"),
),
```

For an effectful builtin, the `ForeignNode` entry adds `refListFields = mapOf("effects" to listOf("<fxName>"))` referencing an `EffectCategory` reserved entry. If that effect category isn't already in the prelude (existing: `nowFx`, `writeFx`, `connectFx`, `cryptoFx`, plus the four StateMachine.* effects), add it too:

```kotlin
"logFx" to ReservedNodeSpec(
    jsonType = "EffectCategory",
    stringFields = mapOf("categoryName" to "Log.Write"),
),
```

**Name conventions:**

- Reserved short name is lowercase camelCase, matching the existing pattern (`add`, `now`, `sqrt`, `randInt`, `hexOf`). Choose the shortest unambiguous form — these are global names competing with agent-chosen ids.
- If the natural short name collides with an existing reserved name but the new target has different semantics (e.g., `Math.Mod` vs the existing `mod = Int.Mod`), pick a distinct name (`mmod` in that case) and document the distinction in a comment.
- The `FunctionType`'s name is `<callableName>T` by convention. Even when two builtins share an identical signature shape (`absT` and `signT` are both `(Int) -> Int`), each gets its own FNT entry for readability — mirrors the existing `addT`/`subT`/`mulT`/`divT`/`modT` pattern.

**Verification test:** add a `Round2PreludeTest`-style assertion under `authoring/src/test/kotlin/org/strand/authoring/` that compiles a tiny Layer A program using the new reserved name and inspects the synthesized JSON to confirm the target, foreignType, and effects fields are correct. Three lines per entry; the existing `Round2PreludeTest.kt` is a complete template.

## Step 5: Document in `evaluation/dynamic/prompts/strand-system.md`

Under the "Implicit prelude" section, add the new entries to the existing tables:

- **FunctionType signatures** table: append the new `<name>T` and its shape (e.g., `sqrtT — (Float) -> Float`).
- **Foreign-node builtins** table: append the new short name with its target / effect notes (e.g., `sqrt — Math.Sqrt (Float -> Float)`).
- **Effect categories** table (only if a new one was added): append the new `<name>Fx` and its category name.

If the builtin is one of the documented exceptions (polymorphic, Option-returning, agent-typed), instead add a line to the "Round-2 builtins NOT in the prelude" subsection (or rename if a new exception family emerges) explaining what the agent should declare at the use site.

Update the count summary at the top of each table ("FunctionType signatures (N)", "Foreign-node builtins (N)", "Effect categories (N)") so the numbers stay accurate.

## Step 6: Run the full gradle test suite

```
./gradlew test
```

Expected delta:
- N new tests in `Builtins{Topic}Test.kt` (one per case from step 3).
- 1-2 new tests in `Round2PreludeTest.kt` or equivalent (per step 4, if monomorphic).
- All existing tests still green.

If the test count is lower than expected, find the missing registration — usually a forgotten `@Test` annotation or a new test class not picked up by the test discovery.

## Step 7: Commit

One commit, scope `builtins:`. Subject line names the namespace and short summary (`builtins: add Math.Sqrt and friends`). Body lists:

- Which entries were added (registry + prelude + effect categories, with explicit counts).
- Why any documented exception was taken (polymorphic, Option-returning, agent-typed).
- Test coverage summary (what's new, what's preserved).

If the slice spans multiple related builtins (e.g., a whole `Math.*` family), one commit per logical group is fine — keep them tight, scope-prefixed, and named.

## Final summary to the user

When done, give a concise summary:

- New builtin targets (full `strand-builtin:Namespace.Name` list).
- Prelude additions (reserved names).
- Any documented exceptions and the reason.
- Test counts (new in each file, total now passing).
- Whether the system prompt was updated.

## Convention checks

- **Prelude entry required for monomorphic builtins.** This is the rule the skill enforces; only the three documented exceptions justify skipping.
- **Structured runtime errors via `IoFailure(kind, detail)`** — never string-thrown exceptions out of a builtin body, since the interpreter translates `IoFailure` to a typed `InterpretError.IoFailure` carrying the call-site NodeId.
- **Injectable state for nondeterminism** — Time, Random, anything else that needs replay determinism gets a `@Volatile var` on `Builtins` (default to a production-real source) so tests can install a fixed/seeded variant.
- **Reserved names compete with agent-chosen ids** — when in doubt, pick the shortest name that's still descriptive; resist crowding the prelude with rarely-used builtins.
- **System prompt counts stay accurate** — the "FunctionType signatures (N)" / "Foreign-node builtins (N)" / "Effect categories (N)" counts in the prompt are read by agents; bump them when adding entries.

## Things not to do

- Don't ship a monomorphic builtin without a prelude entry. That's the gap this skill exists to close.
- Don't add prelude entries for polymorphic builtins assuming a single monomorphic instantiation — the wrong shape locks in the wrong type at every use site.
- Don't put effectful logic in a `Fn`'s body without declaring the effect category at every `ForeignNode` use site — the verifier won't catch this for you, but agents will see capability violations at runtime.
- Don't add a non-`strand-builtin:` target without checking the trust model — `wasm:`, `process:`, and other namespaces are reserved for future dispatchers per the Layer 4 step 2 design.
- Don't skip the test suite. A builtin without a unit test is a runtime bug waiting to happen.
