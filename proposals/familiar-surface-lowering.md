# Familiar-Shaped Authoring Surface with Mechanical Lowering

**Document:** `proposals/familiar-surface-lowering.md`
**Status:** Draft proposal — step 1 implemented 2026-06-13 (see § Implementation progress); steps 2–3 open
**Date:** 2026-06-11
**Concerns:** [Q-021](../open-questions.md#Q-021), [Q-034](../open-questions.md#Q-034), [Q-051](../open-questions.md#Q-051) (error-to-source mapping), [Q-057](../open-questions.md#Q-057), [`02-core-thesis.md`](../02-core-thesis.md) Claims 1–2 and § strongest-alternatives, [`decisions/ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md), [`decisions/ADR-002-no-human-projection.md`](../decisions/ADR-002-no-human-projection.md), [`01-prior-art.md`](../01-prior-art.md)
**Scope:** large (multi-step shipping)

This proposal adds a second authoring projection — Layer F, a fully typed, restricted dialect with familiar TypeScript-shaped syntax — that lowers mechanically to canonical dag-json. It attacks the two costs the dynamic measurement attributes to representation unfamiliarity: the in-context teaching prompt and the first-pass deficit. The canonical form, the verifier, and program identity are untouched; Layer F occupies exactly the disposable-projection role the 2026-06-11 revision of Claims 1–2 assigns to authoring surfaces.

## 1. Problem statement

The dynamic measurement shows Strand's cost gap is a familiarity gap, not an emission-size gap. Per-emission output is below the Python baseline and static density is 0.81×, yet tokens-per-successful-task run 15.7–22.4× the conventional baselines and first-pass verification trails at 11/15 versus 15/15 ([`evaluation/dynamic-results.md`](../evaluation/dynamic-results.md), Run 7). The driver is that Layer A is a novel symbolic vocabulary: the model needs a ~21,500-token grammar primer per attempt and still slips on grammar forms it has never seen in pretraining. The surveyed evidence points the same direction — code-shaped action formats outperform structured formats for current models, and performance on a representation tracks its pretraining presence ([`01-prior-art.md`](../01-prior-art.md), CodeAct entry and decode-time-enforcement section); QUASAR demonstrated the decoupling directly by having models emit a Python subset transpiled to a separately designed execution language.

The 2026-06-11 thesis revision makes the surface shape an empirical parameter rather than a design commitment: the artifact of record is the verified graph, and authoring projections carry no canonical status. Layer A's shape — novel-compact — was chosen to minimize emission tokens. The measurement says emission tokens were never the binding constraint; familiarity is. A familiar-shaped projection tests the other point in the surface design space while preserving every load-bearing property: lowering output is canonical dag-json, verification happens at admission on the artifact, and the lowerer — like the Layer A elaborator today — needs no trust.

## 2. Prior art

- **QUASAR** — LLMs emit a Python subset transpiled to a secure execution language; 42 percent faster, 52 percent fewer approval interactions than direct generation on ViperGPT. The decoupling pattern this proposal adopts; QUASAR transpiles from general Python, which forces inference of structure the source does not state. Layer F avoids that by giving every Strand-semantic construct an explicit source form.
- **Zero (Vercel Labs)** — agent-first toolchain over a graph-as-database with text projections and capability effects in signatures; independent convergence on "graph canonical, text surface" from industry.
- **CodeAct (ICML 2024)** — code-shaped actions beat structured action formats by up to 20 percent task success; the empirical basis for familiar-shaped over novel-symbolic.
- **Hazel typed holes (OOPSLA 2024)** — the structure-editor lineage's own pivot: keep text generation, use the structured representation to serve the model context. Same division of labor as Layer F over the Strand store.
- **Type-constrained decoding (PLDI 2025)** — type-aware decode masks halve compiler errors for TypeScript; applicable to Layer F's grammar the same way GBNF applies to Layer A's.

## 3. Recommended approach

A dialect, not an existing language: TypeScript-shaped surface syntax, fully typed, with explicit dialect constructs for everything Strand-semantic, so that lowering is a syntax-directed translation with no inference beyond the eleven cases the Elaborator already implements. TypeScript-shaped rather than Python-shaped because the dialect must be fully annotated (TypeScript's annotation syntax is idiomatic where Python's is culturally optional), discriminated unions map directly onto SumType/Match, object types map onto ProductType, and brace delimitation avoids indentation ambiguity under generation. The Python-shaped alternative is recorded in § 8.

What the dialect deliberately lacks: mutation and reassignment (`const` only; `let` is a parse error), loops (recursion and the higher-order `List.*` builtins cover iteration; a `for..of`-to-fold sugar is deferred), classes (the `machine` template is the only class-shaped form), `any`/untyped parameters, exceptions (`attempt` is the only failure construct), and arbitrary imports (only the prelude namespace and hash-pinned references). An agent writing real TypeScript habits gets a parse error with a dialect hint, not silent acceptance.

Layer A remains: it is the reverse-projection target (`strand translate`), the dense form for a future fine-tuned emitter, and the regression surface for the density work. If measurement shows Layer F dominating Layer A on every axis for in-context models, retiring Layer A from the agent-facing default is a future decision this proposal does not make.

## 4. Detailed mechanism

### 4.1 Construct-to-node lowering table

| Layer F construct | Lowers to |
|---|---|
| `const x = expr` | Let (N-013) |
| `function f(a: T): R uses E(args) { ... }` / arrow function | Lambda with ParameterDecl, declared effects |
| named self-reference inside `function` | lambda-lifted Fixpoint (N-021) with recursion slot |
| `type P = { a: T, b: U }` | ProductType; object literal → ProductValue; `.field` → ProductFieldGet |
| `type S = { tag: "A", v: T } \| { tag: "B" }` | SumType with SumTypeCase per tag; construction → SumValue |
| `match (x) { A(v) => e1, B => e2 }` | Match with case patterns (dialect keyword; lowered exactly as Layer A MAT) |
| `uses Filesystem.Write(path)` clause | EffectDecl with projection-aligned instances at each application site |
| `declare function f(a: T): R from "strand-builtin:X" uses C(a)` | ForeignNode (N-020) + FunctionType with the declared effect row on both; a parameterized `uses` entry is a surface-level Q-039 projection instantiating call-site EffectDecls |
| `capability { only: [...] } in { ... }` | CapabilityScope (N-036) |
| `attempt { ... }` | Attempt (N-047), consumed with `match` on `Ok`/`Err` |
| `machine M(state: S) on (e: Ev) { ... return [state2, [outs]] }` | StateMachine (N-027) with EventStreams and pure transition Lambda |
| `schema Name<T> invariant (v) => bool` | Schema (N-032) + Invariant (N-033) |
| `handler (E => fn) in { ... }` | Handler (N-043) |
| `use prelude.fsWrite` / `use "b3:..."` | prelude resolution / NodeRef by hash (N-019) |

The right-hand column is the existing algebra; the proposal introduces no node category, no encoding change, and no verifier rule.

### 4.2 Worked example

The Run 7 file-write task in Layer F:

```
use prelude.{add}

declare function write(path: String): Int from "strand-builtin:Filesystem.Write" uses Filesystem.Write(path)

function main(): Int uses Filesystem.Write("/tmp/strand-eval.log") {
  return add(write("/tmp/strand-eval.log"), 1)
}
```

Lowering: `main` is the program root expression; the `use` line resolves `add` through the prelude; the `declare function` lowers to the ForeignNode + FunctionType pair with the effect row on both, and its parameterized `uses` entry is a surface-level Q-039 projection — the call site receives an EffectDecl whose parameter references the same path node the application consumes. The canonical graph is node-for-node the corpus file-write shape, and hashes identically to the same program authored through Layer A — the equality test in § 7 makes that the defining correctness property, and this example ships verbatim as `evaluation/dynamic/tasks/09-file-write-capability/reference.familiar`. (The example as originally drafted called a single-argument prelude `fsWrite` and bound the result with `const`; the prelude `fsWrite` is the two-argument real `Fs.Write`, the Run 7 task pins the legacy single-argument `Filesystem.Write` stub reachable only through the `declare` form, and a `const` inside `main` lowers to a Let the corpus shape does not contain — see § Implementation progress.)

### 4.3 Error mapping

`Authoring.compile` for Layer F returns the same `CompileResult` shape as Layer A, with `sourceLines` mapping node ids to Layer F line numbers; the Q-051 `NodeRefAnnotator` then renders verifier errors against the source the agent actually wrote. Provenance is metadata and never hashes.

### 4.4 Trust position

The lowerer holds the same position the Layer A elaborator holds today: untrusted producer. Its output is admitted only through the verifier, and any consumer of the stored graph re-verifies the artifact itself. Nothing about the harm bound, admission, or content addressing changes with the surface.

## 5. Verifier rules

None. All Layer F rejections are parse- or lowering-time; everything semantic remains the existing verifier's job, which is the design's point.

## 6. Runtime semantics

None new. Lowered programs are ordinary canonical graphs.

## 7. Test scenarios

1. **Cross-surface hash equality** — for each of the 22 evaluation tasks, the Layer F reference lowers to the identical root hash as the semantically identical Layer A reference.
2. **Corpus expressibility sweep** — every corpus program expressible in Layer A is expressible in Layer F; gaps are enumerated and either closed or recorded against the Q-057 parity convention.
3. **Mutation rejection** — `let` reassignment and `x = y` statements fail at parse with a dialect hint.
4. **Untyped-parameter rejection** — a parameter without an annotation fails at parse (no `any`).
5. **Effect omission** — calling an effectful builtin from a function without the matching `uses` clause produces the standard verifier UncoveredEffects against the Layer F line.
6. **Recursion lifting** — a named recursive function lowers to Fixpoint and evaluates correctly (factorial reference).
7. **Machine template** — the toggle-machine task in `machine` form runs under `strand machine` with the same trace as the Layer A version.
8. **Attempt form** — `attempt` plus `match` reproduces corpus 86 (Fs.Read fallback) semantics.
9. **Error-line mapping** — an injected semantic error reports the Layer F line, not a bare node id.
10. **Non-dialect TypeScript** — classes, `async`, template-literal types, and `import` statements are rejected with hints, not mis-lowered.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Reverse projection (canonical → Layer F)** — Layer A keeps the read-back role via `strand translate`; a Layer F renderer is a follow-up once forward emission proves out.
- **Loop sugar, async surface for machine groups, sourcemap debugging** — each is additive once the core dialect ships.
- **Python-shaped variant** — the alternative shape; revisit if measurement shows TypeScript-shaped underperforming the CodeAct expectation. The lowering table is shape-agnostic, so the cost of a second front-end is bounded.

**Real research questions:**

- *Does familiarity survive the dialect restrictions?* The pretraining advantage is for real TypeScript; a constrained dialect may forfeit part of it. The Q-021 A/B (Layer F versus Layer A versus baselines, N>1) is the deciding measurement, and the predicted outcome — first-pass near baseline parity with a prompt under 3,000 tokens — is falsifiable.
- *Surface-count maintenance* — every new node surface now owes two projections or a recorded exemption, extending the Q-057 convention. If the measurement decides decisively for one surface, the convention collapses back to one.

This proposal does not relitigate ADR-001 or ADR-002. The canonical form remains the graph; no text form carries identity or receives verification; Layer F is human-legible incidentally but is built for agent emission, not for the human-projection role ADR-002 declines.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `impl-kotlin/authoring/.../familiar/` (new package: lexer, parser, lowerer) | the dialect front-end emitting the existing dag-json document model | Large |
| `impl-kotlin/authoring/.../Authoring.kt` | `compile(surface = FAMILIAR)` entry returning `CompileResult` with sourceLines | Small |
| `impl-kotlin/cli` | `strand author --surface familiar`; annotator wiring | Small |
| `impl-kotlin/corpus` | Layer F references for evaluation tasks; cross-surface hash-equality test | Medium |
| `evaluation/dynamic/prompts/strand-familiar-system.md` (new) | dialect prompt: restrictions and the non-TypeScript constructs only | Medium |
| `evaluation/dynamic/strand_eval/` | `familiar` language adapter for the A/B measurement | Small |

**Order of work.** Step 1: pure subset plus effects and `attempt` (covers 9 of 22 tasks; the original 15-of-22 estimate preceded the handler/schema census of the Run 7 task set); cross-surface hash test from the first commit. Step 2: `machine`, `schema`/`invariant`, `handler`, `capability` (the remaining 13 tasks). Step 3: the harness A/B measurement, which is the proposal's exit criterion in either direction.

**Not in this slice.** Reverse projection, loop sugar, GBNF for the dialect (worthwhile, after the grammar settles), any Layer A removal.

## Implementation progress

**Step 1 landed 2026-06-13.** The pure subset plus effects and `attempt`: lexer, parser, and lowerer in `impl-kotlin/authoring/src/main/kotlin/org/strand/authoring/familiar/`, the `Authoring.compile(text, surface = FAMILIAR)` entry returning the standard `CompileResult` with Layer F source lines, and `strand author --surface familiar` (a `.familiar` extension auto-selects the surface). The lowerer targets the Layer A document model, so Layer F runs the existing Elaborator and DagJsonEmitter unchanged and inherits prelude resolution (Q-063), registry-wide dotted builtins and `@auto` effect-instance synthesis (Q-060 density v5), and the Q-051 error-line annotation. Coverage: 9 of the 22 evaluation tasks carry `reference.familiar` files asserted root-hash-identical to their live `reference.layer-a` compiles by `FamiliarSurfaceHashEqualityTest` (01, 04, 05, 09, 11, 15, 16, 19, 22); the remaining 13 require step-2 constructs (2 machines, 5 schemas, 5 handlers, 1 capability block) and are recorded as explicit pending entries in the same test, which fails if a `reference.familiar` appears without promotion to the covered list. Of the § 7 scenarios, 1, 3, 4, 5, 6, 8, 9, and 10 are covered by tests; 2 (corpus sweep) and 7 (machine template) ride steps 2 and 3. Zero golden-hash impact — both surfaces compile live in the equality test.

Grammar and lowering decisions taken in step 1 beyond the proposal text:

- **`declare function` ambient form.** `declare function f(a: T): R from "strand-builtin:X" uses C(a)` declares a foreign target the implicit surface does not reach — needed because the Run 7 file-write tasks pin the legacy `Filesystem.Write` stub, which the density-v5 signature table deliberately excludes. The form lowers to the corpus convention for hand-declared builtins: the effect row appears on both the FunctionType and the ForeignNode (the prelude entries carry it on the ForeignNode only, which is a different — and differently hashing — shape). A parameterized `uses` entry binds category parameters to declared value parameters by name, a surface-level Q-039 projection; the lowerer plants an explicit EffectDecl at each call site referencing the same argument nodes the application consumes.
- **§ 4.2 example revised.** The original example called a one-argument prelude `fsWrite` (the prelude's `fsWrite` is the two-argument real `Fs.Write`) and bound the call with a `const`, which lowers to a Let absent from the corpus file-write shape. The revised example is the declare-form program above and ships verbatim as the task-09 reference.
- **Effect-instance placement.** A call site receives EffectDecls in exactly two cases: a declare-form callee whose `uses` entry is parameterized (the surface projection), or a prelude/dotted callee carrying a Q-039 projection for a parameterized category when the governing function's `uses` entry for that category is itself parameterized — lowered as the `@auto` marker so AutoEffectSynthesis builds the instances. Bare `uses` entries declare the category on the Lambda effect row only, matching the corpus convention that parameterless-category calls carry no instances.
- **Effect rows are always explicit.** A `function` without a `uses` clause lowers to a Lambda with an empty effects list rather than an absent one, so the always-on Elaborator cannot infer the omission away and scenario 5 surfaces as the standard verifier UncoveredEffects. Arrow functions are pure by construction (empty row; no `uses` syntax).
- **Sum surface.** Construction is the tagged object literal (`{ tag: "Cons", head: h, tail: t }`); a case with a single payload field collapses to a bare `caseType` (producing the `Some(Int)`-shaped corpus graphs), a multi-field case lowers to a payload product, and a direct self-reference produces the RecursiveType + RecursiveSelf inner/outer product split with outer products emitted lazily at use sites. One-field payloads that must be genuine products are inexpressible in step 1. Indirect (mutually) recursive aliases are rejected with a hint.
- **`main` lowers to the root expression**, not a zero-parameter Lambda — the evaluation and corpus references are bare-expression roots. A top-level `const` is a shared document node (graph sharing, the corpus convention); a `const` inside a function body is a Let. Mutual recursion between functions is rejected with a hint (direct self-recursion lambda-lifts to Fixpoint).
- **Dotted-name resolution order.** A dotted callee resolves against the density-v5 signature table first, then against a registry-target index of the prelude (so `Math.Abs(x)` lowers to the reserved `abs` without an import); excluded registry names are rejected with a hint naming the `declare` form.
- **Operators, `if`/ternary, generics, lowercase TS primitive names (`number`/`string`/`boolean`), block-bodied arrows, and statement-position TypeScript habits** reject with per-construct corrective hints (§ 3's parse-error-with-hint contract), including a per-operator hint naming the prelude builtin to call.

**Remaining.** Step 2: the `machine`, `schema`/`invariant`, `handler`, and `capability` forms (the 13 pending tasks, scenario 7, and the scenario-2 corpus sweep). Step 3: the dialect system prompt, the `familiar` harness adapter, and the Q-021 A/B measurement that is the proposal's exit criterion in either direction.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — Claims 1–2 as revised 2026-06-11; § strongest-alternatives
- [`decisions/ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md) — the canonical-form decision this proposal operates under
- [`decisions/ADR-002-no-human-projection.md`](../decisions/ADR-002-no-human-projection.md) — the human-projection decision this proposal does not reopen
- [`01-prior-art.md`](../01-prior-art.md) — QUASAR, Zero, CodeAct, Hazel, decode-time enforcement
- [`evaluation/dynamic-results.md`](../evaluation/dynamic-results.md) — the measurement motivating the surface change
- [`design/node-algebra.md`](../design/node-algebra.md) — the lowering target
- [`open-questions.md`](../open-questions.md) — Q-021, Q-034, Q-051, Q-057

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-061 points at this proposal
- [`proposals/README.md`](README.md)
- [`proposals/implemented/authoring-cost-reduction.md`](implemented/authoring-cost-reduction.md) — the near-term track inside Layer A (implemented 2026-06-12; measurement gates pending under Q-021 Run 8)
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section
- [`ROADMAP.md`](../ROADMAP.md) — Tier 1.5
