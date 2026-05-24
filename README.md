# Strand

**A graph-native programming language designed for generation by AI agents rather than authorship by humans.**

Strand is a research project exploring whether a programming language built around the operational characteristics of large language models — content-addressed graph nodes, mandatory effect declarations, capability-based execution — can produce more correct, more analyzable, and more easily distributed programs than text-based languages adapted for AI use.

## Status

Strand is in **design phase**. No implementation exists. This repository contains the design specifications, architectural decisions, and research plan. The documents are intended to be sufficient to begin implementation and to support empirical evaluation of the central thesis.

## Reading order

Readers new to the project should read documents in this order:

1. [`00-motivation.md`](00-motivation.md) — why this project exists
2. [`02-core-thesis.md`](02-core-thesis.md) — the central design claims
3. [`01-prior-art.md`](01-prior-art.md) — how Strand relates to existing work
4. [`design/node-algebra.md`](design/node-algebra.md) — the foundational structures (Wave 3, forthcoming)
5. Remaining design documents as needed

Readers picking up the project after time away should start with [`INDEX.md`](INDEX.md) for navigation and [`open-questions.md`](open-questions.md) for current unresolved work.

## Document organization

The corpus is organized into four tiers, from most general to most specific:

| Tier | Location | Purpose |
|------|----------|---------|
| Overview | Top-level numbered documents (`00-*`, `01-*`, `02-*`) | Motivation, prior art, core thesis |
| Decisions | `decisions/ADR-*.md` | Atomic architectural decisions with rationale |
| Design | `design/*.md` | Detailed specifications of major components |
| Meta | `INDEX.md`, `open-questions.md`, `research-plan.md` | Navigation and research state |

## Navigation conventions

The corpus uses several conventions to remain navigable as it grows:

- **Stable identifiers.** ADRs, node types, effect categories, and other recurring concepts have stable identifiers (e.g., `ADR-001`, `N-001`, `E-001`) that are referenced from other documents. Identifiers do not change once assigned.
- **Section anchors.** Major sections within documents have explicit anchor IDs (e.g., `{#effects-capabilities}`) for stable cross-document linking.
- **Reference sections.** Each document ends with a "References" section listing outgoing citations and incoming citations from other documents.
- **Master index.** [`INDEX.md`](INDEX.md) provides three views of the corpus: an alphabetical concept index, the document tree, and the identifier registry.
- **Open questions are separate.** Specification documents describe the current design without inline caveats. Unresolved issues live in [`open-questions.md`](open-questions.md), which references specific documents and sections by identifier.

## Voice and authority

Documents are written in neutral specification voice ("Strand uses content-addressed nodes because...") rather than narrative or exploratory voice. This reflects the current state of the design, not the process by which it was reached. The design is provisional in the sense that no implementation has tested it; document-level revisions are expected as implementation surfaces issues. Major revisions are recorded in each document's history section.

## License and contribution

To be determined. This document will be updated when the project moves out of design phase.

## References

This document is the entry point and is referenced by all others. No outgoing references.
