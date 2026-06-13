# Embeddable runtime and per-instance host policy

**Document:** `proposals/embeddable-runtime.md`
**Status:** Draft proposal
**Date:** 2026-06-13
**Concerns:** Q-054, Q-040 / Q-041 / Q-042 (the host-policy carriers consolidated here), Q-008 (the distributed runtime this is a prerequisite to), Q-017 step 2 (the Rust VM host API this shapes), Q-058 (run-by-hash, the natural follow-on entry point), Q-055 / Q-059 / Q-064 (siblings that compose on this surface), Kotlin/JVM reference implementation
**Scope:** medium

This proposal closes Q-054 (the embeddable-runtime item of ROADMAP Tier 3.5, single-process operational substrate). It introduces a published embedding surface — a `HostPolicy` value object and a `StrandRuntime` facade — so a JVM host can verify and run a Strand program in-process against an injected policy bundle, and so two graphs can run under different policies in one process. It does not change the node algebra, the canonical encoding, any hash, or the verifier's type-checking. It is a runtime-architecture refactor.

## 1. Problem statement

The only executable entry point to the reference implementation is the CLI. A host that wants to run a Strand program in-process must hand-replicate the CLI's pipeline — ingest, finalize, verify, schema-check, build runtime schema obligations, evaluate — and must participate in a mutation protocol over roughly a dozen process-global `@Volatile` singletons on the `Builtins` object: `clock`, `random`, `logSink`, `osEnv`, `exitHandler`, `credentialProvider`, `llmHttpClient`, `vectorHttpTransport`, `toolLoopLimit`, `streamReceiveTimeoutMillis`, `verifierNodeTypes`, `sandboxPolicy`, and `nameResolver`, plus the process-global `CredentialScrubber` registry. The save/restore discipline that keeps this correct lives in the CLI's `withProgramEvaluationContext` helper, which exists precisely because these are global and is correct only because the CLI runs exactly one program per process.

Two concurrent evaluations in one JVM would share a single sandbox policy and credential provider. That is a security hazard rather than a style concern: a `SECURE_DEFAULT`-sandboxed graph and an `OPEN_DEFAULT` graph cannot coexist, because the second `withProgramEvaluationContext` to install clobbers the first's policy for the duration of the overlap. The same is true for credentials, the RNG seed, the clock, and the verifier `nodeTypes` map the LLM tool-dispatch path reads. In-source comments on `Builtins.clock`, `Builtins.sandboxPolicy`, and `Builtins.random` already acknowledge per-interpreter policy injection as a future refactor; nothing tracked it until Q-054.

There are two distinct sub-problems. The first is the *missing facade*: there is no `verify-and-run` API a host can call without re-deriving the CLI's pipeline. The second is *scattered policy*: the inputs the run depends on are spread across the `Builtins` singletons and the `EvaluationLimits` data class with no single carrier. This proposal addresses both — a `HostPolicy` value object that bundles the policy, and a `StrandRuntime` facade that consumes it.

## 2. Prior art

- **GraalVM polyglot `Context.Builder`** — a guest-language context is built from an explicit builder (allowed host access, IO filters, resource limits, options) and is the unit of isolation; two contexts in one JVM carry independent policy. The analogue here is `HostPolicy` as the builder output and `StrandRuntime` as the context.
- **Wasmtime `Store` / `Engine` split** — the `Engine` holds shared compilation config; each `Store` holds the per-instance state and limits. Strand's `Builtins` registry is the shared, immutable compilation-side artifact (the engine); `HostPolicy` is the per-store state. This proposal does not split the registry — it is already immutable and shared correctly — but the conceptual line is the same.
- **The JDK `SecurityManager` (now removed)** — a single process-global policy object proved unworkable precisely because it could not vary per call site without a thread-local install/restore dance. Strand's `Builtins` singletons are that pattern; `withProgramEvaluationContext` is the install/restore dance. The lesson taken here is to make policy a value threaded explicitly where the call graph permits, and to centralize the unavoidable install/restore in one audited place rather than scatter it.
- **Deno permissions** — each subprocess/worker carries its own permission set rather than inheriting an ambient one; the embedding API takes permissions as a constructor argument. `HostPolicy.sandbox` plays the same role.

## 3. Recommended approach

Introduce two types in a position both the CLI and external hosts can depend on.

