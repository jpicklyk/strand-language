# Bounded retrieval-augmented generation demonstration {#bounded-rag-demo}

**Document:** `demos/bounded-rag/README.md`
**Status:** Executable demonstration of the canonical agent RAG pattern under a refined data-access capability
**Last revised:** 2026-06-13

## What this demonstration is

The canonical agent pattern — embed the user's query, retrieve relevant context
from a vector index, and generate an answer from that context — expressed as a
verified Strand graph. The distinctive property this demonstration shows is that
the index an agent may reach is not a configuration string the code can
overwrite at will, but a declared, verifier-surfaced, refinement-checked
capability: `Vector.Read` is granted *refined to a specific store*
(`Vector.Read{provider=pinecone, store=corp-kb}`), so the agent provably can
query only that index. A retrieval against any other store is denied at the
foreign-call boundary, before a request leaves the process.

The program's effect closure is exactly `{LLM.Embed, Vector.Read, LLM.Generate}`
— the three AI-native capabilities the RAG pipeline incurs. The host reads that
closure off the verified artifact (the Q-067 surfaced closure) and treats it as
the agent's *data-access manifest*: a machine-checked statement of everything the
agent can reach, available before any execution. The data an agent may touch
becomes a verifiable property of the artifact, not a convention buried in
configuration.

The host is an ordinary JVM caller of the shipped embedding surface — the Q-054
`StrandRuntime` facade, the Q-031 refined `CapabilitySet` grant, and the Q-064
`DenialReport`. It introduces no language feature, no node category, no encoding
change, and no verifier rule. The two transports the pipeline reaches — the LLM
provider (embed and generate) and the vector store (query) — are deterministic
mocks injected through the per-instance `HostPolicy`, so the run performs no real
network I/O. Every property the demonstration claims is one the runtime enforces,
and the assertion net (`BoundedRagDemoTest`) protects each one: the surfaced
closure is the verifier's own, and the denial's `DenialReport` is the one the
interpreter constructed at the refinement-check site — not a staged stand-in.

The RAG programs are hand-authored stand-ins for agent submissions. They live as
canonical dag-json under [`programs/`](programs/). Hand-authoring them isolates
the data-access containment — the subject of this demonstration — from the
separate question of how an agent generates programs, which the Q-021 cost
measurement and the deferred Run 8 dynamic study address.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:boundedRagDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.BoundedRagDemoTest"
```

The driver `BoundedRagDemo` and the test `BoundedRagDemoTest` live in the
`:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one body of
scenario code, so the printed demonstration and the regression net cannot
diverge. They stay in `:runtime` because they compile against the runtime
modules. The driver loads the committed canonical dag-json from
[`programs/`](programs/) through the test classpath
(`runtime/build.gradle.kts` copies the directory in via `processTestResources`),
so the artifact the host admits is the content-addressed graph, not the
human-facing projection.

## The scenarios

### R1 Bounded RAG run

The program `bounded-rag` is the three-stage pipeline. It calls
`OpenAI.Embeddings.Create` (declaring E-036 `LLM.Embed`) to embed the query
string, opens and queries the `corp-kb` Pinecone index via
`Pinecone.Index.Open` + `Pinecone.Index.Query` (declaring E-037 `Vector.Read`),
and generates an answer with `Anthropic.Messages.Create` (declaring E-035
`LLM.Generate`). The stages are sequenced through a `Let` chain, and the data
flow is genuine: the query vector handed to the retrieval stage is the *real
output of the embed stage*, and the program's root value bundles the retrieved
hits with the generated answer so the retrieval is observably consumed, not
sequenced and discarded.

The host verifies the program, reads its surfaced effect closure (Q-067) off
`VerifyResult.Ok` — `{LLM.Embed, Vector.Read, LLM.Generate}` — and prints it as
the data-access manifest. It then grants exactly those three categories, with
`Vector.Read` refined to `{pinecone, corp-kb}` and the two LLM categories
granted wildcard, and runs the pipeline under the deterministic mocks. The mock
LLM client serves the OpenAI embedding response and the Anthropic completion; the
mock vector transport serves the Pinecone query matches. All three stages run
once each, the retrieval returns two hits, and the program produces a generated
answer alongside the retrieved context. The bound is read off the artifact before
the run, and the grant covers exactly the surfaced closure.

