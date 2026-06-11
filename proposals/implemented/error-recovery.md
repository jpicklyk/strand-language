# Error Recovery (Attempt Nodes and the Catchable-Failure Taxonomy)

**Document:** `proposals/implemented/error-recovery.md`
**Status:** Implemented (landed 2026-06-11 in the Kotlin/JVM reference implementation; see the Implementation note)
**Date:** 2026-06-10
**Concerns:** [`design/node-algebra.md`](../../design/node-algebra.md), [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md), [`decisions/ADR-004-effects-as-edges.md`](../../decisions/ADR-004-effects-as-edges.md), [Q-048](../../open-questions.md#Q-048), [Q-040](../../open-questions.md#Q-040) (resource limits — the budget that must remain uncatchable), [Q-041](../../open-questions.md#Q-041) (sandbox policy — likewise), [Q-042](../../open-questions.md#Q-042) (credential redaction of caught error detail), [Q-044](../../open-questions.md#Q-044) (the harm-bound argument the closure rule must preserve), [Q-047](../../open-questions.md#Q-047) (runtime schema enforcement — the second catchable class)
**Scope:** Medium

This proposal covers the in-language error-recovery surface for Strand: a new node category that lets a program observe a runtime failure as a value, and the taxonomy deciding which `InterpretError` variants a program may observe and which must remain host-terminal. It is the defining proposal for Q-048 and unblocks the ROADMAP Tier 1 retry-loop-exercising tasks and the Tier 2 in-language error recovery item.

## Implementation note (2026-06-11)

Implemented in the Kotlin/JVM reference implementation (branch commits `3421cdb`..`e05286f`, merged at `f67aac5`, integration reconciliation in `da7148f` — canonical-encoding spec rows plus a `CanonicalEncodingSpecTest` byte-trace — and `e8ad98f` — golden hashes for corpus 85–87). Full suite after integration: 1448 tests, 0 failures, 2 expected skips; corpus 86 verified end-to-end through `strand run` (`BytesV("{}")` fallback) and an authored `TRY` program through `strand author`. Eighteen dedicated unit tests (AttemptTest, AttemptVerifierTest, AttemptAuthoringTest, AttemptVmTest) plus encoder traces; corpus 85 (Ok passthrough), 86 (Fs.Read fallback), 87 (retry-with-backoff, the canonical agent program), all three VM-equivalent and registered across CorpusTest, CorpusHashingTest, LayerAReverseRoundTripTest, and the golden-hash vectors (adds-only diff confirmed). Seven recorded deviations:

1. **`SUM_NEW` reuse instead of `WRAP_SUM` (§ 6.3).** Only `ATTEMPT_PUSH` (0x74) and `ATTEMPT_POP` (0x75) are new opcodes; the Ok/Err wrapping reuses the existing `SUM_NEW` with `SumCaseC` constants. The attempt marker records frame depth, operand-stack depth, capability-stack depth and saved capability set, handler depth, and the absolute error-label pc; `applyClosure` snapshots and clears the marker stack so transition and invariant bodies are isolated.
2. **Result synthesis at the `TypeExpr` level (§ 5).** `synthesizeResultSum` builds a `TypeExpr.Sum` directly, mirroring `synthesizeInputEventSum` (which also builds a `TypeExpr`, not a stored node). Hash-identity with agent-declared sums is the verifier's origin-excluded structural equality; `resolveType` preserves declaration order for sum cases and product fields, so the (Ok, Err) and (kind, detail) orders align exactly.
3. **No schema-catch corpus program or dedicated VM guard test (§ 6.3).** The VM's schema non-enforcement is already pinned by the `CorpusRuntimeSchemaTest` precedent; schema-catch is exercised at the unit level in the interpreter, and the VM unwinder's `buildErrorPayload` handles the `SchemaInvariantViolation` shape for forward compatibility against the deferred `CHECK_SCHEMA`.
4. **The WHEN-from-TRY elaborator inference case (§ 6.4) is deferred.** The `RES` sugar and explicit sum declarations carry the type today; a fully-sugared `WHEN t (RES okT) "..."` cannot yet infer constructor-pattern payload types from the synthesized sum. Recorded follow-up in the Q-034 elaborator series.
5. **The § 8 builtin-contract hygiene follow-up (`BuiltinContractViolation`) did not ship in this slice** — deliberately skipped to protect the green integration; it remains a recorded follow-up overlapping Q-056's dispatch-boundary translation work. `Int.Div` by zero remains a raw, uncatchable `IllegalArgumentException`; Attempt does not observe it (only `InterpretException` is inspected).
6. **The VM IoFailure/SandboxViolation translation parity gap is closed (§ 6.3, as planned).** Before this work, raw `IoFailure`/`SandboxViolation` escaped the VM's foreign-dispatch boundary untranslated; the unwind boundary now translates both, honouring `ErrorVerbosity`, with `SandboxViolation` translated and rethrown (never unwound) and `VmResourceExhaustion` / `VmCapabilityViolation` / `VmNoMatchingCase` never caught.
7. **The implementation branch was based on a pre-2026-06-10 tree** and merged across the review-hardening work (NodeRefAnnotator, Match exhaustiveness, the warnings channel, golden hashes, the canonical-encoding specification) with zero behavioral changes — the streams were structurally disjoint, and the canonical-encoding rows added at integration document the encoding exactly as `encodeAttempt` implements it.

During integration a pre-existing prelude inconsistency was discovered (present before this work): the prelude `fsRead`/`fsExists` ForeignNodes carry an `ArgRef(0)` effect projection against the parameterless `readFx` EffectCategory, so authoring a prelude-`fsRead` program fails with `ProjectionSourceArityMismatch`. Recorded as a follow-up outside this proposal; the worked examples here declare explicit ForeignNodes.

## 1. Problem statement

Strand programs cannot observe their own failures. Every runtime failure in the reference implementation propagates as a host-level exception: the interpreter wraps a structured `InterpretError` in `InterpretException` and unwinds the whole evaluation ([`InterpretError.kt`](../../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/InterpretError.kt)). No node category in the algebra can observe an `InterpretError`. The `IoFailure` variant's own documentation records the gap as a deliberate deferral: "IO failures surface as exceptions rather than Result-typed values ... Future work can introduce a blessed Result<T, IoError> sum type with explicit error handling." This proposal is that future work.

The Handler node (N-043) does not close the gap. Interception fires *before* dispatch and replaces the effectful call wholesale — `effects-and-capabilities.md` § Effect handlers and [`proposals/implemented/effect-handlers.md`](effect-handlers.md) § 7.3 are explicit that the capability check and the real operation are both skipped at an intercepted site. A Handler can mock `Fs.Read` away; it cannot attempt the real read and fall back when the read fails. There is no node that runs the real operation and observes the outcome.

What is recoverable today is value-coded only, decided per builtin at binding time: `Http.Request` returns a non-2xx status as data in its result product; `String.ParseInt`, `Bytes.ParseHex`, `Json.Parse`, `Net.Stream.Receive`, `LLM.Stream.Receive`, and `Process.EnvVar` return the `Option` sum convention (`SumV "Some" / "None"`, exercised by corpus programs [64](../../corpus/64-option-parseint-unwrap.json) and [65](../../corpus/65-option-parseint-fallback.json)). Everything else — connection refused, DNS failure, HTTP-level timeouts, `Fs.Read` on a missing file, every LLM provider error, every `SandboxViolation`, every runtime `SchemaInvariantViolation` (Q-047) — terminates evaluation at the host.

The consequence is that the canonical agent program — call an API, retry transient failure with backoff, fall back on permanent failure — is not expressible in Strand. This gates the ROADMAP Tier 1 retry-loop-exercising tasks (the first-pass-correctness measurement cannot include the most common agent workload shape) and is recorded as the Tier 2 "In-language error recovery" item. Q-048 is the open question this proposal defines and answers.

## 2. Prior art

- **Rust** — splits failures into recoverable `Result<T, E>` values and unrecoverable panics; panics signal contract violations and are not caught in ordinary code (`catch_unwind` exists for FFI boundaries, not control flow). The catchable/uncatchable taxonomy in § 4.3 is the same divide: environmental failures are values, program defects and host policy stops are terminal.
- **WebAssembly / WASI** — WASI returns `errno`-style result codes for I/O while traps (memory violations, exhaustion) terminate the instance and are not observable from inside it. Strand's `IoFailure`-catchable / `ResourceExhaustion`-terminal split mirrors the errno/trap line exactly.
- **Erlang/OTP** — "let it crash": in-process `try/catch` exists but the idiom is to let defects kill the process and recover at the supervisor. Strand's analogue is that defect-class errors stay terminal and recovery for them belongs to the generating agent (regenerate the program), not to the program.
- **Java checked exceptions** — per-API error declaration in the signature, widely judged an ergonomic failure that callers neutralize with blanket catches. Option (a)'s per-builtin `Try` variants would reproduce this shape (a parallel error-typed surface per API) and is rejected partly on this evidence.
- **Koka / Eff** — exceptions are an algebraic effect; an exception handler is an abort-style effect handler. This is option (d): the principled generalization, requiring continuation or abort machinery the N-043 no-continuation form deliberately avoided.

## 3. Options survey and recommended approach

Four designs were evaluated against five criteria: agent emission ergonomics (the Layer A cost of "read a file, fall back to a default on failure" and of the retry-with-backoff loop), verifier soundness (the effect closure must remain the sound harm bound of Q-044), hash stability of every existing program, implementation cost across both evaluator backends, and interaction with `EvaluationLimits` and Q-042 redaction.

### 3.1 Option (a) — blessed Result convention plus Try-variant builtins

Bless the structural sum `Ok(T) | Err({kind: String, detail: String})` as a convention and add a `.Try` variant of each fallible IO builtin: `Fs.Read.Try : (String) -> Result<Bytes>`, `Http.Request.Try`, `Net.Connect.Try`, and so on. No algebra change, no encoding change, perfect hash stability. The fallback pattern in Layer A:

```layer-a
okCase SCS "Ok" bytesT
errCase SCS "Err" errPayloadT
resBytesT SUM [okCase errCase]
fsReadTryT FNT [stringT] resBytesT [readFx]
fsReadTry FN "strand-builtin:Fs.Read.Try" fsReadTryT [readFx]
pathS STR "config.json"
attempt APP fsReadTry [pathS]
defaultB BYT "e30="
result WHEN attempt resBytesT "Ok(b) -> b | Err(e) -> defaultB"
```

The costs compound. The effectful builtin surface roughly doubles (every `Fs.*`, `Net.*`, `Http.*`, `Process.*`, LLM and vector-store binding gains a sibling), and each `.Try` variant needs its own `FNT` + `FN` prelude pair — the same monomorphic-instantiation bloat that keeps the Option-returning builtins out of the prelude today (`impl-kotlin/CLAUDE.md` § When adding a new builtin). The failure shape is decided per builtin rather than once. Most decisively, the mechanism covers only single foreign calls: it cannot catch a `SchemaInvariantViolation` (not a builtin call), and a multi-call pipeline must `.Try` every step and thread the result through a Match tower per call — without monadic sugar, emission cost for composite bodies grows linearly in intermediate Matches, which is exactly the token cost Strand's authoring layer works to avoid.

### 3.2 Option (b) — a catching form of Handler

Extend N-043 with failure semantics: a handler that fires *after* dispatch, when the intercepted call raises, with the handler's return value replacing the failed call's result. Because adding a field or mode to N-043 changes its edge schema, the versioning rule in `node-algebra.md` § Versioning forces a new category tag anyway — this is in effect a new `CatchHandler` node, not a modification. The fallback pattern is compact:

```layer-a
pathS STR "config.json"
readApp APP fsRead [pathS]
defaultB BYT "e30="
fallback LAM [p:stringT] defaultB
guarded CH readFx fallback readApp
```

Three structural problems remain. First, failures are keyed to effect categories, but failures are not effects: a `SchemaInvariantViolation` carries no `EffectCategory`, so the mechanism cannot address the second catchable class at all. Second, the N-043 uniform-signature rule carries over — every intercepted call in the body must share one argument/result signature, so a pipeline mixing `Fs.Read` and `Http.Request` under one recovery policy needs nested same-shape handlers. Third, the handler receives the failed call's *arguments*, not the error: it cannot distinguish a transient timeout from a permanent DNS failure, so retry-then-fall-back — the canonical workload — is not cleanly expressible. The closure rule would also diverge confusingly from N-043: a catching handler attempts the real operation, so it must *not* subtract `intercept` from the closure, giving two Handler-shaped nodes with opposite closure algebra.

### 3.3 Option (c) — an Attempt node category

A new node category (N-047, the next free identifier) with a single structural edge: `Attempt(body: Expression)`. Evaluating the Attempt evaluates `body`; if `body` produces a value `v`, the Attempt produces `Ok(v)`; if evaluation of `body` raises a *catchable* `InterpretError` (§ 4.3), the Attempt produces `Err({kind, detail})` and evaluation continues. Uncatchable errors propagate through Attempt unchanged. The result type is the structural sum `Ok(T) | Err(ErrorPayload)` synthesized by the verifier from the body's type — no new type former, no parametric blessed type; the agent matches on it with the ordinary Match + ConstructorPattern machinery exactly as for Option. The fallback pattern:

```layer-a
okCase SCS "Ok" bytesT
errCase SCS "Err" errPayloadT
resBytesT SUM [okCase errCase]
pathS STR "config.json"
readApp APP fsRead [pathS]
tryRead TRY readApp
defaultB BYT "e30="
result WHEN tryRead resBytesT "Ok(b) -> b | Err(e) -> defaultB"
```

(With the `RES` sugar and the elaborator inference case of § 6.4, the three type-declaration lines collapse into the `WHEN` line.) Attempt scopes over any expression, so one Attempt covers a whole pipeline; the error payload is observable, so transient-versus-permanent branching and bounded retry through Fixpoint are expressible; failures are decoupled from effect categories, so `SchemaInvariantViolation` is covered by the same node. Hash stability is preserved by construction — a new category tag extends the registry without touching any existing encoding.

### 3.4 Option (d) — a full algebraic-effect failure channel

Model failure as an effect: every fallible operation carries a `Fail` operation in its effect row, and an abort-capable handler interprets it, Koka-style. This is the theoretically clean account — recovery, retry, and fallback all become handler programs, and the effect closure describes fallibility statically. It is rejected honestly on implementation cost and fit. The N-043 form is deliberately no-continuation: no `resume`, no abort, no CPS transformation, and that restriction is what made its interpreter footprint small ([`proposals/implemented/effect-handlers.md`](effect-handlers.md) § 7.5). A failure channel needs at minimum abort-to-handler semantics — unwinding machinery in both the tree-walker and the VM — which is the same unwinding Attempt needs, but spread across the general handler-dispatch path rather than localized at one node. It would also make fallibility part of every fallible function's effect row, churning the type of every IO builtin (a hash break for every program that declares those builtins' `FunctionType`s structurally) or requiring an implicit-row mechanism the type system does not have. The agent-facing surface would be the most complex of the four options for the least common denominator workload. If full handlers ever land (the deferred continuation work in the N-043 proposal), a failure channel can be revisited as an upward extension; Attempt's `Ok`/`Err` values would remain valid programs.

