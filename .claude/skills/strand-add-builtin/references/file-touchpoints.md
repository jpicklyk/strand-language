# File-by-file change templates for adding a builtin

Use these as starting points for each file touched in the three coordinated steps. Patterns are taken from how the round-2 stdlib (Math.*, Hash.*, Random.*, Bytes.FormatHex) and the round-1 IO surface (Fs.*, Net.*, Process.*, Http.Request) were added.

## Step 3: `impl/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt`

### Pure monomorphic (most common)

Place near related entries (Math near Math, Hash near Hash, etc.). The body should be a pure expression of `kotlin.math.*` / `java.security.*` / plain Kotlin.

```kotlin
"strand-builtin:Math.Sqrt" to Fn { args ->
    require(args.size == 1) { "Math.Sqrt expects 1 arg (f: Float), got ${args.size}" }
    Value.FloatV(kotlin.math.sqrt((args[0] as Value.FloatV).v))
},
```

### Effectful with replay-determinism (Time, Random, Clock-injected things)

Use an injectable `@Volatile var` on the `Builtins` object so tests can install a deterministic source.

```kotlin
@Volatile
var random: java.util.Random = java.security.SecureRandom()

// later in registry:
"strand-builtin:Random.Int" to Fn { args ->
    require(args.size == 2) { "Random.Int expects 2 args (min, max: Int), got ${args.size}" }
    val min = (args[0] as Value.IntV).v
    val max = (args[1] as Value.IntV).v
    require(max > min) { "Random.Int requires max > min" }
    Value.IntV(min + random.nextInt((max - min).toInt()).toLong())
},
```

Test setup pattern (mirrors `BuiltinsRandomTest`):

```kotlin
@BeforeAll fun installSeed() { Builtins.random = java.util.Random(SEED) }
@AfterAll fun restore()     { Builtins.random = java.security.SecureRandom() }
```

### Effectful IO (may fail)

Catch host exceptions and rethrow as `IoFailure(kind, detail)`. The interpreter's `applyForeign` translates these to `InterpretError.IoFailure(at, kind, detail)` carrying the call-site NodeId.

```kotlin
"strand-builtin:Fs.Read" to Fn { args ->
    require(args.size == 1) { "Fs.Read expects 1 arg (path: String), got ${args.size}" }
    val path = (args[0] as Value.StringV).v
    try {
        Value.BytesV(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)))
    } catch (e: java.nio.file.NoSuchFileException) {
        throw IoFailure("filesystem-read", "$path: file does not exist")
    } catch (e: java.io.IOException) {
        throw IoFailure("filesystem-read", "$path: ${e.message}")
    }
},
```

### Higher-order (takes a Strand callable as one of its args)

Registered in the **separate** `higherOrderRegistry` (not `registry`). The `apply: ApplyFn` continuation lets the builtin invoke the user's callable.

```kotlin
"strand-builtin:List.Map" to FnH { args, apply ->
    require(args.size == 2) { "List.Map expects 2 args (list, fn), got ${args.size}" }
    val fn = args[1]
    val transformed = mutableListOf<Value>()
    var cur: Value = args[0]
    while (true) {
        val sumV = cur as? Value.SumV ?: break
        if (sumV.case != "Cons") break
        val payload = sumV.payload as Value.ProductV
        transformed += apply.apply(fn, listOf(payload.fields.getValue("head")))
        cur = payload.fields.getValue("tail")
    }
    var result: Value = Value.SumV("Nil", null)
    for (h in transformed.reversed()) {
        result = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to result)))
    }
    result
},
```

### Unit tests

Put in `impl/interpreter/src/test/kotlin/org/strand/interpreter/Builtins{Topic}Test.kt`. Use `Builtins.lookup(name)!!` (or `Builtins.lookupHigherOrder(name)!!`) to fetch the registered function.

```kotlin
class BuiltinsMathTest {
    private fun lookup(name: String) = Builtins.lookup(name)!!

    @Test
    fun `Math_Sqrt of 4 is 2`() {
        assertEquals(Value.FloatV(2.0), lookup("strand-builtin:Math.Sqrt").invoke(listOf(Value.FloatV(4.0))))
    }
}
```

For higher-order builtins, construct the `ApplyFn` directly in Kotlin to isolate the traversal logic from interpreter dispatch — see `BuiltinsListHoTest`.

## Step 4: `impl/authoring/src/main/kotlin/org/strand/authoring/LayerAGrammar.kt`

Add entries in `reservedNodes` (the `linkedMapOf` near the top of the file). Group with related entries.

### Two entries per pure monomorphic builtin (FNT + ForeignNode)

```kotlin
"sqrtT" to ReservedNodeSpec(
    jsonType = "FunctionType",
    refListFields = mapOf("parameters" to listOf("floatT")),
    refFields = mapOf("result" to "floatT"),
),
"sqrt" to ReservedNodeSpec(
    jsonType = "ForeignNode",
    stringFields = mapOf("target" to "strand-builtin:Math.Sqrt"),
    refFields = mapOf("foreignType" to "sqrtT"),
),
```