`HostPolicy` — an immutable value object that bundles everything the singletons and `EvaluationLimits` currently scatter: the sandbox policy, the evaluation limits (which already carry error verbosity), the clock, the credential provider, the name resolver, the RNG, the stream-receive timeout, the LLM and vector HTTP clients, the log sink, the OS-env source, the exit handler, and the tool-loop limit. Constructed by the host; `OPEN` and `SECURE` companion defaults mirror the existing `SandboxPolicy.OPEN_DEFAULT` / `SECURE_DEFAULT` split (`OPEN` for library/test callers — unchanged behaviour; `SECURE` for agent-facing hosts — the default-deny surface the CLI installs today).

`StrandRuntime` — the published embedding facade. It is constructed with a `HostPolicy` and exposes the verify-and-run pipeline as methods: `verify(program)`, `run(program)` for pure/Layer-1–5 graphs, `runMachine(program, events)` for a single state machine, and `runGroup(program, routedEvents)` for a machine group. Each method threads the policy explicitly into the `Interpreter` / `Vm` / `StateMachineRuntime` it constructs, and installs the policy's `Builtins`-routed fields around the evaluation through a single internal `withPolicy { }` block that subsumes the CLI's `withProgramEvaluationContext`. The CLI becomes a thin client: it parses flags into a `HostPolicy`, constructs a `StrandRuntime`, and calls the facade.

The scope decision (stated as a tradeoff in §8, made here): the `Builtins` singletons are *retained as a process-default context that the facade installs and restores*, rather than removed. Policy is threaded explicitly through the public eval entry points where the call graph already carries an explicit context (the `Interpreter` / `Vm` / `StateMachineRuntime` constructors, the `eval` / `applyCallable` / `runMachine` / `runGroup` signatures that already take `EvaluationLimits`). For the fields read by bare name *inside* individual builtin lambdas — `clock`, `random`, `sandboxPolicy`, `nameResolver`, `streamReceiveTimeoutMillis`, `verifierNodeTypes`, `llmHttpClient`, `credentialProvider`, and the rest — the facade installs them onto the singletons for the duration of the run and restores them in a `finally`. This is the safe path: the registry is a single immutable `Map<String, Entry<Fn>>` built once at class-init, the lambdas have fixed `(args) -> Value` and `(args, applyFn) -> Value` signatures with no slot for a context, and threading a per-call context into all 218 registrations would be a large, fragile ripple against a 2200-test suite. Full removal of every global read is a documented follow-up under this identifier (§8). What the facade *does* buy immediately: there is exactly one place the install/restore happens (auditable, not scattered across subcommands), the single-program-per-process correctness contract is made explicit and centralized, and the published surface is a value-threaded API even though one layer underneath still routes through an installed default. The honest characterization is "per-call policy, serialized at the singleton boundary" — sufficient for sequential embedding and for the CLI; true concurrent multi-tenant isolation in one JVM is gated on the follow-up that removes the singleton reads, and this facade is the structural prerequisite for it.

## 4. Detailed mechanism

### 4.1 No node category

This proposal introduces no node category, no canonical-encoding change, no new `VerifyError`, and no effect-category identifier. The work is entirely in the runtime architecture. Hashes are unaffected; the golden vectors are untouched.

### 4.2 The `HostPolicy` value object

A new `org.strand.interpreter.HostPolicy` data class (placed in `:interpreter` because that is where the singletons it consolidates live, and `:runtime` / `:cli` already depend on `:interpreter`). Fields:

```
data class HostPolicy(
    val limits: EvaluationLimits,             // includes errorVerbosity, streamReceiveTimeoutMillis
    val sandbox: SandboxPolicy,
    val clock: Builtins.Clock,
    val random: java.util.Random,
    val credentialProvider: CredentialProvider,
    val nameResolver: NameResolver,
    val llmHttpClient: LlmHttpClient,
    val vectorHttpTransport: VectorHttpTransport,
    val logSink: Builtins.LogSink,
    val osEnv: Builtins.OsEnv,
    val exitHandler: Builtins.ExitHandler,
    val toolLoopLimit: Int,
)
```

`streamReceiveTimeoutMillis` is intentionally *not* a separate field: it already lives on `EvaluationLimits.streamReceiveTimeoutMillis`, and the existing `withProgramEvaluationContext` reads it from there. `HostPolicy` keeps that single source of truth — `limits.streamReceiveTimeoutMillis` is what gets installed onto the singleton.

Companion defaults:

```
companion object {
    // Library/test surface: open sandbox, default limits, system clock,
    // secure-random RNG, env credentials — i.e. the current singleton defaults.
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

    // Agent-facing surface: secure-default sandbox; everything else as OPEN.
    // Mirrors the CLI's "install SECURE_DEFAULT at startup" stance.
    val SECURE: HostPolicy = OPEN.copy(sandbox = SandboxPolicy.SECURE_DEFAULT)
}
```

