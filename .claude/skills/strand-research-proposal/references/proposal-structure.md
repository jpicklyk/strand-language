# Standard proposal-document structure

Use this template for any new `proposals/<topic>.md` file. The structure is what the three existing proposals follow — refinement-lattice matching, effect handlers, state machines runtime.

## Template

```markdown
# <Topic Title>

**Document:** `proposals/<topic>.md`
**Status:** Draft proposal
**Date:** <YYYY-MM-DD>
**Concerns:** <bullet list of related design docs and Q-NNN identifiers>
**Scope:** <small / small-medium / medium / medium-large / large>

<Brief framing paragraph: what slice of the implementation this proposal covers, and how it relates to the layered roadmap if appropriate.>

## 1. Problem statement

<One to three paragraphs describing what's missing, in concrete terms. Cite specific sections of relevant design docs. Make clear what the current state is and what gap the proposal closes.>

## 2. Prior art (optional but often valuable)

<3–5 bullets on relevant approaches in other languages or in the research literature. Verify the claims — don't invent prior art. Useful for showing the recommendation isn't ad hoc.>

- **<Language/system 1>** — <one-sentence summary of how they handled the analogous problem and what tradeoff they chose>
- **<Approach 2>** — <similar>
- **<Approach 3>** — <similar>

## 3. Recommended approach

<Be opinionated. Pick ONE design and commit. Cite alternatives in §8 (tradeoffs) rather than hedging here.>

<For proposals that span multiple sub-decisions, a "Decisions to make" table is sometimes useful instead of prose. See `proposals/refinement-lattice-capability-matching.md` for an example.>

## 4. Detailed mechanism

<Walk through the concrete design. Topics to cover, picking what's relevant:>

### 4.1 Node category (if introducing one)
- Identifier (next free N-NNN)
- Edges and content fields
- Why this shape and not another

### 4.2 Canonical encoding (if relevant)
- Category tag value
- Field-by-field encoding
- Any subtleties (alpha-equivalence, sort order, optional payload handling)

### 4.3 Worked example
- Walk through a concrete instance: what bytes get emitted, what hashes get computed

## 5. Verifier rules

<For each new well-formedness rule, name the rule, give the condition, and name the new VerifyError variant.>

## 6. Interpreter / runtime semantics

<For each new evaluation behavior, give pseudocode or a precise description. Cover both happy paths and error paths.>

## 7. Test scenarios

<Enumerate 5–10 concrete test cases. Mix happy paths and error paths. These directly become unit tests when implemented.>

1. **<Scenario name>** — <one-sentence description of what's tested and the expected outcome>
2. **<Scenario name>** — <similar>
...

## 8. Tradeoffs and open questions

<What's intentionally deferred, and why. What alternatives were considered. Any genuinely unresolved sub-questions that the implementer should be aware of.>

**Deferred intentionally:**

- **<Feature>** — <reason for deferring; what would unblock taking it up>
- **<Feature>** — <similar>

**Real research questions:**

- *<Question>* — <description of why it's open; any framing that might help future research>

## 9. Implementation sketch

<File-by-file table of changes. Use Small / Medium / Large for scope labels per file.>

| File | Change | Size |
|------|--------|------|
| `<path>` | <one-line description of what changes> | Small |
| `<path>` | <similar> | Medium |
...

**Order of work.** <Recommended sequencing if the implementation has dependencies.>

**Not in this slice.** <List of deferred follow-ups so the implementing session doesn't accidentally scope-creep.>

## References

**Outgoing references:**
- [`<path>`](<relative-path>) — <one-line explanation of what this proposal cites from there>
- ...

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-NNN points at this proposal
- [`proposals/README.md`](README.md)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Known gaps section
```

## Notes on each section

### Status, Date, Concerns

The header is parsed by future sessions when they grep proposals. Keep it machine-friendly. `Status` is always `Draft proposal` until the implementing session updates it; `Date` is when the proposal was written (not when it was accepted); `Concerns` lists the design docs and Q-NNN that the proposal interacts with.

### Scope

A rough estimate. The scale:
- **Small**: a few files, maybe a day of work
- **Small-medium**: one module's worth of changes
- **Medium**: comparable to Layer 5 step 3a (product values) — a coherent feature touching 4–6 files plus tests and corpus
- **Medium-large**: comparable to recursive types — a feature that requires careful design plus implementation
- **Large**: multi-step shipping required, comparable to Layer 6 state machines

### Worked example in §4

This is the section most likely to get skipped, and most likely to be missed when omitted. Concrete byte-level encoding examples or "for the program X, here's what happens" walkthroughs save the implementing session significant time.

### Tradeoffs (§8)

Two distinct things to capture:

1. **Intentional deferrals** — features the proposal scopes out and why. Saves the implementer from accidentally implementing more than was agreed.
2. **Real research questions** — sub-questions the research didn't resolve. The implementer should know these exist before starting.

### Implementation sketch (§9)

The file table makes the "is this medium or large" question concrete. Include scope per file. Note when a change is just an additive case in a `when` block (Small) vs introducing a new module (Medium-Large).

## Length guidelines

The three existing proposals are in this range:

- Effect handlers: ~2500 words
- Refinement-lattice matching: ~2500 words
- State machines runtime: ~3000 words

If your proposal is much shorter than 1500 words, you're probably missing detail the implementer will need. If much longer than 3500 words, consider splitting into sub-proposals (multi-step shipping like state machines).
