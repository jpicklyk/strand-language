# Agent-platform demonstration {#agent-platform-demo}

**Document:** `demos/agent-platform/README.md`
**Status:** Runnable demonstration; built on shipped APIs only
**Last revised:** 2026-06-14

## What this demonstration is

This is the end-to-end narrative — the spine that turns twelve separate guarantees
into *a platform*. The other demonstrations each isolate one mechanism: a host that
bounds an unfamiliar program's harm, a clean-room that proves non-exfiltration, a
RAG pipeline whose data access is refinement-checked, a tool-using agent under a
visible effect bound, a model whose output is verified, a poisoned input contained
as data, a rogue agent contained in a group, a run that replays bit-identically.
Read separately they are points. This demonstration draws a line through them: it
walks **one untrusted, agent-generated task** — a retrieval-backed support agent —
through the **full lifecycle** of running it on Strand, drawing each guarantee from
the demonstration that isolates it, narrated as a single task's journey.

The thesis the line traces is the platform claim:

> Across the whole lifecycle of running untrusted, agent-generated code, the
> maximum harm is **computable from the artifact before execution** and
> **bounded during it** — the property no "LLM-writes-Python-in-a-sandbox" stack
> can offer.

A sandbox is a perimeter around a *running process*: it observes syscalls and
hopes the policy is complete. Strand reads a *graph*. The harm bound is not a
runtime observation that could be evaded; it is a property of the artifact,
computed before any code runs, and enforced by a graph that cannot *express* the
harm it does not declare. That is the difference between watching for exfiltration
and admitting only a program that has no node able to open a socket.

The five-stage arc is the lifecycle of running such a task:

1. **ADMIT — bound it before running.** The platform receives a program it has
   never seen, computes its maximum harm from the artifact (the Q-067 surfaced
   effect closure), confirms it is within the tenant's grant and free of egress
   (`Network.*` / `Process.*` absent), and admits it. An over-reaching submission
   is *rejected at admission*, before any execution.
2. **RUN — bounded execution.** The admitted agent runs under exactly its declared
   capabilities: it retrieves from one refined knowledge-base index
   (`Vector.Read{store}` only) and calls the model under its bound.
3. **VERIFY — the model's output is checked.** The model's structured response is
   constrained going out (N-045) and the returned value is verified at runtime
   (Q-047) before it is used; a semantically-invalid response is contained before
   output.
4. **WITHSTAND — attacks are contained.** An over-reaching tool effect is denied
   with a `DenialReport`; a query against a non-granted index is denied; a poisoned
   input is contained as data and cannot expand the action set; and at scale, a
   rogue agent in a group is denied while the others survive. The principle:
   untrusted **code** is bounded; untrusted **data** is contained.
5. **AUDIT — the run is reproducible and content-addressed.** The run replays
   bit-identically and the task is admitted-once / run-by-hash.

The host throughout is an ordinary JVM caller of the shipped embedding surface —
the Q-054 `StrandRuntime` facade, the Q-067 surfaced effect closure, the Q-031
refined `CapabilitySet` grant, and the Q-064 `DenialReport`. This demonstration
introduces no language feature, no node category, no encoding change, and no
verifier rule. It is the suite, narrated as a platform.

## What composes it (and what that means)

This demonstration is honest about being a **composition**. The "single task" is a
narrative *frame* over the suite's mechanisms; under the hood, each stage runs the
already-passing scenario method of the demonstration that isolates the underlying
guarantee. The driver (`AgentPlatformDemo`) calls those scenario methods directly —
they live as `object`s in the same `:runtime` test source set — and sequences them
into the five stages. Reusing the regression-tested bodies is the robustness
guarantee: every property shown is one another demonstration already pins, and
sequencing them through one composed pass proves the scenarios' builtin-install
discipline does not cross-contaminate.

