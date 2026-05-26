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

        // Q-031 reference targets: Filesystem.Write and Network.Connect.
        // These nominally exercise refinement-bearing effects
        // (Filesystem.Write{path: String}, Network.Connect{host: String,
        // port: Int}). In this reference implementation they do not
        // perform any real I/O — they return a stable Int so call sites
        // can be exercised end-to-end under various CapabilitySet
        // shapes. Production runtimes would replace these with real
        // implementations under the same target identifiers.
        "strand-builtin:Filesystem.Write" to Fn { args ->
            require(args.size == 1) { "Filesystem.Write expects 1 arg (path: String), got ${args.size}" }
            require(args[0] is Value.StringV) {
                "Filesystem.Write expects a StringV path argument, got ${args[0]::class.simpleName}"
            }
            Value.IntV(0)  // bytes written; the reference impl is a no-op
        },
        "strand-builtin:Network.Connect" to Fn { args ->
            require(args.size == 2) {
                "Network.Connect expects 2 args (host: String, port: Int), got ${args.size}"
            }
            require(args[0] is Value.StringV && args[1] is Value.IntV) {
                "Network.Connect expects StringV host + IntV port, got " +
                    "(${args[0]::class.simpleName}, ${args[1]::class.simpleName})"
            }
            Value.IntV(1)  // connection id; reference impl returns a stable sentinel
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
