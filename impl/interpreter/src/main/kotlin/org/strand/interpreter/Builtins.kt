package org.strand.interpreter

/**
 * Registry of trusted in-process builtins that [ForeignNode][org.strand.core.Node.ForeignNode]
 * declarations may target. Layer 4 step 1 ships a small fixed set; later
 * layers will add WebAssembly-sandboxed bindings (Milestone 2.4 per the
 * research plan).
 *
 * Each builtin is a host-language function from a list of argument [Value]s
 * to a result [Value]. The runtime invokes the builtin only after the
 * verifier has type-checked the ForeignNode and the interpreter has
 * confirmed the calling capability context covers the ForeignNode's
 * declared effects (the same check applied to ordinary Lambdas).
 *
 * Builtins are pure functions from the interpreter's perspective even when
 * the foreign effect declaration says otherwise: a builtin like
 * `strand-builtin:Time.Now` returns a fixed timestamp in this reference
 * implementation so that replay determinism is preserved during testing.
 * A production runtime would replace these with real implementations under
 * the same key.
 *
 * Targets are namespaced strings; the `strand-builtin:` prefix designates
 * the in-process registry. Other registries (e.g., `wasm:`, `process:`)
 * will be served by separate dispatchers.
 */
object Builtins {

    /** A single foreign callable: takes the evaluated argument list and returns a value. */
    fun interface Fn {
        fun invoke(args: List<Value>): Value
    }

    /**
     * Pluggable clock for the `Time.*` builtins. Default is [SystemClock]
     * (real wall-clock time and real sleep). Tests that need
     * deterministic timestamps install [FixedClock] in setup and reset
     * to [SystemClock] in teardown. Thread safety: this is a global
     * mutable field; tests that mutate it must not run in parallel
     * with each other or with code that reads it. Per-interpreter
     * clock injection is a future refactor if parallelism becomes a
     * concern.
     */
    interface Clock {
        fun nowMillis(): Long
        fun sleep(millis: Long)
    }