| Stage | Guarantee | Drawn from |
|-------|-----------|------------|
| **ADMIT** | No egress in the closure → cannot exfiltrate, by construction | [`clean-room`](../clean-room/README.md) CR1 |
| **ADMIT** | Over-reach rejected at admission, before execution | [`containment-host`](../containment-host/README.md) S1 |
| **ADMIT** | Exfiltrator (egress in closure) refused before any run | [`clean-room`](../clean-room/README.md) CR2 |
| **RUN** | Retrieval bounded to one refinement-checked index | [`bounded-rag`](../bounded-rag/README.md) R1 |
| **RUN** | Model called under exactly its surfaced `{LLM.Generate}` bound | [`agent-workflow`](../agent-workflow/README.md) A1 |
| **VERIFY** | Structured response constrained (N-045) and valid value passes | [`verified-llm-output`](../verified-llm-output/README.md) VO1 |
| **VERIFY** | Semantically-invalid response contained at runtime (Q-047) before output | [`verified-llm-output`](../verified-llm-output/README.md) VO2 |
| **WITHSTAND** | Over-reaching tool effect denied at dispatch (untrusted code) | [`agent-workflow`](../agent-workflow/README.md) A2 |
| **WITHSTAND** | Query against a non-granted index denied (untrusted code) | [`bounded-rag`](../bounded-rag/README.md) R2 |
| **WITHSTAND** | Poisoned input contained as data; action set unchanged | [`skill-workflow`](../skill-workflow/README.md) SK2a |
| **WITHSTAND** | Rogue agent denied at its actor boundary; group survives | [`multi-agent-supervisor`](../multi-agent-supervisor/README.md) MA2 |
| **AUDIT** | Run replays bit-identically with zero live IO | [`replay-timetravel`](../replay-timetravel/README.md) R1 |
| **AUDIT** | Admitted-once, re-run by content hash with identical result | [`containment-host`](../containment-host/README.md) S4 |

Two demonstrations in the suite — [`mcp-tool-manifest`](../mcp-tool-manifest/README.md)
(machine-checked capability manifests) and [`llm-virtualization`](../llm-virtualization/README.md)
(effect virtualization via the N-043 Handler) — are not on the critical path of
*this* task's lifecycle, so the spine does not draw a stage from them; they
generalize the same admit/bound machinery to tool bundles and to intercepting an
effect wholesale, and the platform thesis covers them by the same argument.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:agentPlatformDemo -q
```

Run the assertion-backed test that pins every stage in lifecycle order:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.AgentPlatformDemoTest"
```

The driver `AgentPlatformDemo` and the test `AgentPlatformDemoTest` live in the
`:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one
composed `run()` pass, so the printed demonstration and the regression net cannot
diverge. They stay in `:runtime` because they compile against the runtime modules
and call the other demo drivers' public scenario methods directly. There are no
new programs of this demonstration's own: the optional fused happy-path program
was not built (see *What this demonstrates and what it does not* below), so each
stage runs over the hand-authored canonical dag-json the underlying demonstration
already committed under its own `programs/` directory.

## What this demonstrates and what it does not

**What it demonstrates.**

- **A lifecycle, not a feature.** The point is the *line*: admit → run → verify →
  withstand → audit, each step a real property the shipped runtime enforces,
  composed into one coherent story for one task. The maximum harm is computable
  before execution (ADMIT) and bounded during it (RUN / VERIFY / WITHSTAND), and
  the run is reproducible and content-addressed (AUDIT).
- **The code/data threat-model split.** The WITHSTAND stage shows both halves on
  one screen: untrusted *code* (an over-reaching tool, a query against a
  non-granted index, a rogue worker) is *bounded* — denied at a capability or
  refinement check with a structured `DenialReport` — while untrusted *data* (a
  poisoned model decision) is *contained* — consumed as inert digest content that
  cannot add a graph node, so it cannot add an action.
- **Composition without contradiction.** Thirteen scenario methods across nine
  demonstrations, run in lifecycle order through one pass, produce a clean
  transcript with no cross-contamination — the test asserts each stage's property
  and that the three WITHSTAND denials are distinct reports at distinct boundaries.

**What it does not demonstrate.**

- **It is a narrative frame, not a single fused program.** The "single task" is a
  story told over the suite's mechanisms. Each underlying demonstration isolates
  its own subject — its own hand-authored program, its own deterministic mock or
  stand-in (a mock `LlmHttpClient` / `VectorHttpTransport`, a `(String) -> Int`
  model stand-in, hand-authored `decisions` literals for the model's output) — so
  the stages do not literally pass one in-memory value down a single pipeline. An
  optional *fused* happy-path program (one verifying graph that embeds a query,
  reads a refined vector store, calls the model with an N-045 response schema,
  checks the returned value through a Q-047 schema position, and writes a report)
  was considered for stages 1–3 and **deliberately not built**: fusing
  `bounded-rag` and `verified-llm-output` into one verifying artifact is costly,
  and the narrative does not depend on it — the composed scenario methods carry
  every load-bearing property. The frame is explicit so nothing is overstated.
- **It is not first-pass correctness or inference cost.** Every underlying program
  is hand-authored; the model transports are mocks. Whether an agent *generates* a
  correct, admissible program on the first try, and at what token cost across its
  retry loop, is the deferred Run 8 dynamic study
  ([`model-api-integration.md`](../../proposals/model-api-integration.md), Q-021) —
  out of scope here, exactly as it is for every demonstration this composes.
- **It is a demonstration of mechanisms, not a soundness proof.** As stated in
  [`containment-results.md`](../../evaluation/containment-results.md), soundness is
  a universal property argued from the mechanisms with executed witnesses as
  spot-checks. This is the end-to-end executed witness — it drives the whole
  lifecycle through the published APIs a host would actually use — not a proof.

## Transcript

The exact output of `./gradlew :runtime:agentPlatformDemo -q`. The per-run-varying
root hash and machine instance id are shown as `<varies>`; everything else is
reproduced verbatim.

```text
========================================================================
Strand -- the agent platform, end to end
One untrusted, agent-generated task (a retrieval-backed support agent)
walked through the full lifecycle of running it on Strand. Each stage
draws its guarantee from the demonstration that isolates it.

