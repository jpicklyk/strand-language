package org.strand.interpreter

import org.strand.core.EvaluationLimits

/**
 * Q-054: the per-instance host policy bundle. An immutable value object that
 * consolidates everything the process-global [Builtins] `@Volatile` singletons
 * and [EvaluationLimits] previously scattered, so a host can construct one
 * policy, hand it to a runtime facade, and run a program under it without
 * touching any global.
 *
 * See [`proposals/embeddable-runtime.md`](../../../../../../../proposals/embeddable-runtime.md)
 * for the design and the scope decision. The short version of the scope
 * decision: this value object IS the published policy surface, but the fields
 * read by bare name inside the builtin lambdas ([clock], [random],
 * [sandbox], [nameResolver], the stream-receive timeout carried on [limits],
 * [llmHttpClient], [credentialProvider], and the rest) are still installed
 * onto the [Builtins] singletons for the duration of a run by the facade,
 * which restores them afterward. Threading an explicit context into the
 * immutable single-map registry of fixed-signature builtin lambdas is the
 * documented follow-up; this object plus the facade's centralized
 * install/restore is the structural prerequisite for it.
 *
 * The fields mirror the [Builtins] singletons one-for-one, with one
 * deliberate omission: the per-read streaming-receive ceiling is NOT a
 * separate field here — it already lives on
 * [EvaluationLimits.streamReceiveTimeoutMillis], and that is the single
 * source of truth the facade installs onto [Builtins.streamReceiveTimeoutMillis].
 *
 * Construct via the [OPEN] / [SECURE] companions (mirroring the
 * [SandboxPolicy.OPEN_DEFAULT] / [SandboxPolicy.SECURE_DEFAULT] split) and
 * `.copy(...)` for variations — e.g. `HostPolicy.SECURE.copy(limits = tighter)`
 * for an agent-facing host with a tightened step budget, or
 * `HostPolicy.OPEN.copy(random = java.util.Random(42))` for a test that needs
 * a reproducible RNG sequence.
 */
data class HostPolicy(
    /**
     * Evaluation limits — also carries [EvaluationLimits.errorVerbosity]
     * (Q-042) and [EvaluationLimits.streamReceiveTimeoutMillis] (Q-045), so
     * those two host-policy dimensions ride here rather than as separate
     * fields.
     */
    val limits: EvaluationLimits,
    /** Q-041 sandbox policy mediating every `Fs.*` / `Net.Connect` / `Http.Request` foreign call. */
    val sandbox: SandboxPolicy,
    /** Q-040/Q-045 clock for the `Time.*` builtins. */
    val clock: Builtins.Clock,
    /** Randomness source for the `Random.*` builtins. */
    val random: java.util.Random,
    /** Q-037/Q-038/Q-042 credential resolution for the LLM and vector-store builtins. */
    val credentialProvider: CredentialProvider,
    /** Q-041 DNS resolver used by the network sandbox. */
    val nameResolver: NameResolver,
    /** Q-037 HTTP transport for the LLM provider bindings. */
    val llmHttpClient: LlmHttpClient,
    /** Q-038 HTTP transport for the vector-store providers. */
    val vectorHttpTransport: VectorHttpTransport,
    /** Round-3 log sink for the `Log.*` builtins. */
    val logSink: Builtins.LogSink,
    /** Round-3 host-environment source for the `OS.*` builtins. */
    val osEnv: Builtins.OsEnv,
    /** Round-3 exit handler for `System.Exit`. */
    val exitHandler: Builtins.ExitHandler,
    /** Q-037 maximum tool-use iterations per per-provider Generate call. */
    val toolLoopLimit: Int,
) {
    companion object {
        /**
         * Library / test surface: the current [Builtins] singleton defaults.
         * Open sandbox (no workspace constraint, no network default-deny),
         * default evaluation limits, system clock, a cryptographically-secure
         * RNG, environment-variable credentials, and the default real
         * transports. A library or test caller that wants the pre-Q-054
         * behaviour passes this.
         *
         * The [random] field is a fresh [java.security.SecureRandom] captured
         * at companion access; a test that needs a reproducible sequence
         * supplies its own via `.copy(random = java.util.Random(seed))`.
         */
        val OPEN: HostPolicy = HostPolicy(
            limits = EvaluationLimits.DEFAULTS,
            sandbox = SandboxPolicy.OPEN_DEFAULT,
            clock = Builtins.SystemClock,
            random = java.security.SecureRandom(),
            credentialProvider = EnvCredentialProvider,
            nameResolver = SystemNameResolver,
            llmHttpClient = DefaultLlmHttpClient,
            vectorHttpTransport = JdkHttpTransport,
            logSink = Builtins.StderrLogSink,
            osEnv = Builtins.SystemOsEnv,
            exitHandler = Builtins.RealExitHandler,
            toolLoopLimit = 10,
        )

        /**
         * Agent-facing surface: the [SandboxPolicy.SECURE_DEFAULT] sandbox
         * (workspace-rooted filesystem, default-deny network with the full
         * blocked-range list), everything else as [OPEN]. Mirrors the CLI's
         * "install SECURE_DEFAULT at startup" stance — and differs from [OPEN]
         * only in the [sandbox] field.
         */
        val SECURE: HostPolicy = OPEN.copy(sandbox = SandboxPolicy.SECURE_DEFAULT)
    }
}