### 3.5 Comparison and recommendation

| Criterion | (a) Try builtins | (b) catching Handler | (c) Attempt | (d) failure channel |
|---|---|---|---|---|
| Fallback emission cost | Medium (per-call) | Low | Low | High |
| Retry-with-backoff expressible | Yes, per call only | No (error not observable) | Yes | Yes |
| Covers SchemaInvariantViolation | No | No | Yes | Only by reclassifying it as an effect |
| Composite-body coverage | No (Match tower per call) | Per category, uniform signature | Yes (one node scopes the pipeline) | Yes |
| Effect closure impact | None | New non-subtracting handler rule | None (§ 5) | Changes every fallible row |
| Hash stability | Total | Total (new tag) | Total (new tag) | Broken for IO-typed programs |
| Implementation cost | Low per builtin × many builtins, forever | Medium | Medium (both backends) | Large |

**Recommendation: option (c), the Attempt node (N-047), with the catchable/uncatchable taxonomy of § 4.3 as the policy core.** Option (a) is not adopted as a parallel surface, but its one genuinely good idea — a single blessed `ErrorPayload` shape — is absorbed into Attempt's `Err` case. Option (b) is rejected; option (d) is deferred as a possible future generalization that Attempt does not foreclose.

## 4. Detailed mechanism

