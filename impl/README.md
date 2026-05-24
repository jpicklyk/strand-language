# Strand reference implementation — Layer 1

This directory holds the Kotlin/JVM reference implementation of Strand. The
goals and conventions are set in `../CONTINUATION.md`; this README covers
only what is specific to the implementation.

## Layer 1 scope

Layer 1 implements node types **N-001 through N-019 plus N-034 and N-035** as
specified in `../design/node-algebra.md`:

- Six literals (IntLit, FloatLit, StringLit, BoolLit, UnitLit, BytesLit).
- Eight types (PrimitiveType, ProductType, ProductTypeField, SumType,
  SumTypeCase, FunctionType, TypeParameter, ForallType).
- Six function/binding constructs (Lambda, TypeAbstraction, ParameterDecl,
  Application, Let, VarRef).
- NodeRef.

What is **deliberately deferred** to later layers:

- BLAKE3-over-CBOR content addressing (Layer 2). The store keys nodes by an
  opaque sequential `NodeId`; a `Hasher` interface stands as a
  forward-compatibility hook.
- Effects (N-021/N-022) and capabilities (Layer 3). The JSON schema does not
  yet accept `effect` edges; the `FunctionType` parser ignores any effect
  annotation if encountered.
- Foreign nodes (N-020, Layer 4).
- Match / MatchCase / Pattern (N-023..N-025) and Fixpoint (N-026) (Layer 5).
- State machines (N-027..N-029) (Layer 6).
- Schema and Invariant (N-032/N-033) (later still).

## Module layout

```
impl/
├── build.gradle.kts              shared build configuration
├── settings.gradle.kts           module list
├── gradle.properties             daemon and toolchain flags
├── gradlew, gradlew.bat          Gradle wrapper scripts
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── core/                         Node ADT, NodeStore, JSON ingest
├── verifier/                     Well-formedness + explicit-instantiation type checking
├── interpreter/                  Tree-walking evaluator
├── cli/                          'strand verify|run <file.json>'
├── corpus/                       Seed corpus + end-to-end tests
└── README.md
```

**Rationale.** `core` holds the data model and ingest only; `verifier` and
`interpreter` each depend on `core`. `interpreter` depends on `verifier` so
that a future end-user might call them through one entry point, but the
interpreter never re-runs verification — it assumes its input has been
verified. `cli` is a small driver tying them together. `corpus` is its own
module so that the seed corpus is a first-class versioned artifact and its
end-to-end test sees the same packaging consumers will.

## JSON schema

Strand programs are authored as JSON in the **flat form**: a top-level
document declares its `version`, names a `root`, and contains a `nodes`
object whose keys are author-chosen string ids and whose values are node
records. Author ids are arbitrary strings used only inside the document to
wire nodes together; the ingester rewrites them to opaque `NodeId`s in a
single pass. Forward references are allowed.

Example: polymorphic identity applied at `Int`.

```json
{
  "version": 1,
  "root": "app",
  "nodes": {
    "T_a":     { "type": "TypeParameter", "name": "a" },
    "intT":    { "type": "PrimitiveType", "kind": "Int" },
    "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
    "xRef":    { "type": "VarRef", "binder": "x" },
    "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
    "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
    "arg":     { "type": "IntLit", "value": 42 },
    "app":     { "type": "Application", "function": "id", "arguments": ["arg"], "typeArguments": ["intT"] }
  }
}
```

The polymorphic Lambda is wrapped in a `TypeAbstraction` that binds the
`TypeParameter` it references; `Application.typeArguments` instantiates the
TypeAbstraction's bound parameters positionally. A monomorphic call (one whose
function's type is a plain `FunctionType` with no quantification) supplies
`typeArguments: []` or omits the field.

The full per-node-type field schema is documented in the kdoc on
`org.strand.core.JsonIngest`.

**Why flat over inline.** With inline children, a node that should be
referenced from multiple positions would have to either be duplicated
(producing distinct hashes once Layer 2 lands, contrary to ADR-003) or
introduce ad-hoc reference syntax. The flat form models the Merkle DAG
directly: each node is named once and referenced by name. This also matches
the eventual store-by-hash representation; the only thing that changes in
Layer 2 is that author ids are replaced by hashes.

## Seed corpus

The seed corpus is at `corpus/src/main/resources/corpus/`. See
`corpus/src/main/resources/corpus/README.md` for the per-program
descriptions.

