# Authoring layer for efficient LLM emission

**Document:** `proposals/llm-authoring-layer.md`
**Status:** Step 1 fully implemented 2026-05-24; Layer A density v1–v4 follow-up landed 2026-05-25 (see [`proposals/implemented/layer-a-density.md`](layer-a-density.md)). Tokenizer alignment and the full Q-021 baseline evaluation suite remain Phase 1 / Phase 4 work per §7; final Q-034 resolution remains gated on those measurements.
**Date:** 2026-05-24 (step 1), extended 2026-05-25 (v4 cleanup pass)
**Concerns:** [`00-motivation.md`](../00-motivation.md), [`decisions/ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md), [`decisions/ADR-002-no-human-projection.md`](../decisions/ADR-002-no-human-projection.md), [`design/node-algebra.md`](../design/node-algebra.md), [`research-plan.md`](../research-plan.md), [Q-021](../open-questions.md#Q-021), [Q-034](../open-questions.md#Q-034)
**Scope:** Medium-large for the initial slice; tokenizer work and the full Phase 1 evaluation are subsequent shipping steps

> **Implementation note (2026-05-24, revised 2026-05-25).** Step 1 of Q-034 ships in four sub-slices; a follow-up cleanup pass on 2026-05-25 collapsed the optional-elaboration surface and retired the JSON → Layer A reverse direction once it was no longer load-bearing.
>
> **(A) Layer A grammar + parser + dag-json emitter** — full coverage of the implemented node algebra. New `:authoring` Gradle module under `authoring → core` with `LayerAGrammar` (42 codes spanning Layer 1 through Layer 7: literals, types, lambda/application/let/varref/NodeRef, type abstractions, effects + capability scope, foreign nodes, match + four pattern variants, fixpoint, product/sum values, recursive types, handlers, state machines + three EventStream variants + Transition, schemas + invariants; only N-030 Name and N-031 Provenance are unmapped because they have no current corpus usage). Variant-bearing categories use a `CodeSchema.discriminator` field so one Layer A code maps to each Pattern.kind / EventStream.streamKind variant. `LayerAParser` is a generic line-based tokenizer that accumulates multi-error reports; `DagJsonEmitter` performs per-code arg-shape validation + canonical dag-json assembly.
>
> **(B) Layer C elaboration, always-on.** As of the 2026-05-25 cleanup pass, `Authoring.compileToDagJson(text)` runs elaboration unconditionally; the prior `compileWithElaboration` entry point and the `strand author --elaborate` CLI flag were collapsed away. The Layer A → dag-json pipeline is the single agent-facing entry point. The elaborator currently ships eleven inference cases: the original four sketched in §5.3 of this proposal (`Lambda.effects` effect-closure inference, `Application.effectInstances` defaulting from capability context, `Application.typeArguments` inference from value-argument types, `Lambda.paramType` inference from call-site context) plus seven additional cases added by the Layer A density v4 work (recursion-slot `paramType`, `FunctionType` synthesis from a `FIX recursionType` reference to an undeclared name, `SCS` caseType inference from `SumValue` payloads, compact-LAM parameter inference at call sites, Match scrutinee context, `ProductFieldValue` context, `ProductFieldGet` target context — plus the StateMachine transitionFn context). The inference passes run in a fixed-point loop so earlier cases feed later ones. See [`proposals/implemented/layer-a-density.md`](layer-a-density.md) for the v4 extensions, the measured geomean trajectory, and the per-pass design notes.
>
> **(C) Layer B constraint grammar.** [`ConstraintGrammar`](../impl/authoring/src/main/kotlin/org/strand/authoring/ConstraintGrammar.kt) emits GBNF (llama.cpp's grammar format, also consumable by Outlines / LMQL) derived dynamically from [`LayerAGrammar.codes`](../impl/authoring/src/main/kotlin/org/strand/authoring/LayerAGrammar.kt). Top rule + header rule + per-code node rules + optional-slot suffix alternatives + lexical primitives (identifier, int, float, bool, string, list_ref, nullable_ref, keyword, newline). `strand grammar` CLI subcommand prints the GBNF. The grammar enforces lexical and per-code syntactic correctness only; semantic constraints (references resolve to declared nodes, type-parameter lists match TAB arity, scope rules) require context-sensitive parsing GBNF cannot express and are left to the verifier downstream.
>
> **(D) Corpus and tests.** The 33 hand-authored `*.layer-a` fixtures that originally paired with canonical JSON corpus programs, the `corpus/layer-a/elaborated/` subdirectory, the `LayerARoundTripTest` they fed, and the `LayerATranslator` / `LayerARenderer` modules that mechanized the JSON → Layer A direction were all removed in the 2026-05-25 cleanup pass. They were superseded by the density work: every density-v* fixture is itself a round-trip test (its compiled dag-json must hash-equal the canonical JSON of the program it shadows), and the elaborator-driven density forms render the hand-authored legacy fixtures redundant. The new regression net is [`LayerADensityTest`](../impl/corpus/src/test/kotlin/org/strand/corpus/LayerADensityTest.kt) — 10 density fixtures covering all density slices (v1, v1.5, v2, v2.5, v3, v4) across factorial, JSON value, toggle machine, and option-some-unwrap programs. The `sugarOnly` marker on `CodeSchema` (which existed only so the reverse translator could skip sugar codes) was dropped with the translator. CLI `strand translate` was retired alongside.
>
> **Measured impact.** The original step-1 measurement (3.40× byte compression on the 33-program subset) is preserved as a historical reference; the actively-maintained number is the three-task evaluation suite tracked under `evaluation/results.md`: baseline Strand Layer A sits at 2.20× Python+type-hints geomean, and the v4 final form reaches **0.81× geomean** (factorial 0.87×, json-value 0.81×, toggle-machine 0.76×) — under the Python+type-hints baseline on every task. The §6 projection of 0.8–1.3× post-elaboration is met without requiring the deferred Phase 4 tokenizer alignment.
>
> **Step 1's remaining pieces.** Tokenizer alignment (§3.3, Phase 4 fine-tuning investment) and the full Q-021 baseline evaluation against all five conventional-language baselines on the broader task family (§7, Phase 1 work) are intentionally out of step 1's scope. The current three-task evaluation MVP is a static-cost measurement only; dynamic cost (tokens-per-successful-task across an agent's retry loop) requires model-API integration that is also a Phase 1 follow-up. The full Q-034 resolution remains gated on those measurements.

The canonical dag-json authoring format is verbose. Informal estimates place its token cost at 2-5× the equivalent text-source for non-trivial programs, and the multiplier is worse for small programs where JSON structural overhead dominates. This pressures the AI-first framing in [`00-motivation.md`](../00-motivation.md): if generation cost dominates retry cost, the verifier's correctness wins may not pay for themselves on a per-program token basis. This proposal specifies a layered emission stack that an LLM agent uses to construct Strand graphs at a fraction of canonical token cost, while preserving the canonical graph as the verifier's input and the language's source of truth.

## 1. Problem statement

ADR-001 commits Strand to a graph-native representation: programs are content-addressed graphs of typed nodes, not text. ADR-002 forbids a *human-readable* projection: there is no parallel textual language that humans edit and the tool compiles. The current authoring practice is the flat-form dag-json described in [`impl/README.md`](../impl/README.md) — a top-level document declaring `version`, `root`, and a `nodes` object whose keys are author-chosen string ids and whose values are node records with explicit `type`, edge fields, and metadata.

This format is well-suited to its job: it is unambiguous, content-addressable after canonicalization, and trivially machine-parseable. It is poorly suited to LLM emission for three measurable reasons. First, every node record carries quoted field names (`"type"`, `"parameters"`, `"body"`, `"value"`) that an LLM must emit per occurrence even though their positions are fully determined by the node category. Second, every author id appears as a quoted string at both its definition site and every reference site, and edge lists wrap those strings in JSON arrays with separators. Third, the verifier requires every binder to declare a `paramType`, every polymorphic call to supply `typeArguments`, every effectful function to declare `effects`, and every refined call site to supply `effectInstances` — annotations that are mechanically derivable from context but that the LLM must emit verbatim today. The cumulative token cost is the per-character cost of JSON syntax multiplied by the per-program cost of every annotation no inference recovers.

A worked baseline. The polymorphic identity program `02-identity-applied.json` in the seed corpus expresses a single Application of System F identity at `Int` in roughly 350 bytes of canonical dag-json (~110 BPE tokens under a general-purpose tokenizer). The equivalent text in any conventional ML-family language is approximately 30 characters (~10 tokens). The ratio for this program is ~10×; informal observation across the seed corpus places non-trivial programs in the 2-5× range Q-034 cites, with the dominant overhead shifting from JSON syntax (on small programs) to type and effect annotations (on larger ones).

Resolution of Q-034 requires (a) a design for the authoring layer that closes most of the gap, (b) a concrete enough specification that a Phase 1 / Q-021 evaluation can measure tokens-per-successful-task against the named baselines, and (c) acknowledgement that final adoption is gated on those measurements rather than on the design alone. This proposal supplies (a) and (b). It does not preempt (c); the measurements remain Phase 1 work.

## 2. Prior art

The authoring-layer problem decomposes into separate prior-art lineages: compact serialization of tree-structured data, projectional editing, type and effect elaboration, constrained generation from language models, and structured tool-call interfaces.

- **dag-cbor** ([IPLD](https://ipld.io/docs/codecs/known/dag-cbor/)) is the binary cousin of dag-json that Strand already uses for the BLAKE3 input under ADR-003. The dag-json / dag-cbor pair is the existence proof that a single canonical content-addressed tree can be presented in two equivalent serializations chosen for the audience. The proposal extends the pattern: a third serialization optimized for LLM emission, expanded losslessly to dag-json before reaching the verifier.

- **Hazel** ([Omar et al., 2017](https://hazel.org)) is a structure editor whose source is a tree of typed holes. Hazel's bidirectional projection between a compact surface form and a fully-typed tree demonstrates that a compact authoring presentation and a fully-elaborated underlying form can coexist coherently. Hazel's holes-as-types contract is a direct precedent for the deferred-annotation approach in this proposal.

- **JetBrains MPS** ([Voelter, 2014](https://www.jetbrains.com/mps/)) is the canonical projectional editor; programs are tree structures rendered through user-chosen projections. MPS shows that multiple projections per language are workable when the underlying tree is authoritative. Strand's authoring layer adopts the same separation: the dag-json canonical form is the underlying tree; the LLM-facing projection is one of potentially several authoring presentations.

- **Lean 4's elaborator** ([Moura, Ullrich, 2021](https://leanprover.github.io/papers/lean4.pdf)) translates a compact source into fully-elaborated terms by running unification, instance resolution, and coercion. The Lean source is far smaller than the elaborated term it produces. The proposal's inference layer operates analogously: the LLM emits source-level constructs, an elaboration pass produces the fully-annotated graph.

- **Outlines, JSONFormer, LMQL** ([Outlines](https://github.com/dottxt-ai/outlines), [JSONFormer](https://github.com/1rgs/jsonformer), [Beurer-Kellner et al., 2023](https://arxiv.org/abs/2212.06094)) are production libraries for grammar-constrained generation: at each decoding step the next-token distribution is restricted to tokens that extend a valid grammar parse. Used today to enforce JSON Schema, regular expressions, context-free grammars. The proposal applies the same technique to the authoring layer's grammar.

- **Anthropic tool use and OpenAI function calling** are platform-native interfaces for incremental structured output. A model emits a sequence of `(tool_name, arguments)` calls; the platform validates each call against a declared schema. This shifts the per-position commitment cost from "next byte of a long serialized output" to "next tool call from a closed inventory."

- **Damas-Milner / bidirectional type checking** ([Damas, Milner, 1982](https://dl.acm.org/doi/10.1145/582153.582176); [Dunfield, Krishnaswami, 2013](https://research.cs.queensu.ca/home/jana/papers/bidir/)) are the canonical algorithms for type inference and local type inference respectively. The proposal's inference pass uses bidirectional checking because Strand's System F shape (explicit type abstractions, explicit type applications at use sites) matches the form bidirectional algorithms handle without unification.

- **MessagePack and Protobuf** are compact wire formats for tree-structured data. They are not authoring-layer candidates (binary, not human-or-LLM-friendly to emit) but they bound the achievable compression: roughly 4-6× over JSON for typical payloads. The proposal's text-projection target sits between dag-json and these binary formats in compactness.

- **Carp and Wisp** ([Carp](https://github.com/carp-lang/Carp), [Wisp](https://srfi.schemers.org/srfi-119/)) are Lisp-family languages with compact textual surface forms. Their s-expression and indentation-based readers are 2-4× more compact than equivalent C-family or JSON-family syntaxes for the same trees. The proposal's projection draws stylistically from this lineage while remaining unambiguous enough for constrained-generation tooling.

## 3. Technique evaluation

Q-034 names six candidate techniques. The proposal evaluates each independently before committing to a stacked design.

### 3.1 Authoring-layer projection

A compact text serialization that the LLM emits and that tooling expands losslessly to canonical dag-json. The projection drops JSON quoting on field names, drops field names entirely where edge position is determined by node category, uses single-character or short symbolic markers for the most common node categories, and inlines small literal values directly. Recommendation: **include**, as the foundation of the stack. Estimated standalone savings: 2-4× on the JSON-syntax overhead, plus whatever symbolic markers absorb of the per-node category cost. The form must remain parseable by a deterministic shift-reduce or PEG grammar so the constrained-generation layer (§3.4) can apply.

### 3.2 Inference passes on the authoring layer

The LLM omits annotations that are mechanically recoverable: `Lambda.paramType` where the call-site argument type forces it; `Application.typeArguments` where bidirectional checking of value arguments determines the instantiation; `effects` lists where the body's effect closure is computable; `effectInstances` where the surrounding capability context determines the refinement. The verifier sees a fully-annotated graph after the inference pass; correctness wins are unchanged. Recommendation: **include**. Estimated standalone savings: 30-50% on programs whose annotation overhead is significant (typed lambdas, refined effect calls). The inference must not change verifier semantics — anything the inference cannot derive remains the LLM's responsibility, and a `SchemaInferenceDeferred`-style diagnostic surfaces unresolved positions before they reach the verifier.

### 3.3 Tokenizer alignment

A domain-specific tokenizer treating node category tags, common builtin target strings (`strand-builtin:Int.Add`), edge labels, and base-N hash digests as single tokens. Hash digests are the worst current offender: a 32-byte BLAKE3 hash in base16 is 64 tokens under most general-purpose tokenizers; a domain-aware tokenizer would treat it as a single token, a ~64× local saving on hash-heavy passages. Recommendation: **defer**. The technique requires either a fine-tuned model with a custom tokenizer or a wrapper tokenizer used only at training time. Both are model-side investments the project cannot make in Phase 2; the proposal treats Layer 3 (tokenizer alignment) as a Phase 4 follow-up once Phase 1 measurements have confirmed the rest of the stack is worth investing in.

### 3.4 Constrained generation

Grammar-driven decoding against the closed inventory of node categories and per-category edge schemas. At each emission position, the next-token distribution is masked to tokens that extend a valid parse. This guarantees syntactically valid output and reduces the model's commitment cost per position (it commits among the valid alternatives only, not the whole vocabulary). Recommendation: **include** as the decoding-time enforcement for the Layer 1 projection. The library ecosystem (Outlines, LMQL, llama.cpp grammar support, native structured-output features in OpenAI and Anthropic APIs) makes this an integration rather than a research project. Estimated effect: 50-90% reduction in retry rate from parse failures (which today are the majority of verifier-rejected emissions before any semantic check fires), plus a model-side reasoning improvement from spending decoding bits on semantics not syntax.

### 3.5 Session-scoped handles

Short local identifiers for recently-introduced nodes within an authoring session, resolved to canonical hashes at commit. The current flat-form dag-json already uses author ids; the technique here is to make those ids as short as possible — single-character or numeric, reused across the whole document, optionally anonymous for nodes that appear only once. Recommendation: **include**. The mechanism is already half-present in the dag-json ingest; the proposal extends it to anonymous positional handles for one-use nodes and to a tight numeric namespace. Estimated savings: 10-20% on programs with many nodes. The handle scheme is intra-document; cross-document references still use canonical hashes.

### 3.6 Tool-call assembly

The LLM constructs the graph through tool invocations rather than serialized output. Each call adds a node or wires an edge; the platform validates incrementally. Recommendation: **include as an alternative interaction pattern**, not as a replacement for the text projection. Modern function-calling APIs make tool-call assembly natural when available; raw text generation remains the fallback. The two interfaces map to the same elaboration pipeline — the schema for the tool calls is derived from the same grammar that drives constrained text generation. Estimated effect on token cost: format-dependent. Tool-call serialization carries its own per-call overhead (function name, JSON parameter wrapper); on long programs the per-call overhead dominates and text projection wins; on highly iterative or interactive emissions, tool calls win because each call is validated and the model can respond to feedback before emitting the next.

## 4. Recommended approach

The authoring layer is a stack of four layers running between the LLM and the verifier. The canonical dag-json form, the canonical CBOR encoding, and the verifier itself are unchanged; the stack sits upstream of them as a tool-layer affordance.

**Layer A — Compact projection (`§3.1` + `§3.5`).** The LLM-facing surface form. A line-oriented s-expression-like syntax that drops JSON quoting on field names, uses positional encoding for child arrays, uses single-character author ids, and inlines small literals. Defined by an LL(1) grammar so any standard PEG or recursive-descent parser implements it. Programs in this form remain unambiguous; the expansion to dag-json is deterministic and bidirectional.

**Layer B — Grammar-constrained decoding (`§3.4`).** When the LLM emits Layer-A tokens, decoding runs under a grammar that mirrors the Layer-A grammar. Invalid emissions have zero probability. The grammar is generated from the same node-category schema that drives Layer A's parser. Tool-call assembly (`§3.6`) is an alternative emission interface that uses the same per-category schema as function signatures; the platform's structured-output enforcement plays the role of Layer B.

**Layer C — Elaboration (`§3.2`).** Layer A → canonical dag-json. The elaboration pass runs bidirectional type checking and effect-closure inference over the Layer-A graph, filling in `paramType` annotations, `typeArguments` instantiations, `effects` lists, and `effectInstances` positions wherever they are mechanically recoverable. Unresolved positions trigger a structured `ElaborationGap(position, reason)` diagnostic that the agent's loop sees before any verifier output. The elaboration is intentionally limited to what bidirectional checking handles deterministically; ambiguous positions remain the LLM's responsibility.

**Layer D — Verifier (unchanged).** Consumes the canonical dag-json the elaboration emits. ADR-001, ADR-002, ADR-003, ADR-004 are all preserved. The verifier sees fully-annotated graphs; its semantics and error vocabulary are identical to today's.

Tokenizer alignment (`§3.3`) is recognized as a future fifth layer beneath A — a Phase 4 fine-tuning investment — and is not in the initial stack.

The stack composes such that an LLM emission travels A → (B at decode time) → C → D. A program authored as canonical dag-json bypasses A, B, and C: the verifier still accepts it. This is the additivity property — every existing seed-corpus program in `impl/corpus/` continues to work unchanged; the stack is a tool affordance, not a language change.

## 5. Detailed mechanism

### 5.1 Layer A grammar sketch

A Layer-A program is a header line followed by a sequence of node forms. Each form is a single-character category marker, optional author id, ordered child sequence in parentheses or angle brackets per the category's edge schema. Literal nodes inline their value. Comments and whitespace are stripped before grammar parsing.

The polymorphic identity program from `02-identity-applied.json` becomes approximately:

```
@v1 r=app
T a
P_Int i
P x:a
V x→x
λ[x] x
Λ[a] λ
$intlit 42
app id[i](42)
```

Each line is one node. `T` introduces a TypeParameter `a`; `P_Int` introduces a primitive Int type bound to `i`; `P x:a` is a ParameterDecl with name `x` and paramType `a`; `V` is a VarRef; `λ[x] x` is a Lambda over parameter `x` with body the previously-declared VarRef; `Λ[a] λ` is a TypeAbstraction binding `a` with body the previously-declared Lambda; `$intlit 42` is an IntLit; the final line is the Application. Categories use single-character markers (`T`, `P`, `V`, `λ`, `Λ`, `$`, `app`, etc.) drawn from a fixed alphabet documented in the Layer-A spec.

This is illustrative — the production grammar would settle the bracket conventions, the order of child fields, and the rules for inlining vs naming. The expansion to dag-json is unambiguous given a fixed grammar: each Layer-A line expands to one node record with the author id, type tag, and child references the grammar specifies.

Token-cost on this example: the dag-json `02-identity-applied.json` is approximately 110 BPE tokens; the Layer-A equivalent above is approximately 35 tokens. Standalone savings ~3×, before any elaboration.

### 5.2 Layer B constraint grammar

The constraint grammar is derived from Layer A's grammar plus the per-category edge schema. At each emission position, the live grammar state determines the set of legal next tokens. The decoder masks the model's logits to that set before softmax.

When the LLM is emitting a Lambda body and the surrounding type expectation is `Int`, the grammar permits IntLit values, VarRefs to Int-typed binders in scope, Applications whose return type is Int, and so on; it forbids StringLits, Boolean returns, or unbound VarRefs. The grammar's lookahead is bounded by the Layer-A category schemas, which are themselves bounded by the node algebra.

When the deployment uses tool-call assembly (`§3.6`), Layer B's role is played by the platform's structured-output enforcement: the tool's parameter schema is derived from the same edge schema, and the model is forced to emit valid arguments by the platform's decoder.

### 5.3 Layer C elaboration

The elaboration pass runs bidirectional type checking and effect-closure inference over the Layer-A graph. Concretely:

- **Type inference for `Lambda.paramType`.** When the Lambda appears in an Application position whose function type is known, the parameter's type is forced. The annotation is filled in. When the Lambda appears unannotated and untyped by context (e.g., a top-level binding), elaboration emits `ElaborationGap(at = Lambda, reason = "untyped lambda not in call position")` and the LLM must annotate.
- **Type-argument inference for `Application`.** When the function is a `TypeAbstraction`-typed value and the value arguments have known types, the type arguments are inferred by structural matching of the value-argument types against the function's parameter types. When matching is ambiguous (e.g., two different instantiations produce well-typed terms), elaboration emits `ElaborationGap(at = Application, reason = "ambiguous type instantiation")`.
- **Effect-closure computation for `Lambda.effects`.** The closure-of-body computation per `design/effects-and-capabilities.md` § Effect closure is mechanical. The elaboration fills in the effects list. The LLM may emit a partial or empty effects list; the elaboration replaces it. The verifier's existing closure-coverage check then runs against the fully-filled-in list.
- **Effect-instance defaulting for `Application.effectInstances`.** When the surrounding capability context grants exactly one capability whose category matches, that capability's `EffectDecl` is the default. When zero or multiple match, elaboration emits `ElaborationGap`.

The elaboration is intentionally limited to what bidirectional checking handles without unification. Hindley-Milner style let-generalization is out of scope; matching the existing Layer 1 implementation's "no inference" choice keeps the elaboration deterministic and the LLM's emission predictable.

### 5.4 Layer D handoff

Elaboration emits canonical dag-json. The verifier sees the same shape it sees today. If elaboration emitted unresolved gaps, the orchestrator surfaces them to the agent loop before invoking the verifier — agents that emit unresolved gaps fail fast at the elaboration boundary, not after a wasted verifier pass.

The full pipeline: `LLM → Layer A token stream → Layer B constraint → Layer A parser → Layer C elaboration → canonical dag-json → Layer D (existing verifier) → either verified or structured rejection`. Verifier rejections feed back into the agent's loop in the same structured form as today.

## 6. Token-cost analysis

The standalone savings per layer, drawn from the §3 evaluations:

| Layer | Standalone token-cost saving | Notes |
|------|------------------------------|-------|
| A (projection) | 2-4× | Replaces JSON syntax overhead with a tight grammar |
| C (elaboration) | 1.3-1.7× | Recovers annotation overhead from inference |
| E (handles, in A) | 1.1-1.2× | Shortens author ids |
| B (constraints) | (per-program neutral) | Reduces retry rate, not per-program size |
| Tokenizer (deferred) | 1.3-2× | Estimated; depends on tokenizer training |

The stack's compounded savings are the product of the standalone savings of the layers it includes. For the recommended A+B+C+E stack, the estimated multiplier is `2.0 × 1.3 × 1.1 ≈ 2.9×` at the low end and `4.0 × 1.7 × 1.2 ≈ 8.2×` at the high end. The Q-021 baselines (Python+type-hints, Kotlin Coroutines, Rust, TypeScript-strict, SimPy) sit at 1× by definition. A stack that achieves 3-8× compression over canonical dag-json brings the canonical-to-baseline ratio from today's 2-5× back toward parity or better.

Counted differently: the per-program token cost of canonical dag-json relative to Python+type-hints today is approximately 2.5× on the seed corpus's larger programs (40-45, state-machine examples) and approximately 4-6× on the smaller programs (01-10). With the recommended stack, the projected ratios are 0.8-1.3× on the larger programs and 1.5-2.5× on the smaller ones. The crossover where Strand's token cost is competitive with conventional languages happens at moderate program size; very small programs may remain more expensive per-program but the agent's retry-rate advantage (Layer B + the verifier together) may close the per-task gap.

These estimates are not measurements. The Phase 1 / Q-021 evaluation framework (§7) is what produces actual numbers.

## 7. Evaluation framework

Q-034's own constraint is that resolution requires "a working verifier and an agent generating against it." The proposal supplies a design that can be implemented and measured. The measurement framework that closes Q-034:

**Baselines.** Q-021 already names five: Python+type-hints, Kotlin Coroutines, Rust, TypeScript-strict, SimPy. Add a sixth: raw canonical dag-json emission (the current state).

**Test programs.** A task suite drawn from Q-021's three families (reproduction, effects, distribution) plus the seed corpus's representative programs. Each task is a natural-language prompt paired with at least one known-correct reference solution.

**Metrics.** For each task, for each baseline and for each authoring-layer configuration:
- Tokens-per-emission attempt
- First-pass verification rate
- Tokens-per-successful-task (the integral over the agent's retry loop)
- Wall-clock latency (decoding + elaboration + verification)
- Elaboration gap rate (proportion of emissions with unresolved gaps)

**Configurations to measure.** Six authoring-layer configurations crossed against the baselines:
1. Raw canonical dag-json
2. Layer A alone
3. Layer A + Layer C
4. Layer A + Layer B
5. Layer A + Layer B + Layer C (the recommended stack)
6. Tool-call assembly + Layer C (the alternative interaction interface)

**Success criterion.** The recommended stack is adopted if it achieves tokens-per-successful-task within 1.3× of the geometric mean of the five conventional-language baselines on the task suite. The 1.3× floor accounts for Strand's annotation surface that no inference can absorb (the verifier's correctness wins must justify any residual cost).

**Statistical discipline.** Each task is run with N=20 sampled emissions per (configuration, model) cell. Per-task confidence intervals are bootstrapped; the geometric mean across tasks is the headline number; the per-task variance is reported because per-task performance is often more diagnostic than the mean.

This framework is itself a Phase 1 / Q-021 work item — it does not need to be in Q-021's existing specification to be added during execution. The proposal flags it here so the implementing session for Q-034 picks it up as part of the same slice.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Tokenizer alignment (Layer 3.3).** The technique with the largest theoretical local saving on hash-heavy passages is out of the initial stack because it requires model-side training investment the project cannot make in Phase 2. Revisit in Phase 4 once the rest of the stack has been measured.
- **Inference for let-generalization or full Hindley-Milner.** The elaboration is bidirectional only. Programs that would benefit from let-polymorphism still require explicit TypeAbstraction annotations from the LLM. The matching choice (no inference at the language level) is preserved at the authoring layer.
- **Multiple authoring projections.** A second Layer A optimized for a different model family (e.g., a code-model-friendly projection vs a chat-model-friendly projection) is forward-compatible but not part of the initial proposal. The Layer C / D contract is the stable interface.
- **Cross-document references.** Author ids are intra-document; cross-document references continue to use canonical hashes. A future "session" model that scopes handles across multiple documents in one editing session is recognized but deferred.
- **Provenance of authoring-layer source.** Where the Layer A source comes from (the LLM's emission), and whether the agent loop archives Layer A source for replay, is a Phase 1 infrastructure question. The proposal assumes the orchestrator captures Layer A as the unit of replay; the elaboration is deterministic so re-running Layer A → dag-json produces the same canonical hashes.
- **Interactive editing.** Holes, partial programs, and structure-editor-style refinement (the Hazel pattern at full strength) are out of scope. The LLM emits complete Layer A programs; elaboration accepts or rejects them as a unit. Interactive editing is a separate tooling track.

**Real research questions:**

- **OQ-Q034-a.** What is the right grammar for Layer A? The single-character category markers in §5.1 are a sketch; the production grammar must balance per-position emission cost against LL(1)/PEG parseability and against the constraint-grammar derivation. A small adversarial corpus of programs that stress the grammar's ambiguity is needed to settle the choice.
- **OQ-Q034-b.** Does bidirectional elaboration cover the right fraction of programs without explicit annotations? Empirical answer only — needs a corpus, an inference implementation, and a measurement of the `ElaborationGap` rate per program category. If the gap rate is high on real workloads, the elaboration may need to be enriched or the LLM emission contract may need to require more annotations.
- **OQ-Q034-c.** How sensitive is the token-cost analysis to the choice of LLM tokenizer? The §6 estimates assume general-purpose BPE. Code-specialized tokenizers (Codex, CodeLlama) compress code-like text by 1.3-2× already; the marginal value of Layer A under those tokenizers is smaller. The §7 framework measures this across the model families used in evaluation.
- **OQ-Q034-d.** What is the latency budget for the elaboration pass? If elaboration runs in tens of milliseconds, the agent's loop is unaffected. If it runs in seconds (e.g., bidirectional checking with backtracking on complex programs), the latency adds to the per-emission cost. The §7 framework includes wall-clock latency for this reason.
- **OQ-Q034-e.** How does the authoring layer interact with the schema mechanism (Q-035)? Schema-typed values flowing through the elaboration need their structural type checked; the schema's invariant evaluation runs after the verifier. The elaboration is upstream of both; it should not need to know about schemas at all (the canonical dag-json carries the SchemaType annotation already). Confirm during implementation.
- **OQ-Q034-f.** Tool-call assembly's per-call overhead at scale. The §3.6 evaluation noted that tool calls add per-call framing cost; long programs may pay more in framing than they save in incremental validation. The §7 framework's configuration 6 measures this directly.

## 9. Implementation sketch

The implementation is itself a multi-step shipping plan, parallel in structure to the Layer 1-7 plan of the verifier and interpreter. The first step is the smallest coherent slice that demonstrates the stack end-to-end on a representative subset of the seed corpus.

| File | Change | Size |
|------|--------|------|
| `impl/authoring/` | NEW Gradle module — depends on `:verifier` + `:interpreter` + `:core`. Houses Layer A parser, Layer C elaboration, Layer A → dag-json emitter | Medium-Large |
| `impl/authoring/src/main/kotlin/org/strand/authoring/LayerAGrammar.kt` | NEW — formal grammar for Layer A; PEG parser implementation; round-trip property tests | Medium |
| `impl/authoring/src/main/kotlin/org/strand/authoring/LayerAParser.kt` | NEW — Layer A text → `LayerAAst` (intermediate tree before dag-json projection) | Medium |
| `impl/authoring/src/main/kotlin/org/strand/authoring/Elaborator.kt` | NEW — bidirectional type-checking + effect-closure inference; emits `ElaborationGap` for unresolved positions | Large |
| `impl/authoring/src/main/kotlin/org/strand/authoring/ElaborationGap.kt` | NEW — structured gap diagnostic, distinguishing "underspecified" from "ambiguous" from "ill-typed-pre-elaboration" | Small |
| `impl/authoring/src/main/kotlin/org/strand/authoring/DagJsonEmitter.kt` | NEW — elaborated `LayerAAst` → canonical dag-json bytes consumable by the existing `JsonIngest` | Small-Medium |
| `impl/authoring/src/main/kotlin/org/strand/authoring/ConstraintGrammar.kt` | NEW — Layer B grammar derivation from per-category edge schemas; outputs in a format consumable by Outlines, LMQL, or llama.cpp grammar (single shared representation) | Medium |
| `impl/cli/src/main/kotlin/org/strand/cli/Main.kt` | EXTEND — add `strand author <layer-a.txt>` subcommand: elaborate, emit dag-json, optionally pipe to existing `verify` and `run`. Existing subcommands unchanged | Small |
| `impl/corpus/src/main/resources/corpus/layer-a/` | NEW directory — Layer A source for the existing seed corpus, paired with the dag-json equivalents. Test asserts elaboration produces structurally-equal dag-json | Medium |
| `impl/corpus/src/test/kotlin/org/strand/corpus/LayerARoundTripTest.kt` | NEW — for each Layer A corpus entry, parse → elaborate → emit → ingest → verify → assert hash equality with the canonical reference | Medium |
| `impl/authoring/src/test/kotlin/org/strand/authoring/ElaboratorTest.kt` | NEW — unit tests for each elaboration case: Lambda paramType, Application typeArguments, effect closure, effect-instance defaulting | Large |
| `impl/authoring/src/test/kotlin/org/strand/authoring/ConstraintGrammarTest.kt` | NEW — grammar-derivation tests; round-trip property tests (parse-valid-emit-valid for randomly generated graphs) | Medium |
| `proposals/llm-authoring-layer.md` | THIS DOCUMENT | n/a |
| `evaluation/` | NEW top-level directory housing the Q-021 / Q-034 measurement framework: task suite definitions, baseline runners, configuration runners, statistics. Lives outside `impl/` because it spans languages and is not part of the reference runtime | Large; deferred to a follow-up shipping step |
| `impl/CLAUDE.md` | EXTEND — add Layer 8 (authoring layer) entry once the first step ships; update "Deferred to later layers" table | Small |
| `open-questions.md` | EXTEND — Q-034 status Open → Proposed, resolution summary points at this document | Trivial |
| `INDEX.md` | EXTEND — Last revised line; concept index entry for "Authoring layer" already exists from when Q-034 was registered | Trivial |
| `proposals/README.md` | EXTEND — add this proposal to the Current proposals table | Trivial |

**Order of work.**

1. **Layer A grammar and parser** — settle the grammar, implement the PEG parser, write round-trip tests against a small subset of the seed corpus translated to Layer A by hand. Concrete enough to measure §6 token-cost claims for that subset.
2. **Layer C elaboration core** — bidirectional type checking for `Lambda.paramType` and `Application.typeArguments`. Effect-closure inference. `ElaborationGap` diagnostics. Tests on the same Layer A corpus subset.
3. **DagJsonEmitter and end-to-end CLI** — wire `strand author <layer-a.txt>` so a Layer A program elaborates, emits dag-json, and runs through the existing verify/run pipeline. The seed corpus subset must produce hash-equal dag-json on round-trip.
4. **Constraint-grammar derivation** — derive the LL(1) / PEG grammar from the node-category schemas in a single shared representation that Outlines, LMQL, and llama.cpp grammar can consume. No model integration yet — the grammar is the artifact.
5. **Evaluation framework scaffolding** — the `evaluation/` directory, the task suite skeleton, the baseline runners for Python+type-hints and one other conventional language. Wiring against actual model APIs is deferred to a Phase 1 step.
6. **Tool-call assembly schema generation** — derive function-call schemas from the same node-category schemas; produce a tool definition file consumable by Anthropic / OpenAI tool-use APIs. Integration is the agent loop's responsibility, not the authoring layer's.

**Not in this slice.** Tokenizer alignment (Layer 3.3); let-generalization and Hindley-Milner; cross-document handle scope; interactive editing and partial-program holes; model fine-tuning. The Phase 1 / Q-021 actual measurements are the next milestone after the implementation lands.

## References

**Outgoing references:**
- [`00-motivation.md`](../00-motivation.md) — the AI-first framing this proposal serves
- [`decisions/ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md) — graph-native source; the proposal preserves this
- [`decisions/ADR-002-no-human-projection.md`](../decisions/ADR-002-no-human-projection.md) — the proposal sits within ADR-002 by distinguishing LLM emission from human projection
- [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — canonical CBOR and BLAKE3 are unchanged; the authoring layer is upstream
- [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — effect declarations remain mandatory at the canonical form
- [`design/node-algebra.md`](../design/node-algebra.md) — node category schemas drive both Layer A's grammar and Layer B's constraint grammar
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — effect-closure computation is what Layer C's effect inference automates
- [`research-plan.md`](../research-plan.md) — Phase 1 Stage 1.3 is the loop the authoring layer plugs into; Q-021's metrics are the success criterion
- [`open-questions.md`](../open-questions.md) — Q-021 (evaluation metrics), Q-034 (this question), Q-035 (schema mechanism interaction)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — implementation state the new module integrates with

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-034 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Known gaps section