### 4.1 Node category

**N-047 Attempt.** N-046 (ModuleManifest) is the highest assigned identifier; N-047 is next free per the INDEX registry.

| Field | Multiplicity | Target | Role |
|-------|------|---------------|------|
| `body` | 1 | Expression | The expression whose evaluation is attempted |

No content fields; identity is fully structural. Attempt introduces no binders — `body` is hashed under the surrounding binder context, making the encoding strictly simpler than Lambda or Let (the same property the Handler proposal records for its `handle` edge).

Why this shape and not a body-plus-handler pair (`Attempt(body, onError)`): with the error reified as an ordinary sum value, the recovery logic is just a Match, which the algebra already has, the agent already emits for Option, and the verifier already checks. A built-in `onError` edge would duplicate MatchCase with a second dispatch mechanism. The minimal node also keeps the VM lowering to a marker/unwind pair rather than a closure invocation protocol.

Why `body` is any expression and not only an Application: failure sites are not limited to call boundaries — a `SchemaInvariantViolation` fires at a value-flow site, and a pipeline of three IO calls under one recovery policy is the common emission shape. Restricting to Application would reintroduce option (a)'s per-call Match towers for composite bodies.

### 4.2 The synthesized Result type and the ErrorPayload shape

`ErrorPayload` is the fixed structural product:

