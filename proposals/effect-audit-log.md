# Effect-audit log

**Document:** `proposals/effect-audit-log.md`
**Status:** Draft
**Question:** [Q-055](../open-questions.md#Q-055) (resolves the single-process core)
**Related:** [ADR-010](../decisions/ADR-010-reasoning-surface.md), [`design/security-model.md`](../design/security-model.md), [Q-044](../open-questions.md#Q-044), [Q-054](../open-questions.md#Q-054), [Q-042](../open-questions.md#Q-042) (credential scrubbing), [Q-064](../open-questions.md#Q-064) (denial reports)
**Drafted:** 2026-07-04

## Problem

The reasoning surface ([ADR-010](../decisions/ADR-010-reasoning-surface.md)) reports what a program *may* do before execution. Nothing records what it *did*. `design/security-model.md` asserts the runtime emits structured logs and metrics and persistently records admissions, grants, and effect violations; the implementation has none of the three. `strand run` emits a type line and a value line, no hook at the foreign-call boundary records which effects a program actually exercised, and the only enforcement evidence an operator has is the absence of a `CapabilityViolation`. This is the gap between the containment bound being argued ([Q-044](../open-questions.md#Q-044)) and being continuously observable — the runtime half of the reason-first story, and the empirical check on the pre-execution bound the reasoning surface computes: an audit log is what lets an operator confirm that a run's performed effects stayed within its declared closure, and it is what would surface silent-success gaps that a static bound alone cannot.

## Recommended approach

Emit a structured audit record at the single foreign-dispatch boundary, where the EffectDecl, the resolved capability pattern, and the evaluated argument values are already in hand.

- `AuditRecord(callSiteNodeId, effectCategory, refinementParameters, outcome, instanceId?, eventIndex?, phase?)` — the call-site NodeId, the effect category name, the evaluated refinement parameter values (scrubbed), the outcome (`Allowed` or a denial discriminator), and, for state-machine runs, the instance and event index and phase already carried by the [Q-064](../open-questions.md#Q-064) `DenialReport`. The allowed path is the new information; the denied path reuses the Q-064 report's fields so the two reconcile.
- Refinement parameter values pass through the per-context credential `Scrubber` ([Q-042](../open-questions.md#Q-042)) exactly as denial reports and error text do — argument values in the log must be the scrubbed forms.
- The sink is an interface on the `HostPolicy` / `HostContext` ([Q-054](../open-questions.md#Q-054)), defaulting to a no-op so the audit log is opt-in and per-tenant (each tenant's context scrubs and sinks its own records, consistent with the concurrent-isolation model). A host installs a sink to collect records; nothing is emitted by default.
- CLI: a `strand run --audit <file>` mode writing the records as newline-delimited JSON, mirroring the always-on `strand:denial` line ([Q-064](../open-questions.md#Q-064)) but covering the allowed path too.

The result lets an operator reconcile the declared closure (`ProgramAnalysis.totalClosure`) against the per-run performed effects: every performed effect should fall within the pre-execution bound, and the audit log is the artifact that demonstrates it did. It composes with self-gating ([Q-073](../open-questions.md#Q-073)) — the guarded run records what actually happened under the granted budget — and with replay: the recorded effects are a candidate substrate for effect-replay of plain programs, deferred here.

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
- [`decisions/ADR-010-reasoning-surface.md`](../decisions/ADR-010-reasoning-surface.md) — the pre-execution bound this makes empirically observable
- [`design/security-model.md`](../design/security-model.md) — the monitoring claims this closes
- [`open-questions.md`](../open-questions.md) — Q-055 (resolved core), Q-044, Q-054, Q-042, Q-064, Q-073