`OPEN`/`SECURE` differ only in the sandbox field, exactly as the existing `SandboxPolicy.OPEN_DEFAULT`/`SECURE_DEFAULT` split does; hosts that want tighter limits or a custom credential provider use `.copy(...)`. The `random` field on `OPEN` is a fresh `SecureRandom` per access — a `val` is acceptable because `HostPolicy.OPEN` is referenced once at facade construction, but tests that need a reproducible sequence pass an explicit seeded `Random` via `.copy(random = ...)`.

### 4.3 The `StrandRuntime` facade

A new `org.strand.runtime.StrandRuntime` (placed in `:runtime` because it must reach `Interpreter`, `Vm`-free synchronous evaluation, `StateMachineRuntime`, `SchemaChecker`, and the verifier — `:runtime` already depends on `:interpreter` + `:verifier` + `:core`, and `:schema` is a sibling the facade can add as a dependency, or the schema-check can be parameterized in by the caller to avoid a new module edge; see §9). It is constructed with a `HostPolicy`:

```
class StrandRuntime(private val policy: HostPolicy) {
    fun verify(program: FinalizedProgram): VerifyResult
    fun run(program: FinalizedProgram, capabilities: CapabilitySet = CapabilitySet.EMPTY): RunResult
    fun runMachine(program: FinalizedProgram, machine: NodeId, events: List<Value>, caps): Trace
    fun runGroup(program: FinalizedProgram, group: MachineGroup, scope: CoroutineScope): MachineGroupHandle
}
```

The `program` argument is a parsed/finalized graph (`FinalizedProgram`), not a hash — run-by-hash is Q-058 and out of scope. Each method:

1. Constructs the backend (`Verifier`, `Interpreter`, or `StateMachineRuntime`) over the program's `store` / `hashToNodeId` / `resolveTarget`.
2. For evaluating methods, wraps the evaluation in the facade's single `withPolicy { }` block (§4.4), which installs the policy's `Builtins`-routed fields and restores them in a `finally`.
3. Threads `policy.limits` explicitly into the eval call (the entry points already take `EvaluationLimits`).
4. For `run`, builds the `schemaObligations` map from the verify result's `nodeTypes` and passes it to the `Interpreter` constructor, exactly as the CLI does today.

`verify` performs no install (verification reads no singletons) and returns the `VerifyResult` for the caller to branch on. The facade does not call `exitProcess` or print — those are CLI concerns; the facade returns structured results and the CLI renders them.

### 4.4 The single install/restore: `withPolicy`

The facade owns one internal helper that subsumes `withProgramEvaluationContext`:

```
internal fun <T> HostPolicy.withInstalled(
    verifierNodeTypes: Map<NodeId, TypeExpr>?,
    block: () -> T,
): T {
    val prior = Builtins.snapshot()            // capture all routed fields
    Builtins.install(this, verifierNodeTypes)  // set clock/random/sandbox/...
    try { return block() } finally { Builtins.restore(prior) }
}
```

`Builtins.snapshot()` / `install(policy, nodeTypes)` / `restore(snapshot)` are three new methods on `Builtins` that read/write the full set of routed `@Volatile` fields in one place, replacing the three-field save/restore the CLI does by hand. This centralization is the load-bearing improvement: the set of singletons that must be saved/restored is defined once, in `Builtins`, next to the fields themselves, so adding a future routed field cannot drift out of sync with the install/restore protocol (the bug class `withProgramEvaluationContext` was vulnerable to). The `CredentialScrubber` registry is populated as a side effect of `credentialProvider.resolve` (each resolve registers its credential), so installing the provider is sufficient; the scrubber is not separately snapshotted in this slice (recorded as a follow-up in §8 — cross-tenant scrubber-registry bleed is the same singleton class of problem).

### 4.5 Worked example: two policies, one process

```kotlin
val openRt   = StrandRuntime(HostPolicy.OPEN)
val secureRt = StrandRuntime(HostPolicy.SECURE)

val prog = loadFinalized(fsWriteProgramJson)   // a graph that calls Fs.Write outside cwd

val a = openRt.run(prog, grantAll(prog))        // succeeds: OPEN sandbox permits the escape
val b = runCatching { secureRt.run(prog, grantAll(prog)) }
// b fails with InterpretError.SandboxViolation: SECURE sandbox denies the workspace escape
```

Run sequentially, `a` succeeds and `b` fails, demonstrating that the policy is per-`StrandRuntime` and that one run's policy does not leak into the other's — `openRt.run` installs `OPEN_DEFAULT`, restores on exit; `secureRt.run` installs `SECURE_DEFAULT`, restores on exit. The isolation test (§7) asserts exactly this, plus the restoration property (the `Builtins` singletons return to their pre-call values after each run).