Programs run by `CorpusTest`. Some are verify-only because Layer 1 has no
constructors for product or sum values; the type nodes are wired up so the
verifier exercises them, but they cannot yet be evaluated.

## Design judgments worth recording

These are not derivable from `node-algebra.md` alone; flag them in case Jeff
wants them revisited:

1. **`TypeParameter` is a rigid generic identified by node identity.** Two
   distinct `TypeParameter` nodes are two distinct type variables (even with
   the same name); all references to a single `TypeParameter` node resolve
   to the same variable across the whole expression. This is what makes the
   S combinator type-check when its three `T_a`/`T_b`/`T_c` nodes are shared
   across nested Lambdas.

2. **Polymorphism is by explicit type abstraction (System F).** A
   polymorphic term is a `TypeAbstraction` (N-034) whose body is any
   expression and whose `typeParameters` list binds the `TypeParameter`
   nodes appearing free in that body. The type of a `TypeAbstraction` is a
   `ForallType` (N-035) quantified over the same parameters. `Lambda` (N-014)
   does not implicitly bind TypeParameters; a Lambda whose parameter type
   mentions a TypeParameter must be enclosed by a TypeAbstraction that lists
   that TypeParameter, otherwise the verifier rejects with
   `UnboundTypeParameter`.

   Each `Application` carries `typeArguments`: a positional list of
   type-node ids that instantiates the function's quantified type
   parameters. If the function's type is a plain `FunctionType`,
   `typeArguments` must be empty; if it is a `ForallType` over `n`
   parameters, `typeArguments` must have length `n`, and the verifier
   substitutes them into the ForallType's body to obtain a `FunctionType`
   against which the argument types are checked by structural equality. No
   unification, no occurs-check, no let-generalization.

   **Partial type instantiation is rejected.** If substitution at an
   Application reduces a ForallType to another ForallType, the verifier
   reports `PartialTypeInstantiation`. Every Application of a polymorphic
   value must supply enough type arguments to reach a `FunctionType`. This
   restriction may be relaxed in a later layer.

   **Forall types are compared by structural equality keyed on
   TypeParameter NodeIds**, not by alpha-equivalence. Two `ForallType`
   values that quantify over distinct `TypeParameter` nodes are unequal
   even when their bodies are isomorphic. Programs that pass a polymorphic
   value into a position whose parameter type is itself a `ForallType` must
   therefore share the `TypeParameter` node between the parameter's type
   and the abstraction that produces the value. This is consistent with
   the design's treatment of TypeParameter identity. Layer 2's canonical
   encoding normalizes bound-variable positions and lifts the requirement.

3. **`paramType` is mandatory at every `ParameterDecl`** (per the node
   algebra). All function-boundary types must be declared; the verifier
   does no synthesis.

4. **Scope is by `NodeId`, not by name.** The `name` field on
   `ParameterDecl` and `Let` is metadata: the verifier and interpreter use
   the binder's `NodeId` for lookup, exactly as required by the spec's
   alpha-equivalence-by-position story. Multiple parameters with the same
   `name` would not collide in scope.

5. **`NodeRef` is transparent in Layer 1.** It forwards to its target for
   both verification and evaluation. In Layer 2, `NodeRef` becomes a hash
   reference and may cross store boundaries; the seam where Layer 1 forwards
   is the seam where Layer 2 will resolve hashes.

6. **Subtyping is not implemented.** `node-algebra.md` describes structural
   subtyping (width subtyping on products, contravariance on function
   arguments, effect-set inclusion). Layer 1 uses strict structural equality
   when comparing substituted parameter types against argument types. Width
   subtyping can be added without breaking existing tests; it is held back
   to keep the type story small.

7. **`TypeParameter.bound` is parsed but ignored.** Bounds require subtyping;
   see (6).

## Running tests

The Gradle wrapper boots its own Gradle distribution. The wrapper JAR
(`gradle/wrapper/gradle-wrapper.jar`) is the only file not committed by this
scaffolding — it must be generated once with a system Gradle install:

```sh
gradle wrapper --gradle-version 8.10.2
```

Or downloaded directly from:

```
https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar
```

After that, on Windows:

```powershell
.\gradlew.bat test
```

On POSIX:

```sh
./gradlew test
```

Run the CLI against a corpus program:

```sh
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli run corpus/src/main/resources/corpus/02-identity-applied.json
```

## Versions

- Kotlin 1.9.25 (JVM target 21)
- Gradle 8.10.2
- JUnit Jupiter 5.10.2
- kotlinx-serialization-json 1.6.3