### R2 Refined-store denial

The program `bounded-rag-hr` is the same pipeline shape, with one change: it
opens `corp-kb` (a granted `Vector.Read`) but issues its retrieval query
declaring `Vector.Read{provider=pinecone, store=hr-private}` — a store the grant
does not cover. The program verifies clean: its surfaced closure is identical to
R1's at the category level (`{LLM.Embed, Vector.Read, LLM.Generate}`), because the
refinement is not a category — the bound lives in the refinement parameters, not
the closure.

The host grants `Vector.Read` refined to `corp-kb`, exactly as in R1. The embed
stage runs and the open of `corp-kb` succeeds, but at the retrieval query the
runtime evaluates the requested refinement `{pinecone, hr-private}` against the
held grant `{pinecone, corp-kb}`; no granted pattern covers it, so the call is
denied with a `RefinementViolation` carrying a structured `DenialReport`
(category, requested versus held, denying node, phase). The denial fires at the
foreign-call boundary, before the query reaches the vector wire — the transcript
shows zero query HTTP calls. The agent can read only the index it was granted.

This is the contrast with conventional RAG. In a conventional pipeline the index
an agent queries is a configuration string the code holds and can overwrite at
will — nothing structural stops a query from being redirected to a different,
more sensitive index. Here the index is a refinement-checked capability the
runtime enforces against the grant the host applied: the declared store and the
held grant must agree, and they cannot diverge without a denial.

## Transcript

The transcript below is the output of `./gradlew :runtime:boundedRagDemo -q`.