Thesis: across the whole lifecycle of running untrusted agent code, the
maximum harm is COMPUTABLE before execution and BOUNDED during it -- the
property no 'LLM-writes-Python-in-a-sandbox' stack can offer.
========================================================================

STAGE 1  ADMIT -- bound it before running
========================================================================
The platform receives a program it has never seen. Before any code
runs, it computes the task's maximum harm from the artifact alone, and
admits the task only if that harm is within the tenant's grant.

  Admit the support agent: no egress in its closure (the clean-room proof)
------------------------------------------------------------------------
  The host reads the verifier's surfaced effect closure (Q-067) -- the
  task's maximum harm, computed from the artifact before it runs:
    surfaced closure          = [Filesystem.Read]
    egress categories watched = [Network.Connect, Network.Receive, Network.Send, Process.Spawn]
    egress in closure         = []
  egress in closure = [] -> the task CANNOT exfiltrate, by
  construction: the admitted graph has no node that opens a socket. This
  is a proof of the negative, not an observation of one run.
    host decision             = ADMITTED under {Filesystem.Read}
    ran over the real dataset = IntV(v=23) (the analytic result)

  Contrast: an over-reaching submission is REJECTED at admission
------------------------------------------------------------------------
  A different submission declares a write to one path but calls with
  another (an over-reach the verifier catches structurally):
    reachable effect closure  = [Filesystem.Write]
    granted categories (C)    = {Time.Now}
    beyond the grant          = [Filesystem.Write]
    host decision             = REJECTED at admission (verify), before any execution
  A conventional runtime, with no effect declaration to check the call
  argument against, would have run this. Strand stops it before execution.

  Contrast: an exfiltrator (egress in its closure) is REFUSED
------------------------------------------------------------------------
  The same support agent PLUS a Net.Connect egress edge has the egress
  category structurally present in its closure, beyond the grant:
    reachable effect closure  = [Filesystem.Read, Network.Connect]
    beyond the clean-room grant = [Network.Connect]
    host decision             = REFUSED at admission (before any run)
  The egress cannot be hidden, because it is a declared, reachable node.

STAGE 2  RUN -- bounded execution
========================================================================
The admitted agent runs under EXACTLY its declared capabilities -- the
grant covers precisely the surfaced closure, nothing more.

  Retrieve from one refined index (Vector.Read{store} only)
------------------------------------------------------------------------
  The task embeds the query, retrieves from the corp-kb index, and
  generates an answer. Its data-access manifest is the surfaced closure,
  read off the verified artifact BEFORE running:
    surfaced closure (manifest) = [LLM.Embed, LLM.Generate, Vector.Read]
    grant covers exactly it     = true
  Vector.Read is granted refined to {provider=pinecone, store=corp-kb} --
  the agent provably can query only that index.
    embed / query / generate    = 1 / 1 / 1
    retrieved hits              = 2 (first: kb-doc-17)
    generated answer            = "Travel is reimbursed up to $75/day with receipts (per the retrieved policy)."

  Call the model under its surfaced bound
------------------------------------------------------------------------
  The agent's model call runs under a grant of exactly {LLM.Generate} --
  the bound read off the artifact before the run:
    surfaced closure (Q-067)  = [LLM.Generate]
    grant covers exactly it   = true
    LLM turns / completed     = 1 / true
    model result              = "It is sunny and 72F."
  Useful agent work under a bound readable from the artifact.