### Three entries for an effectful monomorphic builtin (FNT + ForeignNode + new effect category)

```kotlin
"logFx" to ReservedNodeSpec(
    jsonType = "EffectCategory",
    stringFields = mapOf("categoryName" to "Log.Write"),
),
"logT" to ReservedNodeSpec(
    jsonType = "FunctionType",
    refListFields = mapOf("parameters" to listOf("stringT")),
    refFields = mapOf("result" to "unitT"),
),
"log" to ReservedNodeSpec(
    jsonType = "ForeignNode",
    stringFields = mapOf("target" to "strand-builtin:Log.Info"),
    refFields = mapOf("foreignType" to "logT"),
    refListFields = mapOf("effects" to listOf("logFx")),
),
```

If the effect category is already in the prelude (e.g., `cryptoFx`, `nowFx`, `writeFx`, `connectFx`), skip the new EffectCategory entry — the existing one is reused via `effects = listOf("existingFx")`.

### Naming conventions

- Reserved short name: lowercase camelCase, shortest unambiguous form. `add`, `now`, `sqrt`, `randInt`, `hexOf`. Resist long names — these compete with agent-chosen ids in a global namespace.
- FunctionType name: `<callableName>T` even when two builtins share an identical signature shape. Mirrors `addT/subT/mulT/divT/modT`.
- If the natural short name collides with an existing entry but the semantics differ (e.g., `Math.Mod` true modulo vs `Int.Mod` JVM `%`), pick a distinct name and document the distinction in a comment. The existing `mod`/`mmod` pair is the precedent.

### Verification test

Add a test in `impl/authoring/src/test/kotlin/org/strand/authoring/` (extend `Round2PreludeTest` or create a new focused class). Verifies that a Layer A program using the reserved name compiles to a JSON tree whose synthesized nodes have the right `target`, `foreignType`, and `effects` fields.

```kotlin
@Test
fun `sqrt elaborates to Math_Sqrt ForeignNode + Float-Float FNT`() {
    val text = "@v=1 root=app\napp APP sqrt [4.0]"
    val sqrt = nodeOf(text, "sqrt")
    assertEquals("strand-builtin:Math.Sqrt", sqrt["target"]!!.jsonPrimitive.content)
    val fnt = nodeOf(text, "sqrtT")
    assertEquals(listOf("floatT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals("floatT", fnt["result"]!!.jsonPrimitive.content)
}
```

`nodeOf(text, id)` is a one-line helper that compiles the text and returns the named node's JSON object — see `Round2PreludeTest.nodeOf` for the existing pattern.

## Step 5: `evaluation/dynamic/prompts/strand-system.md`

The prompt has three tables under the "Implicit prelude" section. Append entries to all that apply:

### FunctionType signatures table

```
    sqrtT lnT expT sinT cosT tanT   — (Float) -> Float
```

(Append the new `<name>T` to an existing row if the shape matches, or add a new row if it's a new shape.)

### Foreign-node builtins table

```
    sqrt pow ln exp sin cos tan     — Math.* Float -> Float
```

(Or add a new family row with a short description.)

### Effect categories table (only if a new category)

```
    logFx         — Log.Write (declared by every Log.* call)
```

### NOT in the prelude subsection (for exceptions)

If step 4 was skipped (polymorphic / Option-returning / agent-typed-payload), add a line to the "Round-2 builtins NOT in the prelude" subsection describing what the agent should declare at the use site.

### Count summaries

Each table has a count at its header (e.g., "FunctionType signatures (N)"). Bump these to reflect the new entries. Agents may rely on these counts to anticipate how complete the prelude is.

## Step 6: Run gradle

```
./gradlew test
```

Expected delta:
- Pure monomorphic builtin: ~3-5 unit tests in `Builtins{Topic}Test.kt`, ~1-2 in `Round2PreludeTest.kt` (or equivalent). All preexisting tests still green.
- Effectful builtin: + 1-2 tests for capability-context integration if the test reaches the interpreter.
- Higher-order builtin: + 1 integration test in `InterpreterTest.kt` covering the full Application → applyForeign → ApplyFn → applyValueToArgs → Closure eval pipeline.

## Step 7: Commit

One commit, `builtins:` scope. Subject line ≤ 60 chars naming the namespace.

```
builtins: add Math.Log10 + ln→log10 prelude entry

Pure (Float)->Float, mirrors the existing ln entry (Math.Log). One
new registry entry plus the matching `log10T` FunctionType and `log10`
ForeignNode in the prelude. System-prompt table updated. Three new
test cases in BuiltinsMathTest plus one in Round2PreludeTest.
```

Body lists the entries added (registry / prelude / effect category counts), why any documented exception was taken, and the test coverage summary.