## 5. Verifier rules

None. This proposal adds no well-formedness rule and no `VerifyError` variant. `StrandRuntime.verify` delegates to the existing `Verifier` unchanged.

## 6. Interpreter / runtime semantics

No change to evaluation semantics. The interpreter, VM, and state-machine runtime evaluate exactly as before; the only change is *where the policy comes from* — explicitly from the `HostPolicy` threaded by the facade for the fields that already take an explicit context, and from the `Builtins` singletons (installed by the facade) for the fields read inside builtin lambdas. A program's value, trace, and error behaviour under a given policy are identical whether driven by the CLI or by a direct `StrandRuntime` call; the CLI-reimplemented-on-the-facade test (§7) is the regression proof of that.

## 7. Test scenarios

1. **HostPolicy.OPEN / SECURE defaults** — `HostPolicy.OPEN.sandbox == SandboxPolicy.OPEN_DEFAULT`, `HostPolicy.SECURE.sandbox == SandboxPolicy.SECURE_DEFAULT`, and `SECURE` differs from `OPEN` only in the sandbox field (every other field equal). Unit test in `:interpreter`.
2. **HostPolicy copy threading** — `HostPolicy.OPEN.copy(limits = tighter)` carries the tighter limits and leaves all other fields at the OPEN defaults. Unit test.
3. **Two-policy isolation (the property the item exists for)** — construct `StrandRuntime(HostPolicy.OPEN)` and `StrandRuntime(HostPolicy.SECURE)`; run a workspace-escaping `Fs.Write` program through both. OPEN succeeds, SECURE raises `SandboxViolation`. Run them in both orders to prove neither leaks into the other. `:runtime` (or `:cli`) test.
4. **Different-limits isolation** — two runtimes with different `maxSteps`; a program that exceeds the tight limit but not the loose one passes through the loose runtime and raises `ResourceExhaustion` through the tight one, with no order dependence.
5. **Singleton restoration** — capture `Builtins.snapshot()` before a `StrandRuntime.run`, run, and assert the post-run snapshot equals the pre-run snapshot (the facade restored every routed field). A second assertion: a run that throws mid-evaluation still restores (the `finally` path).
6. **Facade equals CLI** — a corpus program run through `StrandRuntime.run` produces the same `Value` the CLI prints; a state machine through `StrandRuntime.runMachine` produces the same `Trace`. Cross-check against the existing CLI test expectations.
7. **Credential provider per-runtime** — two runtimes with different `StaticCredentialProvider`s; the LLM-mock path through each sees its own runtime's credential, not the other's, when run sequentially.
8. **CLI behaviour unchanged** — the existing `CliFederationTest` and friends stay green after the CLI is reimplemented on the facade (no flag or output change).
9. **verify does no install** — `StrandRuntime.verify` on a program leaves the `Builtins` singletons untouched (verification reads no routed field).

## 8. Tradeoffs and open questions

**The scope decision, stated as the central tradeoff.** The published surface is value-threaded (a `HostPolicy` per `StrandRuntime`), but one layer underneath — the bare-name reads inside builtin lambdas — still routes through the `Builtins` singletons, which the facade installs and restores around each run. This makes the facade correct for *sequential* embedding and for the CLI, and centralizes the install/restore discipline that was previously scattered, but it does *not* by itself make two runs *concurrent in one JVM* safely isolated: the singleton install is process-global for the duration of the run. The alternative — threading an explicit context into every builtin lambda — was rejected for this slice because the registry is a single immutable map of fixed-signature lambdas built once at class-init, and changing 218 registration signatures plus every call site against a 2200-test suite is a large, fragile change that risks the very regression-freedom this item requires. The pragmatic facade is the shippable, correct-for-its-stated-use surface; full purity is the follow-up below.

**Deferred intentionally:**

- **Full removal of the `Builtins` singleton reads.** The end state is a per-`Interpreter` (or per-`HostPolicy`-threaded) policy carrier that the builtin lambdas read from an argument rather than from a singleton, at which point concurrent multi-tenant isolation in one JVM holds without the install/restore serialization. This requires either changing the builtin `Fn` signature to carry a context, or constructing the registry per-`HostPolicy` (cheap if the lambdas close over a policy passed at registry construction). Tracked under Q-054 as the follow-up; unblocked by this facade, which is its structural prerequisite.
- **`CredentialScrubber` registry snapshot/restore.** The scrubber is a separate process-global registry; this slice relies on `credentialProvider.resolve` repopulating it per run but does not snapshot/restore it. Cross-run scrubber-registry accumulation is benign for sequential runs (a stale entry only over-scrubs) but is part of the same singleton-removal follow-up.
- **Run-by-hash.** The facade takes a parsed `FinalizedProgram`, not a root hash. Run-by-hash and the persistent store are Q-058; the facade is the surface that Q-058's `run(hash)` will extend.
- **The Rust VM host API.** Q-017 step 2's bytecode VM will need an equivalent embedding surface; `HostPolicy` / `StrandRuntime` is the reference shape it should mirror, but this proposal does not specify the Rust side.

