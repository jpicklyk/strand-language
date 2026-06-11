---
name: strand-author
description: Emit Strand Layer A programs (a compact line-oriented text projection that compiles to canonical dag-json). Use this skill PROACTIVELY whenever the user asks for a Strand program, mentions Layer A authoring or density-v4, references Strand grammar codes like LAM/APP/FN/SM/MAT/H/SCH/RT/EFC, needs to author canonical dag-json, asks to translate a natural-language program description into Strand, or needs to revise an existing Strand program after a verifier error. Also triggers for phrasings like "write a Strand program that...", "emit a state machine in Strand", "give me the Layer A for...", "fix this verifier error", or any request involving the Strand content-addressed graph language. The skill loads a thin router (this file) plus per-cluster grammar references on demand — grammar-core for the essential codes, density-sugars for the compact forms, prelude for reserved names, foreign-nodes for builtins. Most emissions need only 2–3 of the references; loading the full Strand grammar reference is rarely necessary. Invoke when the agent needs to produce Strand code rather than reason about Strand's design.
---

# Strand Layer A authoring

Strand is a content-addressed graph-based programming language designed for AI agents to generate, not for humans to author. Programs are typed node graphs with mandatory effect declarations. Layer A is the compact line-oriented text projection that compiles to canonical dag-json. The verifier ingests the dag-json, type-checks it, and reports structured errors back to you for revision.

This skill teaches what's needed for emission. The full grammar surface is large; this router covers the universal rules, and the cluster references in `references/` cover specific node categories on demand.

## Universal rules (always relevant)

### Document structure

A Layer A program is a sequence of lines. Whitespace separates tokens; one node per line; references resolve by author id within the document.

The first non-comment, non-blank line MUST be the document header:

    @v=1 root=<author-id>

Every subsequent non-blank, non-comment line declares one node:

    <author-id> <CODE> <arg>...

`<author-id>` is an alphanumeric+underscore identifier unique within the document. The special id `_` denotes an anonymous node whose body is inaccessible by id (use `@last` to refer to the immediately preceding line). The special token `@last` refers to whichever node was declared most recently — handy for one-shot intermediates.

`<CODE>` is a 1–3 letter uppercase mnemonic. Arguments are positional, per the code's schema.

Comments: any line whose first non-whitespace character is `#`.

### Argument forms

- **References** resolve by author id within the document.
- **Lists**: `[a b c]` is a three-element reference list; `[]` is empty.
- **Strings**: double-quoted with `\"`, `\\`, `\n`, `\t` escapes.
- **Integers**: `42`, `-3`, `0`.
- **Floats** must contain a dot: `3.14`, `-0.5`, `1.0`.
- **Booleans**: `true` or `false`.
- **Null / absent reference**: `_` (single underscore).

### Optional list slots — `[]` vs `_`

When a code has an optional `[refs]` slot you do not need to supply, you have three equivalent choices:

1. Omit the slot entirely (any trailing optional slots also drop).
2. Write `[]` for an empty list.
3. Write `_` (parser accepts this as equivalent to `[]` at LIST_REF slots).

To skip an optional middle slot while supplying a later one, use either `[]` or `_`:

    APP fn [arg] [] [efd]
    APP fn [arg] _  [efd]      -- equivalent

Both canonical-encode identically.

### Declarations vs values

Some codes describe **values** (Lambda, Application, IntLit, ProductValue, SumValue, …). Others describe **declarations or structural pieces** (EffectCategory, EffectDecl, ParameterDecl, ProductTypeField, SumTypeCase, MatchCase). Declaration-only codes — **EFD, EFC, PRC, PRF, SCS, MC** — cannot be inlined as `(CODE args...)` nested expressions. They need their own lines. Correct:

    myDecl EFD writeFx [path]
    call APP write [path] [] [myDecl]

Incorrect:

    call APP write [path] [] [(EFD writeFx [path])]    -- EFD is not value-producing

### The error format