    object SystemClock : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()
        override fun sleep(millis: Long) {
            if (millis > 0) Thread.sleep(millis)
        }
    }

    class FixedClock(private val time: Long) : Clock {
        override fun nowMillis(): Long = time
        override fun sleep(millis: Long) = Unit  // no-op in replay mode
    }

    /** The active clock. Production default: real system time. */
    @Volatile
    var clock: Clock = SystemClock

    private val registry: Map<String, Fn> = mapOf(
        // Pure arithmetic (no declared effects expected).
        "strand-builtin:Int.Add" to Fn { args ->
            require(args.size == 2) { "Int.Add expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            Value.IntV(a + b)
        },
        "strand-builtin:Int.Sub" to Fn { args ->
            require(args.size == 2) { "Int.Sub expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            Value.IntV(a - b)
        },
        "strand-builtin:Int.Mul" to Fn { args ->
            require(args.size == 2) { "Int.Mul expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            Value.IntV(a * b)
        },
        "strand-builtin:Int.Div" to Fn { args ->
            require(args.size == 2) { "Int.Div expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            require(b != 0L) { "Int.Div division by zero" }
            Value.IntV(a / b)
        },
        "strand-builtin:Int.Mod" to Fn { args ->
            require(args.size == 2) { "Int.Mod expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            require(b != 0L) { "Int.Mod division by zero" }
            Value.IntV(a % b)
        },
        "strand-builtin:Int.Neg" to Fn { args ->
            require(args.size == 1) { "Int.Neg expects 1 arg, got ${args.size}" }
            Value.IntV(-(args[0] as Value.IntV).v)
        },
        "strand-builtin:Bool.Not" to Fn { args ->
            require(args.size == 1) { "Bool.Not expects 1 arg, got ${args.size}" }
            Value.BoolV(!(args[0] as Value.BoolV).v)
        },
        "strand-builtin:Bool.And" to Fn { args ->
            require(args.size == 2) { "Bool.And expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.BoolV).v && (args[1] as Value.BoolV).v)
        },
        "strand-builtin:Bool.Or" to Fn { args ->
            require(args.size == 2) { "Bool.Or expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.BoolV).v || (args[1] as Value.BoolV).v)
        },

        // Pure Int comparisons: pair with Match on a BoolLit pattern to give
        // conditional logic.
        "strand-builtin:Int.Eq" to Fn { args ->
            require(args.size == 2) { "Int.Eq expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v == (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Lt" to Fn { args ->
            require(args.size == 2) { "Int.Lt expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v < (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Le" to Fn { args ->
            require(args.size == 2) { "Int.Le expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v <= (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Gt" to Fn { args ->
            require(args.size == 2) { "Int.Gt expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v > (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Ge" to Fn { args ->
            require(args.size == 2) { "Int.Ge expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v >= (args[1] as Value.IntV).v)
        },

        // String operations.
        "strand-builtin:String.Concat" to Fn { args ->
            require(args.size == 2) { "String.Concat expects 2 args, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v + (args[1] as Value.StringV).v)
        },
        "strand-builtin:String.Eq" to Fn { args ->
            require(args.size == 2) { "String.Eq expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.StringV).v == (args[1] as Value.StringV).v)
        },

        // Effectful: Time.Now reads from the active [clock]. Default
        // is [SystemClock] (System.currentTimeMillis()). Tests install
        // [FixedClock] to get deterministic timestamps; the existing
        // FIXED_REPLAY_TIMESTAMP constant remains as the canonical
        // fixed value for tests that compare against a known Now.
        "strand-builtin:Time.Now" to Fn { args ->
            require(args.isEmpty()) { "Time.Now expects 0 args, got ${args.size}" }
            Value.IntV(clock.nowMillis())
        },

        // Effectful: Time.Sleep(millis) suspends the current thread for
        // the requested duration via [clock.sleep]. Default SystemClock
        // calls Thread.sleep; FixedClock is a no-op so tests don't
        // actually wait. Returns UnitV.
        "strand-builtin:Time.Sleep" to Fn { args ->
            require(args.size == 1) { "Time.Sleep expects 1 arg (millis: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            require(millis >= 0) { "Time.Sleep millis must be non-negative, got $millis" }
            clock.sleep(millis)
            Value.UnitV
        },

        // Legacy stub kept for backwards-compat with the seed corpus
        // (programs 35, 39 and the test fixtures in InterpreterTest /
        // ElaboratorTest). Returns IntV(0) for any single StringV arg.
        // Real filesystem writes use the `strand-builtin:Fs.Write`
        // target (Layer 4 step 2 — see below). A future cleanup pass
        // may unify the namespaces once the corpus is updated.
        "strand-builtin:Filesystem.Write" to Fn { args ->
            require(args.size == 1) { "Filesystem.Write expects 1 arg (path: String), got ${args.size}" }
            require(args[0] is Value.StringV) {
                "Filesystem.Write expects a StringV path argument, got ${args[0]::class.simpleName}"
            }
            Value.IntV(0)  // bytes written; legacy stub semantics
        },

        // Layer 4 step 2 — Filesystem builtins under the `Fs.*` target
        // namespace. Real OS calls via java.nio.file.Files. IO failures
        // throw IoFailure, which the interpreter translates to
        // InterpretError.IoFailure carrying the call-site NodeId.
        //
        // Effect category: all of these declare E-007 Filesystem.Write
        // (for Write/Append/Delete) or E-006 Filesystem.Read (for Read/
        // Exists/List). Programs that use them must declare the
        // appropriate EffectCategory and grant a CapabilitySet that
        // covers the call site.

        "strand-builtin:Fs.Write" to Fn { args ->
            require(args.size == 2) {
                "Fs.Write expects 2 args (path: String, bytes: Bytes), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-write", "expected StringV path, got ${args[0]::class.simpleName}")
            val bytes = (args[1] as? Value.BytesV)?.v
                ?: throw IoFailure("filesystem-write", "expected BytesV content, got ${args[1]::class.simpleName}")
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get(path), bytes)
                Value.IntV(bytes.size.toLong())
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-write", "$path: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("filesystem-write", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.Read" to Fn { args ->
            require(args.size == 1) {
                "Fs.Read expects 1 arg (path: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-read", "expected StringV path, got ${args[0]::class.simpleName}")
            try {
                Value.BytesV(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)))
            } catch (e: java.nio.file.NoSuchFileException) {
                throw IoFailure("filesystem-read", "$path: file does not exist")
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-read", "$path: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("filesystem-read", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.Append" to Fn { args ->
            require(args.size == 2) {
                "Fs.Append expects 2 args (path: String, bytes: Bytes), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-append", "expected StringV path, got ${args[0]::class.simpleName}")
            val bytes = (args[1] as? Value.BytesV)?.v
                ?: throw IoFailure("filesystem-append", "expected BytesV content, got ${args[1]::class.simpleName}")
            try {
                java.nio.file.Files.write(
                    java.nio.file.Paths.get(path),
                    bytes,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
                Value.IntV(bytes.size.toLong())
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-append", "$path: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("filesystem-append", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.Exists" to Fn { args ->
            require(args.size == 1) {
                "Fs.Exists expects 1 arg (path: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-exists", "expected StringV path, got ${args[0]::class.simpleName}")
            Value.BoolV(java.nio.file.Files.exists(java.nio.file.Paths.get(path)))
        },

        "strand-builtin:Fs.Delete" to Fn { args ->
            require(args.size == 1) {
                "Fs.Delete expects 1 arg (path: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-delete", "expected StringV path, got ${args[0]::class.simpleName}")
            try {
                Value.BoolV(java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path)))
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-delete", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.List" to Fn { args ->
            require(args.size == 1) {
                "Fs.List expects 1 arg (dir: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-list", "expected StringV dir path, got ${args[0]::class.simpleName}")
            try {
                val entries = java.nio.file.Files.list(java.nio.file.Paths.get(path)).use { stream ->
                    stream.map { it.fileName.toString() }.sorted().toList()
                }
                // Build a SumV-encoded list using the same Cons/Nil
                // convention as the corpus (μ. Cons({head, tail}) | Nil).
                // The Strand-side type is the standard recursive String
                // list; the runtime produces a chain of SumV values
                // terminated by Nil.
                var listValue: Value = Value.SumV(case = "Nil", payload = null)
                for (entry in entries.reversed()) {
                    val payload = Value.ProductV(
                        mapOf("head" to Value.StringV(entry), "tail" to listValue)
                    )
                    listValue = Value.SumV(case = "Cons", payload = payload)
                }
                listValue
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-list", "$path: ${e.message}")
            }
        },

        "strand-builtin:Network.Connect" to Fn { args ->
            require(args.size == 2) {
                "Network.Connect expects 2 args (host: String, port: Int), got ${args.size}"
            }
            require(args[0] is Value.StringV && args[1] is Value.IntV) {
                "Network.Connect expects StringV host + IntV port, got " +
                    "(${args[0]::class.simpleName}, ${args[1]::class.simpleName})"
            }
            Value.IntV(1)  // TODO: real socket — Phase 2 #5
        },

        // Test-only no-op effectful builtin. Returns IntV(0) for any
        // single StringV argument. Used by tests that want to exercise
        // the effect-handler / capability machinery without touching
        // real IO. Not for production use. Target name reserved under
        // `strand-builtin:Test.*`.
        "strand-builtin:Test.EffectfulNoOp" to Fn { args ->
            require(args.size == 1) { "Test.EffectfulNoOp expects 1 arg, got ${args.size}" }
            require(args[0] is Value.StringV) {
                "Test.EffectfulNoOp expects a StringV argument, got ${args[0]::class.simpleName}"
            }
            Value.IntV(0)
        },
    )

    /** Look up a builtin by its target identifier; null if unknown. */
    fun lookup(target: String): Fn? = registry[target]

    /** Snapshot of all registered target identifiers. */
    fun registeredTargets(): Set<String> = registry.keys

    /**
     * A fixed Unix-millis timestamp returned by `strand-builtin:Time.Now`.
     * The exact value is arbitrary — chosen as the project's notional
     * "today" — and is stable across runs to support replay determinism.
     */
    const val FIXED_REPLAY_TIMESTAMP: Long = 1_780_000_000_000L
}
