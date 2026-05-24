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

        // Effectful: nominally exercises Time.Now. Returns a fixed timestamp
        // so replay determinism holds during testing. A production runtime
        // would substitute a real clock here.
        "strand-builtin:Time.Now" to Fn { args ->
            require(args.isEmpty()) { "Time.Now expects 0 args, got ${args.size}" }
            Value.IntV(FIXED_REPLAY_TIMESTAMP)
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