```
ErrorPayload = ProductType {
    kind:   String,   -- stable machine-branchable discriminator
    detail: String,   -- scrubbed human/agent-readable diagnostic
}
```

The type of `Attempt(body : T)` is the structural sum

```
Result<T> = SumType [ Ok(T), Err(ErrorPayload) ]
```

synthesized by the verifier from the body's inferred type, following the precedent of `Verifier.synthesizeInputEventSum` for multi-stream state machines. There is no parametric blessed type and no new type former: `Result<Bytes>` is an ordinary SumType node, and because types are structural and content-addressed, the SumType the agent declares for its Match patterns is *the same node* as the one the verifier synthesizes — hash equality is the type equality.

`kind` values are a closed, documented vocabulary: for `IoFailure`, the existing kind tags pass through unchanged (`"filesystem-read"`, `"network-connect"`, `"http-request"`, `"network-stream-timeout"`, ...); for `SchemaInvariantViolation`, the single tag `"schema-invariant"`. New catchable classes extend the vocabulary with new strings — the `ErrorPayload` *type* never changes, so taxonomy growth is hash-stable for every program that names the type. This is the reason the payload is a flat product rather than a sum over error classes: a sum's case list is structural, and adding a case would change the type's hash and orphan every existing `Err`-matching program.

The payload deliberately excludes NodeIds, hashes, and the offending value. Excluding NodeIds keeps `Err` values identical across the interpreter and the VM (whose opcodes carry no NodeIds — Q-040 implementation note), preserving the `interpreter == VM` equivalence discipline. Excluding the offending value preserves Q-047's soundness statement that "a schema violation is never silently emitted": a program that catches a `SchemaInvariantViolation` learns *that* the violation occurred and what the invariant said, but the invalid value itself is discarded — catch-and-use-anyway is not constructible.

### 4.3 The catchable / uncatchable taxonomy

The policy core of the proposal. The organizing principle: **catchable errors are failures of the world; uncatchable errors are defects of the program or decisions of the host.** A failure of the world (the file was deleted, the connection refused, the upstream returned garbage) is meaningful to handle at runtime — retry or fall back. A defect of the program (a missed match case, a non-callable in call position) is meaningful to handle only at generation time — the agent regenerates against the structured error report. A decision of the host (budget, sandbox, capability policy) is a policy lever that in-language code must not be able to absorb or probe.

Every variant of the sealed `InterpretError` hierarchy, exhaustively:

| Variant | Class | Catchable | Rationale |
|---|---|---|---|
| `MissingNode` | infrastructure defect | No | A reference resolved to a missing node: store corruption or an ingest bug. No program-level response is meaningful. |
| `NotCallable` | program defect (defensive) | No | Unreachable on verified graphs; if it fires, the verifier or interpreter is wrong, not the world. |
| `ArityMismatch` | program defect (defensive) | No | Same as NotCallable — documented as defensive in `InterpretError.kt`. |
| `UnboundAtRuntime` | program defect (defensive) | No | A scope bug; verified graphs exclude it. |
| `CapabilityViolation` | host policy | No | See the probe-ability argument below. |
| `RefinementViolation` | host policy | No | Same argument; the refinement lattice is the finer-grained half of the same policy surface. |
| `UnknownForeignTarget` | deployment defect | No | The binding is host configuration; a program cannot meaningfully proceed past a target the runtime cannot resolve. The agent regenerates against a supported target. |
| `NoMatchingCase` | program defect | No | Position taken: this is a latent type error — Layer 5 step 1 defers exhaustiveness to runtime, and `InterpretError.kt` already anticipates a verify-time coverage analysis. Making it catchable would entrench programs that rely on the verifier's temporary gap and create a compatibility drag on closing it. The recovery path is the agent adding the missing case, not the program absorbing it. |
| `NodeRefTargetNotInStore` | infrastructure | No | Cross-store resolution failure is Q-016/Q-043 territory; if remote-fetch failures later become recoverable, that wants a fetch-level mechanism with retry semantics of its own, not a silent reclassification here. |
| `IoFailure` | failure of the world | **Yes** | The prime candidate: every transport, filesystem, process, and provider failure. The operation was authorized and attempted; the world declined. |
| `ResourceExhaustion` | host policy (budget) | No | Q-040's host contract: the budget bounds the *evaluation*, not a subtree. The counters are evaluation-global, so a catch handler would itself run with the budget already exhausted — the semantics is incoherent, and allowing it would let a program convert the host's hard stop into a soft, absorbable event. |
| `SandboxViolation` | host policy | No | Q-041's policy boundary. A program that can catch sandbox denials can sweep the workspace boundary or the blocked-IP ranges and map the policy silently. The Q-041 goal that "an agent learns what was rejected and retries within policy" is feedback to the *agent across generations* via the host's error report — not observability to the *program within an evaluation*. The distinction matters: the host sees and mediates the former; the latter would be invisible probing. |
| `SchemaInvariantViolation` | failure of the world (data) | **Yes** | Q-047 introduces it as the recoverable runtime check on dynamic values. Dynamic data failing validation is the normal case in agent workloads (reject one record, continue with the rest); the Err payload excludes the offending value (§ 4.2), so the schema discipline is not subverted. |

