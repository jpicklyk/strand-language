# Q-026 blessed libraries: PlainTextDocument + MarkdownDocument

**Document:** `proposals/implemented/plaintext-and-markdown-libraries.md`
**Status:** Implemented 2026-05-24
**Concerns:** [`design/rendering-and-views.md`](../../design/rendering-and-views.md) § Blessed library set, [Q-026](../../open-questions.md#Q-026), [`proposals/implemented/json-blessed-library.md`](json-blessed-library.md), [`proposals/implemented/schema-and-invariant.md`](schema-and-invariant.md)
**Scope:** Two blessed libraries with three corpus programs each — small extension to the existing schema mechanism, no new node categories or verifier rules.

> **Implementation note (2026-05-24).** Two new blessed libraries shipped via six corpus programs, all using the existing Layer 7 step 1 Schema + Invariant mechanism with no new node category, verifier rule, or runtime change. Each library defines its types + schemas inline in the corpus program (the JSON library precedent — there is no separate stdlib mechanism in Strand today; "blessed" means convention codified in the seed corpus). **PlainTextDocument** is the simplest possible blessed schema: a `PlainTextDocument` Schema with `valueType = String` and zero invariants — establishes the naming pattern. **NonEmptyText** layers a `non_empty` invariant on top, expressed as `λ s. Bool.Not(String.Eq(s, ""))` — exercises the `Bool.Not` and `String.Eq` builtins together to encode "string is not the empty string" at the invariant level. **MarkdownDocument** is the structural showcase: a `MarkdownBlock` flat sum over `Heading {level: Int, text: String} | Paragraph {text: String} | CodeBlock {language: String, code: String} | HorizontalRule` (nullary), wrapped by the recursive-list pattern `μ. Cons{head: MarkdownBlock, tail: <self>} | Nil` that the JSON library and corpus 52 already use for non-empty-list. The `NonEmptyMarkdown` variant adds a `Match(Cons → true, Nil → false)` invariant — the same Match-over-pattern shape NonEmptyList uses for Ints, extended to the heterogeneous block sum. Six corpus programs land: 58 (PlainTextDocument), 59 (NonEmptyText pass), 60 (NonEmptyText fail — empty string rejected), 61 (MarkdownDocument with two blocks), 62 (NonEmptyMarkdown pass), 63 (NonEmptyMarkdown fail — empty document rejected). All 13 schema corpus programs (50–56, 58–63) pass `CorpusSchemaTest` and `VmSchemaEquivalenceTest` (interpreter vs VM invariant dispatch agree on every violation + deferred diagnostic). Four of the six new programs ship in Layer A form (58, 59, 61, 62) with hash-equality round-trip through `LayerARoundTripTest`; 60 and 63 are excluded for the same reason 53 and 56 are — the test asserts verifier-pass for both forms, which is true here, but the SchemaChecker stage rejects both equivalently. **Deferred from Q-026's six-library scope:** HTML5 (the largest by far — three layered variants `Html5Document` / `Html5AccessibleAA` / `Html5StrictCSP`, content model invariants for valid HTML5, plus the accessibility + CSP rules), SVG (`SvgDocument`), PDF (`PdfDocument` targeting PDF/A-2u — binary format, separate engineering pass). HTML5 and SVG share the same blocker as the JSON `JsonArray/JsonObject` cases noted in `json-blessed-library.md`: their element tree is nested-μ-recursive (a List of Element inside a recursive Element type), and Strand's `RecursiveSelf` always resolves to the innermost μ binder. Working around this needs either (a) a richer recursive-binder protocol that lets an inner Recursive reference an outer Recursive, or (b) a structural rewrite (e.g., element trees encoded as a linear flat-list with parent indices). Both are separate design slices.

## 1. Problem statement

[Q-026](../../open-questions.md#Q-026) lists six blessed libraries the reference distribution should ship to make common structured-output formats trivially-typeable without per-project schema authoring: HTML5, SVG, JSON, PDF, plain text, Markdown. JSON shipped in Layer 7 step 1.5 (`proposals/implemented/json-blessed-library.md`) as the first. The remaining five are individually shipping steps.

PlainTextDocument and MarkdownDocument are the next two by tractability: both fit the existing Schema + Invariant mechanism without needing nested-μ recursion (HTML5 and SVG do), and both have a meaningful invariant story (NonEmpty variants) that exercises real invariant evaluation, not just type wrapping.

## 2. Library design

### 2.1 PlainTextDocument

The simplest blessed schema: a `String` wrapped in a named Schema. No invariants — the underlying `String` primitive type carries all the structural constraints (Strand strings are valid UTF-16 by host-language guarantee).

**Schema declaration:**
```
PlainTextDocument: Schema { valueType: String, invariants: [] }
```

**Usage pattern:** any place a Strand program wants to declare "this value is a plain-text document" rather than just `String`. The Schema name is the contract; the underlying type is `String`.

### 2.2 NonEmptyText

A `PlainTextDocument` variant with one invariant: the string is not empty.

**Invariant body:**
```
non_empty: λ s. Bool.Not(String.Eq(s, ""))
```

This uses two existing builtins (`Bool.Not`, `String.Eq`) and a string literal for the comparison. The body evaluates to `true` iff the string differs from `""`.

**Schema declaration:**
```
NonEmptyText: Schema { valueType: String, invariants: [non_empty] }
```

### 2.3 MarkdownBlock

A flat sum over the four most common block-level Markdown constructs:

```
MarkdownBlock = Heading        {level: Int, text: String}
              | Paragraph      {text: String}
              | CodeBlock      {language: String, code: String}
              | HorizontalRule
```

`Heading.level` is an integer (the Markdown level 1–6, but no invariant enforces the range in this slice — see § 5). `HorizontalRule` is nullary (no payload).

This is a flat sum — each case has a non-recursive payload — so it can be expressed directly as a `SumType` over `ProductType` cases.

### 2.4 MarkdownDocument

A `MarkdownDocument` is a sequence of `MarkdownBlock` values. Strand has no built-in list type; the recursive-list pattern from `NonEmptyList` (corpus 52) and `JsonObject` (corpus 55) is reused:

```
MarkdownDocument = μ. Cons {head: MarkdownBlock, tail: <self>} | Nil
```

The recursion is **on the document type itself**, not nested — the `head` is `MarkdownBlock` (non-recursive), the `tail` is `<self>` (the same `MarkdownDocument` μ-binder). This matches the JSON library's `JsonObject` shape and avoids the nested-μ blocker that prevents richer HTML/SVG models.

**Schema declaration:**
```
MarkdownDocument: Schema { valueType: <the μ-list above>, invariants: [] }
```

### 2.5 NonEmptyMarkdown

A `MarkdownDocument` variant with one invariant: the document has at least one block.

**Invariant body:**
```
non_empty: λ d. match d with
  | Cons(_) → true
  | Nil     → false
```

The same shape `NonEmptyList`'s invariant uses, with the scrutinee type being the recursive `MarkdownDocument` rather than `NonEmptyList`. The `_` wildcard payload-pattern matches any `Cons` payload without binding it; only the case distinction (Cons vs Nil) drives the verdict.

**Schema declaration:**
```
NonEmptyMarkdown: Schema { valueType: <the μ-list above>, invariants: [non_empty] }
```

## 3. Corpus programs

Six new corpus programs land alongside this slice (numbering continues from the previous corpus highs):

| # | Program | Schema | Value | Expected |
|---|---------|--------|-------|----------|
| 58 | `plain-text-document.json` | PlainTextDocument | `"Hello, Strand."` | pass |
| 59 | `non-empty-text-pass.json` | NonEmptyText | `"Hello, Strand."` | pass |
| 60 | `non-empty-text-fail.json` | NonEmptyText | `""` | SchemaInvariantViolation |
| 61 | `markdown-document.json` | MarkdownDocument | `[Heading(2, "Strand"), Paragraph("Lightweight markup")]` | pass |
| 62 | `non-empty-markdown-pass.json` | NonEmptyMarkdown | `[Heading(2, "Strand")]` | pass |
| 63 | `non-empty-markdown-fail.json` | NonEmptyMarkdown | `[]` (Nil) | SchemaInvariantViolation |

Each program is self-contained — types and schemas are declared inline (the JSON library pattern) rather than imported from a shared stdlib. Strand has no import mechanism today; "blessed" means convention codified in the seed corpus.

## 4. Tests

The new programs are wired into three existing test classes:

- **`CorpusSchemaTest`** asserts each program produces the expected SchemaCheckResult: pass programs have no violations, fail programs have a precise `SchemaInvariantViolation` pointing at the right invariant author-id. All 13 schema corpus programs (50–56, 58–63) pass.
- **`VmSchemaEquivalenceTest`** runs every schema program through both the interpreter-backed and VM-backed `invariantEvaluator` and asserts the SchemaCheckResults match (same violations, same deferred diagnostics). All 13 pass.
- **`LayerARoundTripTest`** adds 58, 59, 61, 62 to the hash-equality round-trip set. 60 and 63 are excluded for the same reason 53 and 56 are — the test asserts verifier-pass for both canonical and Layer A forms, which is true here, but the SchemaChecker stage rejects both equivalently downstream.

The new programs all translate cleanly through the `strand translate` JSON→Layer A pipeline; round-trip hash-equality holds for the four pass programs and ingest+verify-equivalence holds for the two fail programs.

## 5. Deferred from Q-026

Five of the six blessed libraries the proposal names are still deferred:

- **HTML5** (`Html5Document`, `Html5AccessibleAA`, `Html5StrictCSP`) — the biggest. HTML5 element trees are nested-μ-recursive: a `List<HtmlElement>` (recursive on the list type) appears inside a `HtmlElement` definition (recursive on the element type). Strand's `RecursiveSelf` always resolves to the innermost μ binder, so the inner list can't reference the outer element type without either a richer recursive-binder protocol or a structural rewrite. Same blocker that prevents `JsonValue` from including `JsonArray(List<JsonValue>)` per the `json-blessed-library.md` deviation. HTML5 also carries substantial content-model invariants (void elements with no children, head/body structure, etc.), accessibility rules for the AA variant, and CSP rules for the strict variant — each a sub-slice.
- **SVG** (`SvgDocument`) — same nested-μ blocker as HTML5.
- **PDF** (`PdfDocument` targeting PDF/A-2u) — binary format. Separate engineering pass; the Schema mechanism is the wrong abstraction (would need byte-level invariants, not value-level).

Five Markdown invariants that would be useful but didn't ship this slice:
- `heading_levels_in_range_1_6`: walks the document list, asserts every `Heading.level` is between 1 and 6. Requires a Fixpoint-based walker like JSON's `unique_keys` — doable but ~70 lines of inline graph per usage.
- `code_blocks_have_known_language`: walks the document, asserts every `CodeBlock.language` is in a known set.
- `paragraph_lines_under_80`: per-block character-count check on Paragraph.text.

All three are extensions of the same Fixpoint-over-document pattern. Adding them is a corpus-level expansion that doesn't need new infrastructure.

## 6. References

**Outgoing references:**
- [`design/rendering-and-views.md`](../../design/rendering-and-views.md) § Blessed library set — names Q-026's six libraries
- [`proposals/implemented/schema-and-invariant.md`](schema-and-invariant.md) — the underlying Schema + Invariant mechanism
- [`proposals/implemented/json-blessed-library.md`](json-blessed-library.md) — the first blessed library; this proposal follows the same pattern

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-026 lists this as part of the six-library set
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md)
