# Rendering and Views {#rendering-and-views}

**Document:** `design/rendering-and-views.md`
**Status:** Wave 3 draft
**Last revised:** 2026-05-23

## Summary

This document specifies how Strand graphs produce outputs that humans and other systems consume — HTML pages, SVG diagrams, PDF reports, JSON API responses, audio frames, screen renderings, and similar artifacts. It establishes the layering between in-graph structured values, pure serialization to bytes, and effectful emission to the outside world. It specifies the schema mechanism introduced by [ADR-009](../decisions/ADR-009-structured-outputs.md) — node categories Schema (N-032) and Invariant (N-033), the verifier extension protocol, and the trust model for invariant checkers. It describes how interactive and live outputs compose with the state machine model from [ADR-007](../decisions/ADR-007-state-machines.md), how multiple output formats share upstream computation, and how provenance from rendered artifact back to source node hashes is preserved.

The design is intended to address two questions the prior Wave 3 corpus did not. First, what does an agent generating a user-facing artifact actually emit, and what guarantees does the language make about that artifact before the bytes leave the runtime. Second, where exactly does the boundary fall between Strand's structural reasoning and the rendering engines (browsers, PDF readers, terminals) that ultimately produce pixels.

Resolves [Q-025](../open-questions.md#Q-025) (schema mechanism scope), [Q-026](../open-questions.md#Q-026) (blessed library set), [Q-027](../open-questions.md#Q-027) (provenance encoding), and [Q-028](../open-questions.md#Q-028) (cross-library invariant composition) as proposed designs. Identifiers N-032 and N-033 are assigned below.

## Layering {#layering}

A rendering pipeline in Strand has four layers, every one of which is expressible in the existing algebra plus the two new node categories.

**Layer 1: Data.** A pure subgraph computes the data the output will display. This is ordinary Strand: functions over typed values, possibly with effects required to source the data (queries, file reads, sensor reads). The data subgraph is not specific to any output format.

**Layer 2: Structured value.** A pure subgraph transforms the data into a value of a schema-bearing type. For an HTML page, this is an `HtmlElement` tree; for an SVG diagram, an `SvgElement` tree; for a PDF, a `PdfDocument` value; for a JSON response, a `JsonValue`. The transformation is pure: no effects, no foreign calls, no platform-specific behavior. The value is content-addressed like every other Strand node.

**Layer 3: Serialization.** A pure function from the structured value to `Bytes`. Serialization is a library-supplied operation declared on each schema; for HTML the standard serialization is UTF-8 text following the HTML5 syntax; for PDF it is the PDF binary format; for JSON the textual JSON encoding. Serialization is always pure: the same value always produces the same bytes. Alternative serializations (pretty-printed vs. minified, different encodings) are different functions, each pure.

**Layer 4: Emission.** An effectful node consumes the bytes and emits them across an effect boundary. The effect category is one of the existing categories from [effects-and-capabilities.md](effects-and-capabilities.md): `Network.Send` for an HTTP response, `Filesystem.Write` for a file on disk, `Hardware.GPU` for direct screen rendering, `StateMachine.Send` for a stream output. No new effect category is introduced; emission is a use of the existing categories with the bytes as the payload.

The layering is strict: Layer 1 has no rendering-specific structure, Layer 2 has no effects, Layer 3 has neither effects nor foreign calls, Layer 4 has no construction of the structured value. This separation is mechanically enforced by the type system: a function returning a schema-typed value cannot perform effects, a serialization function takes the schema value and returns `Bytes` with no other parameters, an emission node consumes `Bytes` and is the only point at which a capability is required.

The strict separation has practical value. The same data subgraph can feed multiple structured-value transforms (HTML for the browser, JSON for the API client, PDF for the export). The same structured value can be serialized to multiple representations (compact for the wire, pretty for debugging). The same bytes can be emitted to multiple destinations (the browser response and the audit log). The pure layers are content-addressed and trivially cacheable; only the emission layer is non-deterministic.

## Schema mechanism {#schema-mechanism}

The schema mechanism is the central addition introduced by [ADR-009](../decisions/ADR-009-structured-outputs.md). It allows a library to declare a class of values whose structural validity the verifier checks at graph-construction time, in addition to the standard type and effect checks.

### Schema (N-032)

A `Schema` node declares a contract that a class of values must satisfy. Its edges:

| Edge | Multiplicity | Target | Description |
|------|-------------|--------|-------------|
| `schemaName` | 1 | Name (metadata) | Library-scoped identifier |
| `valueType` | 1 | Type | The structural type values must inhabit |
| `invariant` | * | Invariant | Predicates the verifier checks |
| `libraryBinding` | 1 | Provenance (metadata) | Origin and trust information |

A value is *of* a schema when it has the declared `valueType` and every `invariant` evaluates to true on it. Schemas are content-addressed; two schemas with identical structure and invariants are the same schema. Two schemas with the same name from different libraries are distinct nodes because their `libraryBinding` differs.

Schemas may extend or refine other schemas through composition: a schema's `valueType` may be a refinement of another schema's `valueType` (in the structural-subtyping sense), and its invariants may be in addition to or in place of the parent schema's. The reference distribution's HTML5 schema is layered: a base `Html5Document` schema covers structural well-formedness; a `Html5AccessibleAA` schema extends it with WCAG AA invariants; a `Html5StrictCSP` schema extends it further with content-security-policy invariants. Composition is by graph construction, not by inheritance: a value claimed to satisfy the accessible-AA schema must also satisfy the base schema, because the verifier checks both.

### Invariant (N-033)

An `Invariant` node declares a single verifier-checkable predicate. Its edges:

| Edge | Multiplicity | Target | Description |
|------|-------------|--------|-------------|
| `invariantName` | 1 | Name (metadata) | Library-scoped identifier |
| `targetSchema` | 1 | Schema | The schema this invariant applies to |
| `body` | 1 | Expression or ForeignNode | Pure expression or registered checker |

The invariant's `body` is either a pure Strand expression returning `Bool` over a single parameter of the schema's `valueType`, or a ForeignNode whose binding registers a checker with the verifier. Pure-expression invariants are verified using the standard type and effect checks: the expression is type-checked, declared effect-free, and evaluated by the verifier at graph-construction time over symbolic representations of the value's structure. ForeignNode invariants delegate to a library-supplied checker that the verifier invokes; the checker receives the value's canonical encoding and returns a verdict.

The expressiveness of pure-expression invariants is bounded by what the verifier can decide. Structural invariants over finite trees (every `img` has an `alt` edge, every list has only `li` children, no `paragraph` contains another `paragraph`) are decidable by recursive structural traversal. Invariants requiring arithmetic, unbounded quantification, or external knowledge (a list of WCAG-compliant color contrast pairs) are expressed as ForeignNode-backed checkers. The boundary between pure and foreign invariants is a library-design decision, not a language constraint.

### Verifier extension protocol

A library registers its schemas and invariants with the verifier through the standard library-loading mechanism. The registration includes (a) the Schema and Invariant nodes themselves (which are part of the loaded graph), (b) bindings for any ForeignNode-backed checkers, and (c) provenance metadata identifying the library and its version. When the verifier encounters a node claimed to be of a schema's value type with the schema's invariants asserted, it dispatches to the registered checkers in the order declared on the Schema. Failure of any checker rejects the graph.

A graph that uses a schema must reference the Schema node by hash, so the verifier can resolve the invariant set and the checker bindings. The same schema name from a different library is a different Schema node and a different set of checkers; the verifier never confuses them. This is the same property that makes ForeignNode safe: identity is by hash, not by name, so substitution requires explicit consent.

### Cross-library composition {#cross-library-composition}

Two schemas from different libraries may apply to the same value. The HTML5 schema and a custom schema declaring that a page must include an organization-specific footer can both be claimed on the same `HtmlElement` value. The verifier checks all invariants from all claimed schemas. Conflict (two invariants whose conjunction is unsatisfiable) is not detected by the verifier as such; an unsatisfiable conjunction simply rejects every concrete value, which is observed at graph-construction time when the agent fails to construct a value that passes both. This places the burden of compatible-schema selection on the agent or on a higher-level library curator.

The composition is the subject of [Q-028](../open-questions.md#Q-028). The current design defers conflict detection to the agent's construction loop, on the principle that the verifier should be a sound checker rather than an automated conflict resolver. Future work may add diagnostic tooling that suggests which invariants are in tension when construction repeatedly fails.

## Output as effect-edge terminus {#output-as-terminus}

The emission of bytes is the only effectful step in a rendering pipeline. The effect category is determined by the destination; no new effect categories are introduced for rendering specifically.

| Destination | Effect category | Parameters |
|-------------|----------------|-----------|
| HTTP response body | `Network.Send` | connection, bytes |
| File on disk | `Filesystem.Write` | path, bytes |
| Screen / display | `Hardware.GPU` | device, framebuffer bytes |
| Sound output | `Hardware.Sensor` (reused for output) | device class, sample bytes |
| Event stream output | `StateMachine.Send` | streamId, event containing bytes |
| Sealed storage | `Trust.SealedStorage` | sealed path, bytes |

The unification of output destinations under existing effect categories has two consequences. First, the capability model already in place for those effects governs output: a graph that emits HTML over a network connection requires the same capabilities as any other network send; a graph that writes a PDF to disk requires the same as any other file write. No new capability discipline is required for "rendering." Second, the trust boundary for an output destination is the same as for any other effectful interaction with that destination: the receiver (the browser, the file system, the audio device) is responsible for interpreting the bytes safely, and the language's guarantees end at the emission boundary.

For destinations whose receivers benefit from structural validation (a browser receiving HTML, a PDF reader receiving PDF), the schema mechanism provides validation before the bytes are emitted, so the receiver gets a value the verifier has already checked. For destinations whose receivers do not benefit from validation (a raw audio stream, an opaque binary blob), the schema mechanism is unused and the pipeline is data → serialization → emission.

## Blessed library set {#blessed-libraries}

The reference distribution includes a small set of schemas covering common structured output formats. The selection is deliberately bounded; expansion is by community contribution under the standard library-loading mechanism.

**HTML5.** Schema `Html5Document` with invariants covering structural well-formedness (elements may contain only their permitted children, attributes may appear only on permitted elements, void elements may not have closing tags, document has the required head/body structure). Schema `Html5AccessibleAA` extends the base with WCAG 2.1 AA invariants (every `img` has alt text, every form control has an associated label, every `iframe` has a title, color contrast meets thresholds for declared color pairs). Schema `Html5StrictCSP` extends further with content-security-policy invariants (inline scripts and styles are forbidden, only declared external sources are permitted).

**SVG.** Schema `SvgDocument` with invariants covering structural well-formedness and the SVG 2.0 element/attribute taxonomy. Includes invariants ensuring viewBox is well-formed and that text elements have appropriate accessibility metadata when used as primary content.

**JSON.** Schema `JsonValue` with invariants covering structural well-formedness (no NaN/Infinity at the JSON layer, no duplicate keys in objects, valid UTF-8 string content). Application-specific JSON shapes are derived by composing this schema with custom invariants declaring required fields, value ranges, and inter-field constraints.

**PDF.** Schema `PdfDocument` with invariants covering structural well-formedness of the PDF object graph and tagged-content invariants for accessibility (logical reading order is preserved, structural elements are tagged, embedded images have alt text). The reference serializer targets PDF/A-2u for archival compliance.

**Plain text.** Schema `PlainTextDocument` with invariants covering UTF-8 validity and line-ending consistency. This is a baseline schema useful when output must be safely interpretable across systems with different conventions.

**Markdown.** Schema `MarkdownDocument` with invariants covering CommonMark structural validity. Useful for outputs intended to be rendered by downstream Markdown processors with predictable structure.

Libraries beyond this initial set — DOCX, XLSX, vector formats for specific tooling, application protocols — are out of scope for the reference distribution but are first-class additions through the schema mechanism. The criterion for inclusion in the blessed set is (a) widespread relevance to the kinds of outputs agents produce, (b) the existence of well-defined structural invariants the verifier can check, and (c) the existence of a maintained reference implementation that survives library audit. These criteria are part of the curation policy, not the language design; the boundary will move as the ecosystem develops.

## Live and interactive views {#live-views}

Outputs whose content changes over time compose naturally with the state machine model from [ADR-007](../decisions/ADR-007-state-machines.md) and [state-machines.md](state-machines.md).

A *live view* is a state machine whose output stream carries serialized renderings of a schema-bearing value. On each transition, the new state implies a new structured value; the machine emits its serialization to the output stream; subscribers apply the rendering. The verifier's invariant guarantees hold across every state the machine can reach, because the schema is checked on each value produced by the transition function.

| Component | Role |
|-----------|------|
| State | Contains or implies the current rendered value |
| Transition function | `(State, Event) → (State, [Event])`; emitted events carry serialized renderings |
| Input stream | External events driving the view (user interactions, data updates) |
| Output stream | Serialized renderings; subscriber is the rendering host (browser, terminal, display) |
| Foreign event source | WebSocket, SSE, native UI event loop, or sensor input |

The interactive case adds an input stream from a UI host that translates user actions into events the machine consumes. A click event from a browser becomes an `Event` of type `ClickEvent` with the target element identifier (preserved through the rendering's provenance mapping; see below) and any payload data. The transition function maps the event to a new state, and the cycle repeats. The browser is a foreign event source connected via WebSocket or SSE; the connection is established by an effectful node holding `Network.Listen` or equivalent.

Differential rendering — emitting only the parts of the structured value that changed between transitions — is an optimization library libraries may implement on top of the basic emission. The differential is computed from the content hashes of the two structured values; subtrees with unchanged hashes are not re-serialized or re-emitted. This optimization is possible only because the schema-bearing values are content-addressed.

Server-rendered, client-interactive applications combine the patterns. The initial rendering is produced by a Strand graph on a server, emitted as HTML, and delivered to the browser. The browser establishes a WebSocket; subsequent events flow through a state machine that produces differential renderings the browser applies to the existing DOM. The whole thing is one Strand graph; the boundary between server and client is a placement decision made by the distribution scheduler ([distribution-model.md](distribution-model.md)) according to which effects each subgraph requires.

## Multi-format rendering {#multi-format}

A single data subgraph may feed multiple structured-value transforms, each producing a different output format from the shared upstream data. The pattern is unremarkable in implementation — the data subgraph's content hash is the same for every consumer, so the consumers are downstream of a single computation — but it has consequences worth stating.

The classic use case is a report or document available in multiple formats: HTML for the web, PDF for printing, JSON for programmatic access, RSS for syndication. In conventional implementations, these are often produced by independent code paths that diverge in subtle ways (the HTML and PDF report different totals because their aggregation logic drifted apart). In Strand, the data subgraph is by content-addressing necessarily shared: any difference between the formats is necessarily a difference in the per-format transform, not in the source data. The verifier can reject pipelines where two formats claim to render the same data but reach different conclusions, by structural comparison.

A related use case is canonical and view-specific renderings. The canonical form of a document might be a PDF/A-2u for archival; the view-specific form might be HTML with progressive enhancement for the browser. Both render the same data subgraph; both apply the same per-document invariants (the totals match, the sections are in order, the signatures verify). Format-specific invariants apply on top: the PDF's tagged structure is verified for screen reader compatibility; the HTML's CSP compliance is verified for the browser.

The multi-format pattern is the strongest argument for the layering specified above. The strict separation of data, structured value, serialization, and emission is what makes "same data, multiple formats" mechanical rather than error-prone.

## Provenance from output to source {#provenance}

Because every node in a structured-value tree is content-addressed ([ADR-003](../decisions/ADR-003-content-addressing.md)) and serialization is a pure function, each position in the serialized output corresponds to a specific subtree of the structured value, which in turn corresponds to a specific subgraph of the data layer that produced it. This provenance is recoverable in principle; the design specifies an encoding that makes it recoverable in practice.

### Provenance encoding

A serializer may emit a *provenance manifest* alongside the serialized bytes. The manifest is a structure mapping byte ranges in the output to the hashes of the structured-value nodes that produced them. For HTML, the manifest maps element ranges to `HtmlElement` node hashes; for PDF, the manifest maps content-stream operators to the `PdfPage` and `PdfTextRun` hashes; for JSON, the manifest maps key paths to `JsonValue` node hashes. The manifest is itself a content-addressed value, produced alongside the serialization.

The manifest is opt-in. A serializer used in a context where provenance is not required produces only the bytes. A serializer used for debugging, audit, or differential rendering produces both. The cost of the manifest is small (a small fraction of the serialized size for typical structured values); the benefit is the ability to answer "which node produced this output position" without re-serializing or running additional analysis.

The encoding format is the subject of [Q-027](../open-questions.md#Q-027). The current design adopts a structural manifest in a uniform format (a tree of byte ranges paired with node hashes), with format-specific extensions where helpful (e.g., source-map-compatible output for HTML and SVG, so existing browser tooling can consume the provenance directly). The detailed format is part of the reference implementation work and is not fixed by this design.

### Use cases for provenance

Provenance enables several capabilities that conventional rendering pipelines lack.

**Debugging.** "Why does this rendered cell show 42?" is answered by reading the manifest to identify the structured-value node responsible, then walking that node's source data via standard graph navigation. The walk terminates at literals or at effectful nodes whose value was sourced from outside the graph. The whole chain is recoverable from the artifact alone, provided the manifest accompanies it.

**Audit.** A regulator examining a generated report can verify that the report's conclusions were derived from the data by reproducing the data subgraph (which is content-addressed) and re-running it. The provenance manifest identifies which input data positions contributed to each output position; the verifier confirms the derivation.

**Differential rendering.** When two related structured values are rendered (one before a transition, one after), the manifests identify which byte ranges in the output changed because the structured value's subtree hash changed. The receiver applies only the changed ranges. This is the same principle as virtual-DOM diffing in conventional UI frameworks but uses content-addressed equality directly rather than reconstructed structural comparison.

**Targeted updates and event routing.** A user click on a specific element in a rendered HTML page produces an event identifying the element by its position in the rendering. The manifest maps the position back to the source node; the state machine receives an event whose payload includes the source node hash. The transition function dispatches on hash, not on rendered position, so the same logic applies regardless of how the rendering changes shape.

## Effect closure of rendering pipelines {#effect-closure}

The effect closure of a complete rendering pipeline is the union of the effects required to source the data, the effects required to serialize (typically none), and the effects required to emit. The pure construction and serialization layers contribute nothing to the closure; the effect surface is entirely in Layer 1 (data sourcing) and Layer 4 (emission).

This has implications for capability minimization. A pipeline that fetches user data, renders it as HTML, and emits the HTML to an HTTP response requires capabilities for the data fetch (database or API connection), no capabilities for the rendering, and capabilities for the network send. A graph that performs the same rendering but emits the result to a file requires the data-fetch capabilities and a `Filesystem.Write` capability. A graph that renders without emitting (a preview, a hash, a snapshot for differential computation) requires only the data-fetch capabilities; the rendering itself is unconstrained.

Server-side rendering composed with client-side interactivity has a specific effect structure worth highlighting. The server's rendering pipeline requires the data-fetch capabilities and the network-send capability to deliver the initial HTML. The client's interactive subgraph, placed by the distribution scheduler on the user's device, requires the WebSocket capability for the back-channel and any capabilities the interactive logic needs (typically minimal: the rendering and event handling are pure once the data is in hand). The capability minimization that falls out of this structure is significant: an XSS-style attack on the rendered page cannot escalate to data-fetch capabilities because the rendered page's subgraph does not hold them.

## Trust model for invariant checkers {#trust-model}

Invariant checkers are trusted components: a checker that wrongly accepts an invalid value breaks the verifier's guarantee for every graph that uses the schema. The trust model parallels the trust model for ForeignNode bindings specified in [security-model.md](security-model.md).

Three mechanisms apply.

**Signed provenance.** Every Schema and every Invariant carries `libraryBinding` provenance metadata. Deployments may require that checkers be signed by trusted issuers; unsigned or untrusted checkers are refused. The signature covers the checker's binding (for ForeignNode checkers, the binding's identity and effect declarations; for pure-expression checkers, the expression itself).

**Reproducible checkers.** A pure-expression checker is reproducible by definition: the expression is in the graph, its evaluation is deterministic, and any party can re-run it on the same input to confirm the verifier's verdict. ForeignNode-based checkers may be reproducible if the foreign code is reproducibly built and its source is available; the same reproducibility infrastructure used for ForeignNode bindings applies here.

**Sandboxed checker execution.** ForeignNode-based checkers run in the same sandboxed contexts as other ForeignNode invocations. A checker that attempts an effect outside its declared set (a checker that should be pure but tries to read the network) is halted by the runtime. The platform-dependent enforcement (WebAssembly, seccomp, TEE) applies to checkers as to any other foreign call.

The deployment policy combines these mechanisms according to the sensitivity of the schemas in use. A reference deployment for low-sensitivity work might require only pure-expression invariants and unsigned ForeignNode checkers; a high-sensitivity deployment for regulated outputs might require signed checkers, reproducible builds, and sandboxed execution on attested platforms.

The trust model does not eliminate the possibility of a malicious checker; it raises the cost and shrinks the population of plausible attackers. The same observation applies as for ForeignNode: the language can make trust visible and policy-enforceable; it cannot make trust automatic.

## Interaction with encrypted nodes {#interaction-with-encryption}

A schema-bearing value may be encrypted under the per-node encryption mechanism specified in [ADR-006](../decisions/ADR-006-per-node-encryption.md) and [encryption-model.md](encryption-model.md). The verifier's invariant check requires access to the plaintext structure; this implies that the verifier executes in a capability context that holds the necessary decryption keys.

Three patterns emerge.

**Validated-then-encrypted.** The value is constructed and verified in plaintext, then encrypted for distribution or storage. Downstream consumers decrypt and use the value without re-verifying; the value's hash, which is preserved across encryption, attests that it was verified in a previous context.

**Encrypted-with-public-interface.** The schema's invariants are checkable from the encrypted node's public interface (its declared type and any non-encrypted metadata). The verifier can check structural invariants without decrypting. This pattern is restricted: most useful invariants require seeing the content.

**Decrypted-for-verification-then-discarded.** The verifier decrypts the value temporarily to check invariants, then operates on the encrypted form. This requires that the verifier hold appropriate keys, which itself requires that the verification context be granted the decrypt capability. This pattern is appropriate for re-verifying received values when the receiver is also authorized to read them.

The choice between patterns depends on the deployment's trust model: which parties are allowed to verify, which are allowed to read, and whether the assertion of verification (carried implicitly by the value's hash) is trustable to downstream parties.

## Implementation milestones {#implementation}

The schema mechanism and the reference output libraries fit into the existing research-plan structure ([research-plan.md](../research-plan.md)). Three milestones are added.

**Milestone 2.6 (Phase 2): Schema mechanism in the verifier.** Implement Schema and Invariant node categories in the reference verifier, including pure-expression invariant evaluation and the ForeignNode checker dispatch protocol. Validate against a small set of synthetic schemas. Estimated effort: comparable to the effect-closure implementation already planned for Milestone 2.1.

**Milestone 2.7 (Phase 2): Reference output libraries.** Implement the six blessed schemas (HTML5, SVG, JSON, PDF, plain text, Markdown) and their reference serializers. Each library includes structural invariants in pure-expression form and any accessibility or compliance invariants as ForeignNode-backed checkers. Includes integration tests verifying that the schemas reject malformed values and accept valid ones.

**Milestone 2.8 (Phase 2): Live-view runtime.** Implement the WebSocket and SSE foreign event sources, the differential rendering optimization, and an end-to-end live-view example (a server-rendered, client-interactive demo application). Validates that the schema mechanism, state machine model, and distribution model compose as designed.

These milestones precede Phase 3 evaluation, since the evaluation task suite includes UI generation tasks that depend on the rendering pipeline.

## Future directions {#future-directions}

Several extensions are recognized as plausible but not pursued in the current design.

**Schemas for non-output values.** The schema mechanism is general; it applies to any value whose validity is structurally describable. Configuration data, message protocols, state-machine event types, query results, and other internal structures could benefit from schema-typed validation. Adopting the mechanism beyond output formats is straightforward but is not specified in this document.

**Refinement types as a generalization.** The schema mechanism is a constrained subset of refinement typing. A future research direction is the generalization to full refinement types with SMT-backed decision procedures, subsuming the schema mechanism while expanding the class of decidable invariants. This is recognized in [ADR-009](../decisions/ADR-009-structured-outputs.md) and is not part of the current design.

**Schema inference for legacy data.** Tools that take existing data corpora (JSON dumps, HTML pages from the web) and infer schemas that the verifier can then enforce on new productions are a plausible direction for tooling. The inference is heuristic; the resulting schemas are starting points that authors refine.

**Cross-format invariant propagation.** When the same data subgraph feeds multiple structured-value transforms, some invariants may apply across all formats (the total at the bottom of the report matches a sum). A future extension might allow such invariants to be declared once and checked across all derived renderings. The current design requires them to be declared on the data subgraph rather than on the per-format schemas.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — outputs as the boundary to non-Strand worlds
- [`ADR-002-no-human-projection.md`](../decisions/ADR-002-no-human-projection.md) — distinction between program and output
- [`ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — provenance and differential rendering depend on this
- [`ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — emission as existing effect categories
- [`ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — foreign checkers and rendering engines
- [`ADR-006-per-node-encryption.md`](../decisions/ADR-006-per-node-encryption.md) — interaction with encrypted values
- [`ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md) — live views as state machine outputs
- [`ADR-009-structured-outputs.md`](../decisions/ADR-009-structured-outputs.md) — architectural basis for this document
- [`node-algebra.md`](node-algebra.md) — N-032 and N-033 extend this algebra
- [`effects-and-capabilities.md`](effects-and-capabilities.md) — effect categories used for emission
- [`state-machines.md`](state-machines.md) — live view composition
- [`security-model.md`](security-model.md) — trust model for checkers
- [`distribution-model.md`](distribution-model.md) — placement of server vs. client rendering
- [`encryption-model.md`](encryption-model.md) — encrypted-value verification
- [`research-plan.md`](../research-plan.md) — Milestones 2.6, 2.7, 2.8
- [`open-questions.md`](../open-questions.md) — Q-025, Q-026, Q-027, Q-028

**Incoming references:**
- [`decisions/ADR-009-structured-outputs.md`](../decisions/ADR-009-structured-outputs.md) — defers detailed specification to this document
