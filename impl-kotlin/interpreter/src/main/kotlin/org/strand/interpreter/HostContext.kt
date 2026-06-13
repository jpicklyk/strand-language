package org.strand.interpreter

import org.strand.core.NodeId
import org.strand.verifier.TypeExpr

/**
 * Q-054 follow-up: the immutable, per-invocation projection of a [HostPolicy]
 * that flows through evaluation as an ordinary value. This is the runtime-side
 * carrier that lets two [org.strand.runtime.StrandRuntime] instances run
 * effectful programs *concurrently* in one JVM under different policies
 * without clobbering each other — the closing of the Q-054 facade's documented
 * scope decision.
 *
 * Every host-routed field a builtin lambda reads is carried here rather than
 * read by bare name off the [Builtins] process-global singletons. The
 * interpreter threads one [HostContext] into every builtin invocation
 * ([Builtins.Fn.invoke] / [Builtins.FnH.invoke] take it as their first
 * argument); the async actor runtime threads it as a value into each per-actor
 * [Interpreter] and each stream feeder. Because the context is immutable and
 * passed by value, work that moves across threads (the actor runtime launches
 * on `Dispatchers.IO`) reads the right tenant's policy by construction — there
 * is no thread-local or mutable global on the read path.
 *
 * The ~165 pure builtins ignore the context; the isolation-critical effectful
 * builtins (clock / random / sandbox / credentials / name resolution / stream
 * timeouts / verifier node-types) read it.
 *
 * Each context carries its own [Scrubber] so error-text credential scrubbing
 * uses the active tenant's resolved credentials, never another tenant's.
 *
 * Construct via [fromPolicy] (the runtime facade's path) for an isolated
 * per-tenant context, or [processDefault] for the single-tenant default that
 * reads the current [Builtins] singletons — the latter keeps every direct
 * `Interpreter(...)` / `Vm(...)` construction in the existing test suite and
 * the CLI working unchanged.
 */
data class HostContext(
    /** Q-040/Q-045 clock for the `Time.*` builtins. */
    val clock: Builtins.Clock,
    /** Randomness source for the `Random.*` builtins. */
    val random: java.util.Random,
    /** Q-041 sandbox policy mediating every `Fs.*` / `Net.Connect` / `Http.Request` foreign call. */
    val sandbox: SandboxPolicy,
    /** Q-041 DNS resolver used by the network sandbox. */
    val nameResolver: NameResolver,
    /** Q-045 per-read blocking-receive ceiling (millis) for streaming sockets / SSE reads. */
    val streamReceiveTimeoutMillis: Long,
    /** Q-037/Q-038/Q-042 credential resolution for the LLM and vector-store builtins. */
    val credentialProvider: CredentialProvider,
    /** Q-037 HTTP transport for the LLM provider bindings. */
    val llmHttpClient: LlmHttpClient,
    /** Q-038 HTTP transport for the vector-store providers. */
    val vectorHttpTransport: VectorHttpTransport,
    /**
     * The verifier's `nodeTypes` map for the executing program. The N-044
     * ToolDef parameter-schema projection and the N-045 ResponseSchemaSpec
     * projection read it to resolve a Schema NodeId to its
     * [TypeExpr.SchemaType]. Null degrades both projections to empty `{}`
     * JSON schemas.
     */
    val verifierNodeTypes: Map<NodeId, TypeExpr>?,
    /** Round-3 log sink for the `Log.*` builtins. */
    val logSink: Builtins.LogSink,
    /** Round-3 host-environment source for the `OS.*` builtins. */
    val osEnv: Builtins.OsEnv,
    /** Round-3 exit handler for `System.Exit`. */
    val exitHandler: Builtins.ExitHandler,
    /** Q-037 maximum tool-use iterations per per-provider Generate call. */
    val toolLoopLimit: Int,
    /**
     * Q-054 per-context credential scrubber. A credential resolved through
     * [credentialProvider] is registered here by the resolving builtin, and
     * `IoFailure`/`SandboxViolation` detail is scrubbed against it at the
     * interpreter's error-translation point.
     */
    val scrubber: Scrubber,
) {
    companion object {
        /**
         * Derive an isolated [HostContext] from [policy] plus the program's
         * [verifierNodeTypes]. Allocates a fresh per-context [Scrubber] so the
         * tenant's resolved credentials never bleed into another tenant's
         * scrubbing. This is the runtime facade's path.
         */
        fun fromPolicy(
            policy: HostPolicy,
            verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
        ): HostContext = HostContext(
            clock = policy.clock,
            random = policy.random,
            sandbox = policy.sandbox,
            nameResolver = policy.nameResolver,
            streamReceiveTimeoutMillis = policy.limits.streamReceiveTimeoutMillis,
            credentialProvider = policy.credentialProvider,
            llmHttpClient = policy.llmHttpClient,
            vectorHttpTransport = policy.vectorHttpTransport,
            verifierNodeTypes = verifierNodeTypes,
            logSink = policy.logSink,
            osEnv = policy.osEnv,
            exitHandler = policy.exitHandler,
            toolLoopLimit = policy.toolLoopLimit,
            scrubber = Scrubber(),
        )

        /**
         * The single-tenant process default: reads the current [Builtins]
         * host-routed singletons and the process-global default [Scrubber].
         * This is the context every direct `Interpreter(...)` / `Vm(...)`
         * construction gets when no explicit context is supplied, so the
         * existing test suite and any singleton-driven path behave exactly as
         * before. A library or test that installs a singleton (e.g.
         * `Builtins.clock = FixedClock(...)`) before constructing an
         * interpreter sees that value here.
         *
         * Read off the singletons at call time (not cached): a test that
         * mutates `Builtins.clock` then constructs an `Interpreter` must see
         * the mutated clock.
         */
        fun processDefault(): HostContext = HostContext(
            clock = Builtins.clock,
            random = Builtins.random,
            sandbox = Builtins.sandboxPolicy,
            nameResolver = Builtins.nameResolver,
            streamReceiveTimeoutMillis = Builtins.streamReceiveTimeoutMillis,
            credentialProvider = Builtins.credentialProvider,
            llmHttpClient = Builtins.llmHttpClient,
            vectorHttpTransport = Builtins.vectorHttpTransport,
            verifierNodeTypes = Builtins.verifierNodeTypes,
            logSink = Builtins.logSink,
            osEnv = Builtins.osEnv,
            exitHandler = Builtins.exitHandler,
            toolLoopLimit = Builtins.toolLoopLimit,
            scrubber = CredentialScrubber.default,
        )
    }
}