Builtin-internal contract failures (`Int.Div` / `Int.Mod` / `Math.Mod` by zero) surface today as raw `IllegalArgumentException` from `require(...)` in `Builtins.kt` — not as any `InterpretError`. They are program defects under this taxonomy (the agent guards the divisor) and remain uncatchable; § 9 lists a hygiene follow-up to give them a structured terminal variant rather than a bare JVM exception.

**The capability probe-ability question, argued both ways.** For catchability: a program that could observe `CapabilityViolation` could degrade gracefully under varying host policies — try the preferred sink, fall back to a granted one — and observing a denial confers no authority, so the Q-044 harm bound is untouched (a denied operation never executes; the bound only shrinks). Against: first, the effect closure already forces the program's full demand surface to be declared statically, so runtime probing gains nothing that admission-time negotiation could not — the host sees the closure before granting anything, and the right place to resolve a capability mismatch is the admission boundary. Second, observable denial turns the capability context into an oracle: a program can binary-search the refinement lattice (which paths, which hosts, which providers) and exfiltrate the policy shape through any granted output channel; in multi-tenant settings the policy is host-confidential. Third, the graceful-degradation workload has a principled alternative: if capability introspection is ever wanted, it should be an explicit, grantable effect (a `Policy.Probe` category) so probing is itself declared, visible in the closure, and deniable — not a side channel of catch. **Decision: uncatchable.** The explicit-probe-effect alternative is recorded as a research question in § 8.

### 4.4 Canonical encoding

```
Tag      : 47 (CategoryTag.Attempt, 4-byte big-endian: 0x00 0x00 0x00 0x2F)
Fields   :
  hash(body)   -- canonical-CBOR byte string of the 33-byte multi-hash of the body child
```

One child, no content fields, no binder semantics. Two Attempts over hash-identical bodies are the same node. Every existing program's encoding is untouched: per `node-algebra.md` § Versioning, a new category tag extends the registry without renumbering, and a runtime that predates N-047 simply rejects graphs that use it.

### 4.5 Worked example

The fallback program of § 3.3, end to end. The agent emits (density v4, with the `RES` sugar of § 6.4):

```layer-a
pathS STR "config.json"
tryRead TRY (APP fsRead [pathS])
defaultB BYT "e30="
result WHEN tryRead (RES bytesT) "Ok(b) -> b | Err(e) -> defaultB"
```

Elaboration expands `RES bytesT` to the structural sum `SUM [SCS "Ok" bytesT, SCS "Err" errPayloadT]` with `errPayloadT = PRD [PRF "kind" stringT, PRF "detail" stringT]`, and the `WHEN` to the Match / MatchCase / ConstructorPattern tower. The verifier types `tryRead` by inferring `Bytes` for the Application (from `fsReadT`), synthesizing `Result<Bytes>`, and checking the Match's patterns against it by hash equality.

At run time under a workspace sandbox where `config.json` does not exist: the Application dispatches `strand-builtin:Fs.Read`, the builtin raises `IoFailure("filesystem-read", "config.json: file does not exist")`, `applyForeign` translates it through the Q-042 verbosity gate into `InterpretError.IoFailure(at, kind, detail)`, the enclosing Attempt classifies it catchable and produces

```
SumV("Err", ProductV { kind = StringV("filesystem-read"),
                       detail = StringV("config.json: file does not exist") })
```

and the Match takes the `Err` branch, yielding `BytesV("{}")`. If the file exists, the Attempt yields `SumV("Ok", BytesV(<contents>))` and the `Ok` branch passes the payload through. The retry-with-backoff shape is the same mechanism inside a Fixpoint: recurse with a decremented attempt counter and a `sleep` call on the `Err` branch while the counter is positive, fall back (or return the `Err` itself) at zero — corpus program 87 in § 7.

### 4.6 Credential redaction and determinism of Err values

The `Err` payload is constructed from the *already-translated* `InterpretError`, downstream of every Q-042 mechanism: `IoFailure.detail` has passed through `CredentialScrubber.scrub` at exception construction and through the `ErrorVerbosity` gate in `translateIoFailure` (`Interpreter.kt`). A caught error's detail string is therefore exactly the scrubbed form the host would have seen — catching cannot recover a credential that redaction removed, and `ErrorVerbosity.RedactedWithKindOnly` yields `Err` payloads whose detail is the fixed suppression placeholder.

