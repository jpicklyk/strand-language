package org.strand.core

/**
 * Q-042: host-configured verbosity of agent-visible runtime errors. Threads
 * through the existing [EvaluationLimits] carrier (see § 4.5 of
 * `proposals/implemented/credential-isolation.md`) so a single configuration
 * point controls every error-surfacing path — tree-walker, bytecode VM,
 * state-machine runtime, schema checker.
 *
 * The variants trade diagnostic detail against credential-leakage risk:
 *
 *  - [Redacted] (default) runs every `IoFailure.detail` through the
 *    [CredentialScrubber] before exposing it. Concretely, the
 *    `InterpretError.IoFailure` materialiser reads the already-scrubbed
 *    field of the runtime exception. Structural diagnostics (HTTP status,
 *    `kind` discriminator, response shape) survive; any literal credential
 *    value registered with [CredentialScrubber.register] is replaced with a
 *    `[REDACTED:<provider>:<key>]` placeholder.
 *
 *  - [Full] reads the runtime exception's `unscrubbedDetail` field — the
 *    original, unscrubbed text. Reserved for dev/debug deployments where
 *    the operator has accepted the leak risk and needs to diagnose an
 *    upstream-response shape they cannot reproduce from scrubbed output.
 *    A one-time warning is emitted at runtime entry when this verbosity
 *    is active.
 *
 *  - [RedactedWithKindOnly] strips the detail entirely; the agent sees
 *    only the structured `kind` field of `InterpretError.IoFailure`. Most
 *    restrictive — appropriate when even the scrubbed structural shape
 *    (HTTP status code, JSON error layout) is information the operator
 *    chooses not to surface.
 *
 * The `PERMISSIVE` companion of [EvaluationLimits] does NOT relax this
 * default: "permissive" means resource limits, not credential leakage.
 * `PERMISSIVE` keeps [Redacted] so a benchmark or stress test cannot
 * accidentally expose secrets if it happens to interact with the
 * credential surface.
 */
enum class ErrorVerbosity {
    /**
     * Default. The agent-facing detail for every [IoFailure]-derived
     * [InterpretError.IoFailure] runs through [CredentialScrubber.scrub]
     * at construction time; every registered credential value is
     * replaced with its placeholder before the error reaches the agent.
     * Production deployments use this.
     */
    Redacted,

    /**
     * Surface the unscrubbed `detail`. Dev/debug only. Runtime entry
     * points log a warning the first time they observe this setting
     * (mirroring the "this is non-default" idiom).
     */
    Full,

    /**
     * Strip `detail` entirely; only `kind` survives. Most restrictive
     * mode — agent sees only the discriminator (`"anthropic-http-status"`,
     * `"pinecone-upsert"`, etc.) and no further text.
     */
    RedactedWithKindOnly,
}