If the verifier rejects an emission, you'll receive feedback in shapes like:

    verification failed:
      ErrorName(at=#<N>, <detail>)

or for Layer A compile errors:

    Layer A compilation failed:
      line N: <message>

Verifier errors reference nodes by integer node id (`#23`), never by author id. The CLI annotates each `#N` with the corresponding author id (and the Layer A line where known); nodes the compiler synthesized (sugar expansions, implicit prelude) are flagged as such. Use the annotation plus the error class name to locate and fix the failing node. Layer A compile errors fire before the verifier; fix them first.

## Choosing which cluster references to load

Load only the references you need for the task at hand. Most programs need 2–3.

| If your task uses... | Load |
|---|---|
| Literals, lambdas, applications, types, let-bindings, varrefs — the universal core | [references/grammar-core.md](references/grammar-core.md) |
| Reserved names like `add`, `intT`, `now`, `nowFx`, `gt`, `fsWrite` — the implicit prelude | [references/prelude.md](references/prelude.md) |
| Density sugars: IF, WHEN, compact LAM params, inline literals, auto-VarRef, `@last`, inline FIELD_LIST, nested expressions | [references/density-sugars.md](references/density-sugars.md) |
| Specific foreign builtins (arithmetic, IO, hashing, LLM providers, vector stores) | [references/foreign-nodes.md](references/foreign-nodes.md) |
| Effects, capabilities, handlers (EFC, EFD, CAP, H) | [references/effects.md](references/effects.md) |
| Pattern matching (MAT, MC, PLT, PVR, PWC, PCN) | [references/grammar-core.md](references/grammar-core.md) §Control flow |
| Product/sum value construction (PV, PFV, PFG, SV) | [references/grammar-core.md](references/grammar-core.md) |
| Recursive types (RT, RS), Fixpoint (FIX) | [references/grammar-core.md](references/grammar-core.md) |
| State machines (SM, ESE, ESI, ESO, TR) | [references/state-machines.md](references/state-machines.md) |
| Schemas and invariants (SCH, INV) | [references/grammar-core.md](references/grammar-core.md) §Schema and Invariant |

Each reference file is self-contained; load only the ones relevant to the task. A factorial-shaped task typically loads SKILL.md + grammar-core.md + prelude.md (and maybe density-sugars.md). A state-machine task loads SKILL.md + state-machines.md + prelude.md. An effects-heavy task loads SKILL.md + effects.md + prelude.md + foreign-nodes.md.

## A worked example — factorial via Fixpoint

This is the smallest end-to-end program demonstrating the universal rules plus a few essential codes. Useful as a pattern to anchor on.

```layer-a
@v=1 root=app
factT FNT [intT] intT
factBody LAM [recurse:factT n:intT] (IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])]))
fact FIX factT factBody
app APP fact [5]
```

This computes `factorial(5) = 120`. Key elements:

- `intT` is from the implicit prelude (no local declaration needed).
- `FNT` is a FunctionType: `(Int) -> Int`.
- `LAM [recurse:factT n:intT]` uses compact-LAM parameter syntax — declares two ParameterDecl bindings inline.
- `(IF cond then else)`, `(APP fn args)`, etc. are nested-expression density sugars — they compile to standalone IF / Application nodes with synthetic author ids.
- `FIX factT factBody` is a Fixpoint. The body Lambda's FIRST parameter is the recursive call slot (`recurse`); remaining parameters are user-facing (`n`).
- `app APP fact [5]` applies the fixpoint to the literal `5` (inline literal sugar).

If unfamiliar with any code here, load the relevant references file.

## Working with verifier errors

The verifier produces structured errors with `at=#<N>` naming the offending node by integer node id (the CLI annotates it with the author id where available). Common error classes and what they typically mean:

- `UncoveredEffects(at=#12, missing={...})` — a Lambda's body uses an effect, but the Lambda's `effects` slot doesn't declare it. Add the effect category to the LAM's effects list: `LAM [params] body [effectCategory1 effectCategory2]`.
- `EffectDeclArityMismatch` / `EffectDeclParameterTypeMismatch` — the parameter list on an EffectDecl doesn't match the EffectCategory's parameter count or types. Check the EFC's declared parameters and supply matching positional values in the EFD.
- `HandlerSignatureMismatch(at=#7, expected=..., actual=...)` — the `handle` lambda's type doesn't match the intercepted function's signature. Adjust the LAM's parameter types and body return type to match.
- `SchemaInvariantViolation(at=#19, schema=..., invariant=...)` — a statically-known value violates a Schema's invariant. Either change the value or relax the invariant.
- `UnboundRecursiveSelf(at=#4, hint=...)` — a RecursiveSelf reference can't resolve to an enclosing RecursiveType. Read the hint — it points at the inner/outer PRD split, or now (after auto-Outer-PRD synthesis) is usually rescued automatically.
- `code 'X' at position N expected <kind> but got <actual>` — Layer A compile error at the tokenization level. The slot at position N expected a different argument shape. Common cases: passing a bare token where a list belongs, or vice versa.

When you receive a verifier error, focus on the specific node id and error class. Don't rewrite the whole program — fix the named node.

## Density-v4 is recommended

The density sugars (IF, WHEN, compact LAM, inline literals, auto-VarRef, anonymous `_`, inline FIELD_LIST, nested expressions) reduce per-emission token count by roughly 4× relative to canonical dag-json. Use them unless explicitly asked to emit canonical form. The verifier accepts either; both hash byte-identically to the same dag-json.

Load [references/density-sugars.md](references/density-sugars.md) for the full catalog of sugar forms.

## Output convention

Emit your Strand program inside a fenced code block:

````
```layer-a
@v=1 root=...
(your program)
```
````

Optional explanation outside the fence is fine. Keep it brief.