STAGE 3  VERIFY -- the model's output is checked
========================================================================
Constraining a model is not guaranteeing its output. The structured
response is constrained going out (N-045) and the returned value is
verified coming back (Q-047) before it is used.

  A valid structured response is constrained and passes
------------------------------------------------------------------------
  The model call carries a ResponseSchemaSpec (N-045) that constrains
  what the model may emit; a valid score flows into a Schema-typed
  position and the runtime invariant (0 <= score <= 100) passes:
    model returned score      = 87
    model transport invoked   = 1 time(s) (a genuine model call)
    invariant passed          = true
    validated result          = 87

  A semantically-invalid response is CONTAINED before output
------------------------------------------------------------------------
  The SAME pipeline, but the model returns a value that is structurally
  an Int yet out of its declared range (> 100) -- a constrained model
  can still return this. The Q-047 obligation fires BEFORE output:
    model returned score      = 142
    raised at runtime         = true
    runtime error             = SchemaInvariantViolation
    offending value           = IntV(v=142)
    contained before output   = true
  Constraining narrows what the model MAY emit; verifying catches what a
  constrained model STILL returns. The malformed output never escapes.

STAGE 4  WITHSTAND -- attacks are contained
========================================================================
The threat model holds across four attack shapes. The principle:
untrusted CODE is bounded; untrusted DATA is contained.

  An over-reaching tool effect is DENIED (untrusted code, bounded)
------------------------------------------------------------------------
  The model invokes a tool whose implementation reaches Filesystem.Write,
  an effect absent from the {LLM.Generate} grant. The static bound looks
  identical to the clean run (a ToolDef's effects fire at dispatch, not
  in the root closure); the ungranted reach is denied at runtime:
    surfaced closure (Q-067)  = [LLM.Generate]
    denied                    = true (CapabilityViolation)
    DenialReport: category=Filesystem.Write requested=[] held=[] node=#35 phase=expression
  The prompt-injection-contained story: a tool reaching for an ungranted
  effect is stopped at the foreign-call boundary.

  A query against a non-granted index is DENIED (untrusted code, bounded)
------------------------------------------------------------------------
  A variant opens corp-kb (granted) then issues its retrieval query
  declaring Vector.Read{pinecone, hr-private} -- a store the grant does
  not cover. The read is denied at the foreign-call boundary:
    denied                    = true (RefinementViolation)
    query reached the wire     = false (query HTTP calls = 0)
    DenialReport: category=Vector.Read requested=[pinecone, hr-private] held=[Vector.Read{pinecone, corp-kb}] node=#122 phase=expression
  The index an agent may reach is a refinement-checked capability, not a
  config string the code can overwrite -- denied before any request leaves.

  A poisoned input is CONTAINED as data (untrusted data, contained)
------------------------------------------------------------------------
  The model's decisions flow into a verified actuator graph as DATA the
  graph consumes, not CODE it runs. One decision carries prompt-injection
  text. The surfaced closure is computed from the actuator, not the data:
    closure(benign decisions)   = [Filesystem.Write]
    closure(poisoned decisions) = [Filesystem.Write]
    closures identical          = true
    injection landed as content = true (inert digest text)
    egress added                = [] (none)
  The poisoned note is data; it cannot add a graph node, so it cannot add
  an action. The action set is graph structure, fixed at admission.

  At scale, a rogue agent is DENIED while the group survives
------------------------------------------------------------------------
  In a supervised multi-agent group, one worker reaches an effect beyond
  the grant. It is denied at its actor boundary; the OTHER agents keep
  running and complete -- a rogue agent contained without whole-system failure:
    rogue worker (workerC) halted = true
    DenialReport: category=Filesystem.Write requested=[/agent/worker-c.log] held=[Filesystem.Write{/agent/worker-a.log}, Filesystem.Write{/agent/worker-b.log}] instance=<varies> eventIndex=0 phase=transition
    surviving worker A final  = IntV(v=12) (completed)
    surviving worker B final  = IntV(v=10) (completed)
    supervisor stayed up      = true
    contained (denied + survived) = true

STAGE 5  AUDIT -- the run is reproducible and content-addressed
========================================================================
After the fact, the run is reproducible and the task is identified by
the hash of its graph.

  The run replays bit-identically, with zero live IO
------------------------------------------------------------------------
  A recorded run (stamps read from the live clock) replays from its log
  under a clock that THROWS if read -- a clean replay proves zero live IO:
    replay touched no live IO = true
    trajectories identical    = true
    outputs identical         = true
    replay == live != contrast= true
  A fresh live run under a different clock genuinely differs; replay does
  not. A conventional service cannot promise this: effects are implicit,
  so a second pass reads fresh clocks/IO and diverges.

  The task is admitted-once and re-run by hash
