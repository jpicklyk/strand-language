# Skill-workflow demonstration — design spec

**Document:** `demos/skill-workflow/SPEC.md`
**Status:** Design spec for review; the demonstration is not yet built. Last revised 2026-06-13.
**Purpose:** Pin the *model-as-policy / graph-as-actuator* framing before building, so this demonstration earns its place instead of re-skinning `agent-workflow` + `mcp-tool-manifest`.

## Thesis: the threat model flips

Every existing demonstration contains untrusted **code**: `containment-host` runs an unfamiliar program under a bound, `plugin-host` attenuates a delegated capability, `clean-room` proves a submitted computation cannot exfiltrate. This demonstration contains untrusted **data** — the output of an LLM flowing into trusted, verified, bounded code.

A skill whose workflow is required (a procedural, effect-bearing skill — triage-and-notify, apply-review-decisions, post-a-digest) splits into two parts that today's agent harnesses entangle:

- **Policy — the model.** The fuzzy judgment: which items matter, how to classify them, what to say. Non-deterministic, unbounded, and *outside* the verified graph.
- **Actuator — the graph.** The deterministic, effect-bearing spine that executes the decided actions: write the digest, post to the one webhook, update the one record. Verified, content-addressed, capability-bounded.

In a conventional harness the model emits tool calls that are executed — the model's output *is* the action set, so a poisoned or confused model expands what runs. Strand lets the actions be a verified graph the model merely *parameterizes*: the model's output is **data the graph consumes, not code the graph runs**. The action set is graph structure — fixed and verified at admission — so a poisoned decision can at worst supply bad data to an already-bounded action; it can never add an action. That is the load-bearing, genuinely-distinct property this demonstration exists to show, and it is the most direct answer to "where does Strand actually fit in an agent stack": as the verified actuator beneath an untrusted policy.

## Subject

A triage-and-notify skill. Given a batch of items (issues, log lines), the **model** classifies each into a structured decision `{item, severity, note}`. The model is external to the demonstration and is represented by a hand-authored `decisions` value — the stand-in for untrusted LLM output, exactly as every other demonstration hand-authors its programs to isolate the generation question.

The **actuator** is a verified Strand graph that folds the decisions into a digest and performs exactly the skill's declared effects: a `Filesystem.Write` of the digest to one path (and, optionally, a `Network.Connect` to one notification host). Its surfaced effect closure — refined to that one path / one host — *is* the skill's published capability manifest.

Actuator form: a recursive function (Fixpoint) folding the decisions list and performing the bounded write, run via `StrandRuntime.run` — replay is deterministic re-run (same decisions → same effects). A state-machine actuator (decisions as an event stream, the host performing emitted actions) is an alternative that buys richer time-travel but moves the effect out of the graph; the function form keeps "the graph does the bounded effect" crisp and is preferred unless SK3 wants a trace.

## Scenarios

- **SK1 — The actuator runs under its published bound.** The host loads the verified actuator, renders its harm-bound manifest (the Q-067 surfaced closure refined to the path/host), feeds in the model's decisions, and runs it. The digest is written; the surfaced closure is exactly the action set. *The model decided; the graph acted; the bound is provable from the graph alone, independent of the model.*
- **SK2 — A poisoned decision cannot exceed the bound (the keystone).** The decisions value is untrusted — a prompt-injected model could emit a malicious "action." Two sub-cases: (a) a decision whose `note` carries an attempted path/host escape is consumed as *data* — it lands in the digest content, and no new effect occurs, because the model cannot add graph nodes; (b) a decision that steers the write toward an out-of-grant path is denied by the refined `Filesystem.Write{path=…}` capability with a `DenialReport`. The contrast: the model supplied arbitrary data, yet the action set is unchanged.
- **SK3 — The skill replays deterministically.** Given the same decisions, the actuator produces the identical effects/result — a skill invocation is reproducible and auditable. You can replay exactly what the skill *did* given what the model *decided*, separating "was the action correct" (graph, replayable) from "was the judgment correct" (model, out of scope).
- **SK4 (optional) — Hash-pinned skill contract.** The actuator (wrapped in an N-046 `ModuleManifest`) has a content hash; the published skill is that hash; a consumer installs by hash and gets the provable bound, à la `mcp-tool-manifest`. A change to the actuator's actions is visibly a different hash.

## What makes this distinct (state explicitly in the README)

- vs **agent-workflow**: there the LLM loop *is* the program (model in the loop, tool calls inside a bounded call). Here the LLM is *out* of the graph; the graph is the actuator over the model's decisions. The demo shows the judgment↔action *boundary*.
- vs **containment / clean-room / plugin-host**: those contain untrusted *code*. Here the actuator is *trusted and verified*; the untrusted thing is the model's *data*. A different threat model — untrusted data into bounded code, not untrusted code under a bound.
- vs **mcp-tool-manifest**: that is a tool bundle's manifest; here it is a workflow/skill's actuator plus its harm-bound contract, anchored on the model-as-policy split.

## Mechanisms (all shipped — no new capability needed)

Verified actuator via `StrandRuntime.run`; Q-067 surfaced closure as the manifest; refined `CapabilitySet` (Q-031) for the bounded action; `DenialReport` (Q-064) for SK2b; deterministic re-run for SK3; optional N-046 `ModuleManifest` + content hash for SK4.

## Honest caveats (required in the README)

- The model's decisions are hand-authored stand-ins (the model is external — the same isolation of the agent-generation question as every demonstration).
- The data→action containment holds because the actuator's effects flow through typed builtins with refined capabilities; an actuator that shelled out with model-supplied arguments (`Process.Spawn`) would leak the bound — the same clean-room profile caveat.
- The split is a design discipline the demonstration *illustrates* (model decides, graph acts); it is not an automatic property of an arbitrary program. The value is that Strand makes the split *expressible and verifiable*, not that it forces it.
- Not first-pass correctness or inference cost (the deferred Run 8 study).

## File plan (when approved)

- `demos/skill-workflow/programs/*.json` — the actuator graph plus a poisoned-decisions input (canonical dag-json).
- `impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/SkillWorkflowDemo.kt` + `SkillWorkflowDemoTest.kt` — driver + assertion test sharing one body of scenario code.
- `demos/skill-workflow/README.md` — narrative matching the existing demonstration READMEs.
- Gradle task `skillWorkflowDemo`, main class `org.strand.runtime.SkillWorkflowDemo`; `runtime/build.gradle.kts` wiring + `demos/README.md` index entry added at build time.