**Real research questions:**

- *Per-machine policy in a group.* `runGroup` installs one `HostPolicy` for the whole group. Q-059 (long-running groups) and a future multi-tenant host may want per-machine or per-instance policy (one actor sandboxed differently from another). That is a richer threading than this slice provides and interacts with the singleton-removal follow-up — until builtins read policy from an argument, per-actor policy cannot vary within one `runGroup` install window.
- *Composition with Q-055 (effect-audit log) and Q-064 (denial reports).* Both name the embedding API's run result as a likely landing surface. This proposal's `RunResult` should be the place a future audit log and the already-shipped `DenialReport` surface attach, but the exact result shape is left to those items to extend rather than over-specified here.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `interpreter/src/main/kotlin/org/strand/interpreter/HostPolicy.kt` | New `HostPolicy` data class + `OPEN`/`SECURE` companions | Medium |
| `interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt` | Add `snapshot()` / `install(policy, nodeTypes)` / `restore(snapshot)` over the routed `@Volatile` fields; an immutable `Snapshot` holder. No change to the registry or the lambdas. | Medium |
| `runtime/src/main/kotlin/org/strand/runtime/StrandRuntime.kt` | New facade: `verify` / `run` / `runMachine` / `runGroup`; internal `withInstalled` subsuming the CLI helper; `RunResult` holder | Medium-Large |
| `runtime/build.gradle.kts` | Add `:schema` to the dependency set if the facade runs the SchemaChecker internally; alternatively the caller passes obligations in (decide at implementation — prefer the module edge so the facade is self-contained) | Small |
| `cli/src/main/kotlin/org/strand/cli/Main.kt` | Parse flags into a `HostPolicy`; replace each subcommand body with a `StrandRuntime` call; delete `withProgramEvaluationContext` (now in the facade) | Large |
| `interpreter/src/test/kotlin/org/strand/interpreter/HostPolicyTest.kt` | Scenarios 1, 2 | Small |
| `runtime/src/test/kotlin/org/strand/runtime/StrandRuntimeIsolationTest.kt` | Scenarios 3, 4, 5, 7, 9 | Medium |
| `cli/src/test/...` | Scenario 6, 8 — existing CLI tests stay green; add a facade-equals-CLI assertion | Small |

**Order of work.** (1) `HostPolicy` + `Builtins.snapshot/install/restore` + unit tests (the value object and the centralized install, independently testable). (2) `StrandRuntime` facade + the isolation/restoration tests (the embedding surface, dogfooded by the tests before the CLI depends on it). (3) Reimplement the CLI on the facade; run the full CLI suite. (4) Full-suite green; move this proposal to `implemented/` with the scope-decision Implementation note.

**Not in this slice.** Removing the singleton reads from builtin lambdas; `CredentialScrubber` snapshot/restore; run-by-hash (Q-058); per-machine policy in a group; the Rust VM host API (Q-017 step 2). Each is recorded in §8.

## References

**Outgoing references:**
- [`open-questions.md`](../open-questions.md) — Q-054 (this proposal's defining question), Q-040 / Q-041 / Q-042 (the host-policy carriers consolidated into `HostPolicy`), Q-058 (run-by-hash, the follow-on), Q-055 / Q-059 / Q-064 (composing siblings)
- [`ROADMAP.md`](../ROADMAP.md) — Tier 3.5 single-process operational substrate, the embeddable-runtime item
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — the `Builtins` singletons and the CLI's `withProgramEvaluationContext` this consolidates
- [`proposals/implemented/interpreter-resource-limits.md`](implemented/interpreter-resource-limits.md) — `EvaluationLimits`, a field of `HostPolicy`
- [`proposals/implemented/io-builtin-sandboxing.md`](implemented/io-builtin-sandboxing.md) — `SandboxPolicy.OPEN_DEFAULT`/`SECURE_DEFAULT`, mirrored by `HostPolicy.OPEN`/`SECURE`
- [`proposals/implemented/credential-isolation.md`](implemented/credential-isolation.md) — `CredentialProvider`, `ErrorVerbosity`, `CredentialScrubber`

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-054 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section, Q-054 bullet
