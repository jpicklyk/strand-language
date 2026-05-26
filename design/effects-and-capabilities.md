# Effects and Capabilities {#effects-and-capabilities}

**Document:** `design/effects-and-capabilities.md`
**Status:** Wave 3 draft
**Last revised:** 2026-05-26 (§ Diagnostic and host-environment effects added — E-032 Log.Write, E-033 OS.Read, E-034 System.Exit — to support the Log.* / OS.* / System.Exit builtin slice added in `stdlib expansion round 3`. No parameters; all three are simple guard categories.) 2026-05-23 (§ Effect handlers expanded with N-043 Handler node shape, closure algebra, runtime dispatch — per Q-030 resolution in `proposals/implemented/effect-handlers.md`)

## Summary

This document specifies the effect system and the capability mechanism that together provide Strand's security and distribution stories. The decisions established in [ADR-004](../decisions/ADR-004-effects-as-edges.md) (effects as mandatory typed edges) and the integration discussion in [02-core-thesis.md](../02-core-thesis.md) provide the high-level frame; this document gives the algebra of effects, the runtime semantics of capabilities, the delegation rules, and the policy for confused-deputy mitigation.

The design treats effects and capabilities as dual aspects of the same phenomenon. An *effect* is a static declaration that a graph node performs some interaction with the world; a *capability* is the runtime token that authorizes that interaction. The verifier statically computes the effect closure of a graph and confirms that the execution context's capabilities cover the closure. The runtime confirms at the point of each effectful operation that the calling context still holds the required capability.

