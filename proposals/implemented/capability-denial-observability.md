# Capability-Denial Observability for Orchestrating Principals

**Document:** `proposals/implemented/capability-denial-observability.md`
**Status:** Implemented (landed 2026-06-12 in the Kotlin/JVM reference implementation; see the Implementation note)
**Date:** 2026-06-12
**Concerns:** [Q-064](../../open-questions.md#Q-064), [Q-048](../../open-questions.md#Q-048) (the uncatchable taxonomy this preserves), [Q-051](../../open-questions.md#Q-051) (node-id annotation reused), [Q-054](../../open-questions.md#Q-054) (the embedding API this shapes), [Q-055](../../open-questions.md#Q-055) (the audit-log record kind this shares), [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md), [`design/security-model.md`](../../design/security-model.md)
**Scope:** small-medium

A generated program that hits a capability or refinement denial terminates, uncatchably, by design. The principal that constructed the capability context — an orchestrating agent deciding whether to repair the program or re-scope the grant — currently learns about the denial only from rendered error text. This proposal gives that principal a structured denial outcome at the host boundary while changing nothing observable from inside the graph.

## Implementation note (2026-06-12)

Implemented as proposed, in the proposed order (report type and interpreter site; runtime attachment; CLI line; opacity re-assertion). Full suite after the slice: 2,139 tests, 0 failures (2,120 baseline + 19 new), zero golden-hash impact.

**The record.** `interpreter/DenialReport.kt` carries `category`, `requested`, `held`, `node`, `instanceId`, `eventIndex`, and a `DenialPhase` discriminator (`expression` / `transition` / `invariant` / `group-start`). It is constructed inside `Interpreter.checkCapabilities` from values already in scope and carried as a `report` field on both `InterpretError.CapabilityViolation` and `InterpretError.RefinementViolation`. Parameter values pass through `CredentialScrubber.scrub` at construction; under `ErrorVerbosity.RedactedWithKindOnly` the `requested` and `held` lists are withheld entirely (category and node survive). The phase is `invariant` when the interpreter's `inInvariant` guard is set — unreachable on verified graphs (`SchemaInvariantBodyMustBePure`) but represented honestly on the trusting-interpreter path. Nothing became observable in-language: no new `Value`, no `isCatchable` change, and the uncatchability tests re-assert that an enclosing Attempt neither observes the denial nor changes the report.

**Surface 1.** The error variants carry the report, and denial-caused state-machine halts expose it: the synchronous fold (`runMachine` / `resume`) translates a per-event denial into a new `HaltReason.CapabilityDenial(report)` — the `ResourceExhaustion` translation precedent — with instance id, zero-based event index, and phase `transition` attached at translation; the async actor halts cleanly on a denial (other actors keep running) and surfaces the same enriched report through `MachineInstanceHandle.denialReport`. Denials during `runGroup` startup (initial spawn evaluation, Q-046 source openers) rethrow with the report re-tagged phase `group-start`.

**Surface 2.** On any denial-caused termination under `run`, `machine`, or `group`, the CLI emits exactly one `strand:denial {json}` stderr line alongside the existing human rendering, then exits 1. The JSON is the report field-for-field; the denying node renders as `#N` with the Q-051 author id and Layer A source line as separate `author` / `line` fields (two new `NodeRefAnnotator` accessors). Worked shape as shipped:

```
strand:denial {"category":"Filesystem.Write","requested":["/etc/passwd"],"held":["Filesystem.Write{/tmp/work/log.txt}"],"node":"#12","author":"writeCall","line":9,"instance":null,"eventIndex":null,"phase":"expression"}
```

**Deviations from the proposal text.** First, `requested` is a positional JSON array of rendered strings, not the § 3 worked example's name-keyed object — `EffectCategory.parameters` is a positional list of types with no parameter names in the node algebra, so the worked example implied names that do not exist; `held` entries render as `Category{slot, ...}` with `*` for wildcard slots. Second, the line carries `eventIndex` as its own field (the § 3 worked shape showed only `instance`). Third, the bytecode VM's denial was unified at the `InterpretError` boundary the way `VmResourceExhaustion` was: `VmCapabilityViolation` is removed, the CALL-site check throws the shared `InterpretException` carrying `CapabilityViolation` with an honestly coarse report — category rendered as the `#N` NodeId (the VM holds no store to resolve the name), `requested` null (category-only checking; no refinements fabricated), node null (no opcode source mapping in slice 1) — and `CapabilityViolation.at` became `NodeId?` for VM parity, following the `BuiltinContractViolation` precedent. Fourth, the sync `runMachine` previously rethrew denials out of the fold; converting them into `HaltReason.CapabilityDenial` is a behavior change to the programmatic surface (no existing test depended on the rethrow), and the group runtime no longer crashes the whole coroutine scope on one actor's denial. For group runs where several actors deny, the CLI emits the first report in instance-id order — one line per terminated run. Fifth, scenario 6's invariant-phase denial is exercised at the interpreter level (verified graphs cannot carry effectful invariant bodies), and the CLI scenarios drive the `DenialLine` helper that every `Main.kt` denial path routes through exactly once, because CLI failure paths call `exitProcess` (the `CliFederationTest` precedent of not driving erroring `main(...)` invocations in-JVM).

## 1. Problem statement

Q-048 classifies capability and refinement denials as terminal regardless of any enclosing Attempt. The rationale is sound: an in-language catch would let a malicious or curious generated program probe the boundaries of its own sandbox. But the rule's adversary is the generated program, not the orchestrator. The orchestrator granted the context; it already knows the grant set; a denial tells it something it is entitled to act on — regenerate the program with a narrower effect declaration, or widen the grant deliberately. Today both the CLI and the programmatic entry points flatten the denial into display text, so an orchestrating agent must parse prose to recover which category, which refinement parameter, and which node denied — exactly the reverse-engineering burden Q-051 removed from verifier errors.

## 2. Recommended approach

One record type, two existing landing surfaces, no new graph or runtime semantics.

**The record.** A `DenialReport` carrying: the effect category name; the requested refinement parameters as rendered, scrubbed strings; a rendered summary of the grants held in context for that category (so the orchestrator sees the mismatch, not just the request); the denying node id with its Q-051 author annotation (`authorId`, source line where available); the machine instance id and event index when the denial occurred inside a state-machine runtime; and a phase discriminator (expression evaluation, transition, invariant, group start). Parameter values pass through `CredentialScrubber` at construction and respect the active `ErrorVerbosity`: under `RedactedWithKindOnly`, values are omitted and only category and node survive.

**Surface 1 — the programmatic result.** The existing capability-denial error variant carries the report as structured data rather than (only) pre-rendered text, and the state-machine halt reason for a denial-caused halt exposes the same report. This is the field the Q-054 embedding API will return verbatim; landing it now means the embedding work inherits it instead of inventing it.

**Surface 2 — the CLI.** On any denial-caused termination under `run`, `machine`, or `group`, the CLI emits exactly one machine-readable line to stderr — `strand:denial {…json…}` — alongside the existing human-rendered error. Always on, no flag: denials are terminal, so the line cannot be noisy, and an orchestrator driving the CLI gets the structure without opting in. The JSON shape is the `DenialReport` field-for-field.

**What does not change.** No new `Value`, no new node category, no change to `isCatchable`, no behavior visible to the evaluating graph. Attempt still cannot observe a denial; a test re-asserts this against the new code path. The report is constructed at the existing denial site after the evaluation outcome is already decided.

## 3. Detailed mechanism

At the existing capability-check failure site, the checker already holds the category, the requested instance parameters, and the context's grant set — the report is assembled from values in scope, not from new bookkeeping. The node-id annotation reuses the Q-051 ingest name map through the same CLI plumbing that annotates verifier errors; programmatic callers receive the raw `NodeId` plus the author id when the map is available. For group runs, the runtime attaches instance id and event index when translating the denial into the halt reason.

Worked shape of the stderr line for a refinement denial:

```
strand:denial {"category":"Filesystem.Write","requested":{"path":"/etc/passwd"},"held":["Filesystem.Write{path: /tmp/work/*}"],"node":"#12","author":"writeCall","line":9,"instance":null,"phase":"expression"}
```

## 4. Verifier rules

None.

## 5. Runtime semantics

None new — the denial still terminates evaluation identically; only the reporting of the already-decided outcome gains structure.

## 6. Test scenarios

1. **Run denial report** — a `run` hitting a refinement denial emits one valid `strand:denial` JSON line with category, requested parameters, held summary, and annotated node.
2. **Group denial report** — a denial inside a machine transition includes instance id, event index, and phase `transition`.
3. **Scrubbing** — a credential-bearing parameter value is scrubbed in both the programmatic report and the stderr line.
4. **Kind-only verbosity** — under `RedactedWithKindOnly` the report carries category and node but no parameter values.
5. **Opacity preserved** — a program wrapping the denying call in Attempt still terminates; the report is identical; no in-language observation point exists (asserted by the existing uncatchability test extended over the new path).
6. **Single line** — exactly one denial line per terminated run, including when the denial occurs inside an invariant evaluation.
7. **Non-denial runs** — no `strand:denial` line on success, IoFailure, or resource exhaustion.

## 7. Tradeoffs and open questions

**Deferred intentionally:**

- **Audit-log integration** — Q-055's per-dispatch log should reuse this record shape for denial entries; landing order is independent.
- **Repair hints** — the report states facts (requested versus held); suggesting a minimal widening is orchestrator policy, not runtime output.
- **Stdout protocol generalization** — a general machine-readable event protocol for the CLI is Q-054/Q-055 territory; this ships one line for one terminal event.

**Real research questions:**

- *Held-grant disclosure breadth* — the report summarizes grants for the denied category only. Whether even that is too much surface for some multi-tenant hosts is a Q-054 policy knob (the embedding API can suppress the `held` field); the CLI default discloses it because the CLI principal is the grantor.

## 8. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `impl-kotlin/interpreter` (denial site + error variant) | `DenialReport` construction; structured field on the capability-denial error | Medium |
| `impl-kotlin/runtime` (halt translation) | instance/event/phase attachment on denial halts | Small |
| `impl-kotlin/cli` | `strand:denial` stderr line on run/machine/group denial terminations; JSON rendering; Q-051 annotation reuse | Small |
| tests (interpreter, runtime, cli) | scenarios 1–7 | Medium |

**Order of work.** Report type and interpreter site first; runtime attachment; CLI line; opacity re-assertion last.

**Not in this slice.** Q-055 audit log, Q-054 embedding API, any change to catchability or the denial decision itself.

## References

**Outgoing references:**
- [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md) — the capability check whose outcome is reported
- [`design/security-model.md`](../../design/security-model.md) — the threat framing distinguishing program from principal
- [`proposals/implemented/error-recovery.md`](error-recovery.md) — the uncatchable taxonomy preserved
- [`open-questions.md`](../../open-questions.md) — Q-048, Q-051, Q-054, Q-055, Q-064

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-064 points at this proposal
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — implementation-state list
- [`ROADMAP.md`](../../ROADMAP.md) — Tier 3.5 (item removed on resolution)
