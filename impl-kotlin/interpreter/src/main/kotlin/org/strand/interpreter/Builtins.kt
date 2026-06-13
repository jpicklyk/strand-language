package org.strand.interpreter

import kotlin.math.pow

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

    /**
     * A single foreign callable: takes the active [HostContext] and the
     * evaluated argument list, returns a value. Q-054 follow-up: the context
     * is the per-invocation projection of the host policy — effectful builtins
     * read their clock / random / sandbox / credentials / etc. from it rather
     * than from the [Builtins] process-global singletons, which is what makes
     * two runtimes concurrently isolated in one JVM. Pure builtins ignore it
     * (their registration helper [det] hides the parameter entirely).
     */
    fun interface Fn {
        fun invoke(ctx: HostContext, args: List<Value>): Value
    }

    /**
     * Continuation passed to higher-order builtins (Slice 2 of stdlib
     * expansion round 2). Lets a builtin invoke a user-supplied
     * callable (a Strand [Value.Closure], [Value.FixpointFn], or
     * [Value.ForeignFn]) with a list of pre-evaluated arguments,
     * returning the result Value.
     *
     * The interpreter constructs an [ApplyFn] that closes over the
     * current capability context and handler stack at the
     * higher-order builtin's call site, so callback evaluations see
     * the same environment the surrounding code sees.
     *
     * Closures must have exactly `args.size` parameters; fixpoints
     * have `args.size + 1` (the first is the recursive self-slot).
     */
    fun interface ApplyFn {
        fun apply(fn: Value, args: List<Value>): Value
    }

    /**
     * Higher-order foreign callable: receives an [ApplyFn] alongside
     * the standard argument list so it can call user-supplied
     * callables. Distinct from [Fn] so the dispatcher knows to thread
     * the callback through.
     */
    fun interface FnH {
        fun invoke(ctx: HostContext, args: List<Value>, apply: ApplyFn): Value
    }

    /**
     * Q-065: the behavioral determinism contract of a registry entry.
     * Purity is structural (the absence of effect edges on the graph-side
     * ForeignNode); determinism is behavioral (same arguments, same
     * result, always). Replay, snapshot recovery, and invariant
     * re-evaluation all assume the two coincide for effect-free builtins;
     * this field records the position explicitly so the assumption is a
     * checked contract rather than an inspection-time observation.
     *
     * The field is registry metadata only — it appears in no node, no
     * canonical encoding, and no hash.
     */
    enum class Determinism {
        /** Same arguments, same result, always (same-process contract). */
        Deterministic,

        /**
         * Result depends on host state or the world. Every builtin that
         * declares an effect category lands here mechanically unless it
         * declares [Nondeterministic].
         */
        Stateful,

        /**
         * Random or scheduling-dependent. Today only the `Random.*`
         * family (which also declares E-024 Crypto.RandomBytes).
         */
        Nondeterministic,
    }

    /**
     * A resolved registry entry: the callable plus its Q-065 metadata.
     * Constructed only through [resolveRegistration] (shipping
     * registries) or [installTestBuiltin] (test overlay), so every entry
     * has taken an explicit determinism position.
     */
    class Entry<F : Any> internal constructor(
        val fn: F,
        /** Whether the builtin's graph-side ForeignNode declares effect categories. */
        val effectful: Boolean,
        val determinism: Determinism,
    )

    /**
     * A pre-resolution registration. [declared] may be null; resolution
     * via [resolveRegistration] enforces the Q-065 audit shape:
     *
     *  - an effect-free registration MUST declare
     *    [Determinism.Deterministic] explicitly — there is no default, so
     *    adding a structurally pure builtin forces its author to take a
     *    position (omission is a construction-time failure);
     *  - an effect-declaring registration defaults to
     *    [Determinism.Stateful].
     *
     * The shipping registries are built through the [det] / [fx] /
     * [nondet] (and [detH] / [fxH]) helpers, which fix the three legal
     * combinations at the registration site.
     */
    class Registration<F : Any>(
        val fn: F,
        val effectful: Boolean = false,
        val declared: Determinism? = null,
    )

    /** Resolve one [Registration], enforcing the Q-065 registration constraint. */
    internal fun <F : Any> resolveRegistration(target: String, reg: Registration<F>): Entry<F> {
        val determinism = reg.declared
            ?: if (reg.effectful) Determinism.Stateful
            else throw IllegalStateException(
                "$target declares no effect category and must explicitly declare " +
                    "Determinism.Deterministic (Q-065: effect-free builtins have no " +
                    "determinism default — take a position or declare the effect)"
            )
        return Entry(reg.fn, reg.effectful, determinism)
    }

    /** Build a resolved registry from registrations, failing construction on any omission. */
    internal fun <F : Any> buildRegistry(
        registrations: Map<String, Registration<F>>,
    ): Map<String, Entry<F>> =
        registrations.mapValues { (target, reg) -> resolveRegistration(target, reg) }

    /**
     * Registration helper: effect-free, explicitly Deterministic. Pure
     * builtins read no host state, so the lambda keeps the legacy
     * `(List<Value>) -> Value` shape and the [HostContext] is discarded by the
     * adapter — no edit to the ~153 `det { args -> }` sites.
     */
    private fun det(fn: (List<Value>) -> Value): Registration<Fn> =
        Registration(Fn { _, args -> fn(args) }, effectful = false, declared = Determinism.Deterministic)

    /**
     * Registration helper: effect-declaring, defaults Stateful. The lambda is
     * a [HostContext] receiver, so a bare read of `clock` / `sandboxPolicy` /
     * `random` / `credentialProvider` / … inside the body resolves to the
     * active context's field rather than the [Builtins] singleton (Q-054
     * concurrent isolation). The `{ args -> }` lambda shape is unchanged at
     * the ~44 `fx { args -> }` sites.
     */
    private fun fx(fn: HostContext.(List<Value>) -> Value): Registration<Fn> =
        Registration(Fn { ctx, args -> ctx.fn(args) }, effectful = true)

    /** Registration helper: effect-declaring and Nondeterministic (the Random.* family); [HostContext] receiver. */
    private fun nondet(fn: HostContext.(List<Value>) -> Value): Registration<Fn> =
        Registration(Fn { ctx, args -> ctx.fn(args) }, effectful = true, declared = Determinism.Nondeterministic)

    /**
     * Registration helper: effect-free higher-order, explicitly Deterministic.
     * The List.* combinators read no host state; the lambda keeps the legacy
     * `(List<Value>, ApplyFn) -> Value` shape with the context discarded.
     */
    private fun detH(fn: (List<Value>, ApplyFn) -> Value): Registration<FnH> =
        Registration(FnH { _, args, apply -> fn(args, apply) }, effectful = false, declared = Determinism.Deterministic)

    /** Registration helper: effect-declaring higher-order, defaults Stateful; [HostContext] receiver. */
    private fun fxH(fn: HostContext.(List<Value>, ApplyFn) -> Value): Registration<FnH> =
        Registration(FnH { ctx, args, apply -> ctx.fn(args, apply) }, effectful = true)

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

    /**
     * Pluggable randomness source for the `Random.*` builtins. Default
     * is a [java.security.SecureRandom] (cryptographically secure
     * non-deterministic). Tests that need reproducible random sequences
     * install a seeded [java.util.Random] in setup and reset in
     * teardown. Thread safety mirrors [clock]: tests that mutate this
     * must not run in parallel.
     *
     * Both `Random.Int` / `Random.Float` / `Random.Bytes` declare
     * effect category `E-024 Crypto.RandomBytes` (the existing
     * registry entry covering cryptographically-secure entropy).
     */
    @Volatile
    var random: java.util.Random = java.security.SecureRandom()

    /**
     * Pluggable log sink for the `Log.*` builtins (round 3). Default
     * routes log lines to `System.err` so they don't tangle with
     * stdout-bound program output. Tests install a captured sink to
     * assert on the emitted lines. Effect category: E-032 Log.Write.
     */
    interface LogSink {
        fun println(line: String)
    }
    object StderrLogSink : LogSink {
        override fun println(line: String) = System.err.println(line)
    }
    @Volatile
    var logSink: LogSink = StderrLogSink

    /**
     * Pluggable host-environment source for the `OS.*` builtins
     * (round 3). Default reads from the live JVM / OS state. Tests
     * install a fixed-value source for replay determinism. Effect
     * category: E-033 OS.Read.
     */
    interface OsEnv {
        fun hostname(): String
        fun platform(): String
        fun cwd(): String
    }
    object SystemOsEnv : OsEnv {
        override fun hostname(): String =
            try { java.net.InetAddress.getLocalHost().hostName }
            catch (_: java.net.UnknownHostException) { "unknown" }
        override fun platform(): String =
            System.getProperty("os.name", "unknown").lowercase().let { name ->
                when {
                    "windows" in name -> "windows"
                    "linux" in name -> "linux"
                    "mac" in name || "darwin" in name -> "macos"
                    "bsd" in name -> "bsd"
                    else -> name
                }
            }
        override fun cwd(): String = System.getProperty("user.dir", ".")
    }
    @Volatile
    var osEnv: OsEnv = SystemOsEnv

    /**
     * Pluggable exit handler for `System.Exit` (round 3, E-034).
     * Default calls `kotlin.system.exitProcess(code)` which
     * terminates the JVM. Tests install [TestExitHandler] which
     * captures the code and throws [SystemExitInvoked] so the test
     * framework observes the call without the JVM actually
     * terminating.
     */
    interface ExitHandler {
        fun exit(code: Int)
    }
    object RealExitHandler : ExitHandler {
        override fun exit(code: Int): Unit = kotlin.system.exitProcess(code)
    }
    class SystemExitInvoked(val code: Int) : RuntimeException("System.Exit($code)")
    class TestExitHandler : ExitHandler {
        var lastCode: Int? = null
        override fun exit(code: Int) {
            lastCode = code
            throw SystemExitInvoked(code)
        }
    }
    @Volatile
    var exitHandler: ExitHandler = RealExitHandler

    /**
     * Pluggable credential provider for the agent-native LLM and
     * vector-store builtins (Q-037 / Q-038). Default reads from
     * environment variables (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`,
     * `GEMINI_API_KEY` / `GOOGLE_API_KEY`). Tests install a
     * [StaticCredentialProvider] to avoid the host's env-var state.
     *
     * Credentials are NOT capabilities — the capability
     * `LLM.Generate{provider: "anthropic", model: *}` authorizes calling
     * Anthropic models but does not carry the API key. See the
     * agent-native-capabilities proposal § 4.4 for the rationale.
     */
    @Volatile
    var credentialProvider: CredentialProvider = EnvCredentialProvider

    /**
     * Pluggable HTTP transport for the LLM provider bindings. Default
     * is [DefaultLlmHttpClient] which uses [java.net.HttpURLConnection]
     * (same surface as `Http.Request`). Tests install a mock client
     * that records the outgoing request and returns a canned response;
     * the per-provider tests cover the wire-format translation without
     * any real network call.
     */
    @Volatile
    var llmHttpClient: LlmHttpClient = DefaultLlmHttpClient

    /**
     * Maximum number of tool-use iterations the per-provider Generate
     * builtin will run before halting and returning [StopReason.ToolUseLimit].
     * Bounded at 10 by default (proposal § 6 — keeps the per-call budget
     * in the builtin rather than relying on agent-side bookkeeping).
     */
    @Volatile
    var toolLoopLimit: Int = 10

    /**
     * Q-045: per-read timeout (milliseconds) installed as the underlying
     * socket's `SO_TIMEOUT` at `Net.Connect` open and as the HTTP read
     * timeout at `*.CreateStream` open. Bounds a single blocking
     * streaming receive that the [org.strand.core.EvaluationLimits]
     * wall-clock sampler cannot see (a native `read` advances no
     * interpreter step). Host policy, never a builtin argument. The CLI
     * installs `EvaluationLimits.streamReceiveTimeoutMillis` here before
     * evaluation and restores the prior value afterward, mirroring the
     * [sandboxPolicy] save/restore pattern; the library default matches
     * `EvaluationLimits.DEFAULTS`. A value outside the positive `Int`
     * range maps to the JVM's "no timeout" (`0`) at the socket layer.
     */
    @Volatile
    var streamReceiveTimeoutMillis: Long = org.strand.core.EvaluationLimits.DEFAULTS.streamReceiveTimeoutMillis

    /**
     * The verifier's `nodeTypes` map for the currently-executing
     * program. Set by the host — the CLI's `run` / `machine` / `group`
     * paths and test harnesses — from a successful
     * `VerifyResult.Ok.nodeTypes` before evaluation begins, and restored
     * to the prior value in a `finally` afterward (the same save/restore
     * pattern as [sandboxPolicy] / [streamReceiveTimeoutMillis]). The
     * tool-dispatch path reads it to resolve a
     * [Value.ToolDefV.parameterSchemaId] to its
     * [org.strand.verifier.TypeExpr.SchemaType] (N-044); the N-045
     * ResponseSchemaSpec projection reads it the same way. When left
     * null, both paths degrade to empty `{}` JSON schemas.
     *
     * This is a single-program global rather than a per-call argument
     * because the LLM.Generate builtin is dispatched via the
     * higher-order builtin registry whose signature is fixed
     * `(args, applyFn) -> Value` — there is no slot for a typing
     * context. The pattern mirrors [llmHttpClient] / [credentialProvider]
     * / [clock] / [random]: a singleton @Volatile that hosts and tests
     * install around their setup/teardown.
     */
    @Volatile
    var verifierNodeTypes: Map<org.strand.core.NodeId, org.strand.verifier.TypeExpr>? = null

    /**
     * Per-server state for the `Http.Listen` / `Http.Accept` builtins.
     * The handler thread enqueues a [HttpPending] for each request and
     * blocks on its latch until Strand calls Http.Respond. The queue
     * is unbounded — backpressure is the agent's responsibility.
     */
    internal data class HttpServerHolder(
        val server: com.sun.net.httpserver.HttpServer,
        val queue: java.util.concurrent.BlockingQueue<HttpPending>,
    )

    /**
     * One pending HTTP request waiting for the Strand program to
     * call Http.Respond. The [latch] releases the handler thread
     * after the response is written; the [exchange] is the underlying
     * JDK HttpExchange the Strand-side responder writes through.
     */
    internal data class HttpPending(
        val exchange: com.sun.net.httpserver.HttpExchange,
        val latch: java.util.concurrent.CountDownLatch,
    )

    /**
     * Pluggable HTTP transport for vector-store providers
     * (Q-038). Default is [JdkHttpTransport]. Tests install
     * [InMemoryHttpTransport] with canned matchers so the
     * provider code never opens a real socket.
     */
    @Volatile
    var vectorHttpTransport: VectorHttpTransport = JdkHttpTransport

    /**
     * Q-041: active sandbox policy mediating every `Fs.*` /
     * `Net.Connect` / `Http.Request` foreign call. The singleton
     * default is [SandboxPolicy.OPEN_DEFAULT] — no workspace
     * constraint, no network blocklist — so pre-Q-041 tests and
     * library callers see unchanged behaviour. The CLI installs
     * [SandboxPolicy.SECURE_DEFAULT] (or a custom flag-driven
     * policy) at startup so agent-facing invocations get the
     * default-deny surface.
     *
     * The volatile-singleton pattern matches [clock] /
     * [credentialProvider] / [random] / [llmHttpClient]: tests
     * that install a custom policy must not run in parallel with
     * other tests that touch this field, and must restore the
     * pre-test value in `@AfterEach`. Per-interpreter policy
     * injection is a future refactor flagged in the proposal §
     * 4.4 as non-blocking cleanup.
     */
    @Volatile
    var sandboxPolicy: SandboxPolicy = SandboxPolicy.OPEN_DEFAULT

    /**
     * Q-041: pluggable DNS resolver used by [NetSandbox]. Defaults
     * to [SystemNameResolver] which delegates to the JVM's own
     * `InetAddress.getAllByName`. Sandbox tests inject a
     * deterministic resolver to exercise multi-A-record SSRF
     * scenarios without depending on real DNS.
     *
     * Mirrors the [clock] / [random] test-injection pattern; tests
     * that mutate this must restore [SystemNameResolver] in
     * `@AfterEach` and not run in parallel.
     */
    @Volatile
    var nameResolver: NameResolver = SystemNameResolver

    private val registry: Map<String, Entry<Fn>> = buildRegistry(mapOf(
        // Pure arithmetic (no declared effects expected).
        "strand-builtin:Int.Add" to det { args ->
            require(args.size == 2) { "Int.Add expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            Value.IntV(a + b)
        },
        "strand-builtin:Int.Sub" to det { args ->
            require(args.size == 2) { "Int.Sub expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            Value.IntV(a - b)
        },
        "strand-builtin:Int.Mul" to det { args ->
            require(args.size == 2) { "Int.Mul expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            Value.IntV(a * b)
        },
        "strand-builtin:Int.Div" to det { args ->
            require(args.size == 2) { "Int.Div expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            require(b != 0L) { "Int.Div division by zero" }
            Value.IntV(a / b)
        },
        "strand-builtin:Int.Mod" to det { args ->
            require(args.size == 2) { "Int.Mod expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            require(b != 0L) { "Int.Mod division by zero" }
            Value.IntV(a % b)
        },
        "strand-builtin:Int.Neg" to det { args ->
            require(args.size == 1) { "Int.Neg expects 1 arg, got ${args.size}" }
            Value.IntV(-(args[0] as Value.IntV).v)
        },
        "strand-builtin:Bool.Not" to det { args ->
            require(args.size == 1) { "Bool.Not expects 1 arg, got ${args.size}" }
            Value.BoolV(!(args[0] as Value.BoolV).v)
        },
        "strand-builtin:Bool.And" to det { args ->
            require(args.size == 2) { "Bool.And expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.BoolV).v && (args[1] as Value.BoolV).v)
        },
        "strand-builtin:Bool.Or" to det { args ->
            require(args.size == 2) { "Bool.Or expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.BoolV).v || (args[1] as Value.BoolV).v)
        },
        "strand-builtin:Bool.Eq" to det { args ->
            require(args.size == 2) { "Bool.Eq expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.BoolV).v == (args[1] as Value.BoolV).v)
        },

        // Pure Int comparisons: pair with Match on a BoolLit pattern to give
        // conditional logic.
        "strand-builtin:Int.Eq" to det { args ->
            require(args.size == 2) { "Int.Eq expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v == (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Lt" to det { args ->
            require(args.size == 2) { "Int.Lt expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v < (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Le" to det { args ->
            require(args.size == 2) { "Int.Le expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v <= (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Gt" to det { args ->
            require(args.size == 2) { "Int.Gt expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v > (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Ge" to det { args ->
            require(args.size == 2) { "Int.Ge expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v >= (args[1] as Value.IntV).v)
        },

        // String operations.
        "strand-builtin:String.Concat" to det { args ->
            require(args.size == 2) { "String.Concat expects 2 args, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v + (args[1] as Value.StringV).v)
        },
        "strand-builtin:String.Eq" to det { args ->
            require(args.size == 2) { "String.Eq expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.StringV).v == (args[1] as Value.StringV).v)
        },

        // Effectful: Time.Now reads from the active [clock]. Default
        // is [SystemClock] (System.currentTimeMillis()). Tests install
        // [FixedClock] to get deterministic timestamps; the existing
        // FIXED_REPLAY_TIMESTAMP constant remains as the canonical
        // fixed value for tests that compare against a known Now.
        "strand-builtin:Time.Now" to fx { args ->
            require(args.isEmpty()) { "Time.Now expects 0 args, got ${args.size}" }
            Value.IntV(clock.nowMillis())
        },

        // Effectful: Time.Sleep(millis) suspends the current thread for
        // the requested duration via [clock.sleep]. Default SystemClock
        // calls Thread.sleep; FixedClock is a no-op so tests don't
        // actually wait. Returns UnitV.
        "strand-builtin:Time.Sleep" to fx { args ->
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
        "strand-builtin:Filesystem.Write" to fx { args ->
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

        // Q-041: each Fs.* builtin resolves the supplied path through
        // [FsSandbox.resolve] before invoking the JVM file API. The
        // resolver enforces workspace containment and symlink rejection
        // per the active [SandboxPolicy.fs]. Failures raise
        // [SandboxViolation] which the interpreter translates to
        // [InterpretError.SandboxViolation] at the call site.

        "strand-builtin:Fs.Write" to fx { args ->
            require(args.size == 2) {
                "Fs.Write expects 2 args (path: String, bytes: Bytes), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-write", "expected StringV path, got ${args[0]::class.simpleName}")
            val bytes = (args[1] as? Value.BytesV)?.v
                ?: throw IoFailure("filesystem-write", "expected BytesV content, got ${args[1]::class.simpleName}")
            val resolved = FsSandbox.resolve(sandboxPolicy.fs, path)
            try {
                java.nio.file.Files.write(resolved, bytes)
                Value.IntV(bytes.size.toLong())
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-write", "$path: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("filesystem-write", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.Read" to fx { args ->
            require(args.size == 1) {
                "Fs.Read expects 1 arg (path: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-read", "expected StringV path, got ${args[0]::class.simpleName}")
            val resolved = FsSandbox.resolve(sandboxPolicy.fs, path)
            try {
                Value.BytesV(java.nio.file.Files.readAllBytes(resolved))
            } catch (e: java.nio.file.NoSuchFileException) {
                throw IoFailure("filesystem-read", "$path: file does not exist")
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-read", "$path: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("filesystem-read", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.Append" to fx { args ->
            require(args.size == 2) {
                "Fs.Append expects 2 args (path: String, bytes: Bytes), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-append", "expected StringV path, got ${args[0]::class.simpleName}")
            val bytes = (args[1] as? Value.BytesV)?.v
                ?: throw IoFailure("filesystem-append", "expected BytesV content, got ${args[1]::class.simpleName}")
            val resolved = FsSandbox.resolve(sandboxPolicy.fs, path)
            try {
                java.nio.file.Files.write(
                    resolved,
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

        "strand-builtin:Fs.Exists" to fx { args ->
            require(args.size == 1) {
                "Fs.Exists expects 1 arg (path: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-exists", "expected StringV path, got ${args[0]::class.simpleName}")
            val resolved = FsSandbox.resolve(sandboxPolicy.fs, path)
            Value.BoolV(java.nio.file.Files.exists(resolved))
        },

        "strand-builtin:Fs.Delete" to fx { args ->
            require(args.size == 1) {
                "Fs.Delete expects 1 arg (path: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-delete", "expected StringV path, got ${args[0]::class.simpleName}")
            val resolved = FsSandbox.resolve(sandboxPolicy.fs, path)
            try {
                Value.BoolV(java.nio.file.Files.deleteIfExists(resolved))
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-delete", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.List" to fx { args ->
            require(args.size == 1) {
                "Fs.List expects 1 arg (dir: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-list", "expected StringV dir path, got ${args[0]::class.simpleName}")
            val resolved = FsSandbox.resolve(sandboxPolicy.fs, path)
            try {
                val entries = java.nio.file.Files.list(resolved).use { stream ->
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

        // Legacy stub kept for backwards-compat with the seed corpus
        // (programs 33, 34 and test fixtures). Real network sockets use
        // the `strand-builtin:Net.*` target namespace (Phase 2 #5).
        "strand-builtin:Network.Connect" to fx { args ->
            require(args.size == 2) {
                "Network.Connect expects 2 args (host: String, port: Int), got ${args.size}"
            }
            require(args[0] is Value.StringV && args[1] is Value.IntV) {
                "Network.Connect expects StringV host + IntV port, got " +
                    "(${args[0]::class.simpleName}, ${args[1]::class.simpleName})"
            }
            Value.IntV(1)  // connection id; legacy stub semantics
        },

        // Layer 4 step 2 — Network builtins under the `Net.*` target
        // namespace. Synchronous JVM Socket-based I/O. Async wrapping
        // into the state-machine actor loop is a follow-up.
        //
        // Effect categories: Net.Connect → E-001 Network.Connect;
        // Net.Send → E-003 Network.Send; Net.Receive → E-004
        // Network.Receive. Net.Close has no specific effect (closing
        // an opened resource is the dual of opening it).

        "strand-builtin:Net.Connect" to fx { args ->
            // (host: String, port: Int) -> SocketHandle
            // Q-041: NetSandbox.checkConnect resolves the host once and
            // refuses connect when the resolved IP lies in any blocked
            // range or when the host name is in the blocklist. The
            // resolved InetAddress is then passed to Socket(InetAddress,
            // port) so the connect goes to the IP that policy approved
            // (DNS pin-at-check defence).
            require(args.size == 2) {
                "Net.Connect expects 2 args (host: String, port: Int), got ${args.size}"
            }
            val host = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("network-connect", "expected StringV host, got ${args[0]::class.simpleName}")
            val port = (args[1] as? Value.IntV)?.v
                ?: throw IoFailure("network-connect", "expected IntV port, got ${args[1]::class.simpleName}")
            val resolvedAddr = NetSandbox.checkConnect(sandboxPolicy.net, host, port.toInt(), nameResolver)
            try {
                val socket = java.net.Socket(resolvedAddr, port.toInt())
                // Q-045: install the host-policy per-read ceiling as the
                // socket's SO_TIMEOUT so a stalled blocking receive
                // (Net.Receive / Net.Stream.Receive) cannot block past it.
                // A timeout outside the positive Int range maps to 0 —
                // the JVM's "no timeout".
                val timeout = streamReceiveTimeoutMillis
                socket.soTimeout = if (timeout in 1..Int.MAX_VALUE.toLong()) timeout.toInt() else 0
                ResourceTable.register(ResourceTable.KIND_SOCKET, socket)
            } catch (e: java.io.IOException) {
                throw IoFailure("network-connect", "$host:$port: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("network-connect", "$host:$port: ${e.message}")
            }
        },

        "strand-builtin:Net.Send" to fx { args ->
            // (handle: SocketHandle, bytes: Bytes) -> Int (bytes written)
            require(args.size == 2) {
                "Net.Send expects 2 args (handle: SocketHandle, bytes: Bytes), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("network-send", "expected Resource handle, got ${args[0]::class.simpleName}")
            val bytes = (args[1] as? Value.BytesV)?.v
                ?: throw IoFailure("network-send", "expected BytesV content, got ${args[1]::class.simpleName}")
            val socket = ResourceTable.get(handle, "socket") as java.net.Socket
            try {
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
                Value.IntV(bytes.size.toLong())
            } catch (e: java.io.IOException) {
                throw IoFailure("network-send", "socket #${handle.id}: ${e.message}")
            }
        },

        "strand-builtin:Net.Receive" to fx { args ->
            // (handle: SocketHandle, maxBytes: Int) -> Bytes
            // Reads up to maxBytes; returns empty Bytes on EOF.
            require(args.size == 2) {
                "Net.Receive expects 2 args (handle: SocketHandle, maxBytes: Int), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("network-receive", "expected Resource handle, got ${args[0]::class.simpleName}")
            val maxBytes = (args[1] as? Value.IntV)?.v?.toInt()
                ?: throw IoFailure("network-receive", "expected IntV maxBytes, got ${args[1]::class.simpleName}")
            require(maxBytes >= 0) { "Net.Receive maxBytes must be non-negative, got $maxBytes" }
            val socket = ResourceTable.get(handle, "socket") as java.net.Socket
            try {
                val buf = ByteArray(maxBytes)
                val n = socket.getInputStream().read(buf)
                if (n <= 0) Value.BytesV(ByteArray(0))
                else Value.BytesV(buf.copyOf(n))
            } catch (e: java.io.IOException) {
                throw IoFailure("network-receive", "socket #${handle.id}: ${e.message}")
            }
        },

        "strand-builtin:Net.Close" to det { args ->
            // (handle: SocketHandle) -> Unit
            require(args.size == 1) {
                "Net.Close expects 1 arg (handle: SocketHandle), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("network-close", "expected Resource handle, got ${args[0]::class.simpleName}")
            // Idempotent: remove from table even if close throws.
            val obj = ResourceTable.remove(handle)
            if (obj is java.net.Socket) {
                try { obj.close() } catch (_: java.io.IOException) { /* ignore — already closed */ }
            }
            Value.UnitV
        },

        // Q-045 streaming socket receive. Identical to Net.Receive except
        // end-of-stream is Option's None rather than empty Bytes —
        // distinguishable from a legitimate zero-length read. Declares
        // E-004 Network.Receive at the use site, exactly as Net.Receive
        // does. The legacy Net.Receive is retained unchanged for hash
        // stability; new programs are steered here via the system-prompt
        // docs. Net.Close serves as the stream's close.
        "strand-builtin:Net.Stream.Receive" to fx { args ->
            // (handle: SocketHandle, maxBytes: Int) -> Option<Bytes>
            require(args.size == 2) {
                "Net.Stream.Receive expects 2 args (handle: SocketHandle, maxBytes: Int), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("network-stream-receive", "expected Resource handle, got ${args[0]::class.simpleName}")
            val maxBytes = (args[1] as? Value.IntV)?.v?.toInt()
                ?: throw IoFailure("network-stream-receive", "expected IntV maxBytes, got ${args[1]::class.simpleName}")
            require(maxBytes >= 0) { "Net.Stream.Receive maxBytes must be non-negative, got $maxBytes" }
            val socket = ResourceTable.get(handle, ResourceTable.KIND_SOCKET) as java.net.Socket
            try {
                val buf = ByteArray(maxBytes)
                val n = socket.getInputStream().read(buf)
                if (n < 0) Value.SumV("None", null)
                else Value.SumV("Some", Value.BytesV(buf.copyOf(n)))
            } catch (e: java.net.SocketTimeoutException) {
                throw IoFailure("network-stream-timeout", "socket #${handle.id}: blocking read exceeded the host stream-receive timeout")
            } catch (e: java.io.IOException) {
                throw IoFailure("network-stream-receive", "socket #${handle.id}: ${e.message}")
            }
        },

        // Q-045 streaming LLM generation. Each per-provider *.CreateStream
        // opens the SSE response and returns an llm_stream handle
        // immediately (before the body arrives); the agent drains it via
        // LLM.Stream.Receive and releases it via LLM.Stream.Close. These
        // are first-order (no tool loop in this slice — tool use over a
        // stream needs SSE decoding first, deferred per proposal § 8).
        // CreateStream declares E-035 LLM.Generate{provider, model} at the
        // use site, exactly as the blocking *.Create variants do; the
        // drain (LLM.Stream.Receive) declares the transport effect E-004
        // Network.Receive. Both surface in the program's effect closure.
        "strand-builtin:Anthropic.Messages.CreateStream" to fx { args ->
            require(args.size == 1) {
                "Anthropic.Messages.CreateStream expects 1 arg (GenerateRequest), got ${args.size}"
            }
            openLlmStream(args[0] as Value.ProductV, "anthropic", AnthropicProvider::generateStreamOpen)
        },
        "strand-builtin:OpenAI.Chat.CompletionsStream" to fx { args ->
            require(args.size == 1) {
                "OpenAI.Chat.CompletionsStream expects 1 arg (GenerateRequest), got ${args.size}"
            }
            openLlmStream(args[0] as Value.ProductV, "openai", OpenAIProvider::generateStreamOpen)
        },
        "strand-builtin:Gemini.GenerateContentStream" to fx { args ->
            require(args.size == 1) {
                "Gemini.GenerateContentStream expects 1 arg (GenerateRequest), got ${args.size}"
            }
            openLlmStream(args[0] as Value.ProductV, "gemini", GeminiProvider::generateStreamOpen)
        },

        "strand-builtin:LLM.Stream.Receive" to fx { args ->
            // (handle: Int, maxBytes: Int) -> Option<Bytes>
            require(args.size == 2) {
                "LLM.Stream.Receive expects 2 args (handle: Int, maxBytes: Int), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("llm-stream-receive", "expected Resource handle, got ${args[0]::class.simpleName}")
            val maxBytes = (args[1] as? Value.IntV)?.v?.toInt()
                ?: throw IoFailure("llm-stream-receive", "expected IntV maxBytes, got ${args[1]::class.simpleName}")
            require(maxBytes >= 0) { "LLM.Stream.Receive maxBytes must be non-negative, got $maxBytes" }
            val holder = ResourceTable.get(handle, ResourceTable.KIND_LLM_STREAM) as LlmStreamHolder
            try {
                val chunk = holder.stream.read(maxBytes)
                if (chunk == null) Value.SumV("None", null)
                else Value.SumV("Some", Value.BytesV(chunk))
            } catch (e: java.net.SocketTimeoutException) {
                throw IoFailure(
                    "llm-stream-timeout",
                    "stream #${handle.id} (${holder.provider}/${holder.model}): " +
                        "blocking read exceeded the host stream-receive timeout",
                )
            } catch (e: java.io.IOException) {
                throw IoFailure(
                    "llm-stream-receive",
                    "stream #${handle.id} (${holder.provider}/${holder.model}): ${e.message}",
                )
            }
        },

        "strand-builtin:LLM.Stream.Close" to det { args ->
            // (handle: Int) -> Unit. Idempotent: a second close, or close
            // of an unknown id, is a no-op.
            require(args.size == 1) {
                "LLM.Stream.Close expects 1 arg (handle: Int), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("llm-stream-close", "expected Resource handle, got ${args[0]::class.simpleName}")
            val obj = ResourceTable.remove(handle)
            if (obj is LlmStreamHolder) {
                try { obj.stream.close() } catch (_: java.io.IOException) { /* ignore — already closed */ }
            }
            Value.UnitV
        },

        // Http.Request convenience builtin (Phase 2 #6). Wraps
        // java.net.URL + HttpURLConnection so simple HTTP/HTTPS
        // calls don't require manual socket framing. HTTP/1.1 only;
        // HTTPS via the JVM's default truststore. Returns a
        // ProductV with fields: {status: Int, body: Bytes}.
        // Response headers are not exposed in this initial slice —
        // most use cases need just status + body. A follow-up can
        // add headers as a SumV-encoded List<{name, value}>.
        //
        // Effect categories: declares E-001 Network.Connect, E-003
        // Network.Send, E-004 Network.Receive when used. Programs
        // typically combine the three under one CapabilityScope.

        // Q-041 redesigned signature (proposal § 4.3 Option A): the
        // (host, port) refinement values are positional arguments so
        // Q-039's projection vocabulary binds them via ArgRef(0) and
        // ArgRef(1). Scheme is validated against {http, https} —
        // file:// reaches outside the network-and-sandbox model and
        // is rejected as HttpSchemeRejected. The legacy single-URL
        // form is preserved below as Http.RequestFromUrl.
        //
        // Headers shape: a SumV-encoded Cons/Nil chain of
        // ProductV("name", "value") entries, matching the convention
        // used by Fs.List / String.Split / Process.Spawn arg lists.
        // The result includes the same shape for response headers.

        "strand-builtin:Http.Request" to fx { args ->
            // (host: String, port: Int, scheme: String, path: String,
            //  method: String, headers: List<Header>, body: Bytes)
            //   -> {status: Int, body: Bytes, headers: List<Header>}
            require(args.size == 7) {
                "Http.Request expects 7 args (host, port, scheme, path, method, headers, body), got ${args.size}"
            }
            val host = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("http-request", "expected StringV host, got ${args[0]::class.simpleName}")
            val port = (args[1] as? Value.IntV)?.v
                ?: throw IoFailure("http-request", "expected IntV port, got ${args[1]::class.simpleName}")
            val scheme = (args[2] as? Value.StringV)?.v
                ?: throw IoFailure("http-request", "expected StringV scheme, got ${args[2]::class.simpleName}")
            val pathArg = (args[3] as? Value.StringV)?.v
                ?: throw IoFailure("http-request", "expected StringV path, got ${args[3]::class.simpleName}")
            val method = (args[4] as? Value.StringV)?.v
                ?: throw IoFailure("http-request", "expected StringV method, got ${args[4]::class.simpleName}")
            val headersValue = args[5]
            val body = (args[6] as? Value.BytesV)?.v
                ?: throw IoFailure("http-request", "expected BytesV body, got ${args[6]::class.simpleName}")

            // Scheme validation: file:// etc. would route around the
            // network policy entirely. Reject as HttpSchemeRejected.
            if (scheme.lowercase() !in setOf("http", "https")) {
                throw SandboxViolation(
                    SandboxViolationKind.HttpSchemeRejected,
                    "scheme '$scheme' is not allowed; expected 'http' or 'https'",
                )
            }

            // Network sandbox check: refuses cloud-metadata, loopback,
            // RFC1918, etc. by default. The resolved InetAddress is
            // what we hand to URI() so the connect goes to the IP that
            // policy approved (DNS pin-at-check).
            val resolvedAddr = NetSandbox.checkConnect(sandboxPolicy.net, host, port.toInt(), nameResolver)

            // Decode headers into a Map<String, String> for the
            // outbound HttpURLConnection. The canonical shape is the
            // Cons/Nil SumV chain over ProductV{head: {name, value},
            // tail: <list>} entries — matching Fs.List / String.Split /
            // Process.Spawn arg lists.
            val requestHeaders = mutableMapOf<String, String>()
            var headerCur: Value = headersValue
            while (true) {
                val sumV = headerCur
                if (sumV !is Value.SumV) break
                if (sumV.case != "Cons") break
                val node = sumV.payload as? Value.ProductV
                    ?: throw IoFailure("http-request", "header list Cons payload is not a ProductV")
                val headProduct = node.fields["head"] as? Value.ProductV
                    ?: throw IoFailure("http-request", "header entry missing ProductV 'head' field")
                val name = (headProduct.fields["name"] as? Value.StringV)?.v
                    ?: throw IoFailure("http-request", "header head missing 'name' String")
                val value = (headProduct.fields["value"] as? Value.StringV)?.v
                    ?: throw IoFailure("http-request", "header head missing 'value' String")
                requestHeaders[name] = value
                headerCur = node.fields["tail"]
                    ?: throw IoFailure("http-request", "header list missing 'tail' edge")
            }

            // Build the URL using the resolved IP literal rather than
            // the original hostname so the JVM connect cannot redo DNS
            // and pick a different address. The Host: header still
            // names the original hostname so the upstream sees a
            // well-formed request (set explicitly below).
            val resolvedHost = resolvedAddr.hostAddress.let { addr ->
                if (resolvedAddr is java.net.Inet6Address) "[$addr]" else addr
            }
            val urlStr = "${scheme.lowercase()}://$resolvedHost:$port$pathArg"
            try {
                val url = java.net.URI(urlStr).toURL()
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = method.uppercase()
                conn.doInput = true
                // Preserve the original hostname in the Host header so
                // virtual-hosted servers route correctly; the request
                // still goes to the policy-approved IP. (DNS rebinding
                // mitigation has the IP pinned; Host header is the
                // standard HTTP/1.1 multiplexing field.)
                conn.setRequestProperty("Host", "$host:$port")
                for ((name, value) in requestHeaders) {
                    conn.setRequestProperty(name, value)
                }
                if (body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                }
                val status = conn.responseCode
                val responseBody = try {
                    (if (status in 200..299) conn.inputStream else conn.errorStream)
                        ?.readBytes() ?: ByteArray(0)
                } catch (_: java.io.IOException) {
                    ByteArray(0)
                }
                // Build the response headers list as the same
                // Cons/Nil shape — one ProductV("name", "value") per
                // header. HttpURLConnection's headerFields() may
                // include a null key for the status line; skip it.
                val headerEntries: MutableList<Value> = mutableListOf()
                for ((name, values) in conn.headerFields) {
                    if (name == null) continue
                    for (v in values) {
                        headerEntries += Value.ProductV(mapOf(
                            "name" to Value.StringV(name),
                            "value" to Value.StringV(v),
                        ))
                    }
                }
                var responseHeaders: Value = Value.SumV("Nil", null)
                for (entry in headerEntries.reversed()) {
                    responseHeaders = Value.SumV("Cons", Value.ProductV(mapOf(
                        "head" to entry,
                        "tail" to responseHeaders,
                    )))
                }
                conn.disconnect()
                Value.ProductV(mapOf(
                    "status" to Value.IntV(status.toLong()),
                    "body" to Value.BytesV(responseBody),
                    "headers" to responseHeaders,
                ))
            } catch (e: java.net.MalformedURLException) {
                throw IoFailure("http-request", "$urlStr: malformed URL: ${e.message}")
            } catch (e: java.net.URISyntaxException) {
                throw IoFailure("http-request", "$urlStr: URI syntax: ${e.message}")
            } catch (e: java.io.IOException) {
                throw IoFailure("http-request", "$urlStr: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("http-request", "$urlStr: ${e.message}")
            }
        },

        // Q-041 legacy wrapper: preserves the pre-Q-041 single-URL
        // signature for backward compatibility with corpus / test
        // fixtures that haven't migrated to the seven-arg form. The
        // wrapper parses the URL host-side, extracts (host, port,
        // scheme, path), and dispatches through the same code path
        // — so the sandbox check fires uniformly. Returns the
        // pre-Q-041 product shape {status: Int, body: Bytes} (no
        // headers) so callers can drop in without changes.
        "strand-builtin:Http.RequestFromUrl" to fx { args ->
            // (method: String, url: String, body: Bytes) -> {status: Int, body: Bytes}
            require(args.size == 3) {
                "Http.RequestFromUrl expects 3 args (method: String, url: String, body: Bytes), got ${args.size}"
            }
            val method = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("http-request", "expected StringV method, got ${args[0]::class.simpleName}")
            val urlStr = (args[1] as? Value.StringV)?.v
                ?: throw IoFailure("http-request", "expected StringV url, got ${args[1]::class.simpleName}")
            val body = (args[2] as? Value.BytesV)?.v
                ?: throw IoFailure("http-request", "expected BytesV body, got ${args[2]::class.simpleName}")

            // Parse the URL host-side so the underlying call sees
            // structured fields. Default port: 80 for http, 443 for
            // https. The path includes the query string if any.
            val uri = try {
                java.net.URI(urlStr)
            } catch (e: java.net.URISyntaxException) {
                throw IoFailure("http-request", "$urlStr: URI syntax: ${e.message}")
            }
            val scheme = uri.scheme ?: "http"
            val host = uri.host
                ?: throw IoFailure("http-request", "$urlStr: missing host")
            val effectivePort = if (uri.port > 0) uri.port
                else when (scheme.lowercase()) {
                    "https" -> 443
                    "http" -> 80
                    else -> 80  // The seven-arg form will reject non-http schemes anyway.
                }
            val pathAndQuery = buildString {
                append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
                if (!uri.rawQuery.isNullOrEmpty()) append("?").append(uri.rawQuery)
            }

            // Dispatch to the seven-arg builtin so the sandbox check
            // and projection-friendly path both run once. We construct
            // an empty header list (the legacy signature had none).
            val componentRequest = lookup("strand-builtin:Http.Request")
                ?: throw IoFailure("http-request", "internal: Http.Request not registered")
            val response = componentRequest.invoke(this, listOf(
                Value.StringV(host),
                Value.IntV(effectivePort.toLong()),
                Value.StringV(scheme),
                Value.StringV(pathAndQuery),
                Value.StringV(method),
                Value.SumV("Nil", null),  // no headers in legacy form
                Value.BytesV(body),
            )) as Value.ProductV

            // Return the pre-Q-041 shape: drop the `headers` field so
            // legacy callers still see {status, body}.
            Value.ProductV(mapOf(
                "status" to response.fields.getValue("status"),
                "body" to response.fields.getValue("body"),
            ))
        },

        // Layer 4 step 2 — Process + env builtins. Spawn/Wait use
        // java.lang.ProcessBuilder; inherited stdio (child's stdout/
        // stderr go to the runtime's stdout/stderr). Captured output
        // (via Process.SpawnCapture returning Bytes) is a follow-up.

        "strand-builtin:Process.Spawn" to fx { args ->
            // (cmd: String, args: List<String>) -> ProcessHandle
            // The args parameter is a SumV-encoded Cons/Nil chain of
            // StringV — matches the convention used by Fs.List.
            require(args.size == 2) {
                "Process.Spawn expects 2 args (cmd: String, args: List<String>), got ${args.size}"
            }
            val cmd = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("process-spawn", "expected StringV cmd, got ${args[0]::class.simpleName}")
            val argList = mutableListOf<String>()
            var cur = args[1]
            while (cur is Value.SumV && cur.case == "Cons") {
                val payload = cur.payload as? Value.ProductV
                    ?: throw IoFailure("process-spawn",
                        "Process.Spawn args list Cons payload not a ProductV (kind=${cur.payload?.let { it::class.simpleName }})")
                val head = payload.fields["head"] as? Value.StringV
                    ?: throw IoFailure("process-spawn",
                        "Process.Spawn args list head not StringV (kind=${payload.fields["head"]?.let { it::class.simpleName }})")
                argList += head.v
                cur = payload.fields["tail"]
                    ?: throw IoFailure("process-spawn", "Process.Spawn args list missing tail")
            }
            try {
                val builder = ProcessBuilder(listOf(cmd) + argList).inheritIO()
                val proc = builder.start()
                ResourceTable.register("process", proc)
            } catch (e: java.io.IOException) {
                throw IoFailure("process-spawn", "$cmd: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("process-spawn", "$cmd: ${e.message}")
            }
        },

        "strand-builtin:Process.Wait" to fx { args ->
            // (handle: ProcessHandle) -> Int (exit code)
            require(args.size == 1) {
                "Process.Wait expects 1 arg (handle: ProcessHandle), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("process-wait", "expected Resource handle, got ${args[0]::class.simpleName}")
            val proc = ResourceTable.get(handle, "process") as Process
            val exitCode = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IoFailure("process-wait", "interrupted waiting for process #${handle.id}")
            }
            ResourceTable.remove(handle)
            Value.IntV(exitCode.toLong())
        },

        "strand-builtin:Process.EnvVar" to fx { args ->
            // (name: String) -> Option<String>
            // Returns SumV "Some" payload=StringV when set, SumV "None"
            // payload=null when missing. Standard Option convention.
            require(args.size == 1) {
                "Process.EnvVar expects 1 arg (name: String), got ${args.size}"
            }
            val name = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("process-envvar", "expected StringV name, got ${args[0]::class.simpleName}")
            val value = System.getenv(name)
            if (value != null) Value.SumV(case = "Some", payload = Value.StringV(value))
            else Value.SumV(case = "None", payload = null)
        },

        // Layer 4 step 2 — String stdlib builtins. Pure (no declared
        // effects). All UTF-8 semantics; ParseInt/ParseFloat return
        // the standard Option<T> sum encoding (SumV "Some" / "None").

        "strand-builtin:String.Length" to det { args ->
            require(args.size == 1) { "String.Length expects 1 arg, got ${args.size}" }
            Value.IntV((args[0] as Value.StringV).v.length.toLong())
        },

        "strand-builtin:String.Substring" to det { args ->
            // (s: String, start: Int, end: Int) -> String
            // start inclusive, end exclusive. Clamped to [0, length].
            require(args.size == 3) { "String.Substring expects 3 args (s, start, end), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val start = (args[1] as Value.IntV).v.toInt().coerceIn(0, s.length)
            val end = (args[2] as Value.IntV).v.toInt().coerceIn(start, s.length)
            Value.StringV(s.substring(start, end))
        },

        "strand-builtin:String.IndexOf" to det { args ->
            // (haystack: String, needle: String) -> Int (-1 if not found)
            require(args.size == 2) { "String.IndexOf expects 2 args, got ${args.size}" }
            val haystack = (args[0] as Value.StringV).v
            val needle = (args[1] as Value.StringV).v
            Value.IntV(haystack.indexOf(needle).toLong())
        },

        "strand-builtin:String.Contains" to det { args ->
            require(args.size == 2) { "String.Contains expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.StringV).v.contains((args[1] as Value.StringV).v))
        },

        "strand-builtin:String.Replace" to det { args ->
            // (s: String, find: String, replace: String) -> String. Literal, not regex.
            require(args.size == 3) { "String.Replace expects 3 args, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v
                .replace((args[1] as Value.StringV).v, (args[2] as Value.StringV).v))
        },

        "strand-builtin:String.Split" to det { args ->
            // (s: String, sep: String) -> List<String>
            // Returns the SumV-encoded Cons/Nil list. Empty separator
            // is rejected (would otherwise loop indefinitely).
            require(args.size == 2) { "String.Split expects 2 args, got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val sep = (args[1] as Value.StringV).v
            require(sep.isNotEmpty()) { "String.Split separator must be non-empty" }
            val parts = s.split(sep)
            var listValue: Value = Value.SumV(case = "Nil", payload = null)
            for (part in parts.reversed()) {
                listValue = Value.SumV(case = "Cons", payload = Value.ProductV(
                    mapOf("head" to Value.StringV(part), "tail" to listValue)
                ))
            }
            listValue
        },

        "strand-builtin:String.Join" to det { args ->
            // (parts: List<String>, sep: String) -> String
            require(args.size == 2) { "String.Join expects 2 args, got ${args.size}" }
            val sep = (args[1] as Value.StringV).v
            val parts = mutableListOf<String>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                parts += (payload.fields.getValue("head") as Value.StringV).v
                cur = payload.fields.getValue("tail")
            }
            Value.StringV(parts.joinToString(sep))
        },

        "strand-builtin:String.ToUpper" to det { args ->
            require(args.size == 1) { "String.ToUpper expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v.uppercase())
        },

        "strand-builtin:String.ToLower" to det { args ->
            require(args.size == 1) { "String.ToLower expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v.lowercase())
        },

        "strand-builtin:String.Trim" to det { args ->
            require(args.size == 1) { "String.Trim expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v.trim())
        },

        "strand-builtin:String.ParseInt" to det { args ->
            // (s: String) -> Option<Int>
            require(args.size == 1) { "String.ParseInt expects 1 arg, got ${args.size}" }
            val n = (args[0] as Value.StringV).v.toLongOrNull()
            if (n != null) Value.SumV("Some", Value.IntV(n))
            else Value.SumV("None", null)
        },

        "strand-builtin:String.ParseFloat" to det { args ->
            // (s: String) -> Option<Float>
            require(args.size == 1) { "String.ParseFloat expects 1 arg, got ${args.size}" }
            val n = (args[0] as Value.StringV).v.toDoubleOrNull()
            if (n != null) Value.SumV("Some", Value.FloatV(n))
            else Value.SumV("None", null)
        },

        "strand-builtin:String.FromInt" to det { args ->
            // (n: Int) -> String. Convenience for formatting.
            require(args.size == 1) { "String.FromInt expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.IntV).v.toString())
        },

        "strand-builtin:String.FromFloat" to det { args ->
            require(args.size == 1) { "String.FromFloat expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.FloatV).v.toString())
        },

        "strand-builtin:String.FromBool" to det { args ->
            require(args.size == 1) { "String.FromBool expects 1 arg, got ${args.size}" }
            Value.StringV(if ((args[0] as Value.BoolV).v) "true" else "false")
        },

        // ===== Stdlib expansion round 5 (2026-05-27) — String formatting =====
        // String.Format / PadLeft / PadRight / Repeat / Lines / Chars /
        // CharAt. All pure. Format / Lines / Chars take or return
        // List<String> so they stay out of the prelude per the
        // documented polymorphic-list exception; CharAt is Option-
        // returning. PadLeft / PadRight / Repeat are monomorphic and
        // preludable.

        "strand-builtin:String.Format" to det { args ->
            // (template: String, args: List<String>) -> String.
            // Template uses {0}, {1}, {2} positional placeholders.
            // Out-of-range or non-numeric placeholders are left
            // verbatim — agents see the literal `{N}` in output when
            // their args list is shorter than expected. Double-braces
            // `{{` / `}}` are NOT escape sequences in this slice; if
            // the template needs a literal `{` it has to live outside
            // a `{N}` match.
            require(args.size == 2) { "String.Format expects 2 args (template, args), got ${args.size}" }
            val template = (args[0] as Value.StringV).v
            val parts = mutableListOf<String>()
            var cur: Value = args[1]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                parts += (payload.fields.getValue("head") as Value.StringV).v
                cur = payload.fields.getValue("tail")
            }
            // Regex-based substitution: match `{<digits>}` and replace
            // with parts[index] when index is in range; leave the match
            // verbatim otherwise.
            val out = StringBuilder()
            var i = 0
            while (i < template.length) {
                val c = template[i]
                if (c == '{') {
                    val close = template.indexOf('}', i + 1)
                    if (close > i + 1) {
                        val inside = template.substring(i + 1, close)
                        val idx = inside.toIntOrNull()
                        if (idx != null && idx >= 0 && idx < parts.size) {
                            out.append(parts[idx])
                            i = close + 1
                            continue
                        }
                    }
                }
                out.append(c)
                i++
            }
            Value.StringV(out.toString())
        },

        "strand-builtin:String.PadLeft" to det { args ->
            // (s: String, n: Int, pad: String) -> String.
            // Pads s on the left with `pad` (must be non-empty) until
            // length >= n. If s is already >= n chars, returns s
            // unchanged. The final pad-run may be truncated to reach
            // exactly n chars.
            require(args.size == 3) { "String.PadLeft expects 3 args (s, n, pad), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val n = (args[1] as Value.IntV).v.toInt()
            val pad = (args[2] as Value.StringV).v
            if (s.length >= n) return@det Value.StringV(s)
            require(pad.isNotEmpty()) { "String.PadLeft pad must be non-empty" }
            val needed = n - s.length
            val out = StringBuilder()
            while (out.length < needed) out.append(pad)
            Value.StringV(out.substring(0, needed) + s)
        },

        "strand-builtin:String.PadRight" to det { args ->
            require(args.size == 3) { "String.PadRight expects 3 args (s, n, pad), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val n = (args[1] as Value.IntV).v.toInt()
            val pad = (args[2] as Value.StringV).v
            if (s.length >= n) return@det Value.StringV(s)
            require(pad.isNotEmpty()) { "String.PadRight pad must be non-empty" }
            val needed = n - s.length
            val out = StringBuilder()
            while (out.length < needed) out.append(pad)
            Value.StringV(s + out.substring(0, needed))
        },

        "strand-builtin:String.Repeat" to det { args ->
            // (s: String, n: Int) -> String. Non-negative n only;
            // n=0 yields "". The repeated output capacity is bounded
            // by Q-040's allocated-values limit indirectly (one
            // BytesV allocation), so very-large n still gets caught
            // at the limit boundary.
            require(args.size == 2) { "String.Repeat expects 2 args (s, n), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val n = (args[1] as Value.IntV).v.toInt()
            require(n >= 0) { "String.Repeat n must be non-negative, got $n" }
            Value.StringV(s.repeat(n))
        },

        "strand-builtin:String.Lines" to det { args ->
            // (s: String) -> List<String>. Splits on `\n`. A trailing
            // newline produces an empty-string entry. `\r\n` splits
            // on the `\n` (the `\r` stays on the preceding line) —
            // matches Kotlin's String.split("\n") behaviour. For
            // POSIX line-by-line iteration this is the expected shape;
            // agents that need CRLF-aware splitting should use
            // Regex.Split with the appropriate pattern.
            require(args.size == 1) { "String.Lines expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val parts = s.split('\n')
            var listValue: Value = Value.SumV("Nil", null)
            for (part in parts.reversed()) {
                listValue = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to Value.StringV(part), "tail" to listValue,
                )))
            }
            listValue
        },

        "strand-builtin:String.Chars" to det { args ->
            // (s: String) -> List<String>. One single-char String per
            // UTF-16 code unit. Surrogate pairs split into two
            // entries (matches Java's `String.length` granularity).
            // Agents that need full Unicode code points should
            // decode via Bytes.ParseUtf8 + a manual code-point walk.
            require(args.size == 1) { "String.Chars expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            var listValue: Value = Value.SumV("Nil", null)
            for (i in s.length - 1 downTo 0) {
                listValue = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to Value.StringV(s[i].toString()), "tail" to listValue,
                )))
            }
            listValue
        },

        "strand-builtin:String.CharAt" to det { args ->
            // (s: String, i: Int) -> Option<String>. Single-char
            // String at index i (UTF-16 granularity), None for
            // negative or out-of-range index.
            require(args.size == 2) { "String.CharAt expects 2 args (s, i), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val i = (args[1] as Value.IntV).v
            if (i < 0 || i >= s.length) Value.SumV("None", null)
            else Value.SumV("Some", Value.StringV(s[i.toInt()].toString()))
        },

        // Layer 4 step 2 — Bytes stdlib builtins. Bytes are runtime-
        // opaque ByteArrays; these helpers cover the common
        // serialization tasks (length, slice, concat, UTF-8 round-trip,
        // base64).

        "strand-builtin:Bytes.Length" to det { args ->
            require(args.size == 1) { "Bytes.Length expects 1 arg, got ${args.size}" }
            Value.IntV((args[0] as Value.BytesV).v.size.toLong())
        },

        "strand-builtin:Bytes.Slice" to det { args ->
            // (b: Bytes, start: Int, end: Int) -> Bytes
            require(args.size == 3) { "Bytes.Slice expects 3 args, got ${args.size}" }
            val b = (args[0] as Value.BytesV).v
            val start = (args[1] as Value.IntV).v.toInt().coerceIn(0, b.size)
            val end = (args[2] as Value.IntV).v.toInt().coerceIn(start, b.size)
            Value.BytesV(b.copyOfRange(start, end))
        },

        "strand-builtin:Bytes.Concat" to det { args ->
            require(args.size == 2) { "Bytes.Concat expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.BytesV).v
            val b = (args[1] as Value.BytesV).v
            Value.BytesV(a + b)
        },

        "strand-builtin:Bytes.Eq" to det { args ->
            // Content equality (Kotlin `==` on ByteArray is reference
            // equality; use contentEquals for value-equal semantics).
            require(args.size == 2) { "Bytes.Eq expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.BytesV).v.contentEquals((args[1] as Value.BytesV).v))
        },

        "strand-builtin:Bytes.ParseUtf8" to det { args ->
            // (b: Bytes) -> Option<String>. None on invalid UTF-8.
            require(args.size == 1) { "Bytes.ParseUtf8 expects 1 arg, got ${args.size}" }
            val bytes = (args[0] as Value.BytesV).v
            try {
                val decoder = Charsets.UTF_8.newDecoder()
                val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                Value.SumV("Some", Value.StringV(text))
            } catch (_: java.nio.charset.CharacterCodingException) {
                Value.SumV("None", null)
            }
        },

        "strand-builtin:Bytes.FromUtf8" to det { args ->
            // (s: String) -> Bytes. UTF-8 encoding always succeeds.
            require(args.size == 1) { "Bytes.FromUtf8 expects 1 arg, got ${args.size}" }
            Value.BytesV((args[0] as Value.StringV).v.toByteArray(Charsets.UTF_8))
        },

        "strand-builtin:Bytes.FormatBase64" to det { args ->
            require(args.size == 1) { "Bytes.FormatBase64 expects 1 arg, got ${args.size}" }
            Value.StringV(java.util.Base64.getEncoder().encodeToString((args[0] as Value.BytesV).v))
        },

        "strand-builtin:Bytes.ParseBase64" to det { args ->
            // (s: String) -> Option<Bytes>. None on invalid base64.
            require(args.size == 1) { "Bytes.ParseBase64 expects 1 arg, got ${args.size}" }
            try {
                Value.SumV("Some", Value.BytesV(java.util.Base64.getDecoder().decode((args[0] as Value.StringV).v)))
            } catch (_: IllegalArgumentException) {
                Value.SumV("None", null)
            }
        },

        // Layer 4 step 2 (round 2 update) — Json.Parse. Parses any
        // valid JSON value (null, true, false, number, string, array,
        // object) into the full JsonValue sum encoding now that the
        // nested-μ blocker is lifted (Slice 3 of stdlib expansion
        // round 2, via the RecursiveSelf depth field).
        //
        // The blessed JsonValue type lives in corpus 66; the four
        // primitive cases match corpus 54's flat predecessor for
        // primitives — programs that only handle primitives continue
        // to work unchanged. Arrays and objects now produce real
        // recursive structure: JsonArray(List<JsonValue>) and
        // JsonObject(List<{key, value}>) using the canonical Cons/Nil
        // sum encoding.
        //
        // Returns Option<JsonValue>: Some on any valid JSON,
        // None on malformed input.

        "strand-builtin:Json.Parse" to det { args ->
            require(args.size == 1) { "Json.Parse expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v.trim()
            try {
                val element = kotlinx.serialization.json.Json.parseToJsonElement(s)
                val converted = jsonElementToValue(element)
                if (converted == null) Value.SumV("None", null)
                else Value.SumV("Some", converted)
            } catch (_: kotlinx.serialization.SerializationException) {
                Value.SumV("None", null)
            } catch (_: IllegalArgumentException) {
                Value.SumV("None", null)
            }
        },

        // Layer 4 step 2 — Markdown.Parse (Phase 4 #9). Stub for
        // symmetry: parses a Markdown source into the MarkdownDocument
        // sum encoding (corpus 61). The corpus encoding is a list of
        // MarkdownBlock variants (Heading, Paragraph, CodeBlock,
        // Quote); full block-level parsing is non-trivial and the
        // blessed library can already represent the result. For now,
        // wrap the entire input as a single Paragraph block. Programs
        // that need real parsing should use a proper markdown lib via
        // a future blessed-library binding.
        "strand-builtin:Markdown.Parse" to det { args ->
            require(args.size == 1) { "Markdown.Parse expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            // Build: Cons(Paragraph(s), Nil) as the document list.
            val paragraphBlock = Value.SumV("Paragraph", Value.StringV(s))
            val singleton = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to paragraphBlock,
                "tail" to Value.SumV("Nil", null),
            )))
            Value.SumV("Some", singleton)
        },

        // Stdlib expansion round 4 — Markdown.Stringify (Phase 4 #1).
        // Inverse of Markdown.Parse, but typed against the canonical
        // corpus-61 MarkdownDocument shape: a recursive list of
        // MarkdownBlock variants where each block is one of
        //   Heading(level: Int, text: String)
        //   Paragraph(text: String)
        //   CodeBlock(language: String, code: String)
        //   HorizontalRule (no payload)
        //
        // Backward compat for Markdown.Parse's flat Paragraph encoding:
        // when a Paragraph block's payload is a bare StringV (not a
        // ProductV wrapping {text}), treat it as the text directly.
        // This means a round-trip through Parse → Stringify works for
        // single-paragraph inputs and a hand-constructed corpus-61
        // document Stringify-ies to standard Markdown.
        //
        // NOT in the prelude (agent-typed payload — the agent picks
        // the exact MarkdownDocument shape; no single monomorphic
        // FNT fits). Use an explicit FN + FNT at the call site.
        "strand-builtin:Markdown.Stringify" to det { args ->
            require(args.size == 1) { "Markdown.Stringify expects 1 arg (doc: MarkdownDocument), got ${args.size}" }
            val out = StringBuilder()
            var cur: Value = args[0]
            var first = true
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val node = sumV.payload as Value.ProductV
                val block = node.fields.getValue("head") as Value.SumV
                if (!first) out.append("\n\n")
                first = false
                when (block.case) {
                    "Heading" -> {
                        val product = block.payload as Value.ProductV
                        val level = (product.fields.getValue("level") as Value.IntV).v.toInt().coerceIn(1, 6)
                        val text = (product.fields.getValue("text") as Value.StringV).v
                        out.append("#".repeat(level)).append(' ').append(text)
                    }
                    "Paragraph" -> {
                        // Accept both the canonical ProductV{text} shape
                        // and the legacy bare-StringV shape Markdown.Parse
                        // produces today.
                        val text = when (val p = block.payload) {
                            is Value.ProductV -> (p.fields.getValue("text") as Value.StringV).v
                            is Value.StringV -> p.v
                            else -> throw IoFailure("markdown-stringify",
                                "Paragraph payload must be ProductV{text} or StringV, got ${p?.let { it::class.simpleName }}")
                        }
                        out.append(text)
                    }
                    "CodeBlock" -> {
                        val product = block.payload as Value.ProductV
                        val lang = (product.fields.getValue("language") as Value.StringV).v
                        val code = (product.fields.getValue("code") as Value.StringV).v
                        out.append("```").append(lang).append('\n').append(code).append("\n```")
                    }
                    "HorizontalRule" -> out.append("---")
                    "Quote" -> {
                        // Tolerate the round-4 catalog's older Quote case
                        // (proposal listed Quote alongside Heading/Paragraph/
                        // CodeBlock — corpus 61 ships HorizontalRule
                        // instead). Treat Quote.text as a blockquote line.
                        val text = when (val p = block.payload) {
                            is Value.ProductV -> (p.fields.getValue("text") as Value.StringV).v
                            is Value.StringV -> p.v
                            else -> throw IoFailure("markdown-stringify",
                                "Quote payload must be ProductV{text} or StringV, got ${p?.let { it::class.simpleName }}")
                        }
                        out.append("> ").append(text)
                    }
                    else -> throw IoFailure("markdown-stringify",
                        "unknown MarkdownBlock case '${block.case}' — expected Heading|Paragraph|CodeBlock|HorizontalRule|Quote")
                }
                cur = node.fields.getValue("tail")
            }
            Value.StringV(out.toString())
        },

        // Stdlib expansion round 2 — Math.* builtins. Pure.
        // Int-typed (Abs/Sign/Min/Max/Mod) compose with the existing
        // Int arithmetic surface; Float-typed (Sqrt/Pow/Log/Exp/Sin/
        // Cos/Tan) are irreducibly real-valued; Floor/Ceil/Round take
        // a Float and return an Int. Math.Mod is the *always-positive*
        // mathematical modulo, distinct from Int.Mod (which follows
        // JVM `%` sign-of-dividend semantics).

        "strand-builtin:Math.Abs" to det { args ->
            require(args.size == 1) { "Math.Abs expects 1 arg (n: Int), got ${args.size}" }
            Value.IntV(kotlin.math.abs((args[0] as Value.IntV).v))
        },

        "strand-builtin:Math.Sign" to det { args ->
            // -1, 0, or 1 depending on the sign of the input.
            require(args.size == 1) { "Math.Sign expects 1 arg (n: Int), got ${args.size}" }
            val n = (args[0] as Value.IntV).v
            Value.IntV(when {
                n > 0 -> 1L
                n < 0 -> -1L
                else -> 0L
            })
        },

        "strand-builtin:Math.Min" to det { args ->
            require(args.size == 2) { "Math.Min expects 2 args (a, b: Int), got ${args.size}" }
            Value.IntV(kotlin.math.min((args[0] as Value.IntV).v, (args[1] as Value.IntV).v))
        },

        "strand-builtin:Math.Max" to det { args ->
            require(args.size == 2) { "Math.Max expects 2 args (a, b: Int), got ${args.size}" }
            Value.IntV(kotlin.math.max((args[0] as Value.IntV).v, (args[1] as Value.IntV).v))
        },

        "strand-builtin:Math.Mod" to det { args ->
            // True mathematical modulo: result has the sign of the
            // divisor (always non-negative for positive divisors).
            // Distinct from Int.Mod, which follows JVM `%` semantics.
            require(args.size == 2) { "Math.Mod expects 2 args (a, b: Int), got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            require(b != 0L) { "Math.Mod division by zero" }
            Value.IntV(((a % b) + b) % b)
        },

        "strand-builtin:Math.Floor" to det { args ->
            // (f: Float) -> Int. Largest Int <= f.
            require(args.size == 1) { "Math.Floor expects 1 arg (f: Float), got ${args.size}" }
            Value.IntV(kotlin.math.floor((args[0] as Value.FloatV).v).toLong())
        },

        "strand-builtin:Math.Ceil" to det { args ->
            // (f: Float) -> Int. Smallest Int >= f.
            require(args.size == 1) { "Math.Ceil expects 1 arg (f: Float), got ${args.size}" }
            Value.IntV(kotlin.math.ceil((args[0] as Value.FloatV).v).toLong())
        },

        "strand-builtin:Math.Round" to det { args ->
            // (f: Float) -> Int. Banker's rounding (round-half-to-even)
            // to avoid the asymmetric bias of round-half-up.
            require(args.size == 1) { "Math.Round expects 1 arg (f: Float), got ${args.size}" }
            Value.IntV(kotlin.math.round((args[0] as Value.FloatV).v).toLong())
        },

        "strand-builtin:Math.Sqrt" to det { args ->
            // (f: Float) -> Float. NaN for negative inputs (matches IEEE 754).
            require(args.size == 1) { "Math.Sqrt expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.sqrt((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Pow" to det { args ->
            // (base: Float, exp: Float) -> Float.
            require(args.size == 2) { "Math.Pow expects 2 args (base, exp: Float), got ${args.size}" }
            Value.FloatV((args[0] as Value.FloatV).v.pow((args[1] as Value.FloatV).v))
        },

        "strand-builtin:Math.Log" to det { args ->
            // (f: Float) -> Float. Natural log. NaN for non-positive inputs.
            require(args.size == 1) { "Math.Log expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.ln((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Exp" to det { args ->
            // (f: Float) -> Float. e^f.
            require(args.size == 1) { "Math.Exp expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.exp((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Sin" to det { args ->
            require(args.size == 1) { "Math.Sin expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.sin((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Cos" to det { args ->
            require(args.size == 1) { "Math.Cos expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.cos((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Tan" to det { args ->
            require(args.size == 1) { "Math.Tan expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.tan((args[0] as Value.FloatV).v))
        },

        // Int <-> Float coercion helpers. Strand has no implicit
        // numeric coercion; these make Math.Sqrt(Float.FromInt(n))
        // ergonomic without round-tripping through String.
        "strand-builtin:Float.FromInt" to det { args ->
            require(args.size == 1) { "Float.FromInt expects 1 arg (n: Int), got ${args.size}" }
            Value.FloatV((args[0] as Value.IntV).v.toDouble())
        },

        "strand-builtin:Int.FromFloatTrunc" to det { args ->
            // Truncation toward zero (drops the fractional part).
            // Distinct from Math.Floor (which rounds toward -infinity).
            require(args.size == 1) { "Int.FromFloatTrunc expects 1 arg (f: Float), got ${args.size}" }
            Value.IntV((args[0] as Value.FloatV).v.toLong())
        },

        // Stdlib expansion round 4 — Float arithmetic and comparisons.
        // Mirrors the Int.* arithmetic surface; Math.* covers the
        // irreducibly-real-valued operations (sqrt, pow, trig, log/exp).
        // All pure. Division by zero produces +/- Infinity per IEEE 754
        // (no exception thrown — matches Kotlin Double semantics).
        // Comparison operators use IEEE 754 ordering: NaN compared to
        // anything (including itself) is false for Lt/Le/Gt/Ge and false
        // for Eq. Agents that need NaN-aware checks should use
        // Math-style sentinels or wrap.

        "strand-builtin:Float.Add" to det { args ->
            require(args.size == 2) { "Float.Add expects 2 args, got ${args.size}" }
            Value.FloatV((args[0] as Value.FloatV).v + (args[1] as Value.FloatV).v)
        },
        "strand-builtin:Float.Sub" to det { args ->
            require(args.size == 2) { "Float.Sub expects 2 args, got ${args.size}" }
            Value.FloatV((args[0] as Value.FloatV).v - (args[1] as Value.FloatV).v)
        },
        "strand-builtin:Float.Mul" to det { args ->
            require(args.size == 2) { "Float.Mul expects 2 args, got ${args.size}" }
            Value.FloatV((args[0] as Value.FloatV).v * (args[1] as Value.FloatV).v)
        },
        "strand-builtin:Float.Div" to det { args ->
            require(args.size == 2) { "Float.Div expects 2 args, got ${args.size}" }
            Value.FloatV((args[0] as Value.FloatV).v / (args[1] as Value.FloatV).v)
        },
        "strand-builtin:Float.Neg" to det { args ->
            require(args.size == 1) { "Float.Neg expects 1 arg, got ${args.size}" }
            Value.FloatV(-(args[0] as Value.FloatV).v)
        },
        "strand-builtin:Float.Eq" to det { args ->
            require(args.size == 2) { "Float.Eq expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.FloatV).v == (args[1] as Value.FloatV).v)
        },
        "strand-builtin:Float.Lt" to det { args ->
            require(args.size == 2) { "Float.Lt expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.FloatV).v < (args[1] as Value.FloatV).v)
        },
        "strand-builtin:Float.Le" to det { args ->
            require(args.size == 2) { "Float.Le expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.FloatV).v <= (args[1] as Value.FloatV).v)
        },
        "strand-builtin:Float.Gt" to det { args ->
            require(args.size == 2) { "Float.Gt expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.FloatV).v > (args[1] as Value.FloatV).v)
        },
        "strand-builtin:Float.Ge" to det { args ->
            require(args.size == 2) { "Float.Ge expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.FloatV).v >= (args[1] as Value.FloatV).v)
        },

        // Stdlib expansion round 2 — Hash.* builtins. Pure. All take
        // Bytes and return Bytes; programs that want hex output
        // compose with Bytes.FormatHex. Blake3 uses the same library
        // and prefix-free output as the project's content-addressing
        // hasher; Sha256 and Md5 use java.security.MessageDigest.

        "strand-builtin:Hash.Blake3" to det { args ->
            // (b: Bytes) -> Bytes (32-byte BLAKE3 digest, no multi-hash prefix)
            require(args.size == 1) { "Hash.Blake3 expects 1 arg (b: Bytes), got ${args.size}" }
            val hasher = io.github.rctcwyvrn.blake3.Blake3.newInstance()
            hasher.update((args[0] as Value.BytesV).v)
            Value.BytesV(hasher.digest())
        },

        "strand-builtin:Hash.Sha256" to det { args ->
            // (b: Bytes) -> Bytes (32-byte SHA-256 digest)
            require(args.size == 1) { "Hash.Sha256 expects 1 arg (b: Bytes), got ${args.size}" }
            val md = java.security.MessageDigest.getInstance("SHA-256")
            Value.BytesV(md.digest((args[0] as Value.BytesV).v))
        },

        "strand-builtin:Hash.Md5" to det { args ->
            // (b: Bytes) -> Bytes (16-byte MD5 digest). Not cryptographically
            // secure — included for integrity/identity use cases where SHA-256
            // is overkill (cache keys, content fingerprinting on trusted input).
            require(args.size == 1) { "Hash.Md5 expects 1 arg (b: Bytes), got ${args.size}" }
            val md = java.security.MessageDigest.getInstance("MD5")
            Value.BytesV(md.digest((args[0] as Value.BytesV).v))
        },

        // Stdlib expansion round 2 — List.* primitives (no lambdas).
        // Walk and produce the canonical Cons(head, tail) / Nil SumV
        // encoding (corpus 31/32, also produced by Fs.List, String.Split,
        // Process.Spawn arg lists). Polymorphic in the element type —
        // the runtime never inspects head values, so any Value type
        // works for the head. Empty-list construction is convenient
        // because Strand has no literal syntax for it (otherwise an
        // agent emits a 3-node Sum-construct boilerplate).
        //
        // Higher-order operations (Map, Filter, Fold, Find, Any, All)
        // are deferred to round 3, which needs the interpreter-callback
        // infrastructure for builtins to invoke Strand lambdas.

        "strand-builtin:List.Empty" to det { args ->
            require(args.isEmpty()) { "List.Empty expects 0 args, got ${args.size}" }
            Value.SumV("Nil", null)
        },

        "strand-builtin:List.IsEmpty" to det { args ->
            require(args.size == 1) { "List.IsEmpty expects 1 arg (list), got ${args.size}" }
            Value.BoolV(args[0] is Value.SumV && (args[0] as Value.SumV).case == "Nil")
        },

        "strand-builtin:List.Length" to det { args ->
            require(args.size == 1) { "List.Length expects 1 arg (list), got ${args.size}" }
            var n = 0L
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                n++
                cur = (sumV.payload as Value.ProductV).fields.getValue("tail")
            }
            Value.IntV(n)
        },

        "strand-builtin:List.Reverse" to det { args ->
            require(args.size == 1) { "List.Reverse expects 1 arg (list), got ${args.size}" }
            var result: Value = Value.SumV("Nil", null)
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val head = payload.fields.getValue("head")
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to head, "tail" to result)))
                cur = payload.fields.getValue("tail")
            }
            result
        },

        "strand-builtin:List.Take" to det { args ->
            // (list, n: Int) -> list. Negative or zero n yields Nil.
            // n larger than list length yields the whole list.
            require(args.size == 2) { "List.Take expects 2 args (list, n: Int), got ${args.size}" }
            val take = (args[1] as Value.IntV).v
            if (take <= 0) return@det Value.SumV("Nil", null)
            val heads = mutableListOf<Value>()
            var cur: Value = args[0]
            var remaining = take
            while (remaining > 0) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                heads += payload.fields.getValue("head")
                cur = payload.fields.getValue("tail")
                remaining--
            }
            var result: Value = Value.SumV("Nil", null)
            for (h in heads.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to result)))
            }
            result
        },

        "strand-builtin:List.Drop" to det { args ->
            // (list, n: Int) -> list. Negative or zero n yields the
            // original list. n larger than list length yields Nil.
            require(args.size == 2) { "List.Drop expects 2 args (list, n: Int), got ${args.size}" }
            var drop = (args[1] as Value.IntV).v
            var cur: Value = args[0]
            while (drop > 0) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                cur = (sumV.payload as Value.ProductV).fields.getValue("tail")
                drop--
            }
            cur
        },

        "strand-builtin:List.Concat" to det { args ->
            // (a, b) -> list. Append b to the end of a.
            require(args.size == 2) { "List.Concat expects 2 args (a, b), got ${args.size}" }
            val heads = mutableListOf<Value>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                heads += payload.fields.getValue("head")
                cur = payload.fields.getValue("tail")
            }
            var result: Value = args[1]
            for (h in heads.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to result)))
            }
            result
        },

        "strand-builtin:List.Nth" to det { args ->
            // (list, i: Int) -> Option<T>. Some(elem) for in-range
            // 0-based index, None otherwise.
            require(args.size == 2) { "List.Nth expects 2 args (list, i: Int), got ${args.size}" }
            var i = (args[1] as Value.IntV).v
            if (i < 0) return@det Value.SumV("None", null)
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                if (i == 0L) {
                    return@det Value.SumV("Some", payload.fields.getValue("head"))
                }
                cur = payload.fields.getValue("tail")
                i--
            }
            Value.SumV("None", null)
        },

        // Stdlib expansion round 4 — List structure ops + Int-specialized
        // reducers. All polymorphic (or Int-typed payload); none fit the
        // monomorphic prelude shape, so each requires an explicit FNT +
        // FRN at the use site. The reducers (Sum/Product/Min/Max) are
        // convenience wrappers around the Fold pattern; the structure
        // ops (Range/Zip/Unzip/Distinct) cover gaps in the round-2
        // primitives.

        "strand-builtin:List.Range" to det { args ->
            // (start: Int, end: Int) -> List<Int>
            // Inclusive start, exclusive end. Empty if start >= end.
            require(args.size == 2) { "List.Range expects 2 args (start, end: Int), got ${args.size}" }
            val start = (args[0] as Value.IntV).v
            val end = (args[1] as Value.IntV).v
            var result: Value = Value.SumV("Nil", null)
            var i = end - 1
            while (i >= start) {
                result = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to Value.IntV(i), "tail" to result,
                )))
                i--
            }
            result
        },

        "strand-builtin:List.Zip" to det { args ->
            // (a: List<A>, b: List<B>) -> List<{first: A, second: B}>
            // Stops at the shorter list's end.
            require(args.size == 2) { "List.Zip expects 2 args (a, b), got ${args.size}" }
            val pairs = mutableListOf<Value>()
            var ca: Value = args[0]
            var cb: Value = args[1]
            while (true) {
                val sa = ca as? Value.SumV ?: break
                val sb = cb as? Value.SumV ?: break
                if (sa.case != "Cons" || sb.case != "Cons") break
                val pa = sa.payload as Value.ProductV
                val pb = sb.payload as Value.ProductV
                pairs += Value.ProductV(mapOf(
                    "first" to pa.fields.getValue("head"),
                    "second" to pb.fields.getValue("head"),
                ))
                ca = pa.fields.getValue("tail")
                cb = pb.fields.getValue("tail")
            }
            var result: Value = Value.SumV("Nil", null)
            for (p in pairs.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to p, "tail" to result)))
            }
            result
        },

        "strand-builtin:List.Unzip" to det { args ->
            // (pairs: List<{first, second}>) -> {first: List<A>, second: List<B>}
            // Inverse of List.Zip.
            require(args.size == 1) { "List.Unzip expects 1 arg (pairs), got ${args.size}" }
            val firsts = mutableListOf<Value>()
            val seconds = mutableListOf<Value>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val pair = payload.fields.getValue("head") as Value.ProductV
                firsts += pair.fields.getValue("first")
                seconds += pair.fields.getValue("second")
                cur = payload.fields.getValue("tail")
            }
            var firstList: Value = Value.SumV("Nil", null)
            for (h in firsts.reversed()) {
                firstList = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to firstList)))
            }
            var secondList: Value = Value.SumV("Nil", null)
            for (h in seconds.reversed()) {
                secondList = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to secondList)))
            }
            Value.ProductV(mapOf("first" to firstList, "second" to secondList))
        },

        "strand-builtin:List.Distinct" to det { args ->
            // (list: List<A>) -> List<A>
            // Preserves first occurrence; uses Value structural equality
            // (Kotlin data class equals walks the structure).
            require(args.size == 1) { "List.Distinct expects 1 arg (list), got ${args.size}" }
            val seen = linkedSetOf<Value>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                seen += payload.fields.getValue("head")
                cur = payload.fields.getValue("tail")
            }
            var result: Value = Value.SumV("Nil", null)
            for (h in seen.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to result)))
            }
            result
        },

        "strand-builtin:List.Sum" to det { args ->
            // (list: List<Int>) -> Int. Empty list returns 0.
            require(args.size == 1) { "List.Sum expects 1 arg (list: List<Int>), got ${args.size}" }
            var sum = 0L
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                sum += (payload.fields.getValue("head") as Value.IntV).v
                cur = payload.fields.getValue("tail")
            }
            Value.IntV(sum)
        },

        "strand-builtin:List.Product" to det { args ->
            // (list: List<Int>) -> Int. Empty list returns 1.
            require(args.size == 1) { "List.Product expects 1 arg (list: List<Int>), got ${args.size}" }
            var product = 1L
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                product *= (payload.fields.getValue("head") as Value.IntV).v
                cur = payload.fields.getValue("tail")
            }
            Value.IntV(product)
        },

        "strand-builtin:List.Min" to det { args ->
            // (list: List<Int>) -> Option<Int>. None for empty list.
            require(args.size == 1) { "List.Min expects 1 arg (list: List<Int>), got ${args.size}" }
            var minOpt: Long? = null
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val n = (payload.fields.getValue("head") as Value.IntV).v
                if (minOpt == null || n < minOpt) minOpt = n
                cur = payload.fields.getValue("tail")
            }
            if (minOpt == null) Value.SumV("None", null)
            else Value.SumV("Some", Value.IntV(minOpt))
        },

        "strand-builtin:List.Max" to det { args ->
            // (list: List<Int>) -> Option<Int>. None for empty list.
            require(args.size == 1) { "List.Max expects 1 arg (list: List<Int>), got ${args.size}" }
            var maxOpt: Long? = null
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val n = (payload.fields.getValue("head") as Value.IntV).v
                if (maxOpt == null || n > maxOpt) maxOpt = n
                cur = payload.fields.getValue("tail")
            }
            if (maxOpt == null) Value.SumV("None", null)
            else Value.SumV("Some", Value.IntV(maxOpt))
        },

        // Stdlib expansion round 2 — Json.Stringify. Inverse of
        // Json.Parse, handling all six JsonValue cases (the four
        // primitives plus JsonArray and JsonObject from the Slice-3
        // nested-μ JsonValue). Recurses through Cons/Nil chains
        // inside array/object payloads.

        "strand-builtin:Json.Stringify" to det { args ->
            require(args.size == 1) { "Json.Stringify expects 1 arg (json: JsonValue), got ${args.size}" }
            Value.StringV(jsonValueToText(args[0] as Value.SumV))
        },

        // Stdlib expansion round 2 — Bytes hex codecs (Phase 4 #2).
        // Mirrors Bytes.FormatBase64 / Bytes.ParseBase64. Lowercase
        // hex on output; case-insensitive on input. Round-trips byte
        // arrays of any length.

        "strand-builtin:Bytes.FormatHex" to det { args ->
            require(args.size == 1) { "Bytes.FormatHex expects 1 arg (b: Bytes), got ${args.size}" }
            val bytes = (args[0] as Value.BytesV).v
            Value.StringV(bytes.joinToString("") { "%02x".format(it) })
        },

        "strand-builtin:Bytes.ParseHex" to det { args ->
            // (s: String) -> Option<Bytes>. None on odd length or non-hex.
            require(args.size == 1) { "Bytes.ParseHex expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            if (s.length % 2 != 0) return@det Value.SumV("None", null)
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(s[i * 2], 16)
                val lo = Character.digit(s[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return@det Value.SumV("None", null)
                out[i] = ((hi shl 4) or lo).toByte()
            }
            Value.SumV("Some", Value.BytesV(out))
        },

        // Stdlib expansion round 2 — Random.* builtins (Phase 5).
        // Effectful: declare E-024 Crypto.RandomBytes at the call
        // site. Reads from the active [random] (default SecureRandom).
        // Tests install a seeded java.util.Random for reproducibility.

        "strand-builtin:Random.Int" to nondet { args ->
            // (min: Int, max: Int) -> Int. Inclusive min, exclusive max.
            // Returns a uniformly-distributed Long in [min, max).
            require(args.size == 2) {
                "Random.Int expects 2 args (min: Int, max: Int), got ${args.size}"
            }
            val min = (args[0] as Value.IntV).v
            val max = (args[1] as Value.IntV).v
            require(max > min) { "Random.Int requires max > min, got min=$min max=$max" }
            val range = max - min
            val sample = if (range <= Int.MAX_VALUE.toLong()) {
                random.nextInt(range.toInt()).toLong()
            } else {
                // 64-bit range: use nextLong and modulo. Slight bias
                // for non-power-of-2 ranges but acceptable for any
                // realistic application range.
                val raw = random.nextLong()
                val unbiased = if (raw == Long.MIN_VALUE) 0L else kotlin.math.abs(raw)
                unbiased % range
            }
            Value.IntV(min + sample)
        },

        "strand-builtin:Random.Float" to nondet { args ->
            // () -> Float. Uniformly distributed in [0.0, 1.0).
            require(args.isEmpty()) { "Random.Float expects 0 args, got ${args.size}" }
            Value.FloatV(random.nextDouble())
        },

        "strand-builtin:Random.Bytes" to nondet { args ->
            // (n: Int) -> Bytes. Exactly n random bytes.
            require(args.size == 1) { "Random.Bytes expects 1 arg (n: Int), got ${args.size}" }
            val n = (args[0] as Value.IntV).v.toInt()
            require(n >= 0) { "Random.Bytes n must be non-negative, got $n" }
            val out = ByteArray(n)
            random.nextBytes(out)
            Value.BytesV(out)
        },

        // Stdlib expansion round 3 phase 4 — HTTP server. Synchronous
        // accept/respond pattern (mirrors the Net.Connect / Send /
        // Receive / Close sync sockets above). Backed by the JDK's
        // com.sun.net.httpserver.HttpServer.
        //
        // Lifecycle: Http.Listen starts a server bound to a port and
        // returns a server handle (Int via ResourceTable, kind
        // "http-server"). Http.Accept blocks until the next request
        // arrives, returns a ProductV {method, path, body, responder}
        // where `responder` is a separate Int handle to the pending
        // exchange (kind "http-pending"). Http.Respond writes the
        // status + body and releases the handler thread.
        // Http.ServerClose tears down the server and frees the port.
        //
        // Effect categories: Listen -> E-002 Network.Listen;
        // Accept -> E-004 Network.Receive; Respond -> E-003 Network.Send.
        //
        // Implementation note: the JDK HttpServer dispatches each
        // request on its own thread. We coordinate via a per-server
        // LinkedBlockingQueue of pending exchanges + a per-exchange
        // CountDownLatch the handler thread waits on until Respond
        // counts it down. The Strand-side loop sees a simple
        // sequential accept/respond protocol; concurrency is the
        // host's problem.

        "strand-builtin:Http.Listen" to fx { args ->
            // (port: Int) -> serverHandle (Int)
            require(args.size == 1) { "Http.Listen expects 1 arg (port: Int), got ${args.size}" }
            val port = (args[0] as Value.IntV).v.toInt()
            try {
                val server = com.sun.net.httpserver.HttpServer.create(
                    java.net.InetSocketAddress(port), 0,
                )
                val queue = java.util.concurrent.LinkedBlockingQueue<HttpPending>()
                server.createContext("/") { exchange ->
                    val latch = java.util.concurrent.CountDownLatch(1)
                    queue.put(HttpPending(exchange, latch))
                    // Block the handler thread until Respond releases.
                    // 30s safety timeout so an unresponsive Strand
                    // program doesn't hang the server thread forever;
                    // Respond is the normal release path.
                    latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                }
                server.executor = java.util.concurrent.Executors.newCachedThreadPool()
                server.start()
                ResourceTable.register("http-server", HttpServerHolder(server, queue))
            } catch (e: java.io.IOException) {
                throw IoFailure("http-listen", "port $port: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("http-listen", "port $port: ${e.message}")
            }
        },

        "strand-builtin:Http.Accept" to fx { args ->
            // (server: serverHandle) -> {method, path, body, responder}
            // Blocks until a request arrives.
            require(args.size == 1) { "Http.Accept expects 1 arg (server: serverHandle), got ${args.size}" }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("http-accept", "expected Resource handle, got ${args[0]::class.simpleName}")
            val holder = ResourceTable.get(handle, "http-server") as HttpServerHolder
            try {
                val pending = holder.queue.take()  // blocks
                val exchange = pending.exchange
                val method = exchange.requestMethod
                val path = exchange.requestURI.toString()
                val body = exchange.requestBody.readAllBytes()
                val responderHandle = ResourceTable.register("http-pending", pending)
                Value.ProductV(mapOf(
                    "method" to Value.StringV(method),
                    "path" to Value.StringV(path),
                    "body" to Value.BytesV(body),
                    "responder" to responderHandle,
                ))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IoFailure("http-accept", "interrupted waiting for request")
            } catch (e: java.io.IOException) {
                throw IoFailure("http-accept", e.message ?: "i/o error")
            }
        },

        "strand-builtin:Http.Respond" to fx { args ->
            // (responder: responderHandle, status: Int, body: Bytes) -> Unit
            // Writes the response and releases the handler thread.
            require(args.size == 3) {
                "Http.Respond expects 3 args (responder: responderHandle, status: Int, body: Bytes), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("http-respond", "expected Resource handle, got ${args[0]::class.simpleName}")
            val status = (args[1] as Value.IntV).v.toInt()
            val body = (args[2] as Value.BytesV).v
            val pending = ResourceTable.get(handle, "http-pending") as HttpPending
            try {
                val exchange = pending.exchange
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                pending.latch.countDown()
                ResourceTable.remove(handle)  // one-shot: free the responder
                Value.UnitV
            } catch (e: java.io.IOException) {
                pending.latch.countDown()  // release the handler thread even on error
                ResourceTable.remove(handle)
                throw IoFailure("http-respond", e.message ?: "i/o error")
            }
        },

        "strand-builtin:Http.ServerClose" to det { args ->
            // (server: serverHandle) -> Unit. Idempotent.
            require(args.size == 1) { "Http.ServerClose expects 1 arg (server: serverHandle), got ${args.size}" }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("http-server-close", "expected Resource handle, got ${args[0]::class.simpleName}")
            val obj = ResourceTable.remove(handle)
            if (obj is HttpServerHolder) {
                try { obj.server.stop(0) } catch (_: Exception) { /* idempotent */ }
            }
            Value.UnitV
        },

        // Stdlib expansion round 3 phase 3 — Map.* (opaque-handle
        // persistent map). Backed by kotlinx.collections.immutable's
        // PersistentMap<Value, Value>. Polymorphic in key + value
        // types from the agent's perspective; the runtime walks Value
        // equality structurally (Value's data-class equals).
        //
        // **Surface-type caveat.** Agents declare Map arguments and
        // results with `bytesT` as the placeholder surface type —
        // this matches the opaque-handle pattern Resource uses for
        // sockets / processes. Type-checking can't distinguish Map
        // from Bytes today; the runtime checks Value.MapV at dispatch
        // and throws ClassCastException if the wrong value flows in.
        // A future node-algebra extension can add a real parametric
        // Map<K, V> primitive type.
        //
        // All operations are pure: PersistentMap's put/remove return
        // new instances via path-copy. No effect category required.

        "strand-builtin:Map.Empty" to det { args ->
            require(args.isEmpty()) { "Map.Empty expects 0 args, got ${args.size}" }
            Value.MapV(kotlinx.collections.immutable.persistentMapOf())
        },

        "strand-builtin:Map.Get" to det { args ->
            // (map, key) -> Option<V>. Some(v) on hit, None on miss.
            require(args.size == 2) { "Map.Get expects 2 args (map, key), got ${args.size}" }
            val map = (args[0] as Value.MapV).entries
            val v = map[args[1]]
            if (v != null) Value.SumV("Some", v) else Value.SumV("None", null)
        },

        "strand-builtin:Map.Put" to det { args ->
            // (map, key, value) -> Map. Replaces the prior binding for
            // key if any; returns a new MapV (path-copy persistence).
            require(args.size == 3) { "Map.Put expects 3 args (map, key, value), got ${args.size}" }
            val map = (args[0] as Value.MapV).entries
            Value.MapV(map.put(args[1], args[2]))
        },

        "strand-builtin:Map.Remove" to det { args ->
            // (map, key) -> Map. No-op if key is absent.
            require(args.size == 2) { "Map.Remove expects 2 args (map, key), got ${args.size}" }
            val map = (args[0] as Value.MapV).entries
            Value.MapV(map.remove(args[1]))
        },

        "strand-builtin:Map.Has" to det { args ->
            // (map, key) -> Bool.
            require(args.size == 2) { "Map.Has expects 2 args (map, key), got ${args.size}" }
            Value.BoolV((args[0] as Value.MapV).entries.containsKey(args[1]))
        },

        "strand-builtin:Map.Size" to det { args ->
            // (map) -> Int.
            require(args.size == 1) { "Map.Size expects 1 arg (map), got ${args.size}" }
            Value.IntV((args[0] as Value.MapV).entries.size.toLong())
        },

        "strand-builtin:Map.Keys" to det { args ->
            // (map) -> List<K>. Order is insertion-order under
            // PersistentMap (deterministic for replay).
            require(args.size == 1) { "Map.Keys expects 1 arg (map), got ${args.size}" }
            val map = (args[0] as Value.MapV).entries
            var listValue: Value = Value.SumV("Nil", null)
            for (key in map.keys.reversed()) {
                listValue = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to key, "tail" to listValue,
                )))
            }
            listValue
        },

        "strand-builtin:Map.Values" to det { args ->
            // (map) -> List<V>. Order matches Map.Keys.
            require(args.size == 1) { "Map.Values expects 1 arg (map), got ${args.size}" }
            val map = (args[0] as Value.MapV).entries
            var listValue: Value = Value.SumV("Nil", null)
            for (v in map.values.reversed()) {
                listValue = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to v, "tail" to listValue,
                )))
            }
            listValue
        },

        "strand-builtin:Map.Entries" to det { args ->
            // (map) -> List<{key, value}>. The canonical serializable
            // form — round-trips back to a Map via repeated Map.Put.
            require(args.size == 1) { "Map.Entries expects 1 arg (map), got ${args.size}" }
            val map = (args[0] as Value.MapV).entries
            var listValue: Value = Value.SumV("Nil", null)
            for ((k, v) in map.entries.reversed()) {
                val entry = Value.ProductV(mapOf("key" to k, "value" to v))
                listValue = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to entry, "tail" to listValue,
                )))
            }
            listValue
        },

        // Stdlib expansion round 3 phase 2 — Regex.*. Uses Kotlin's
        // kotlin.text.Regex (java.util.regex.Pattern under the hood).
        // All pure. Pattern-compile failures throw IoFailure with
        // kind "regex-compile" so agents see a structured error;
        // syntactically-valid patterns that fail to match return
        // None (for Match/Find) or the original input (for Replace).
        //
        // Backtracking and POSIX character classes are supported; the
        // exact dialect is Java's standard one. \\, \d, \w, \s, [^...],
        // (?:...), ^, $, |, *, +, ?, {n,m}, anchors, and groups all
        // work. Named groups are not exposed in this slice (would need
        // a richer return type than Option<String>).

        "strand-builtin:Regex.Match" to det { args ->
            // (pattern: String, input: String) -> Option<String>
            // Returns the first full-match substring, or None if no match.
            require(args.size == 2) { "Regex.Match expects 2 args (pattern, input), got ${args.size}" }
            val pattern = (args[0] as Value.StringV).v
            val input = (args[1] as Value.StringV).v
            val regex = try { Regex(pattern) }
                catch (e: java.util.regex.PatternSyntaxException) {
                    throw IoFailure("regex-compile", "pattern '$pattern': ${e.description}")
                }
            val m = regex.find(input)
            if (m != null) Value.SumV("Some", Value.StringV(m.value))
            else Value.SumV("None", null)
        },

        "strand-builtin:Regex.FindAll" to det { args ->
            // (pattern: String, input: String) -> List<String>
            // Returns every non-overlapping match as a Cons/Nil chain.
            require(args.size == 2) { "Regex.FindAll expects 2 args (pattern, input), got ${args.size}" }
            val pattern = (args[0] as Value.StringV).v
            val input = (args[1] as Value.StringV).v
            val regex = try { Regex(pattern) }
                catch (e: java.util.regex.PatternSyntaxException) {
                    throw IoFailure("regex-compile", "pattern '$pattern': ${e.description}")
                }
            val matches = regex.findAll(input).map { it.value }.toList()
            var listValue: Value = Value.SumV("Nil", null)
            for (m in matches.reversed()) {
                listValue = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to Value.StringV(m), "tail" to listValue,
                )))
            }
            listValue
        },

        "strand-builtin:Regex.Replace" to det { args ->
            // (pattern: String, input: String, replacement: String) -> String
            // Replaces every non-overlapping match. The replacement
            // string supports $1/$2/etc. backreferences for capture
            // groups (java.util.regex semantics).
            require(args.size == 3) { "Regex.Replace expects 3 args (pattern, input, replacement), got ${args.size}" }
            val pattern = (args[0] as Value.StringV).v
            val input = (args[1] as Value.StringV).v
            val replacement = (args[2] as Value.StringV).v
            val regex = try { Regex(pattern) }
                catch (e: java.util.regex.PatternSyntaxException) {
                    throw IoFailure("regex-compile", "pattern '$pattern': ${e.description}")
                }
            Value.StringV(regex.replace(input, replacement))
        },

        "strand-builtin:Regex.Split" to det { args ->
            // (pattern: String, input: String) -> List<String>
            // Splits on every non-overlapping match. Adjacent matches
            // produce empty-string entries (matches Kotlin's split).
            require(args.size == 2) { "Regex.Split expects 2 args (pattern, input), got ${args.size}" }
            val pattern = (args[0] as Value.StringV).v
            val input = (args[1] as Value.StringV).v
            val regex = try { Regex(pattern) }
                catch (e: java.util.regex.PatternSyntaxException) {
                    throw IoFailure("regex-compile", "pattern '$pattern': ${e.description}")
                }
            val parts = regex.split(input)
            var listValue: Value = Value.SumV("Nil", null)
            for (part in parts.reversed()) {
                listValue = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to Value.StringV(part), "tail" to listValue,
                )))
            }
            listValue
        },

        // Stdlib expansion round 3 — Log.* / OS.* / System.Exit
        // diagnostic and host-environment builtins.
        // Effect categories E-032 Log.Write, E-033 OS.Read,
        // E-034 System.Exit per design/effects-and-capabilities.md.

        // Log.* writes to the host's log sink. Default sink is
        // System.err so log lines don't tangle with stdout-bound
        // program output; tests install a captured StringBuilder
        // via the injectable [logSink] for assertion.
        "strand-builtin:Log.Info" to fx { args ->
            require(args.size == 1) { "Log.Info expects 1 arg (msg: String), got ${args.size}" }
            logSink.println("[INFO] " + (args[0] as Value.StringV).v)
            Value.UnitV
        },
        "strand-builtin:Log.Warn" to fx { args ->
            require(args.size == 1) { "Log.Warn expects 1 arg (msg: String), got ${args.size}" }
            logSink.println("[WARN] " + (args[0] as Value.StringV).v)
            Value.UnitV
        },
        "strand-builtin:Log.Error" to fx { args ->
            require(args.size == 1) { "Log.Error expects 1 arg (msg: String), got ${args.size}" }
            logSink.println("[ERROR] " + (args[0] as Value.StringV).v)
            Value.UnitV
        },

        // OS.* observes stable host-environment state. All return
        // String; injectable [osEnv] lets tests pin values.
        "strand-builtin:OS.Hostname" to fx { args ->
            require(args.isEmpty()) { "OS.Hostname expects 0 args, got ${args.size}" }
            Value.StringV(osEnv.hostname())
        },
        "strand-builtin:OS.Platform" to fx { args ->
            require(args.isEmpty()) { "OS.Platform expects 0 args, got ${args.size}" }
            Value.StringV(osEnv.platform())
        },
        "strand-builtin:OS.Cwd" to fx { args ->
            require(args.isEmpty()) { "OS.Cwd expects 0 args, got ${args.size}" }
            Value.StringV(osEnv.cwd())
        },

        // System.Exit terminates the host process via
        // [exitHandler]. Default invokes kotlin.system.exitProcess;
        // tests install a captured handler that records the code
        // and throws SystemExitInvoked so the test framework sees it
        // without actually terminating the JVM.
        "strand-builtin:System.Exit" to fx { args ->
            require(args.size == 1) { "System.Exit expects 1 arg (code: Int), got ${args.size}" }
            val code = (args[0] as Value.IntV).v.toInt()
            exitHandler.exit(code)
            // Production handler never returns; the test handler throws.
            // If a custom handler returns normally we still produce UnitV
            // so the evaluator can continue (mirrors a no-op in tests
            // that don't want termination semantics).
            Value.UnitV
        },

        // Test-only no-op effectful builtin. Returns IntV(0) for any
        // single StringV argument. Used by tests that want to exercise
        // the effect-handler / capability machinery without touching
        // real IO. Not for production use. Target name reserved under
        // `strand-builtin:Test.*`.
        "strand-builtin:Test.EffectfulNoOp" to fx { args ->
            require(args.size == 1) { "Test.EffectfulNoOp expects 1 arg, got ${args.size}" }
            require(args[0] is Value.StringV) {
                "Test.EffectfulNoOp expects a StringV argument, got ${args[0]::class.simpleName}"
            }
            Value.IntV(0)
        },

        // Q-038 Phase 1 — Pinecone vector-store builtins. Each
        // ForeignNode entry declares effect category Vector.Read
        // (E-037) or Vector.Write (E-038) with provider pinned to
        // "pinecone" and store bound to the index name from the
        // open config. Operations target [PineconeProvider] which
        // talks to Pinecone's REST API via
        // [Builtins.vectorHttpTransport] (default JDK-backed;
        // tests inject [InMemoryHttpTransport] with canned
        // matchers).
        //
        // Open returns a Resource handle of kind "pinecone_index";
        // subsequent operations look up by handle. Close is
        // idempotent.

        "strand-builtin:Pinecone.Index.Open" to fx { args ->
            // (config: PineconeIndexConfig) -> pineconeHandle
            // Declares BOTH Vector.Read and Vector.Write (the
            // returned handle supports both directions).
            require(args.size == 1) {
                "Pinecone.Index.Open expects 1 arg (config: PineconeIndexConfig), got ${args.size}"
            }
            val config = VectorValueMarshal.toPineconeConfig(args[0])
            PineconeProvider.open(config, credentialProvider)
        },

        "strand-builtin:Pinecone.Index.Close" to det { args ->
            // (handle: pineconeHandle) -> Unit. Idempotent.
            require(args.size == 1) {
                "Pinecone.Index.Close expects 1 arg (handle: pineconeHandle), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("pinecone-close",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            PineconeProvider.close(handle)
        },

        "strand-builtin:Pinecone.Index.Upsert" to fx { args ->
            // (handle: pineconeHandle, items: List<UpsertItem>) -> Unit
            // Declares Vector.Write{provider: "pinecone", store: <index>}.
            require(args.size == 2) {
                "Pinecone.Index.Upsert expects 2 args (handle, items), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("pinecone-upsert",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            val items = VectorValueMarshal.toUpsertItems(args[1])
            PineconeProvider.upsert(handle, items, vectorHttpTransport)
            Value.UnitV
        },

        "strand-builtin:Pinecone.Index.Query" to fx { args ->
            // (handle: pineconeHandle, request: QueryRequest) -> List<QueryHit>
            // Declares Vector.Read{provider: "pinecone", store: <index>}.
            require(args.size == 2) {
                "Pinecone.Index.Query expects 2 args (handle, request), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("pinecone-query",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            val request = VectorValueMarshal.toQueryRequest(args[1])
            VectorValueMarshal.fromQueryHits(PineconeProvider.query(handle, request, vectorHttpTransport))
        },

        "strand-builtin:Pinecone.Index.Delete" to fx { args ->
            // (handle: pineconeHandle, ids: List<String>) -> Unit
            // Declares Vector.Write{provider: "pinecone", store: <index>}.
            require(args.size == 2) {
                "Pinecone.Index.Delete expects 2 args (handle, ids), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("pinecone-delete",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            val ids = VectorValueMarshal.toStringList(args[1])
            PineconeProvider.delete(handle, ids, vectorHttpTransport)
            Value.UnitV
        },

        "strand-builtin:Pinecone.Index.Fetch" to fx { args ->
            // (handle: pineconeHandle, ids: List<String>) -> List<QueryHit>
            // Declares Vector.Read{provider: "pinecone", store: <index>}.
            require(args.size == 2) {
                "Pinecone.Index.Fetch expects 2 args (handle, ids), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("pinecone-fetch",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            val ids = VectorValueMarshal.toStringList(args[1])
            VectorValueMarshal.fromQueryHits(PineconeProvider.fetch(handle, ids, vectorHttpTransport))
        },

        // Q-038 Phase 1 — Chroma vector-store builtins. Same shape
        // as Pinecone's, mapped to Chroma's REST API
        // (`/api/v1/collections/<id>/{upsert,query,get,delete}`).
        // Effect declarations use provider="chroma".

        "strand-builtin:Chroma.Collection.Open" to fx { args ->
            // (config: ChromaCollectionConfig) -> chromaHandle
            // Resolves the collection id via GET /api/v1/collections/<name>
            // at open time and caches it for subsequent operations.
            require(args.size == 1) {
                "Chroma.Collection.Open expects 1 arg (config: ChromaCollectionConfig), got ${args.size}"
            }
            val config = VectorValueMarshal.toChromaConfig(args[0])
            ChromaProvider.open(config, credentialProvider, vectorHttpTransport)
        },

        "strand-builtin:Chroma.Collection.Close" to det { args ->
            // (handle: chromaHandle) -> Unit. Idempotent.
            require(args.size == 1) {
                "Chroma.Collection.Close expects 1 arg (handle: chromaHandle), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("chroma-close",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            ChromaProvider.close(handle)
        },

        "strand-builtin:Chroma.Collection.Add" to fx { args ->
            // (handle: chromaHandle, items: List<UpsertItem>) -> Unit
            // Bulk upsert. Declares Vector.Write{provider: "chroma", store: <collection>}.
            require(args.size == 2) {
                "Chroma.Collection.Add expects 2 args (handle, items), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("chroma-upsert",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            val items = VectorValueMarshal.toUpsertItems(args[1])
            ChromaProvider.add(handle, items, vectorHttpTransport)
            Value.UnitV
        },

        "strand-builtin:Chroma.Collection.Query" to fx { args ->
            // (handle: chromaHandle, request: QueryRequest) -> List<QueryHit>
            // Declares Vector.Read{provider: "chroma", store: <collection>}.
            require(args.size == 2) {
                "Chroma.Collection.Query expects 2 args (handle, request), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("chroma-query",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            val request = VectorValueMarshal.toQueryRequest(args[1])
            VectorValueMarshal.fromQueryHits(ChromaProvider.query(handle, request, vectorHttpTransport))
        },

        "strand-builtin:Chroma.Collection.Delete" to fx { args ->
            // (handle: chromaHandle, ids: List<String>) -> Unit
            // Declares Vector.Write{provider: "chroma", store: <collection>}.
            require(args.size == 2) {
                "Chroma.Collection.Delete expects 2 args (handle, ids), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("chroma-delete",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            val ids = VectorValueMarshal.toStringList(args[1])
            ChromaProvider.delete(handle, ids, vectorHttpTransport)
            Value.UnitV
        },

        "strand-builtin:Chroma.Collection.Get" to fx { args ->
            // (handle: chromaHandle, ids: List<String>) -> List<QueryHit>
            // Id-based fetch (no similarity search).
            // Declares Vector.Read{provider: "chroma", store: <collection>}.
            require(args.size == 2) {
                "Chroma.Collection.Get expects 2 args (handle, ids), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("chroma-get",
                    "expected Resource handle, got ${args[0]::class.simpleName}")
            val ids = VectorValueMarshal.toStringList(args[1])
            VectorValueMarshal.fromQueryHits(ChromaProvider.get(handle, ids, vectorHttpTransport))
        },

        // ===== Stdlib expansion round 4 (2026-05-27) =====
        // Path.* — pure path-string manipulation (no filesystem access,
        // no effect category). Uses java.nio.file.Paths for separator
        // handling so behavior matches platform convention (forward
        // slashes on POSIX, backslashes on Windows). Lexical only —
        // Path.Normalize collapses . and .. tokens without consulting
        // the actual filesystem; resolving symlinks needs Fs.* under
        // capability.

        "strand-builtin:Path.Join" to det { args ->
            // (a: String, b: String) -> String. Joins two path
            // segments with the platform separator. If b is absolute,
            // it replaces a (matches Paths.get + resolve semantics).
            require(args.size == 2) { "Path.Join expects 2 args (a, b: String), got ${args.size}" }
            val a = (args[0] as Value.StringV).v
            val b = (args[1] as Value.StringV).v
            Value.StringV(java.nio.file.Paths.get(a).resolve(b).toString())
        },

        "strand-builtin:Path.Basename" to det { args ->
            // (path: String) -> String. Last component. Empty for "/"
            // or empty input (java.nio.file.Path.fileName returns null
            // for root paths; we coerce to "").
            require(args.size == 1) { "Path.Basename expects 1 arg (path: String), got ${args.size}" }
            val path = (args[0] as Value.StringV).v
            val name = java.nio.file.Paths.get(path).fileName?.toString() ?: ""
            Value.StringV(name)
        },

        "strand-builtin:Path.Dirname" to det { args ->
            // (path: String) -> String. Parent directory. Empty for
            // bare names (no separator). Mirrors POSIX dirname's
            // "no parent" behavior.
            require(args.size == 1) { "Path.Dirname expects 1 arg (path: String), got ${args.size}" }
            val path = (args[0] as Value.StringV).v
            val parent = java.nio.file.Paths.get(path).parent?.toString() ?: ""
            Value.StringV(parent)
        },

        "strand-builtin:Path.Extension" to det { args ->
            // (path: String) -> String. File extension without the
            // leading dot ("txt" for "foo.txt"). Empty for paths
            // without a dotted suffix in the last component. A leading
            // dot in the last component (".bashrc") is treated as no
            // extension (matches POSIX convention for hidden files).
            require(args.size == 1) { "Path.Extension expects 1 arg (path: String), got ${args.size}" }
            val name = java.nio.file.Paths.get((args[0] as Value.StringV).v)
                .fileName?.toString() ?: ""
            val dot = name.lastIndexOf('.')
            val ext = if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
            Value.StringV(ext)
        },

        "strand-builtin:Path.Normalize" to det { args ->
            // (path: String) -> String. Lexical normalization: collapses
            // . and .. segments, removes duplicate separators. Does NOT
            // resolve symlinks or check the filesystem.
            require(args.size == 1) { "Path.Normalize expects 1 arg (path: String), got ${args.size}" }
            val path = (args[0] as Value.StringV).v
            Value.StringV(java.nio.file.Paths.get(path).normalize().toString())
        },

        // ===== Stdlib expansion round 4 — DateTime.* =====
        // All pure (no clock access — they operate on Int millis the
        // caller provides, typically from Time.Now). UTC throughout;
        // local-time / timezone handling is a future slice if a real
        // workload needs it.
        //
        // FormatIso / ParseIso use ISO 8601 with millisecond precision
        // and a `Z` suffix (e.g., "2026-05-27T15:30:45.123Z"). The
        // *.Year/Month/Day/Hour/Minute/Second extractors return UTC
        // components: Year is the full year (2026), Month is 1-12, Day
        // is 1-31, Hour is 0-23, Minute is 0-59, Second is 0-59.
        // *.Add* arithmetic returns new Int millis.

        "strand-builtin:DateTime.FormatIso" to det { args ->
            // (millis: Int) -> String. ISO 8601 UTC, millisecond precision.
            require(args.size == 1) { "DateTime.FormatIso expects 1 arg (millis: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val instant = java.time.Instant.ofEpochMilli(millis)
            Value.StringV(java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant))
        },

        "strand-builtin:DateTime.ParseIso" to det { args ->
            // (s: String) -> Option<Int>. Some(millis) on success, None
            // on parse failure. Accepts any ISO 8601 instant the JVM
            // parser handles (with or without fractional seconds).
            require(args.size == 1) { "DateTime.ParseIso expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            try {
                val instant = java.time.Instant.parse(s)
                Value.SumV("Some", Value.IntV(instant.toEpochMilli()))
            } catch (_: java.time.format.DateTimeParseException) {
                Value.SumV("None", null)
            }
        },

        "strand-builtin:DateTime.Year" to det { args ->
            require(args.size == 1) { "DateTime.Year expects 1 arg (millis: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val zdt = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC,
            )
            Value.IntV(zdt.year.toLong())
        },
        "strand-builtin:DateTime.Month" to det { args ->
            // 1-12 (matches ISO 8601 / calendar convention, not Java's
            // 0-based Calendar.MONTH).
            require(args.size == 1) { "DateTime.Month expects 1 arg (millis: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val zdt = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC,
            )
            Value.IntV(zdt.monthValue.toLong())
        },
        "strand-builtin:DateTime.Day" to det { args ->
            // 1-31 (day-of-month).
            require(args.size == 1) { "DateTime.Day expects 1 arg (millis: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val zdt = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC,
            )
            Value.IntV(zdt.dayOfMonth.toLong())
        },
        "strand-builtin:DateTime.Hour" to det { args ->
            // 0-23.
            require(args.size == 1) { "DateTime.Hour expects 1 arg (millis: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val zdt = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC,
            )
            Value.IntV(zdt.hour.toLong())
        },
        "strand-builtin:DateTime.Minute" to det { args ->
            // 0-59.
            require(args.size == 1) { "DateTime.Minute expects 1 arg (millis: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val zdt = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC,
            )
            Value.IntV(zdt.minute.toLong())
        },
        "strand-builtin:DateTime.Second" to det { args ->
            // 0-59 (leap seconds clamp to 59 per java.time semantics).
            require(args.size == 1) { "DateTime.Second expects 1 arg (millis: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val zdt = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC,
            )
            Value.IntV(zdt.second.toLong())
        },

        "strand-builtin:DateTime.AddDays" to det { args ->
            // (millis: Int, days: Int) -> Int. Calendar-aware day
            // addition (handles month/year boundaries, leap days).
            require(args.size == 2) { "DateTime.AddDays expects 2 args (millis, days: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val days = (args[1] as Value.IntV).v
            val instant = java.time.Instant.ofEpochMilli(millis).plus(days, java.time.temporal.ChronoUnit.DAYS)
            Value.IntV(instant.toEpochMilli())
        },
        "strand-builtin:DateTime.AddHours" to det { args ->
            require(args.size == 2) { "DateTime.AddHours expects 2 args (millis, hours: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val hours = (args[1] as Value.IntV).v
            val instant = java.time.Instant.ofEpochMilli(millis).plus(hours, java.time.temporal.ChronoUnit.HOURS)
            Value.IntV(instant.toEpochMilli())
        },
        "strand-builtin:DateTime.AddMinutes" to det { args ->
            require(args.size == 2) { "DateTime.AddMinutes expects 2 args (millis, minutes: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val minutes = (args[1] as Value.IntV).v
            val instant = java.time.Instant.ofEpochMilli(millis).plus(minutes, java.time.temporal.ChronoUnit.MINUTES)
            Value.IntV(instant.toEpochMilli())
        },
        "strand-builtin:DateTime.AddSeconds" to det { args ->
            require(args.size == 2) { "DateTime.AddSeconds expects 2 args (millis, seconds: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            val seconds = (args[1] as Value.IntV).v
            val instant = java.time.Instant.ofEpochMilli(millis).plus(seconds, java.time.temporal.ChronoUnit.SECONDS)
            Value.IntV(instant.toEpochMilli())
        },

        // ===== Stdlib expansion round 5 — Set.* =====
        // Opaque persistent Set parallel to Round 3's Map.*. Backed by
        // kotlinx.collections.immutable.PersistentSet. Polymorphic in
        // element type from the agent's perspective; the runtime walks
        // Value equality structurally (Value's data-class equals).
        //
        // Surface-type pattern: agents declare Set arguments and results
        // with `bytesT` as the placeholder (mirrors the Map.* convention).
        // No prelude entries — the polymorphism is incompatible with
        // monomorphic FNTs. All operations are pure (path-copy
        // persistence), no effect category.
        //
        // Set.Fold is higher-order and lives in `higherOrderRegistry`
        // below.

        "strand-builtin:Set.Empty" to det { args ->
            require(args.isEmpty()) { "Set.Empty expects 0 args, got ${args.size}" }
            Value.SetV(kotlinx.collections.immutable.persistentSetOf())
        },

        "strand-builtin:Set.Add" to det { args ->
            // (set, val) -> Set. Idempotent — adding an existing
            // element returns an equal Set.
            require(args.size == 2) { "Set.Add expects 2 args (set, val), got ${args.size}" }
            val set = (args[0] as Value.SetV).entries
            Value.SetV(set.add(args[1]))
        },

        "strand-builtin:Set.Remove" to det { args ->
            // (set, val) -> Set. No-op if val is absent.
            require(args.size == 2) { "Set.Remove expects 2 args (set, val), got ${args.size}" }
            val set = (args[0] as Value.SetV).entries
            Value.SetV(set.remove(args[1]))
        },

        "strand-builtin:Set.Has" to det { args ->
            require(args.size == 2) { "Set.Has expects 2 args (set, val), got ${args.size}" }
            Value.BoolV((args[0] as Value.SetV).entries.contains(args[1]))
        },

        "strand-builtin:Set.Size" to det { args ->
            require(args.size == 1) { "Set.Size expects 1 arg (set), got ${args.size}" }
            Value.IntV((args[0] as Value.SetV).entries.size.toLong())
        },

        "strand-builtin:Set.Union" to det { args ->
            // (a, b) -> Set. All elements that appear in either.
            require(args.size == 2) { "Set.Union expects 2 args (a, b), got ${args.size}" }
            val a = (args[0] as Value.SetV).entries
            val b = (args[1] as Value.SetV).entries
            Value.SetV(a.addAll(b))
        },

        "strand-builtin:Set.Intersect" to det { args ->
            // (a, b) -> Set. Elements that appear in both.
            require(args.size == 2) { "Set.Intersect expects 2 args (a, b), got ${args.size}" }
            val a = (args[0] as Value.SetV).entries
            val b = (args[1] as Value.SetV).entries
            Value.SetV(a.retainAll(b))
        },

        "strand-builtin:Set.Difference" to det { args ->
            // (a, b) -> Set. Elements of a that are NOT in b.
            require(args.size == 2) { "Set.Difference expects 2 args (a, b), got ${args.size}" }
            val a = (args[0] as Value.SetV).entries
            val b = (args[1] as Value.SetV).entries
            Value.SetV(a.removeAll(b))
        },

        "strand-builtin:Set.ToList" to det { args ->
            // (set) -> List<T>. Insertion order (PersistentSet is
            // ordered-hash). Deterministic across runs for replay.
            require(args.size == 1) { "Set.ToList expects 1 arg (set), got ${args.size}" }
            val set = (args[0] as Value.SetV).entries
            var listValue: Value = Value.SumV("Nil", null)
            for (v in set.reversed()) {
                listValue = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to v, "tail" to listValue,
                )))
            }
            listValue
        },

        "strand-builtin:Set.FromList" to det { args ->
            // (list) -> Set. Duplicates collapse to single entries.
            // Insertion order matches the input list's first-occurrence
            // order.
            require(args.size == 1) { "Set.FromList expects 1 arg (list), got ${args.size}" }
            var set: kotlinx.collections.immutable.PersistentSet<Value> =
                kotlinx.collections.immutable.persistentSetOf()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                set = set.add(payload.fields.getValue("head"))
                cur = payload.fields.getValue("tail")
            }
            Value.SetV(set)
        },

        // ===== Stdlib expansion round 5 — CSV/TSV =====
        // Tabular parsing and stringification. Csv.* implements
        // RFC 4180 basic rules: comma-separated cells, double-quote
        // quoting, `""` as an escaped double quote inside a quoted
        // cell, CRLF and LF row separators. Tsv.* is simpler: tab-
        // separated cells, no quoting (typical TSV convention —
        // tabs and newlines inside cells are unsupported on input
        // and rejected/passed-through verbatim on output).
        //
        // Return / accept shape: `List<List<String>>` (the outer
        // list is rows, each row is a list of cells). NOT
        // preludable. Csv.* / Tsv.* never throw — malformed input
        // is parsed best-effort with the parser's recovery rules.

        "strand-builtin:Csv.Parse" to det { args ->
            // (s: String) -> List<List<String>>
            require(args.size == 1) { "Csv.Parse expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val rows = parseCsv(s, ',')
            buildRowList(rows)
        },

        "strand-builtin:Csv.Stringify" to det { args ->
            // (rows: List<List<String>>) -> String.
            // Quotes any cell containing `,`, `"`, or newline; doubles
            // embedded quotes per RFC 4180. Rows separated by CRLF
            // for maximum interoperability.
            require(args.size == 1) { "Csv.Stringify expects 1 arg (rows), got ${args.size}" }
            val rows = walkRowList(args[0])
            val out = StringBuilder()
            for ((i, row) in rows.withIndex()) {
                if (i > 0) out.append("\r\n")
                for ((j, cell) in row.withIndex()) {
                    if (j > 0) out.append(',')
                    val needsQuotes = cell.contains(',') || cell.contains('"') ||
                        cell.contains('\n') || cell.contains('\r')
                    if (needsQuotes) {
                        out.append('"').append(cell.replace("\"", "\"\"")).append('"')
                    } else {
                        out.append(cell)
                    }
                }
            }
            Value.StringV(out.toString())
        },

        "strand-builtin:Tsv.Parse" to det { args ->
            // (s: String) -> List<List<String>>. Tab cells; LF/CRLF
            // row separators. No quoting — tabs and newlines inside
            // cells are not supported by the TSV convention.
            require(args.size == 1) { "Tsv.Parse expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            // Strip a trailing newline so we don't produce a phantom
            // empty trailing row.
            val trimmed = if (s.endsWith("\r\n")) s.dropLast(2)
                else if (s.endsWith("\n")) s.dropLast(1)
                else s
            val rowStrings = if (trimmed.isEmpty()) emptyList<String>()
                else trimmed.split(Regex("\r?\n"))
            val rows = rowStrings.map { it.split('\t') }
            buildRowList(rows)
        },

        "strand-builtin:Tsv.Stringify" to det { args ->
            // (rows: List<List<String>>) -> String. Tab-separated
            // cells, LF row separators. Cells containing `\t` or
            // newline pass through verbatim (TSV has no escape
            // mechanism).
            require(args.size == 1) { "Tsv.Stringify expects 1 arg (rows), got ${args.size}" }
            val rows = walkRowList(args[0])
            Value.StringV(rows.joinToString("\n") { it.joinToString("\t") })
        },

        // ===== Stdlib expansion round 5 — Url.* =====
        // URL parsing + query-string codec via java.net.URI and
        // java.net.URLEncoder/URLDecoder.

        "strand-builtin:Url.Parse" to det { args ->
            // (s: String) -> Option<{scheme, host, port, path, query, fragment}>
            // Returns Some on a syntactically-valid URL with at least
            // a scheme; None otherwise. `port` is the explicit port
            // or -1 if absent. `host`, `path`, `query`, `fragment`
            // are empty strings when the URL omits them.
            require(args.size == 1) { "Url.Parse expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            try {
                val uri = java.net.URI(s)
                if (uri.scheme == null) return@det Value.SumV("None", null)
                val product = Value.ProductV(mapOf(
                    "scheme" to Value.StringV(uri.scheme ?: ""),
                    "host" to Value.StringV(uri.host ?: ""),
                    "port" to Value.IntV(uri.port.toLong()),
                    "path" to Value.StringV(uri.rawPath ?: ""),
                    "query" to Value.StringV(uri.rawQuery ?: ""),
                    "fragment" to Value.StringV(uri.rawFragment ?: ""),
                ))
                Value.SumV("Some", product)
            } catch (_: java.net.URISyntaxException) {
                Value.SumV("None", null)
            }
        },

        "strand-builtin:Url.QueryEncode" to det { args ->
            // (s: String) -> String. application/x-www-form-urlencoded
            // (RFC 3986 + form encoding — spaces become `+`).
            require(args.size == 1) { "Url.QueryEncode expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            Value.StringV(java.net.URLEncoder.encode(s, Charsets.UTF_8))
        },

        "strand-builtin:Url.QueryDecode" to det { args ->
            // (s: String) -> Option<String>. None on malformed
            // percent-encoding (URLDecoder throws IllegalArgumentException).
            require(args.size == 1) { "Url.QueryDecode expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            try {
                Value.SumV("Some", Value.StringV(java.net.URLDecoder.decode(s, Charsets.UTF_8)))
            } catch (_: IllegalArgumentException) {
                Value.SumV("None", null)
            }
        },

        // ===== Stdlib expansion round 5 — Compress.* =====
        // Gzip via java.util.zip (JDK-native, no extra dep). Zstd and
        // its inverse are deferred — supporting Zstd would require
        // adding the `com.github.luben:zstd-jni` dependency, which is
        // a load-bearing decision better left to a separate slice.

        "strand-builtin:Compress.Gzip" to det { args ->
            // (b: Bytes) -> Bytes. Default compression level.
            require(args.size == 1) { "Compress.Gzip expects 1 arg (b: Bytes), got ${args.size}" }
            val bytes = (args[0] as Value.BytesV).v
            val sink = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(sink).use { it.write(bytes) }
            Value.BytesV(sink.toByteArray())
        },

        "strand-builtin:Compress.Gunzip" to det { args ->
            // (b: Bytes) -> Option<Bytes>. None on malformed gzip
            // (truncated header / CRC mismatch / etc.).
            require(args.size == 1) { "Compress.Gunzip expects 1 arg (b: Bytes), got ${args.size}" }
            val bytes = (args[0] as Value.BytesV).v
            try {
                val out = java.io.ByteArrayInputStream(bytes).use { src ->
                    java.util.zip.GZIPInputStream(src).use { it.readBytes() }
                }
                Value.SumV("Some", Value.BytesV(out))
            } catch (_: java.util.zip.ZipException) {
                Value.SumV("None", null)
            } catch (_: java.io.IOException) {
                Value.SumV("None", null)
            }
        },
    ))

    /**
     * Higher-order builtins. Separate registry from the standard
     * [registry] so the dispatcher can branch on which lookup path
     * found the target. A given target name appears in at most one
     * registry — the lookups are checked in order (higher-order
     * first) by callers.
     *
     * Populated by Slice 2 of stdlib expansion round 2 (List.Map,
     * List.Filter, List.Fold, List.Find, List.Any, List.All).
     */
    private val higherOrderRegistry: Map<String, Entry<FnH>> = buildRegistry(mapOf(
        // Stdlib expansion round 2, Slice 2.2 — higher-order List ops.
        // Each takes the canonical Cons/Nil SumV-encoded list as the
        // first arg and a callable (Closure / FixpointFn / ForeignFn)
        // as the lambda. Lambda arity matches the operation
        // (Map/Filter/Find/Any/All take a 1-arg fn; Fold takes a 2-arg
        // fn over (accumulator, element)). The interpreter's
        // [ApplyFn] callback closes over the surrounding capability
        // context, so lambdas inherit the caller's effects.

        "strand-builtin:List.Map" to detH { args, apply ->
            // (list, fn: A -> B) -> List<B>
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

        "strand-builtin:List.Filter" to detH { args, apply ->
            // (list, predicate: A -> Bool) -> List<A>
            require(args.size == 2) { "List.Filter expects 2 args (list, predicate), got ${args.size}" }
            val pred = args[1]
            val kept = mutableListOf<Value>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val head = payload.fields.getValue("head")
                val include = apply.apply(pred, listOf(head)) as Value.BoolV
                if (include.v) kept += head
                cur = payload.fields.getValue("tail")
            }
            var result: Value = Value.SumV("Nil", null)
            for (h in kept.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to result)))
            }
            result
        },

        "strand-builtin:List.Fold" to detH { args, apply ->
            // (list, init, fn: (acc, elem) -> acc) -> acc
            require(args.size == 3) { "List.Fold expects 3 args (list, init, fn), got ${args.size}" }
            val fn = args[2]
            var acc: Value = args[1]
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val head = payload.fields.getValue("head")
                acc = apply.apply(fn, listOf(acc, head))
                cur = payload.fields.getValue("tail")
            }
            acc
        },

        "strand-builtin:List.Find" to detH { args, apply ->
            // (list, predicate: A -> Bool) -> Option<A>
            require(args.size == 2) { "List.Find expects 2 args (list, predicate), got ${args.size}" }
            val pred = args[1]
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val head = payload.fields.getValue("head")
                val match = apply.apply(pred, listOf(head)) as Value.BoolV
                if (match.v) return@detH Value.SumV("Some", head)
                cur = payload.fields.getValue("tail")
            }
            Value.SumV("None", null)
        },

        "strand-builtin:List.Any" to detH { args, apply ->
            // (list, predicate: A -> Bool) -> Bool. Short-circuits on first true.
            require(args.size == 2) { "List.Any expects 2 args (list, predicate), got ${args.size}" }
            val pred = args[1]
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                if ((apply.apply(pred, listOf(payload.fields.getValue("head"))) as Value.BoolV).v) {
                    return@detH Value.BoolV(true)
                }
                cur = payload.fields.getValue("tail")
            }
            Value.BoolV(false)
        },

        "strand-builtin:List.All" to detH { args, apply ->
            // (list, predicate: A -> Bool) -> Bool. Short-circuits on first false.
            require(args.size == 2) { "List.All expects 2 args (list, predicate), got ${args.size}" }
            val pred = args[1]
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                if (!(apply.apply(pred, listOf(payload.fields.getValue("head"))) as Value.BoolV).v) {
                    return@detH Value.BoolV(false)
                }
                cur = payload.fields.getValue("tail")
            }
            Value.BoolV(true)
        },

        // Stdlib expansion round 4 — List.Sort. Stable sort over the
        // Cons/Nil chain using a Bool comparator `(a, b) -> a < b`. The
        // Bool shape matches the existing predicate convention (List.Filter,
        // List.Any, List.All) — agents don't have to think in three-way
        // signed-Int comparator semantics, and the existing `lt` /
        // `Float.Lt` / `String.Eq`-style builtins can be passed directly.
        //
        // Internally: walks the input into a Kotlin MutableList, runs
        // sortWith using a Comparator that calls apply.apply, then
        // rebuilds Cons/Nil. The Java mergeSort under sortWith is
        // stable so equal elements retain their original order.
        "strand-builtin:List.Sort" to detH { args, apply ->
            require(args.size == 2) { "List.Sort expects 2 args (list, comparator), got ${args.size}" }
            val lessThan = args[1]
            val elements = mutableListOf<Value>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                elements += payload.fields.getValue("head")
                cur = payload.fields.getValue("tail")
            }
            elements.sortWith(Comparator { a, b ->
                val aLtB = (apply.apply(lessThan, listOf(a, b)) as Value.BoolV).v
                if (aLtB) -1 else {
                    val bLtA = (apply.apply(lessThan, listOf(b, a)) as Value.BoolV).v
                    if (bLtA) 1 else 0
                }
            })
            var result: Value = Value.SumV("Nil", null)
            for (e in elements.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to e, "tail" to result)))
            }
            result
        },

        // Stdlib expansion round 3 phase 3 — Map.Fold (higher-order).
        // The non-higher-order Map.* builtins are in the standard
        // registry above; Map.Fold lives here because it takes a
        // user-supplied 3-arg fn `(acc, key, value) -> acc`.
        "strand-builtin:Map.Fold" to detH { args, apply ->
            // (map, init, fn: (acc, key, value) -> acc) -> acc
            // Iterates entries in insertion order (deterministic).
            require(args.size == 3) { "Map.Fold expects 3 args (map, init, fn), got ${args.size}" }
            val map = (args[0] as Value.MapV).entries
            val fn = args[2]
            var acc: Value = args[1]
            for ((k, v) in map.entries) {
                acc = apply.apply(fn, listOf(acc, k, v))
            }
            acc
        },

        // ===== Stdlib expansion round 5 — Set.Fold + Map extensions =====

        "strand-builtin:Set.Fold" to detH { args, apply ->
            // (set, init, fn: (acc, elem) -> acc) -> acc.
            // Iterates entries in insertion order (deterministic).
            require(args.size == 3) { "Set.Fold expects 3 args (set, init, fn), got ${args.size}" }
            val set = (args[0] as Value.SetV).entries
            val fn = args[2]
            var acc: Value = args[1]
            for (elem in set) {
                acc = apply.apply(fn, listOf(acc, elem))
            }
            acc
        },

        "strand-builtin:Map.Map" to detH { args, apply ->
            // (map, fn: V -> W) -> Map<K, W>. Transforms each value
            // while preserving keys + insertion order.
            require(args.size == 2) { "Map.Map expects 2 args (map, fn), got ${args.size}" }
            val map = (args[0] as Value.MapV).entries
            val fn = args[1]
            var result: kotlinx.collections.immutable.PersistentMap<Value, Value> =
                kotlinx.collections.immutable.persistentMapOf()
            for ((k, v) in map.entries) {
                result = result.put(k, apply.apply(fn, listOf(v)))
            }
            Value.MapV(result)
        },

        "strand-builtin:Map.Merge" to detH { args, apply ->
            // (a: Map<K, V>, b: Map<K, V>, conflict: (V, V) -> V) -> Map<K, V>
            // Keys present only in a or only in b carry through unchanged;
            // keys in both invoke `conflict(a_value, b_value)` to pick the
            // merged value. Result iteration order: a's keys first (preserving
            // their order), then b's new keys (preserving their order among
            // themselves).
            require(args.size == 3) { "Map.Merge expects 3 args (a, b, conflict-fn), got ${args.size}" }
            val a = (args[0] as Value.MapV).entries
            val b = (args[1] as Value.MapV).entries
            val fn = args[2]
            var result: kotlinx.collections.immutable.PersistentMap<Value, Value> =
                kotlinx.collections.immutable.persistentMapOf()
            for ((k, av) in a.entries) {
                val bv = b[k]
                if (bv != null) {
                    result = result.put(k, apply.apply(fn, listOf(av, bv)))
                } else {
                    result = result.put(k, av)
                }
            }
            for ((k, bv) in b.entries) {
                if (!a.containsKey(k)) {
                    result = result.put(k, bv)
                }
            }
            Value.MapV(result)
        },

        "strand-builtin:Map.Filter" to detH { args, apply ->
            // (map, fn: (K, V) -> Bool) -> Map<K, V>. Keep only entries
            // where fn returns true. Order preserved.
            require(args.size == 2) { "Map.Filter expects 2 args (map, fn), got ${args.size}" }
            val map = (args[0] as Value.MapV).entries
            val fn = args[1]
            var result: kotlinx.collections.immutable.PersistentMap<Value, Value> =
                kotlinx.collections.immutable.persistentMapOf()
            for ((k, v) in map.entries) {
                val keep = (apply.apply(fn, listOf(k, v)) as Value.BoolV).v
                if (keep) {
                    result = result.put(k, v)
                }
            }
            Value.MapV(result)
        },

        // ===== Q-037 Phase 1 — agent-native LLM ForeignNodes =====
        // Per-provider Generate + Embed builtins under operation-shaped
        // E-035 LLM.Generate{provider, model} / E-036 LLM.Embed{provider,
        // model} effect categories. Live in the higher-order registry
        // because Generate runs the tool-use loop and must dispatch
        // agent-supplied tool callables via Interpreter.applyValueToArgs
        // (the ApplyFn passed to higher-order builtins).
        //
        // Request/result Strand shape (proposal § 3.3):
        //   GenerateRequest = {
        //     model: String, messages: List<Message>, system: Option<String>,
        //     maxTokens: Option<Int>, tools: List<ToolDef>,
        //     responseSchema: Option<JsonValue>, temperature: Option<Float>,
        //     providerExtras: Option<JsonValue>
        //   }
        // The agent constructs this as a Strand ProductV; the builtin
        // walks it into the unified [GenerateRequest], runs the per-
        // provider library plus tool loop, and converts the result back
        // to a ProductV. Conversion helpers live below as private
        // funs in this companion (productField / parseStrandMessages / ...).

        "strand-builtin:Anthropic.Messages.Create" to fxH { args, apply ->
            require(args.size == 1) {
                "Anthropic.Messages.Create expects 1 arg (GenerateRequest), got ${args.size}"
            }
            runGenerateLoop(args[0] as Value.ProductV, AnthropicProvider::generate, apply)
        },

        "strand-builtin:Anthropic.Embeddings.Create" to fxH { args, _ ->
            require(args.size == 1) {
                "Anthropic.Embeddings.Create expects 1 arg (EmbedRequest), got ${args.size}"
            }
            // Anthropic does not ship native embeddings — surfaces an
            // IoFailure routed through the interpreter so the agent's
            // feedback is structured. See AnthropicProvider.embed.
            try {
                val bytes = AnthropicProvider.embed(parseEmbedRequest(args[0] as Value.ProductV))
                Value.BytesV(bytes)
            } catch (io: IoFailure) {
                throw io
            }
        },

        "strand-builtin:OpenAI.Chat.Completions" to fxH { args, apply ->
            require(args.size == 1) {
                "OpenAI.Chat.Completions expects 1 arg (GenerateRequest), got ${args.size}"
            }
            runGenerateLoop(args[0] as Value.ProductV, OpenAIProvider::generate, apply)
        },

        "strand-builtin:OpenAI.Embeddings.Create" to fxH { args, _ ->
            require(args.size == 1) {
                "OpenAI.Embeddings.Create expects 1 arg (EmbedRequest), got ${args.size}"
            }
            Value.BytesV(OpenAIProvider.embed(
                parseEmbedRequest(args[0] as Value.ProductV),
                llmHttpClient,
                credentialProvider,
            ))
        },

        "strand-builtin:Gemini.GenerateContent" to fxH { args, apply ->
            require(args.size == 1) {
                "Gemini.GenerateContent expects 1 arg (GenerateRequest), got ${args.size}"
            }
            runGenerateLoop(args[0] as Value.ProductV, GeminiProvider::generate, apply)
        },

        "strand-builtin:Gemini.EmbedContent" to fxH { args, _ ->
            require(args.size == 1) {
                "Gemini.EmbedContent expects 1 arg (EmbedRequest), got ${args.size}"
            }
            Value.BytesV(GeminiProvider.embed(
                parseEmbedRequest(args[0] as Value.ProductV),
                llmHttpClient,
                credentialProvider,
            ))
        },
    ))

    /**
     * Convert a kotlinx-serialization [kotlinx.serialization.json.JsonElement]
     * into the corresponding Strand `JsonValue` SumV encoding. Returns null
     * if a number primitive cannot be represented as a Long (the current
     * JsonNumber payload type) — the caller wraps the result as
     * `Some(...)` on success or `None` otherwise.
     *
     * The output shape matches the post-Slice-3 nested-μ JsonValue
     * (corpus 66). The encoding uses **spliced variants** rather than
     * separate inner-μ types: arrays produce a chain of
     * `JsonArrayCons(head, tail)` cells terminated by `JsonArrayNil`,
     * with `head` and `tail` both directly typed as `JsonValue`
     * (depth-0 RecursiveSelf to the single enclosing μ). Objects use
     * `JsonObjectCons(key, value, tail)` and `JsonObjectNil`
     * analogously. This sidesteps the value-construction constraint
     * that nested μ-types fail (the inner RT can't be resolved
     * standalone) while still producing readable JSON-shaped values.
     */
    // ===== Stdlib expansion round 5 — CSV/TSV helpers =====

    /**
     * RFC 4180 basic parser: comma-separated cells, double-quote
     * quoting, `""` as an escaped double-quote inside a quoted cell,
     * CRLF and LF row separators. Trailing newlines do not produce
     * a phantom empty row. Empty input parses to an empty row list.
     */
    private fun parseCsv(s: String, delim: Char): List<List<String>> {
        if (s.isEmpty()) return emptyList()
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var i = 0
        var inQuotes = false
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        cell.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                } else {
                    cell.append(c)
                    i++
                }
            } else {
                when (c) {
                    '"' -> { inQuotes = true; i++ }
                    delim -> { row += cell.toString(); cell.clear(); i++ }
                    '\r' -> {
                        // Consume CRLF as one row separator; standalone CR
                        // is also accepted as a row separator (defensive).
                        row += cell.toString(); cell.clear()
                        rows += row; row = mutableListOf()
                        if (i + 1 < s.length && s[i + 1] == '\n') i += 2 else i++
                    }
                    '\n' -> {
                        row += cell.toString(); cell.clear()
                        rows += row; row = mutableListOf()
                        i++
                    }
                    else -> { cell.append(c); i++ }
                }
            }
        }
        // Flush the trailing cell + row only if the input did not end
        // with a row separator AND the trailing cell/row is non-empty
        // (avoid the phantom empty row a trailing newline would
        // produce). If the parser ended mid-quoted-cell, the partial
        // cell is still flushed — best-effort recovery.
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }

    /** Build a `List<List<String>>` SumV chain from a Kotlin row list. */
    private fun buildRowList(rows: List<List<String>>): Value {
        var outer: Value = Value.SumV("Nil", null)
        for (row in rows.reversed()) {
            var inner: Value = Value.SumV("Nil", null)
            for (cell in row.reversed()) {
                inner = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to Value.StringV(cell), "tail" to inner,
                )))
            }
            outer = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to inner, "tail" to outer,
            )))
        }
        return outer
    }

    /** Walk a `List<List<String>>` SumV chain back into Kotlin lists. */
    private fun walkRowList(v: Value): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var rowCur: Value = v
        while (rowCur is Value.SumV && rowCur.case == "Cons") {
            val rowPayload = rowCur.payload as Value.ProductV
            val row = mutableListOf<String>()
            var cellCur: Value = rowPayload.fields.getValue("head")
            while (cellCur is Value.SumV && cellCur.case == "Cons") {
                val cellPayload = cellCur.payload as Value.ProductV
                row += (cellPayload.fields.getValue("head") as Value.StringV).v
                cellCur = cellPayload.fields.getValue("tail")
            }
            rows += row
            rowCur = rowPayload.fields.getValue("tail")
        }
        return rows
    }

    private fun jsonElementToValue(element: kotlinx.serialization.json.JsonElement): Value? {
        return when (element) {
            is kotlinx.serialization.json.JsonNull ->
                Value.SumV("JsonNull", null)
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (element.isString) {
                    Value.SumV("JsonString", Value.StringV(element.content))
                } else {
                    val b = element.content.lowercase()
                    when {
                        b == "true" -> Value.SumV("JsonBool", Value.BoolV(true))
                        b == "false" -> Value.SumV("JsonBool", Value.BoolV(false))
                        else -> {
                            val asLong = element.content.toLongOrNull() ?: return null
                            Value.SumV("JsonNumber", Value.IntV(asLong))
                        }
                    }
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                var chain: Value = Value.SumV("JsonArrayNil", null)
                for (entry in element.reversed()) {
                    val converted = jsonElementToValue(entry) ?: return null
                    chain = Value.SumV("JsonArrayCons", Value.ProductV(mapOf(
                        "head" to converted, "tail" to chain,
                    )))
                }
                chain
            }
            is kotlinx.serialization.json.JsonObject -> {
                var chain: Value = Value.SumV("JsonObjectNil", null)
                for ((key, value) in element.entries.reversed()) {
                    val convertedValue = jsonElementToValue(value) ?: return null
                    chain = Value.SumV("JsonObjectCons", Value.ProductV(mapOf(
                        "key" to Value.StringV(key),
                        "value" to convertedValue,
                        "tail" to chain,
                    )))
                }
                chain
            }
        }
    }

    /**
     * Render a Strand `JsonValue` SumV back to canonical JSON text.
     * Inverse of [jsonElementToValue]. Recurses through Cons/Nil
     * chains inside JsonArray / JsonObject payloads.
     */
    private fun jsonValueToText(v: Value.SumV): String = when (v.case) {
        "JsonNull" -> "null"
        "JsonBool" -> if ((v.payload as Value.BoolV).v) "true" else "false"
        "JsonNumber" -> (v.payload as Value.IntV).v.toString()
        "JsonString" -> {
            val s = (v.payload as Value.StringV).v
            kotlinx.serialization.json.JsonPrimitive(s).toString()
        }
        "JsonArrayCons", "JsonArrayNil" -> {
            val out = StringBuilder("[")
            var first = true
            var cur: Value = v
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "JsonArrayCons") break
                val payload = sumV.payload as Value.ProductV
                if (!first) out.append(",")
                first = false
                out.append(jsonValueToText(payload.fields.getValue("head") as Value.SumV))
                cur = payload.fields.getValue("tail")
            }
            out.append("]")
            out.toString()
        }
        "JsonObjectCons", "JsonObjectNil" -> {
            val out = StringBuilder("{")
            var first = true
            var cur: Value = v
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "JsonObjectCons") break
                val payload = sumV.payload as Value.ProductV
                if (!first) out.append(",")
                first = false
                val key = (payload.fields.getValue("key") as Value.StringV).v
                out.append(kotlinx.serialization.json.JsonPrimitive(key).toString())
                out.append(":")
                out.append(jsonValueToText(payload.fields.getValue("value") as Value.SumV))
                cur = payload.fields.getValue("tail")
            }
            out.append("}")
            out.toString()
        }
        else -> "null"
    }

    // ====================================================================
    // Q-037 Phase 1 — Strand ↔ unified LLM shape conversion + tool loop
    // ====================================================================

    /**
     * Optional field accessor: returns the field value when it is
     * `Some(v)` (a Strand `Option<T>` SumV), otherwise null. The
     * provider builtins use this to honor opt-in fields like `system`,
     * `maxTokens`, `temperature` without forcing the agent to supply
     * every position.
     */
    private fun optField(product: Value.ProductV, fieldName: String): Value? {
        val v = product.fields[fieldName] ?: return null
        return when (v) {
            is Value.SumV -> if (v.case == "Some") v.payload else null
            else -> v  // tolerate non-Option-wrapped fields (treated as always-present)
        }
    }

    /**
     * Required field accessor: returns the field's Value, throwing
     * [IoFailure] if absent. Used for `model`, `messages`, `tools`
     * etc. that the proposal marks "required" in the request shape.
     */
    private fun reqField(product: Value.ProductV, fieldName: String, where: String): Value {
        return product.fields[fieldName]
            ?: throw IoFailure("$where-malformed", "missing required field '$fieldName'")
    }

    /**
     * Walk a Strand `List<Message>` (Cons/Nil SumV chain) into a
     * Kotlin list of unified [LlmMessage]. Each `Message` is a SumV
     * with cases `User(content: List<Block>) | Assistant(content:
     * List<Block>) | ToolResult(toolUseId: String, content: Bytes)`.
     */
    private fun parseStrandMessages(listValue: Value): List<LlmMessage> {
        val out = mutableListOf<LlmMessage>()
        var cur = listValue
        while (cur is Value.SumV && cur.case == "Cons") {
            val payload = cur.payload as Value.ProductV
            val msg = payload.fields.getValue("head") as Value.SumV
            out += when (msg.case) {
                "User" -> LlmMessage.User(parseStrandBlocks((msg.payload as Value.ProductV).fields.getValue("content")))
                "Assistant" -> LlmMessage.Assistant(parseStrandBlocks((msg.payload as Value.ProductV).fields.getValue("content")))
                "ToolResult" -> {
                    val p = msg.payload as Value.ProductV
                    LlmMessage.ToolResult(
                        toolUseId = (p.fields.getValue("toolUseId") as Value.StringV).v,
                        content = (p.fields.getValue("content") as Value.BytesV).v,
                    )
                }
                else -> throw IoFailure("llm-message-malformed", "unknown Message case '${msg.case}'")
            }
            cur = payload.fields.getValue("tail")
        }
        return out
    }

    /**
     * Walk a Strand `List<Block>` (Cons/Nil SumV chain) into a Kotlin
     * list of unified [LlmBlock]. Each `Block` is a SumV with cases
     * `Text(String) | ToolUse(id, name, input: JsonValue) |
     * Image(Bytes, mediaType: String) | Document(Bytes, mediaType: String)`.
     */
    private fun parseStrandBlocks(listValue: Value): List<LlmBlock> {
        val out = mutableListOf<LlmBlock>()
        var cur = listValue
        while (cur is Value.SumV && cur.case == "Cons") {
            val payload = cur.payload as Value.ProductV
            val block = payload.fields.getValue("head") as Value.SumV
            out += when (block.case) {
                "Text" -> LlmBlock.Text((block.payload as Value.StringV).v)
                "ToolUse" -> {
                    val p = block.payload as Value.ProductV
                    LlmBlock.ToolUse(
                        id = (p.fields.getValue("id") as Value.StringV).v,
                        name = (p.fields.getValue("name") as Value.StringV).v,
                        input = strandJsonValueToElement(p.fields.getValue("input") as Value.SumV),
                    )
                }
                "Image" -> {
                    val p = block.payload as Value.ProductV
                    LlmBlock.Image(
                        bytes = (p.fields.getValue("bytes") as Value.BytesV).v,
                        mediaType = (p.fields.getValue("mediaType") as Value.StringV).v,
                    )
                }
                "Document" -> {
                    val p = block.payload as Value.ProductV
                    LlmBlock.Document(
                        bytes = (p.fields.getValue("bytes") as Value.BytesV).v,
                        mediaType = (p.fields.getValue("mediaType") as Value.StringV).v,
                    )
                }
                else -> throw IoFailure("llm-block-malformed", "unknown Block case '${block.case}'")
            }
            cur = payload.fields.getValue("tail")
        }
        return out
    }

    /**
     * Walk a Strand `List<ToolDef>` (Cons/Nil SumV chain) into a Kotlin
     * list of unified [LlmToolDef]. Each list head is a
     * [Value.ToolDefV] — produced when the interpreter evaluates a
     * [Node.ToolDef] graph node (N-044). Each ToolDefV carries:
     *  - `name` and `description` (forwarded to provider's tool-def shape)
     *  - `parameterSchemaId` (the NodeId of the Schema whose valueType
     *    projects to JSON Schema via [verifierContext] + [nodeTypes])
     *  - `implementation` (the resolved Strand callable; Closure /
     *    ForeignFn / FixpointFn)
     *
     * The JSON Schema is computed at dispatch time by projecting the
     * Schema's TypeExpr.SchemaType.valueType via
     * [JsonSchemaProjection.project]. The verifier has already enforced
     * that the projection succeeds (else `ToolParamTypeUnsupported`
     * would have fired at admission), so we treat the static rejection
     * as defensive and fall back to an empty schema.
     */
    private fun HostContext.parseStrandTools(listValue: Value): List<LlmToolDef> {
        val out = mutableListOf<LlmToolDef>()
        var cur = listValue
        while (cur is Value.SumV && cur.case == "Cons") {
            val payload = cur.payload as Value.ProductV
            val head = payload.fields.getValue("head") as? Value.ToolDefV
                ?: throw IoFailure(
                    "tool-def-malformed",
                    "expected Value.ToolDefV in tools list head, got " +
                        "${payload.fields.getValue("head")::class.simpleName}. " +
                        "Tools must be authored via the N-044 ToolDef graph node " +
                        "(Layer A code TLD); the legacy pre-N-044 JsonValue-parameterSchema " +
                        "convention is no longer accepted."
                )
            out += toolDefFrom(head)
            cur = payload.fields.getValue("tail")
        }
        return out
    }

    /**
     * Convert a runtime [Value.ToolDefV] into the unified [LlmToolDef]
     * the provider libraries consume. Projects the Schema's valueType
     * to JSON Schema via [JsonSchemaProjection]; on a rejection (which
     * the verifier should have caught), falls back to an empty schema
     * so the loop can still proceed and the failure surfaces as a
     * provider-side rejection rather than a runtime crash.
     */
    private fun HostContext.toolDefFrom(t: Value.ToolDefV): LlmToolDef {
        // Look up the Schema's TypeExpr.SchemaType in the verifier's
        // nodeTypes map (carried by the Interpreter via verifierContext).
        // The SchemaChecker uses the same lookup; we treat the absence
        // of an entry as a defensive fallback rather than a hard error.
        val schemaType = verifierNodeTypes?.get(t.parameterSchemaId) as? org.strand.verifier.TypeExpr.SchemaType
        val jsonSchema: kotlinx.serialization.json.JsonElement = if (schemaType != null) {
            when (val r = org.strand.verifier.JsonSchemaProjection.project(schemaType.valueType)) {
                is org.strand.verifier.JsonSchemaProjection.Result.Success -> r.schema
                is org.strand.verifier.JsonSchemaProjection.Result.Rejected ->
                    kotlinx.serialization.json.JsonObject(emptyMap())  // defensive
            }
        } else {
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
        return LlmToolDef(
            name = t.name,
            description = t.description,
            parameterSchema = jsonSchema,
            implementation = t.implementation,
        )
    }

    /**
     * Convert a Strand JsonValue SumV (corpus 66 encoding) into a
     * kotlinx-serialization JsonElement. Mirrors [jsonElementToValue].
     */
    private fun strandJsonValueToElement(v: Value.SumV): kotlinx.serialization.json.JsonElement {
        return when (v.case) {
            "JsonNull" -> kotlinx.serialization.json.JsonNull
            "JsonBool" -> kotlinx.serialization.json.JsonPrimitive((v.payload as Value.BoolV).v)
            "JsonNumber" -> kotlinx.serialization.json.JsonPrimitive((v.payload as Value.IntV).v)
            "JsonString" -> kotlinx.serialization.json.JsonPrimitive((v.payload as Value.StringV).v)
            "JsonArrayCons", "JsonArrayNil" -> {
                val out = mutableListOf<kotlinx.serialization.json.JsonElement>()
                var cur: Value = v
                while (cur is Value.SumV && cur.case == "JsonArrayCons") {
                    val payload = cur.payload as Value.ProductV
                    out += strandJsonValueToElement(payload.fields.getValue("head") as Value.SumV)
                    cur = payload.fields.getValue("tail")
                }
                kotlinx.serialization.json.JsonArray(out)
            }
            "JsonObjectCons", "JsonObjectNil" -> {
                val out = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
                var cur: Value = v
                while (cur is Value.SumV && cur.case == "JsonObjectCons") {
                    val payload = cur.payload as Value.ProductV
                    val key = (payload.fields.getValue("key") as Value.StringV).v
                    out[key] = strandJsonValueToElement(payload.fields.getValue("value") as Value.SumV)
                    cur = payload.fields.getValue("tail")
                }
                kotlinx.serialization.json.JsonObject(out)
            }
            else -> kotlinx.serialization.json.JsonNull
        }
    }

    /**
     * Convert a kotlinx-serialization JsonElement to the Strand
     * JsonValue SumV (the inverse of [strandJsonValueToElement]).
     * Used to surface a model's tool-call `input` JSON as a Strand
     * JsonValue inside the returned block. Numbers that don't fit a
     * Long are represented as 0 with a JsonNull payload — agents that
     * need full numeric range should parse the raw bytes via
     * `Json.Parse` separately.
     */
    private fun jsonElementToStrand(el: kotlinx.serialization.json.JsonElement): Value.SumV {
        return when (el) {
            is kotlinx.serialization.json.JsonNull -> Value.SumV("JsonNull", null)
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (el.isString) Value.SumV("JsonString", Value.StringV(el.content))
                else when (el.content.lowercase()) {
                    "true" -> Value.SumV("JsonBool", Value.BoolV(true))
                    "false" -> Value.SumV("JsonBool", Value.BoolV(false))
                    else -> Value.SumV("JsonNumber", Value.IntV(el.content.toLongOrNull() ?: 0L))
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                var chain: Value = Value.SumV("JsonArrayNil", null)
                for (entry in el.reversed()) {
                    chain = Value.SumV("JsonArrayCons", Value.ProductV(mapOf(
                        "head" to jsonElementToStrand(entry),
                        "tail" to chain,
                    )))
                }
                chain as Value.SumV
            }
            is kotlinx.serialization.json.JsonObject -> {
                var chain: Value = Value.SumV("JsonObjectNil", null)
                for ((key, value) in el.entries.reversed()) {
                    chain = Value.SumV("JsonObjectCons", Value.ProductV(mapOf(
                        "key" to Value.StringV(key),
                        "value" to jsonElementToStrand(value),
                        "tail" to chain,
                    )))
                }
                chain as Value.SumV
            }
        }
    }

    /**
     * Parse a Strand GenerateRequest ProductV into the unified [GenerateRequest].
     * Mandatory: `model` (StringV), `messages` (Cons/Nil list).
     * Optional: `system`, `maxTokens`, `tools` (defaults to []),
     *           `responseSchema`, `temperature`, `providerExtras`.
     *
     * The `responseSchema` field, when present, carries an
     * [Value.ResponseSchemaSpecV] (N-045 wrapper) — produced when the
     * interpreter evaluates a [Node.ResponseSchemaSpec] graph node. The
     * runtime resolves the wrapper's `schemaId` through
     * [verifierNodeTypes] to obtain the [TypeExpr.SchemaType] and
     * projects via [JsonSchemaProjection] to a JSON Schema the provider
     * library forwards as the constrained-decoding contract. For
     * backward compatibility a `Value.SumV` payload in the JsonValue
     * tower convention is still accepted — older fixtures may still
     * supply the pre-N-045 shape; the legacy fallback is removed when
     * the next major version of the request shape is cut.
     */
    private fun HostContext.parseGenerateRequest(p: Value.ProductV): GenerateRequest {
        val model = (reqField(p, "model", "llm-generate") as Value.StringV).v
        val messages = parseStrandMessages(reqField(p, "messages", "llm-generate"))
        val system = (optField(p, "system") as? Value.StringV)?.v
        val maxTokens = (optField(p, "maxTokens") as? Value.IntV)?.v?.toInt()
        val temperature = (optField(p, "temperature") as? Value.FloatV)?.v
        val toolsList = p.fields["tools"] ?: Value.SumV("Nil", null)
        val tools = parseStrandTools(toolsList)
        val responseSchema = parseResponseSchemaField(optField(p, "responseSchema"))
        val providerExtras = (optField(p, "providerExtras") as? Value.SumV)?.let { strandJsonValueToElement(it) }
        return GenerateRequest(
            model = model,
            messages = messages,
            system = system,
            maxTokens = maxTokens,
            tools = tools,
            responseSchema = responseSchema,
            temperature = temperature,
            providerExtras = providerExtras,
        )
    }

    /**
     * Translate the `responseSchema` Option payload into the JSON
     * Schema element the provider library consumes. Accepts:
     *  - `null`: the Option was None (or the field was absent).
     *  - [Value.ResponseSchemaSpecV]: the N-045 graph-node-backed
     *    convention. The wrapper's `schemaId` is looked up in
     *    [verifierNodeTypes] to obtain the [TypeExpr.SchemaType], then
     *    projected to JSON Schema via [JsonSchemaProjection.project].
     *    The verifier has already enforced that the projection
     *    succeeds (else `ResponseSchemaTypeUnsupported` would have
     *    fired at admission), so we treat a static rejection as
     *    defensive and fall back to an empty schema.
     *  - [Value.SumV] in the JsonValue tower convention: backward-
     *    compatibility for fixtures that still emit the pre-N-045
     *    shape. Translated verbatim via [strandJsonValueToElement].
     *
     * Any other shape surfaces as a structured `IoFailure` so the
     * agent sees a clear diagnostic at the provider boundary rather
     * than a Kotlin-side cast exception.
     */
    private fun HostContext.parseResponseSchemaField(payload: Value?): kotlinx.serialization.json.JsonElement? {
        if (payload == null) return null
        val spec = payload as? Value.ResponseSchemaSpecV
            ?: throw IoFailure(
                "response-schema-malformed",
                "expected Value.ResponseSchemaSpecV in responseSchema, got " +
                    "${payload::class.simpleName}. responseSchema must be authored via " +
                    "the N-045 ResponseSchemaSpec graph node (Layer A code RSC); the " +
                    "legacy pre-N-045 JsonValue-tower convention is no longer accepted."
            )
        val schemaType = verifierNodeTypes?.get(spec.schemaId)
            as? org.strand.verifier.TypeExpr.SchemaType
            ?: return kotlinx.serialization.json.JsonObject(emptyMap())
        return when (val r = org.strand.verifier.JsonSchemaProjection.project(schemaType.valueType)) {
            is org.strand.verifier.JsonSchemaProjection.Result.Success -> r.schema
            is org.strand.verifier.JsonSchemaProjection.Result.Rejected ->
                kotlinx.serialization.json.JsonObject(emptyMap())  // defensive — verifier should have caught
        }
    }

    private fun parseEmbedRequest(p: Value.ProductV): EmbedRequest {
        val model = (reqField(p, "model", "llm-embed") as Value.StringV).v
        val text = (reqField(p, "text", "llm-embed") as Value.StringV).v
        val dimensions = (optField(p, "dimensions") as? Value.IntV)?.v?.toInt()
        return EmbedRequest(model = model, text = text, dimensions = dimensions)
    }

    /**
     * Convert one [LlmBlock] to a Strand `Block` SumV (Cons chain
     * cell head). Inverse of [parseStrandBlocks].
     */
    private fun blockToStrand(b: LlmBlock): Value.SumV = when (b) {
        is LlmBlock.Text -> Value.SumV("Text", Value.StringV(b.text))
        is LlmBlock.ToolUse -> Value.SumV("ToolUse", Value.ProductV(mapOf(
            "id" to Value.StringV(b.id),
            "name" to Value.StringV(b.name),
            "input" to jsonElementToStrand(b.input),
        )))
        is LlmBlock.Image -> Value.SumV("Image", Value.ProductV(mapOf(
            "bytes" to Value.BytesV(b.bytes),
            "mediaType" to Value.StringV(b.mediaType),
        )))
        is LlmBlock.Document -> Value.SumV("Document", Value.ProductV(mapOf(
            "bytes" to Value.BytesV(b.bytes),
            "mediaType" to Value.StringV(b.mediaType),
        )))
    }

    /** Encode a list of [LlmBlock]s as the Strand `List<Block>` Cons/Nil chain. */
    private fun blocksToStrand(blocks: List<LlmBlock>): Value {
        var chain: Value = Value.SumV("Nil", null)
        for (b in blocks.reversed()) {
            chain = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to blockToStrand(b),
                "tail" to chain,
            )))
        }
        return chain
    }

    /** Encode a list of [LlmMessage]s as the Strand `List<Message>` chain. */
    private fun messagesToStrand(messages: List<LlmMessage>): Value {
        var chain: Value = Value.SumV("Nil", null)
        for (m in messages.reversed()) {
            val head: Value = when (m) {
                is LlmMessage.User -> Value.SumV("User", Value.ProductV(mapOf(
                    "content" to blocksToStrand(m.content),
                )))
                is LlmMessage.Assistant -> Value.SumV("Assistant", Value.ProductV(mapOf(
                    "content" to blocksToStrand(m.content),
                )))
                is LlmMessage.ToolResult -> Value.SumV("ToolResult", Value.ProductV(mapOf(
                    "toolUseId" to Value.StringV(m.toolUseId),
                    "content" to Value.BytesV(m.content),
                )))
            }
            chain = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to head,
                "tail" to chain,
            )))
        }
        return chain
    }

    private fun stopReasonToStrand(s: StopReason): Value.SumV = Value.SumV(s.name, null)

    private fun usageToStrand(u: TokenUsage): Value.ProductV = Value.ProductV(mapOf(
        "inputTokens" to Value.IntV(u.inputTokens),
        "outputTokens" to Value.IntV(u.outputTokens),
        "cacheReadTokens" to Value.IntV(u.cacheReadTokens),
        "cacheWriteTokens" to Value.IntV(u.cacheWriteTokens),
    ))

    /**
     * Drive the tool-use loop for a per-provider Generate ForeignNode.
     *
     * Algorithm (proposal § 3.8 / § 6):
     *  1. Parse the Strand GenerateRequest ProductV into [GenerateRequest].
     *  2. Call the provider's `generate(req)`.
     *  3. Inspect the result blocks: if any are [LlmBlock.ToolUse],
     *     dispatch the named tool via [Builtins.ApplyFn] against the
     *     tool's `implementation` callable with the parsed input value.
     *  4. Append `ToolResult` messages to the conversation and call
     *     the provider again. Cap iterations at [toolLoopLimit].
     *  5. Return when no tool use is requested (or limit hit) — the
     *     final blocks, stopReason, usage, and the rewritten message
     *     list become the GenerateResult ProductV.
     *
     * Tool result encoding: the tool's returned [Value] is serialized
     * to UTF-8 bytes for the [LlmMessage.ToolResult]'s `content` field.
     * StringV / BytesV serialize directly; everything else routes
     * through `toString()`. The agent author can wrap a richer result
     * in their tool implementation.
     */
    private fun HostContext.runGenerateLoop(
        requestProduct: Value.ProductV,
        generate: (GenerateRequest, LlmHttpClient, CredentialProvider) -> GenerateResult,
        apply: ApplyFn,
    ): Value {
        var req = parseGenerateRequest(requestProduct)
        var loops = 0
        var lastResult: GenerateResult
        val workingMessages = req.messages.toMutableList()
        var lastBlocks: List<LlmBlock> = emptyList()
        var lastUsage = TokenUsage()
        var lastStop = StopReason.EndTurn
        while (true) {
            lastResult = generate(req, llmHttpClient, credentialProvider)
            lastBlocks = lastResult.content
            lastUsage = TokenUsage(
                inputTokens = lastUsage.inputTokens + lastResult.usage.inputTokens,
                outputTokens = lastUsage.outputTokens + lastResult.usage.outputTokens,
                cacheReadTokens = lastUsage.cacheReadTokens + lastResult.usage.cacheReadTokens,
                cacheWriteTokens = lastUsage.cacheWriteTokens + lastResult.usage.cacheWriteTokens,
            )
            lastStop = lastResult.stopReason
            val toolUses = lastBlocks.filterIsInstance<LlmBlock.ToolUse>()
            if (toolUses.isEmpty()) break
            // Append the assistant message that requested the tool uses.
            workingMessages += LlmMessage.Assistant(lastBlocks)
            for (tu in toolUses) {
                val tool = req.tools.find { it.name == tu.name }
                val toolResultBytes: ByteArray = if (tool == null) {
                    "tool '${tu.name}' not found".toByteArray(Charsets.UTF_8)
                } else {
                    // Convert the tool-use input JSON to a Strand
                    // JsonValue SumV; the implementation takes a single
                    // JsonValue parameter and returns a Value the loop
                    // serializes. Agent-author convention.
                    val inputAsStrand = jsonElementToStrand(tu.input)
                    val resultValue = apply.apply(tool.implementation, listOf(inputAsStrand))
                    valueToToolResultBytes(resultValue)
                }
                workingMessages += LlmMessage.ToolResult(tu.id, toolResultBytes)
            }
            loops++
            if (loops >= toolLoopLimit) {
                lastStop = StopReason.ToolUseLimit
                break
            }
            req = req.copy(messages = workingMessages.toList())
        }
        return Value.ProductV(mapOf(
            "content" to blocksToStrand(lastBlocks),
            "stopReason" to stopReasonToStrand(lastStop),
            "usage" to usageToStrand(lastUsage),
            "finalMessages" to messagesToStrand(workingMessages),
        ))
    }

    /**
     * Q-045: open a streaming LLM completion. Parses the Strand
     * GenerateRequest ProductV (reusing [parseGenerateRequest]), calls
     * the provider's stateless `generateStreamOpen` to issue the
     * streaming request and open the SSE response, then registers the
     * live stream under [ResourceTable.KIND_LLM_STREAM] and returns the
     * handle. The capability check (E-035) and sandbox net check run in
     * [Interpreter.applyForeign] before this body executes; the open is
     * the single point at which they happen — subsequent
     * `LLM.Stream.Receive` drains carry only the transport effect E-004.
     */
    private fun HostContext.openLlmStream(
        requestProduct: Value.ProductV,
        provider: String,
        open: (GenerateRequest, LlmHttpClient, CredentialProvider) -> LlmHttpClient.LlmStream,
    ): Value {
        val req = parseGenerateRequest(requestProduct)
        val stream = open(req, llmHttpClient, credentialProvider)
        return ResourceTable.register(
            ResourceTable.KIND_LLM_STREAM,
            LlmStreamHolder(provider, req.model, stream),
        )
    }

    /**
     * Serialize a tool-implementation result to the bytes the next
     * provider call carries as `ToolResult.content`. Common cases:
     * StringV → UTF-8 bytes; BytesV → identity; everything else →
     * value's `toString()` UTF-8 bytes. Agent authors who need a
     * richer encoding wrap the result in their implementation.
     */
    private fun valueToToolResultBytes(v: Value): ByteArray = when (v) {
        is Value.StringV -> v.v.toByteArray(Charsets.UTF_8)
        is Value.BytesV -> v.v
        else -> v.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Q-065 test-injection seam, mirroring the [clock] / [random] /
     * [sandboxPolicy] pattern: a transient overlay of extra registry
     * entries visible to [lookup] and [determinismOf]. Entries are
     * constructed directly (NOT through [resolveRegistration]), so a test
     * can force-mark an effect-free builtin [Determinism.Nondeterministic]
     * — the "first lock loosened" configuration the verifier's
     * `NondeterministicInReplayContext` warning exists to catch. Tests
     * that install entries must call [clearTestBuiltins] in teardown and
     * must not run in parallel with other registry-reading tests.
     */
    @Volatile
    private var testOverlay: Map<String, Entry<Fn>> = emptyMap()

    /** Install a test-only builtin into the overlay, bypassing the registration constraint. */
    fun installTestBuiltin(target: String, effectful: Boolean, determinism: Determinism, fn: Fn) {
        testOverlay = testOverlay + (target to Entry(fn, effectful, determinism))
    }

    /** Remove every overlay entry installed by [installTestBuiltin]. */
    fun clearTestBuiltins() {
        testOverlay = emptyMap()
    }

    /** Look up a builtin by its target identifier; null if unknown. */
    fun lookup(target: String): Fn? =
        testOverlay[target]?.fn ?: registry[target]?.fn

    /** Look up a higher-order builtin by target identifier; null if unknown. */
    fun lookupHigherOrder(target: String): FnH? = higherOrderRegistry[target]?.fn

    /** Snapshot of all registered target identifiers across both registries. */
    fun registeredTargets(): Set<String> = registry.keys + higherOrderRegistry.keys

    /**
     * Q-065: the determinism position of the registry entry for [target],
     * or null when the target is not registered. Consults the test
     * overlay first, then both shipping registries.
     */
    fun determinismOf(target: String): Determinism? =
        testOverlay[target]?.determinism
            ?: registry[target]?.determinism
            ?: higherOrderRegistry[target]?.determinism

    /** Q-065: one registry entry's metadata, for registry-wide sweeps. */
    data class EntryMeta(
        val target: String,
        val higherOrder: Boolean,
        val effectful: Boolean,
        val determinism: Determinism,
    )

    /** Q-065: metadata for every shipping registry entry (overlay excluded). */
    fun entryMetadata(): List<EntryMeta> =
        registry.map { (target, e) ->
            EntryMeta(target, higherOrder = false, effectful = e.effectful, determinism = e.determinism)
        } + higherOrderRegistry.map { (target, e) ->
            EntryMeta(target, higherOrder = true, effectful = e.effectful, determinism = e.determinism)
        }

    /**
     * A fixed Unix-millis timestamp returned by `strand-builtin:Time.Now`.
     * The exact value is arbitrary — chosen as the project's notional
     * "today" — and is stable across runs to support replay determinism.
     */
    const val FIXED_REPLAY_TIMESTAMP: Long = 1_780_000_000_000L

    // Q-054 follow-up (concurrent isolation, completed): the
    // `Snapshot`/`snapshot()`/`install()`/`restore()` protocol that the
    // facade used to save/install/restore these singletons around each run is
    // removed. Policy now flows through evaluation as an explicit
    // [HostContext] value (derived via [HostContext.fromPolicy]); the
    // `@Volatile` fields above are retained only as the single-tenant default
    // source that [HostContext.processDefault] reads and as the test-injection
    // seam (a test sets `Builtins.clock = FixedClock(...)` before constructing
    // an interpreter, which captures it into its process-default context).
    // They are no longer on the isolation-critical path: two runtimes with
    // different policies run concurrently without touching them.
}

/**
 * Q-054 follow-up: convenience invocation that supplies a
 * [HostContext.processDefault] (reading the current [Builtins] singletons at
 * call time). It exists so the many unit tests that drive a builtin directly
 * via `Builtins.lookup(target)!!.invoke(args)` keep working unchanged after
 * the [Builtins.Fn] signature gained an explicit [HostContext] parameter — a
 * test that installs `Builtins.credentialProvider = ...` /
 * `Builtins.clock = ...` in `@BeforeEach` and then invokes a builtin directly
 * sees those installed values through the process-default context. Production
 * dispatch (the [Interpreter] / [org.strand.vm.Vm] / actor runtime) always
 * passes an explicit context and never routes through this overload.
 */
fun Builtins.Fn.invoke(args: List<Value>): Value =
    invoke(HostContext.processDefault(), args)

/** Q-054 follow-up: process-default-context convenience for higher-order builtins. See [Builtins.Fn.invoke]. */
fun Builtins.FnH.invoke(args: List<Value>, apply: Builtins.ApplyFn): Value =
    invoke(HostContext.processDefault(), args, apply)