------------------------------------------------------------------------
  The task is ingested into a persistent store (admitted and verified
  once), then re-run by its root hash with no re-verification:
    root hash                 = <varies>...
    verdict cached at ingest  = true
    first-run value           = IntV(v=42)
    run-by-hash value         = IntV(v=42)
    identical result          = true
  The task IS its content hash -- the same graph always runs the same way.

========================================================================
The platform thesis, demonstrated: across the whole lifecycle of running
untrusted, agent-generated code, the maximum harm is COMPUTABLE from the
artifact before execution (ADMIT) and BOUNDED during it (RUN, VERIFY,
WITHSTAND), and the run is reproducible and content-addressed (AUDIT).
No 'LLM-writes-Python-in-a-sandbox' stack can offer this: a sandbox
watches a running process, while Strand reads a graph that cannot express
the harm it does not declare.

Honest scope: this COMPOSES proven demo scenarios -- the 'single task' is
a narrative frame over the suite's mechanisms, each of which isolates its
own subject with deterministic mocks/stand-ins and hand-authored programs.
It is NOT first-pass correctness or inference cost (the deferred Run 8
study). Every property is one the shipped runtime enforces; the assertion
net (AgentPlatformDemoTest) pins each stage in lifecycle order.
========================================================================
```

## References

**Outgoing references** (the demonstrations and mechanisms this spine composes):

- [`demos/containment-host/README.md`](../containment-host/README.md) — the
  untrusted-program host; ADMIT draws its over-reach rejection from S1 and AUDIT
  draws admitted-once / run-by-hash from S4.
- [`demos/clean-room/README.md`](../clean-room/README.md) — the non-exfiltration
  proof; ADMIT draws the egress-empty admission from CR1 and the exfiltrator
  refusal from CR2.
- [`demos/bounded-rag/README.md`](../bounded-rag/README.md) — the refined-index
  RAG pipeline; RUN draws the bounded retrieval from R1 and WITHSTAND draws the
  non-granted-index denial from R2.
- [`demos/agent-workflow/README.md`](../agent-workflow/README.md) — the bounded
  tool-using agent; RUN draws the model call under its surfaced bound from A1 and
  WITHSTAND draws the over-reaching-tool denial from A2.
- [`demos/verified-llm-output/README.md`](../verified-llm-output/README.md) — the
  constrain-then-verify loop; VERIFY draws the valid-response pass from VO1 and the
  invalid-response containment from VO2.
- [`demos/skill-workflow/README.md`](../skill-workflow/README.md) — the
  model-as-policy / graph-as-actuator demonstration; WITHSTAND draws the
  poisoned-input-contained-as-data property from SK2a.
- [`demos/multi-agent-supervisor/README.md`](../multi-agent-supervisor/README.md) —
  the supervised actor group; WITHSTAND draws the rogue-agent-denied / group-
  survives property from MA2.
- [`demos/replay-timetravel/README.md`](../replay-timetravel/README.md) — the
  sound deterministic replay; AUDIT draws the bit-identical zero-live-IO replay
  from R1.
- [`demos/mcp-tool-manifest/README.md`](../mcp-tool-manifest/README.md) and
  [`demos/llm-virtualization/README.md`](../llm-virtualization/README.md) — the two
  suite demonstrations not on this task's critical path; the platform thesis
  generalizes the same admit/bound machinery to them.
- [`evaluation/containment-results.md`](../../evaluation/containment-results.md) —
  the Q-044 containment measurement; the harm bound `closure(g) ∩ C ∩ B ∩ P` whose
  computed-before / bounded-during property this lifecycle narrates.
- [`open-questions.md`](../../open-questions.md#Q-067) — Q-067, the surfaced effect
  closure (`VerifyResult.Ok.rootClosure`) every ADMIT/RUN stage reads as the task's
  maximum harm.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade and `HostPolicy` the host is built on.
- [`proposals/implemented/capability-denial-observability.md`](../../proposals/implemented/capability-denial-observability.md)
  — Q-064, the structured `DenialReport` the three WITHSTAND denials carry.
- [`proposals/model-api-integration.md`](../../proposals/model-api-integration.md) —
  Q-021, the deferred Run 8 dynamic study (first-pass correctness, inference cost)
  this demonstration is explicitly not.

**Incoming references:**

- [`demos/README.md`](../README.md) — index entry (the "Start here" pointer).