Resolves [Q-003](../open-questions.md#Q-003) (effect categorization), [Q-004](../open-questions.md#Q-004) (delegation), [Q-005](../open-questions.md#Q-005) (confused deputy), [Q-007](../open-questions.md#Q-007) (effect inference) as proposed designs. Identifiers E-001 through E-031 are assigned below.

## Effect categories {#effect-categories}

The initial effect taxonomy is a small set of category groups. Categories within a group share the kind of resource they describe; each individual category names a specific kind of interaction. Categories are parameterized: a category may carry runtime values that further refine its meaning (a host address, a file path, a key identifier).

### Network effects (E-001 through E-005)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-001 | Network.Connect | host: String, port: Int | Initiate an outbound connection |
| E-002 | Network.Listen | port: Int, interface: String | Accept inbound connections |
| E-003 | Network.Send | connection: Connection, bytes: Bytes | Send bytes on an established connection |
| E-004 | Network.Receive | connection: Connection | Receive bytes from a connection |
| E-005 | Network.DNS | name: String | Resolve a DNS name |

### Filesystem effects (E-006 through E-009)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-006 | Filesystem.Read | path: Path | Read file contents |
| E-007 | Filesystem.Write | path: Path | Write file contents |
| E-008 | Filesystem.Execute | path: Path | Invoke an executable |
| E-009 | Filesystem.Watch | path: Path | Subscribe to filesystem change events |

### Time effects (E-010 through E-012)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-010 | Time.Now | (none) | Read the current wall-clock time |
| E-011 | Time.Sleep | duration: Duration | Suspend execution for a duration |
| E-012 | Time.Schedule | when: Time, callback: NodeRef | Schedule a deferred invocation |

### Process effects (E-013 through E-015)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-013 | Process.Spawn | program: Path, args: [String], env: Map | Spawn a child process |
| E-014 | Process.Signal | pid: ProcessId, signal: Int | Send a signal to a process |
| E-015 | Process.Wait | pid: ProcessId | Wait for a process to exit |

### Memory effects (E-016, E-017)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-016 | Memory.Allocate | size: Int | Allocate mutable memory |
| E-017 | Memory.MutableState | stateType: Type | Hold mutable state (typed) |

### Hardware effects (E-018 through E-020)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-018 | Hardware.GPU | device: DeviceId | Access a GPU device |
| E-019 | Hardware.NPU | device: DeviceId | Access an NPU device |
| E-020 | Hardware.Sensor | sensorClass: Name | Read from a hardware sensor |

### Cryptographic effects (E-021 through E-024)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-021 | Crypto.Sign | keyId: KeyId | Sign data with a private key |
| E-022 | Crypto.Encrypt | keyId: KeyId | Encrypt data with a key |
| E-023 | Crypto.Decrypt | keyId: KeyId | Decrypt data with a key |
| E-024 | Crypto.RandomBytes | length: Int | Sample cryptographically secure random bytes |

### Trust effects (E-025 through E-027)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-025 | Trust.Attestation | scheme: Name | Produce or consume a TEE attestation |
| E-026 | Trust.SealedStorage | path: Path | Read or write a TEE-sealed storage region |
| E-027 | Trust.MeasuredLaunch | image: Hash | Verify a measured-launch identity |

### State machine effects (E-028 through E-031)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-028 | StateMachine.Send | streamId: NodeRef, event: Event | Send an event to an event stream |
| E-029 | StateMachine.Receive | streamId: NodeRef | Receive an event from a stream |
| E-030 | StateMachine.Spawn | machineId: NodeRef | Instantiate a state machine |
| E-031 | StateMachine.Terminate | machineId: NodeRef | Terminate a running state machine |

### Diagnostic and host-environment effects (E-032 through E-034)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-032 | Log.Write | (none) | Emit a diagnostic line to the host's log sink (stderr or equivalent) |
| E-033 | OS.Read | (none) | Observe stable host-environment state (hostname, platform, working directory) |
| E-034 | System.Exit | (none) | Terminate the current evaluation with a host-level exit code |

### Language-model effects (E-035 through E-036)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-035 | LLM.Generate | provider: String, model: String | Invoke a language model for text or structured generation |
| E-036 | LLM.Embed | provider: String, model: String | Compute an embedding from text |

Both categories are operation-shaped: `LLM.Generate` is one effect category whether Anthropic, OpenAI, Gemini, or a future provider executes it. The `provider` and `model` parameters discriminate at the refinement-lattice level — `LLM.Generate{provider: "anthropic", model: *}` is a finer capability than `LLM.Generate{provider: *, model: *}`. This mirrors `Network.Connect{host}` (E-001) and `Filesystem.Read{path}` (E-006): the category names the kind of side effect; refinement parameters name the specific resource. Per-provider ForeignNodes (`Anthropic.Messages.Create`, `OpenAI.Chat.Completions`, etc.) sit at the binding layer; each pins the `provider` parameter to a string literal so the verifier sees provider identity in the effect closure without it needing its own category. The design rationale, prior-art survey, and tool-dispatch semantics are documented in [`proposals/agent-native-capabilities.md`](../proposals/agent-native-capabilities.md).

### Vector store effects (E-037 through E-038)

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-037 | Vector.Read | provider: String, store: String | Query or fetch from a vector store |
| E-038 | Vector.Write | provider: String, store: String | Insert, upsert, or delete in a vector store |

Operation-shaped (one category per direction across every provider), with `provider` and `store` parameters carrying the per-binding discrimination. The Read / Write split mirrors `Filesystem.Read` / `Filesystem.Write` (E-006 / E-007): most retrieval workloads read more than they write, so capability minimization naturally exploits the split. Capability scoping flows through the refinement-lattice — `Vector.Read{provider: "pinecone", store: "main"}` authorizes exactly one Pinecone index, `Vector.Read{provider: "pinecone", store: *}` authorizes any Pinecone read, `Vector.Read{provider: *, store: *}` authorizes any read.

Per-provider ForeignNodes under the `strand-builtin:` namespace carry the binding-layer discrimination: each binding's effect declaration pins `provider` to its provider's string literal (`"pinecone"`, `"chroma"`, ...) and binds `store` to the call site's argument. Switching providers means swapping ForeignNodes, which changes the graph's content-address hash per ADR-005. This pattern is consistent with WIT / WASI's one-interface-with-per-implementation-bindings convention and with the operation-shaped effect categorization established by E-001..E-034. The Q-038 proposal [`proposals/agent-native-vector-stores.md`](../proposals/agent-native-vector-stores.md) records the prior-art analysis and the seven API design questions (metric grain, index opacity, filter expression language, typed metadata, batching, pagination, idempotency) the surface answers.

This inventory is intentionally bounded but extensible. New categories may be added as new platform integrations are required; the category-tag space accommodates growth in the same way as node categories ([node-algebra.md](node-algebra.md), versioning section).

## Effect closure semantics {#effect-closure}

The *effect closure* of a node N is the set of effect categories that an evaluation of N may exercise. It is defined inductively:

1. The closure of N is the union of N's direct effect declarations and the closures of every node N references through structural (non-metadata) edges.
2. The closure terminates: cycles in the graph are broken by Fixpoint or NodeRef, both of which contribute their declared effects without expanding further until evaluation.
3. The closure includes a node's declared effects regardless of whether they are exercised on every evaluation path. The closure is a static overapproximation.

The closure is computed by graph traversal. The verifier maintains the closure for the graph's roots and recomputes incrementally when nodes are added.

Effect categories support a refinement order: `Network.Connect{host: "api.example.com", port: 443}` is *more specific* than `Network.Connect{host: *, port: 443}`, which is more specific than `Network.Connect{host: *, port: *}`. The refinement order forms a lattice for each category. Capability matching is by refinement: a capability covers a required effect if and only if the capability's specification is at-least-as-general as the requirement.

## Capability mechanism {#capabilities}

A *capability* is a runtime token authorizing a specific effect. Capabilities are not graph nodes; they are runtime values that the runtime constructs, holds, and confers. A capability has a category (one of E-001 through E-031 or an extension) and a parameter specification that may be fully or partially refined.

The *capability context* of an evaluation is the multi-set of capabilities the runtime confers on that evaluation. Capabilities are conferred at the entry points of the system — the runtime's top-level invocation, the boundary at which a graph is loaded for execution, or an explicit grant from a TEE attestation. Within an evaluation, capabilities flow with the call chain: a function called from a context holding capability C is itself evaluated with capability C in scope.

The default flow of capabilities is *implicit*: capabilities are ambient within a call chain, in the same way that lexical scope flows through nested function calls. This is the conventional behavior in most languages where authority is per-process. Implicit flow is the default because the alternative (explicit passing of every capability to every function) imposes substantial boilerplate without a security benefit when the trust boundary does not change.

The flow becomes *restricted* at *CapabilityScope* nodes. A `CapabilityScope` is a graph operation that evaluates its body expression in a new capability context, derived from the surrounding context by *narrowing*: the new context holds a subset of the surrounding context's capabilities, specified by the CapabilityScope's parameters. Narrowing cannot add capabilities, only remove. This is the mechanism by which a graph designates that a sub-computation should run with less authority than the surrounding code.

Capabilities are *un-forgeable*: there is no graph operation that constructs a capability for an effect the surrounding context does not already hold. New capabilities enter the system only at runtime boundaries, never within the language. This corresponds to the standard object-capability discipline.

## Delegation semantics {#delegation}

[Q-004](../open-questions.md#Q-004) asks whether capabilities are passed explicitly as arguments or forwarded implicitly. The chosen design is hybrid:

**Implicit forwarding within a graph.** A function called within a Strand graph inherits the capability context of its caller. Explicit capability arguments are not required for in-graph calls. This avoids the boilerplate of every effectful function declaring capability parameters.

**Explicit narrowing through CapabilityScope.** A graph that wants a sub-computation to run with reduced authority uses a CapabilityScope to specify the reduced context. This is the security-relevant operation; the design makes it explicit so that authority changes are visible in the graph.

**Explicit re-grant across foreign boundaries.** A ForeignNode does not implicitly inherit Strand-side capabilities. The capabilities a ForeignNode receives are those specified in its declaration and granted to it explicitly at the call site. Foreign code cannot acquire capabilities by ambient flow; it must be told. This protects against foreign code that would otherwise use the calling graph's full authority.

**Explicit re-grant across encrypted-node boundaries.** Per-node encryption creates a trust boundary at the point of decryption ([encryption-model.md](encryption-model.md)). The runtime, having decrypted a node, evaluates it with the capability context the surrounding graph confers; but the encryption envelope may further narrow the context (the envelope's interface declaration may carry capability restrictions).

The combination yields ambient flow within a trust domain and explicit declaration at trust boundaries. This matches the OCAP discipline as practiced in modern capability systems and is consistent with the threat model in [security-model.md](security-model.md).

## Confused deputy mitigation {#confused-deputy}

[Q-005](../open-questions.md#Q-005) asks how to mitigate confused-deputy attacks, in which a privileged subgraph is induced by a less-privileged caller to perform actions on the caller's behalf using the subgraph's authority.

The design adopts three layers of mitigation.

**Parameter-tagged capabilities.** Capabilities carry refined parameters that name the specific resources they authorize. A logging service does not hold `Filesystem.Write{path: *}` but `Filesystem.Write{path: "/var/log/app.log"}`. A caller cannot induce the logging service to write to a different file because the capability does not authorize it. This is the primary defense and is structural rather than behavioral.

**Capability minimization at scope entry.** When code crosses a trust boundary (a graph segment from a less-trusted source), a CapabilityScope narrows the context to the minimum capabilities the sub-computation requires. Less-trusted code thus runs with reduced authority by construction.

**Argument provenance checks.** When a privileged function accepts arguments that name resources (a path, a connection, a key identifier), the runtime may be configured to confirm that the caller had authority over the named resource. This is opt-in and adds runtime overhead; it is most useful at security-critical service boundaries.

These mitigations do not eliminate confused-deputy attacks entirely. They reduce the attack surface to cases where (a) capabilities are over-broad despite minimization, or (b) the privileged function is induced by argument values that the caller did have authority over but whose use the caller did not anticipate. The latter is a design problem at the level of the privileged interface, not a language-level vulnerability.

## Effect handlers {#effect-handlers}

Algebraic effect handlers, in the Koka and Eff tradition, permit a caller to install a handler for an effect that intercepts and provides semantics for the effect. Strand supports a restricted form of handlers as a graph construct, not as a full algebraic-effects calculus.

### Node shape

A `Handler` (N-043) is a graph node with three structural edges: `intercept` to an `EffectCategory` (N-021), `handle` to an expression of function type, and `body` to the expression whose evaluation the handler observes. Identity is fully structural — two Handlers with identical (intercept, handle, body) triples are the same node by content hash.

### Restricted form: no continuation

The handle expression is just a function. There is no `resume`, no captured continuation, no abort. When the body performs an effect whose category equals `intercept`, the handle function is invoked with the intercepted call's value arguments and its return value replaces the call's result. The body's evaluation continues from there. This covers the test-mocking and effect-redirection workloads without requiring CPS transformation. Multi-shot continuations, one-shot continuations, re-raise, and abort-only handlers are not part of this form; they are upward extensions that preserve the wire format and existing semantics.

### Handler signature

The handle expression's type is `(P1, ..., Pn) -> R ![Eh]`, where `P1..Pn` are the value-argument types of every intercepted Application within `body`, `R` is their common result type, and `Eh` is the handler's own declared effect set. The verifier enforces this signature agreement: every Application reachable in `body` whose function's `FunctionType.effects` contains `intercept` must have value-argument types structurally equal to `P` and result type structurally equal to `R`. Step 3a is uniform — handlers are monomorphic at the Handler node, and the verifier rejects a handle expression of `Forall` type.

### Closure algebra

The closure of a Handler is the only node-category closure rule that *removes* an effect from a graph's effective requirements:

```
closureOf(handler) = (closureOf(body) - {intercept}) ∪ closureOf(handle) ∪ <effects declared by the handle function>
```

The intercepted effect is consumed inside the Handler and no longer flows to the surrounding context. The handler's own static closure (typically empty — a handle expression is normally a Lambda or VarRef that exercises no effects at construction time) and the handler function's declared effects do flow to the surrounding context: a handler that writes to a sink requires the surrounding context to grant the sink's effect category. This is the property that distinguishes Handler from CapabilityScope (N-036): CapabilityScope narrows the runtime context but does not change the closure; Handler changes the closure.

### Runtime dispatch

Evaluation enters a Handler by computing the handle value once, in the surrounding handler stack. The body is then evaluated with a new handler frame appended to the stack. At every Application within the body, the runtime checks the handler stack: if any active handler intercepts an effect declared by the called function, the innermost such handler's value is invoked with the call's evaluated arguments in place of the original dispatch, and the capability check for the intercepted category is skipped at that site. The handler's own declared effects are still checked against the surrounding capability context when the handler runs. The innermost-wins rule lets nested Handlers shadow outer ones over the same category.

### Boundaries

The handler mechanism is not a substitute for capability mediation. A handler can intercept an effect but cannot synthesize the capability to perform it: the handler runs under the surrounding capability context and its own declared effects are checked there. A handler can mock an effectful operation away (returning a constant), can translate one effect into another (the handler performs its own effect in service of the body's), and can capture lexical state at Handler-entry to implement bounded-call patterns; it cannot let the body access a resource its surrounding context never granted.

## Effect inference {#effect-inference}

[Q-007](../open-questions.md#Q-007) asks how effects are determined for code that does not declare them explicitly. Strand's answer is conservative: native graph nodes always declare effects directly through effect edges; the language does not infer effects from node bodies. For foreign code, the answer depends on the source language and the available metadata.

**Strand-native graphs.** Effects are declared. The verifier checks declarations against the closure; declarations less than the closure are rejected as ill-formed.

**WebAssembly Component Model bindings.** WIT annotations specify imports and exports including effect-relevant operations; binding generation maps these to Strand effect categories where possible, refusing to generate a binding when the mapping is ambiguous.

**Erlang/Elixir bindings.** Erlang functions have specifications that include side-effect annotations; bindings derive effects from these specifications, with the binding author confirming or overriding before publication.

**C/C++/Rust/native bindings.** Source-level effect annotations are usually absent. Bindings require manual effect annotation by the binding author. Tools may propose annotations by static analysis (e.g., for Rust, the type system already separates `safe` from `unsafe`; for C, callgraph analysis identifies syscall use) but the proposals are advisory.

**Inference for unknown code.** When effects cannot be determined, the default policy is to refuse the binding; conservative "assume all effects" annotations are technically possible but yield unusable bindings (a function annotated with every effect category cannot be called from any reasonable capability context). The policy is to require effort at binding creation rather than to admit unusable bindings.

## Verification algorithm {#verification}

The effect verifier is part of the graph runtime. Its operation on a graph G is:

1. Compute the effect closure of G's roots: for each root node, accumulate the union of its declared effects and the closures of its referenced nodes.
2. Confirm that the execution context's capability set covers the closure: for every effect category in the closure with parameters, there must be a capability in the context whose specification is at-least-as-general.
3. Reject the graph if (1) or (2) fails. Provide a structural error indicating which node demands which effect and which capability is missing.

The verifier is incremental: adding a node updates the closure for the new node's ancestors and rechecks coverage; removing a node similarly. The cost is proportional to the size of the affected subgraph, not to the size of the whole graph.

At runtime, each node's effect declarations are confirmed at evaluation time. The runtime check is constant-time per effect (capability lookup in a hash structure). Failure to match a capability at runtime halts evaluation with a contract violation; the verifier should have prevented this case at admission time, so a runtime violation indicates either a verifier bug, an external change to capabilities (revocation), or a foreign call whose declared effects diverged from its actual behavior.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — capability execution discussion
- [`ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — effects as edges
- [`ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — foreign effect declarations
- [`node-algebra.md`](node-algebra.md) — EffectCategory and EffectDecl nodes
- [`encryption-model.md`](encryption-model.md) — encryption capabilities
- [`security-model.md`](security-model.md) — threat model
- [`state-machines.md`](state-machines.md) — StateMachine effect categories
- [`open-questions.md`](../open-questions.md) — Q-003, Q-004, Q-005, Q-007 resolved here

**Incoming references:**
- [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md)
- [`node-algebra.md`](node-algebra.md)
- [`state-machines.md`](state-machines.md)
- [`encryption-model.md`](encryption-model.md)
- [`security-model.md`](security-model.md)
- [`distribution-model.md`](distribution-model.md)
- [`research-plan.md`](../research-plan.md)
- [`rendering-and-views.md`](rendering-and-views.md) — output emission as existing effect categories
- [`proposals/implemented/effect-handlers.md`](../proposals/implemented/effect-handlers.md) — full algebra and implementation notes for the no-continuation Handler form