One caveat is inherent and recorded rather than hidden: `detail` strings interpolate JVM exception text (`IOException.message`), which varies by platform and locale. A program that *branches* on `detail` is therefore nondeterministic across hosts; `kind` is the stable vocabulary and the documented branching surface. The agent-facing system-prompt documentation must state this. Programs that merely propagate `detail` outward (the common case — returning it as diagnostic output for the agent's next generation) are unaffected.

## 5. Verifier rules

**Typing.** `infer(Attempt) = SumType[Ok(infer(body)), Err(ErrorPayload)]`, where the Ok case's `caseType` is the body's inferred type and `ErrorPayload` is the fixed product of § 4.2. The synthesized SumType is constructed through the same store-backed path as `synthesizeInputEventSum` so it is hash-identical to an agent-declared equivalent.

**AttemptBodyMustBeMonomorphic** — new `VerifyError` variant. If the body's type is a `Forall`, reject: `Result<Forall ...>` would put a polymorphic value inside a monomorphic sum case, which no Match could eliminate under the explicit-instantiation discipline. Mirrors `HandlerOverPolymorphicHandle`.

**Effect closure: unchanged by construction.** `closureOf(attempt) = closureOf(body)`. Failures are not effects; an Attempt declares nothing, subtracts nothing, and narrows nothing. This is the load-bearing soundness statement for Q-044: the harm bound `closure(g) ∩ C ∩ B ∩ P` is computed identically with and without Attempt nodes, and every operation a post-failure continuation can perform was already in the closure, already gated by the capability context, already metered by the budget, and already filtered by the sandbox. What Attempt changes is only whether evaluation *halts* at a catchable failure — and the bound is a statement about what the program can do, not about whether it runs to completion. The uncatchable list in § 4.3 is exactly the set of variants where continuation would weaken a host policy lever (budget, sandbox, capability denial), so the containment argument survives with no new clauses.

No closure-rule interaction with Handler arises: a Handler inside an Attempt body still subtracts its `intercept` from the body's closure before the Attempt passes it through; an Attempt inside a Handler body is an ordinary expression whose closure unions upward. The two nodes compose without special cases.

## 6. Interpreter / runtime semantics

### 6.1 Tree-walking interpreter

A single new case in the `eval` dispatch:

```kotlin
is Node.Attempt -> {
    try {
        val v = eval(node.body, env, context, handlers, counters, limits)
        counters.allocV(Value.SumV(case = "Ok", payload = v), limits)
    } catch (e: InterpretException) {
        if (!e.error.isCatchable) throw e
        counters.allocV(Value.SumV(case = "Err", payload = errorPayload(e.error)), limits)
    }
}
```

`isCatchable` is a property on the sealed `InterpretError` hierarchy implementing the § 4.3 table (`IoFailure` and `SchemaInvariantViolation` true, every other variant false) — keeping the classification next to the variants so a future variant cannot be added without taking a position. `errorPayload` builds `ProductV { kind, detail }`: for `IoFailure`, the variant's own fields; for `SchemaInvariantViolation`, `kind = "schema-invariant"` and `detail = valueDescription` prefixed with the invariant's structural name when resolvable from the store. Raw JVM exceptions (the `require(...)` contract failures of § 4.3) are *not* caught — only `InterpretException` is inspected, so anything unstructured remains terminal by default.

Capability context, the active-handler list, and the schema-obligation map all thread through unchanged — Attempt is transparent to all three. The Q-047 check fires at the body's obligation sites as usual; when it raises inside an Attempt, the catch converts it. Stack-depth bookkeeping needs no special handling: every `eval` frame decrements `counters.currentDepth` in its `finally`, so unwinding to the Attempt restores the correct depth.

### 6.2 Interaction with EvaluationLimits

`ResourceExhaustion` is uncatchable, so every Q-040 budget remains a hard stop — `maxSteps`, `maxStackDepth`, `maxAllocatedValues`, and the wall clock all propagate through any number of nested Attempts. Equally important: the counters are shared and are *not* reset by a catch. Steps, allocations, and elapsed time consumed by a failed body remain consumed, so a retry loop is budget-bounded by construction — a program cannot use Attempt to extend its own budget, only to spend the granted budget on retries instead of on a crash. This is the correct division of labor: the host bounds total resources once; the program decides how to allocate them across attempts.

### 6.3 Bytecode VM lowering and unwind protocol

The Q-047 foundational slice set a precedent of interpreter-only runtime enforcement, pinning the VM's non-enforcement in a guard test. That precedent is **not** repeated here, for a reason that distinguishes the two features: schema enforcement is a check the VM merely omits — pass-path results still agree. Attempt is result-shaping — a fallback program's entire value is the `Err` branch, so a VM without Attempt support cannot run the Tier 1 retry tasks at all, and the Lowerer would reject the node anyway. Both backends ship in the same slice.

Lowering (`bytecode/Lowerer.kt`), for `Attempt(body)`:

```
ATTEMPT_PUSH @errLabel      ; push (frameIndex, stackDepth, handlerDepth, capDepth, errLabel)
<body opcodes>
WRAP_SUM "Ok"               ; pop v, push SumV("Ok", v)
ATTEMPT_POP
JUMP @endLabel
errLabel:                   ; unwinder has pushed the ErrorPayload ProductV
WRAP_SUM "Err"
endLabel:
```

Runtime (`vm/Vm.kt`): `runLoop` keeps an attempt-marker stack alongside the existing capability stack and active-handler list. Today both `IoFailure` and `SandboxViolation` propagate raw out of the VM's `builtin.invoke` with no translation layer — a pre-existing parity gap with the interpreter's `translateIoFailure` path. This work adds the catch boundary at the dispatch step and resolves the gap along the way: the unwinder handles `IoFailure` and any `InterpretException` whose error `isCatchable`; `SandboxViolation` is translated to its terminal `InterpretError` form and rethrown. On a catchable failure with a non-empty marker stack: truncate `frames` and the operand stack to the marker's recorded depths, restore handler/capability depths, push the `ErrorPayload` product, jump to `errLabel`. With an empty marker stack, rethrow. `VmResourceExhaustion`, `VmCapabilityViolation`, `VmNoMatchingCase`, and `SandboxViolation` are never caught by the unwinder — the uncatchable filter is enforced by exception type before the marker stack is consulted.

One asymmetry is inherited rather than created: the VM does not enforce schema invariants (Q-047 deferred item (c)), so a program that catches a `SchemaInvariantViolation` diverges between backends — the interpreter takes the `Err` branch, the VM never raises. The schema-catch corpus program is therefore interpreter-only with a VM guard test, exactly as `CorpusRuntimeSchemaTest` pins the violation case today; the divergence closes when `CHECK_SCHEMA` lands. IoFailure-catching programs have full VM equivalence because `Builtins` constructs the failure identically for both backends and the payload excludes NodeIds (§ 4.2).

### 6.4 Layer A surface

Three coordinated additions to `authoring/LayerAGrammar.kt` and the elaborator:

- **`TRY body:ref`** — the Attempt node code. `tryRead TRY readApp`.
- **`RES okType:ref`** — type sugar expanding to `SUM [SCS "Ok" okType, SCS "Err" errPayloadT]`, mirroring the IF/WHEN expansion precedent. Saves the three-line sum declaration at every use.
- **Prelude entries** — `errPayloadT` (the ErrorPayload ProductType) plus its two `PRF` constituents (`errKindField`, `errDetailField`), so `Err(e)` patterns and `PFG e "kind"` reads need no local declarations.

One new elaborator inference case: a `WHEN` whose scrutinee is a `TRY` infers its `sumType` as the synthesized Result over the body's inferred type, letting the density-v4 form omit the `RES` annotation entirely. The agent-facing system prompt (`evaluation/dynamic/prompts/strand-system.md`) gains a short error-model section: the `TRY`/`RES` codes, the kind vocabulary, the branch-on-kind-not-detail rule, and the uncatchable list stated as "these terminate evaluation regardless of TRY".

## 7. Test scenarios

1. **Ok passthrough** — `TRY` over a pure expression; result is `Ok(v)`; VM equivalence holds. (Corpus 85.)
2. **Fs.Read fallback** — `TRY (APP fsRead [missing])` under a workspace sandbox; result is the `Err` branch's default; `Err.kind == "filesystem-read"`; VM equivalence holds. (Corpus 86.)
3. **Retry with backoff** — Fixpoint counting down attempts, `sleep` between tries, against a builtin that fails deterministically; result is the fallback after exactly N attempts; recorded step counts confirm the failed attempts consumed budget. (Corpus 87, the canonical agent program.)
4. **ResourceExhaustion propagates** — infinite Fixpoint inside `TRY` under tight `maxSteps`; `InterpretException(ResourceExhaustion)` reaches the host; the Attempt does not produce `Err`. (Unit test — corpus runs under defaults.)
5. **SandboxViolation propagates** — `TRY (APP fsRead [escape-path])`; `SandboxViolation(FsPathEscape)` reaches the host uncaught.
6. **CapabilityViolation propagates** — `TRY` over an effectful call evaluated under an empty capability context; denial reaches the host uncaught.
7. **NoMatchingCase propagates** — `TRY` over a Match with no covering case; uncaught.
8. **Schema-invariant catch (interpreter)** — `TRY` over a dynamic value flowing into a violated schema obligation (the corpus-83 shape); interpreter yields `Err(kind = "schema-invariant")`; VM guard test pins non-enforcement until `CHECK_SCHEMA`.
9. **Nested Attempts** — inner `TRY` catches; outer sees `Ok(innerResult)`; an uncatchable error crosses both.
10. **Attempt inside a Handler body and Handler inside an Attempt body** — interception and catching compose; the Handler's closure subtraction is unaffected; a failure raised by the *handler function itself* is caught by an enclosing Attempt like any other failure.
11. **Credential scrubbing of caught detail** — register a credential, force an `IoFailure` whose raw detail embeds it, catch; the `Err.detail` carries `[REDACTED:...]`. Under `RedactedWithKindOnly`, detail is the suppression placeholder.
12. **Verifier: AttemptBodyMustBeMonomorphic** — `TRY` over a TypeAbstraction is rejected.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Catchable capability denial.** Decided uncatchable (§ 4.3). Unblocked by designing an explicit `Policy.Probe` effect category if the capability-adaptive-program workload materializes — probing as a declared, grantable, closure-visible effect rather than a side channel of catch.
- **Structured error payload (sum over error classes).** The flat `{kind, detail}` product is deliberately growth-stable (§ 4.2). A typed payload sum would give richer static branching at the cost of hash-breaking every Err-matching program whenever a class is added. Revisit only if kind-string branching proves error-prone in the dynamic evaluation.
- **Try-variant builtins.** Subsumed by Attempt; not shipped even as sugar, to keep one recovery surface.
- **A blessed retry combinator** (`Retry.WithBackoff` as a library Fixpoint or higher-order builtin). Corpus 87 establishes the hand-rolled pattern first; a combinator is an authoring-layer economy to measure, not a semantics question.
- **Abort/timeout handlers and the algebraic failure channel** (option (d)). Upward-compatible if continuation-bearing handlers ever land.
- **Q-046 bridge feeder failures.** An IO failure inside a host-side stream feeder is delivered to no expression context; surfacing it as a poisoned stream event or group-level halt is actor-runtime design, out of scope here.
- **Structured terminal variant for builtin contract failures.** `Int.Div` by zero and kin currently escape as raw `IllegalArgumentException`; they should become a structured uncatchable `InterpretError` variant for host-report hygiene. Independent cleanup.

**Real research questions:**

- *Does observable failure change agent emission behavior?* The Q-044 follow-up measures whether mandatory effect declaration surfaces intent; an analogous question is whether observable failure tempts agents to over-wrap (a `TRY` around everything, masking defects the taxonomy deliberately keeps terminal). The uncatchable list bounds the damage — defects still escape — but the dynamic evaluation should track `TRY` density per task.
- *Cross-host determinism of `detail`.* § 4.6 documents branch-on-kind as the rule; whether the rule needs enforcement (e.g., a lint in the authoring layer rejecting `String.Eq` against `Err.detail`, or normalizing detail strings at the builtin boundary) is open until misuse is observed.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `impl-kotlin/core/Node.kt` | `data class Attempt(val body: NodeId)` + category name | Small |
| `impl-kotlin/core/Json.kt` | Ingest case for `"Attempt"` | Small |
| `impl-kotlin/verifier/Verifier.kt` | `inferAttempt`: Result-sum synthesis, monomorphism check, closure passthrough | Medium |
| `impl-kotlin/verifier/VerifyError.kt` | `AttemptBodyMustBeMonomorphic(at, residual)` | Small |
| `impl-kotlin/interpreter/InterpretError.kt` | `isCatchable` property on the sealed hierarchy | Small |
| `impl-kotlin/interpreter/Interpreter.kt` | `Node.Attempt` eval case + `errorPayload` builder | Small |
| `impl-kotlin/hashing/CategoryTag.kt`, `CanonicalEncoder.kt` | Tag 47, encode case, `Hasher.walk` case | Small |
| `impl-kotlin/bytecode/Opcode.kt`, `Lowerer.kt` | `ATTEMPT_PUSH` / `ATTEMPT_POP` / `WRAP_SUM`, Attempt lowering | Medium |
| `impl-kotlin/vm/Vm.kt` | Attempt-marker stack, catchability-filtered unwind in `runLoop`, IoFailure translation parity | Medium |
| `impl-kotlin/authoring/LayerAGrammar.kt` | `TRY` code, `RES` sugar, `errPayloadT` prelude entries | Small |
| `impl-kotlin/authoring/Elaborator.kt` | WHEN-sumType-from-TRY inference case | Small |
| `corpus/85-87*.json` + corpus tests | Scenarios 1–3 as corpus programs; 4–12 as unit tests (`AttemptTest`, VM equivalence entries, schema-catch guard) | Medium |
| `evaluation/dynamic/prompts/strand-system.md` | Error-model section: TRY/RES, kind vocabulary, uncatchable list | Small |

Design-corpus changes when accepted: N-047 row in `node-algebra.md` § Control flow and the INDEX registry; `effects-and-capabilities.md` gains a one-paragraph cross-reference distinguishing Attempt (observes failure) from Handler (replaces dispatch); Q-048 entry in `open-questions.md` updated with the resolution.

**Order of work.** (1) Node + ingest + encoding (hash-stability tests first); (2) verifier; (3) interpreter + taxonomy + unit tests 4–12; (4) corpus 85–87; (5) bytecode/VM + equivalence tests; (6) authoring + prompt documentation. Steps 1–4 are a usable interpreter-only checkpoint if the slice must split, but per § 6.3 the slice is not *done* until step 5 lands.

**Not in this slice.** Everything under § 8 deferrals; VM schema enforcement (tracked under Q-047); any change to Handler (N-043) semantics; retry combinators.

## References

**Outgoing references:**
- [`design/node-algebra.md`](../../design/node-algebra.md) — node inventory, hash construction, versioning rule for new category tags
- [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md) — effect closure semantics, § Effect handlers (the interception-before-dispatch limitation)
- [`decisions/ADR-004-effects-as-edges.md`](../../decisions/ADR-004-effects-as-edges.md) — effects as mandatory declarations; failures are not effects
- [`proposals/implemented/effect-handlers.md`](effect-handlers.md) — N-043 semantics, the no-continuation restriction, closure subtraction
- [`proposals/implemented/interpreter-resource-limits.md`](interpreter-resource-limits.md) — Q-040 `EvaluationLimits`, the budget contract `ResourceExhaustion` must keep
- [`proposals/implemented/io-builtin-sandboxing.md`](io-builtin-sandboxing.md) — Q-041 `SandboxPolicy`, the policy boundary `SandboxViolation` must keep
- [`proposals/implemented/credential-isolation.md`](credential-isolation.md) — Q-042 scrubbing and `ErrorVerbosity`, upstream of the Err payload
- [`proposals/implemented/runtime-schema-enforcement.md`](runtime-schema-enforcement.md) — Q-047 `SchemaInvariantViolation`, the never-silently-emitted property § 4.2 preserves
- [`open-questions.md`](../../open-questions.md) — Q-048 (this proposal), Q-040, Q-041, Q-042, Q-044, Q-047, Q-016
- [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/InterpretError.kt`](../../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/InterpretError.kt) — the sealed hierarchy § 4.3 classifies exhaustively
- [`corpus/64-option-parseint-unwrap.json`](../../corpus/64-option-parseint-unwrap.json), [`corpus/65-option-parseint-fallback.json`](../../corpus/65-option-parseint-fallback.json) — the Option-matching convention the Result convention extends

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-048 points at this proposal
- [`ROADMAP.md`](../../ROADMAP.md) — Tier 2 "In-language error recovery"; Tier 1 retry-loop-exercising tasks
- [`proposals/README.md`](../README.md)