```
========================================================================
Strand -- bounded retrieval-augmented generation (RAG).
embed -> vector-query(store=corp-kb) -> generate, as a verified graph
whose data-access capability is refined to one index. Transports are
deterministic mocks; no real network I/O.
========================================================================

R1  Bounded RAG run -- the agent's data-access manifest is the closure
------------------------------------------------------------------------
  Program: bounded-rag (embed the query, retrieve from the corp-kb
           index, generate an answer from the retrieved context).
  The data-access manifest is read off the verified artifact BEFORE
  running -- the surfaced effect closure (Q-067):
    surfaced closure (manifest) = [LLM.Embed, LLM.Generate, Vector.Read]
    granted categories          = [LLM.Embed, LLM.Generate, Vector.Read]
    grant covers exactly it     = true
  Vector.Read is granted refined to {provider=pinecone, store=corp-kb}
  -- the agent provably can query only that index.
  Host runs the pipeline under that grant with the deterministic mocks:
    verified clean              = true
    embed / query / generate    = 1 / 1 / 1
    all three stages ran        = true
    retrieved hits              = 2 (first: kb-doc-17)
    completed                   = true
    generated answer            = "Travel is reimbursed up to $75/day with receipts (per the retrieved policy)."
  The query vector is the real embed output; retrieval returns real
  hits; the output bundles the retrieved context with the answer.

R2  Refined-store denial -- the agent can read only the granted index
------------------------------------------------------------------------
  Program: bounded-rag-hr (opens corp-kb, then issues its retrieval
           query declaring Vector.Read{pinecone, hr-private}).
  Same surfaced closure as R1 -- the manifest is identical at the
  category level; the refinement is where the bound lives:
    surfaced closure (manifest) = [LLM.Embed, LLM.Generate, Vector.Read]
    verified clean              = true
  Host grants Vector.Read refined to corp-kb. The embed and the open
  of corp-kb succeed; the hr-private query is beyond the refined grant:
    embed calls before denial   = 1
    query reached the wire      = false (query HTTP calls = 0)
    denied                      = true (RefinementViolation)
    DenialReport: category=Vector.Read requested=[pinecone, hr-private] held=[Vector.Read{pinecone, corp-kb}] node=#122 phase=expression
  The read is denied at the foreign-call boundary, before any request
  leaves the process. The index an agent may reach is a refinement-
  checked capability, not a config string the code can overwrite.

========================================================================
What this demonstrates: bounded, provable data access for the canonical
agent pattern -- the index an agent can query is a verifier-surfaced,
refinement-checked capability. NOT first-pass correctness or inference
cost (the deferred Run 8 study); the transports are mocks and the
program is hand-authored. The refined-store guarantee rests on
Vector.Read being refined by {provider, store}.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows that the data an agent may reach is a declared,
verifier-surfaced, refinement-checked property of the artifact. It shows the
canonical RAG pipeline running under a grant read off the program's own effect
closure, with the vector capability pinned to one store; and it shows a query
against a different store denied at the foreign-call boundary with the real
`RefinementViolation` and `DenialReport` the runtime produces. Each is a property
the shipped runtime enforces, witnessed under the published embedding API.

It does **not** demonstrate the following, and the boundaries are stated honestly:

- **Real model or vector behavior.** Both transports are deterministic mocks
  injected through the `HostPolicy`. The embedding, the retrieved matches, and the
  completion text are canned; the mock LLM client never reaches the network and
  the mock vector transport returns fixed matches. The demonstration measures the
  containment property — bounded, surfaced, refinement-checked data access — not
  retrieval quality or generation quality, which depend on real providers.

- **Agent generation.** The two programs are hand-authored canonical dag-json,
  not agent emissions. Hand-authoring isolates the data-access containment from
  the separate question of how an agent generates an admissible program.

- **First-pass correctness or inference cost.** Whether an agent's submission is
  the program it intended, and the tokens it spends to produce an admissible one,
  belong to the deferred Run 8 dynamic measurement recorded in
  [`evaluation/dynamic-results.md`](../../evaluation/dynamic-results.md), which
  requires agent-emission sampling through the model API and is a distinct study.

The refined-store guarantee in R2 rests specifically on the `Vector.Read` effect
category being **refined by `{provider, store}`** (its two parameters), so the
runtime can match a call-site `EffectDecl`'s declared store against the held
grant's store. The guarantee is exactly as strong as that refinement: it bounds
which index a `Vector.Read` call may name, not the transitive behavior of a
provider that, once reached, ignores the named store — that is the trust model of
the vector binding, out of scope here. The demonstration claims neither more nor
less than the mechanism delivers.

One authoring choice worth recording: the RAG programs declare
`Pinecone.Index.Open` with only the `Vector.Read` effect, rather than the
read-and-write surface the corpus reference for the binding (corpus 68) declares.
The pipeline uses the index read-only, so declaring only `Vector.Read` keeps the
surfaced closure equal to the RAG triple `{LLM.Embed, Vector.Read, LLM.Generate}`.
The verifier trusts a `ForeignNode`'s declared effects (ADR-005 trust model — the
binding's actual effects are not re-derived at admission), so this narrowing is a
deliberate, honest statement that the program opens the index for reading only.

## References

**Outgoing references:**
- [`proposals/implemented/agent-native-vector-stores.md`](../../proposals/implemented/agent-native-vector-stores.md)
  — Q-038, the Pinecone vector binding (E-037 `Vector.Read` / E-038 `Vector.Write`,
  each refined by `{provider, store}`) the retrieval stage uses and R2 refines.
- [`proposals/implemented/agent-native-capabilities.md`](../../proposals/implemented/agent-native-capabilities.md)
  — Q-037, the per-provider LLM bindings (E-035 `LLM.Generate` / E-036 `LLM.Embed`)
  the embed and generate stages use.
- [`proposals/implemented/refinement-lattice-capability-matching.md`](../../proposals/implemented/refinement-lattice-capability-matching.md)
  — Q-031, how a refined capability matches or denies a concrete request (R2's
  refined-store denial).
- [`open-questions.md`](../../open-questions.md#Q-067)
  — Q-067, the verifier-surfaced effect closure the host reads as the data-access
  manifest (R1, R2).
- [`proposals/implemented/capability-denial-observability.md`](../../proposals/implemented/capability-denial-observability.md)
  — Q-064, the structured `DenialReport` captured in R2.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade and per-instance `HostPolicy` (the mock
  transport injection) this host is built on.
- [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md)
  — the effect-category and refinement-lattice model the demonstration rests on.
- [`demos/agent-workflow/README.md`](../agent-workflow/README.md) — the
  bounded-agent-workflow demonstration that puts the LLM tool-use loop under a
  surfaced bound; this one extends the AI-native primitives to the RAG pattern and
  the refined data-access capability.

**Incoming references:**
- [`demos/README.md`](../README.md) — the demonstrations index.
- [`INDEX.md`](../../INDEX.md) — changelog entry (2026-06-13).
