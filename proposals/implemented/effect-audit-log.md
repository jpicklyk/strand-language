# Effect-audit log

**Document:** `proposals/implemented/effect-audit-log.md`
**Status:** Implemented 2026-07-04 (commit `c4fa701` on `reason-first`); single-process core + `strand run --audit` CLI, transition-phase enrichment of the audit record deferred
**Question:** [Q-055](../../open-questions.md#Q-055) (resolves the single-process core)
**Related:** [ADR-010](../../decisions/ADR-010-reasoning-surface.md), [`design/security-model.md`](../../design/security-model.md), [Q-044](../../open-questions.md#Q-044), [Q-054](../../open-questions.md#Q-054), [Q-042](../../open-questions.md#Q-042) (credential scrubbing), [Q-064](../../open-questions.md#Q-064) (denial reports)
**Drafted:** 2026-07-04

## Problem

The reasoning surface ([ADR-010](../../decisions/ADR-010-reasoning-surface.md)) reports what a program *may* do before execution. Nothing records what it *did*. `design/security-model.md` asserts the runtime emits structured logs and metrics and persistently records admissions, grants, and effect violations; the implementation has none of the three. `strand run` emits a type line and a value line, no hook at the foreign-call boundary records which effects a program actually exercised, and the only enforcement evidence an operator has is the absence of a `CapabilityViolation`. This is the gap between the containment bound being argued ([Q-044](../../open-questions.md#Q-044)) and being continuously observable — the runtime half of the reason-first story, and the empirical check on the pre-execution bound the reasoning surface computes: an audit log is what lets an operator confirm that a run's performed effects stayed within its declared closure, and it is what would surface silent-success gaps that a static bound alone cannot.

## Recommended approach

Emit a structured audit record at the single foreign-dispatch boundary, where the EffectDecl, the resolved capability pattern, and the evaluated argument values are already in hand.

- `AuditRecord(callSiteNodeId, effectCategory, refinementParameters, outcome, instanceId?, eventIndex?, phase?)` — the call-site NodeId, the effect category name, the evaluated refinement parameter values (scrubbed), the outcome (`Allowed` or a denial discriminator), and, for state-machine runs, the instance and event index and phase already carried by the [Q-064](../../open-questions.md#Q-064) `DenialReport`. The allowed path is the new information; the denied path reuses the Q-064 report's fields so the two reconcile.
- Refinement parameter values pass through the per-context credential `Scrubber` ([Q-042](../../open-questions.md#Q-042)) exactly as denial reports and error text do — argument values in the log must be the scrubbed forms.
- The sink is an interface on the `HostPolicy` / `HostContext` ([Q-054](../../open-questions.md#Q-054)), defaulting to a no-op so the audit log is opt-in and per-tenant (each tenant's context scrubs and sinks its own records, consistent with the concurrent-isolation model). A host installs a sink to collect records; nothing is emitted by default.
- CLI: a `strand run --audit <file>` mode writing the records as newline-delimited JSON, mirroring the always-on `strand:denial` line ([Q-064](../../open-questions.md#Q-064)) but covering the allowed path too.

The result lets an operator reconcile the declared closure (`ProgramAnalysis.totalClosure`) against the per-run performed effects: every performed effect should fall within the pre-execution bound, and the audit log is the artifact that demonstrates it did. It composes with self-gating ([Q-073](../../open-questions.md#Q-073)) — the guarded run records what actually happened under the granted budget — and with replay: the recorded effects are a candidate substrate for effect-replay of plain programs, deferred here.

## Where it lives

The `AuditRecord` type and the `AuditSink` interface on `HostContext` / `HostPolicy` in `:interpreter`; the emission point at the interpreter's `checkCapabilities` / foreign-dispatch boundary (the same site the Q-064 `DenialReport` is built); the CLI `--audit` flag in `:cli`. The async actor runtime threads the sink into per-actor contexts alongside the existing metrics, so group runs audit per instance.

## Deferred and open

- Graph-admission and capability-grant records (the other two of the three the security model names) are a lighter addition on the same sink and can follow; the foreign-dispatch effect record is the load-bearing core.
- Feeding the recorded effects into a replay substrate for plain (non-state-machine) programs is deferred.
- A structured sink target beyond newline-delimited JSON (a metrics system, an external audit service) is a host concern beyond the reference implementation.

## Scope

Small to medium. A record type, a per-context sink interface with a no-op default, one emission point at the existing dispatch boundary, a CLI flag, and tests. Hash-neutral; runtime policy only, no node, encoding, or golden-hash change.

## References

**Outgoing references:**
- [`decisions/ADR-010-reasoning-surface.md`](../../decisions/ADR-010-reasoning-surface.md) — the pre-execution bound this makes empirically observable
- [`design/security-model.md`](../../design/security-model.md) — the monitoring claims this closes
- [`open-questions.md`](../../open-questions.md) — Q-055 (resolved core), Q-044, Q-054, Q-042, Q-064, Q-073

## Implementation note

Landed in `impl-kotlin/` on the `reason-first` branch. Hash-neutral, runtime policy only — the default no-op sink means every existing test and run behaves identically; the full suite passes with no golden regeneration.

Anchors:
- `interpreter/AuditRecord.kt` — new `AuditRecord(callSiteNodeId, effectCategory, refinementParameters, outcome, instanceId?, eventIndex?, phase)`, the `AuditOutcome` sealed hierarchy (`Allowed` object, `Denied(report)` reusing the Q-064 `DenialReport`), the single-method `AuditSink` fun-interface, the `NoOpAuditSink` opt-in default, and a thread-safe `CollectingAuditSink` for tests / in-process hosts.
- `interpreter/HostPolicy.kt` and `interpreter/HostContext.kt` — a new `auditSink: AuditSink = NoOpAuditSink` field on both, threaded `HostPolicy.auditSink -> HostContext.auditSink` in `HostContext.fromPolicy`. The `StrandRuntime` facade already derives every per-run and per-actor context through `fromPolicy`, so `runGroup` threads the sink into per-actor contexts alongside the existing metrics with no further wiring.
- `interpreter/Interpreter.kt` — `checkCapabilities` now emits through `hostContext.auditSink` at the same foreign-dispatch boundary the Q-064 `DenialReport` is built: an `Allowed` record per declared category the call site concretely exercises (an EffectDecl instance present and the check passing), and a `Denied` record reusing the report being built on each violation. Refinement values on the allowed path are rendered and scrubbed through the per-context `Scrubber` via `renderAuditParameter` (the Q-042 seam), exactly as the denial report scrubs its `requested` list.
- `cli/AuditLine.kt` — the `strand run --audit <file>` sink: a `FileAuditSink` writing one newline-delimited JSON object per record (mirroring the always-on `strand:denial` line's compact shape, plus the `author` / `line` source-map fields), flushed per record and closed in a `finally`. Wired in `cli/Main.kt` via a new `extractAuditPath` flag extractor (mirroring `extractStore`) and `basePolicy.copy(auditSink = ...)` in the `run` branch; the usage line advertises `[--audit <file>]`.

Deviation / scope: the CLI `--audit` flag shipped (it was a modest addition). One honest limitation: the audit record is emitted at the interpreter site, so a state-machine transition-phase Allowed/Denied record carries `phase = Expression` and null `instanceId` / `eventIndex` (the runtime re-tags the reused `DenialReport` at halt translation for the denial surface, but that enrichment does not flow back into the already-emitted audit record). The load-bearing fields Q-055 specifies — category, call-site, scrubbed refinement values, and the Allowed/Denied outcome — are correct on every dispatch; per-instance transition enrichment of the audit record itself is a follow-up.

Tests (12 total): `interpreter/EffectAuditLogTest` (4 — an allowed dispatch emits one `Allowed` record with the right category, call-site, and refinement value; a denied dispatch emits one `Denied` record reusing the report; the default no-op sink emits nothing and the run is unchanged; a credential-bearing refinement value appears scrubbed via the Q-042 seam) and `cli/AuditLineTest` (8 across three cases — allowed/denied NDJSON rendering with author/line/transition fields, and the file sink writing one newline-delimited record per emission).
