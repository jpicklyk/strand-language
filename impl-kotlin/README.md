# Strand reference implementation

This directory holds the Kotlin/JVM reference implementation of Strand. The
project goals and corpus-wide conventions are set in `../CLAUDE.md`; this
README covers only the durable facts about the implementation — what it is,
how its authoring format works, and how to build and test it.

## Current state and module layout

The authoritative, always-current description of what is implemented — which
layers and steps are complete, the module list and dependency direction, the
type-system status, and the design judgments worth recording — lives in
[`CLAUDE.md`](CLAUDE.md). It is maintained every session and is the single
source of truth for implementation state. This README does not restate
per-layer scope, because a second copy is what falls out of date.

In short: `core` holds the node ADT, store, and JSON ingest; `verifier` and
`interpreter` build on it; `hashing` owns Layer 2 content addressing;
`runtime`, `schema`, `authoring`, `bytecode`, and `vm` add later layers; `cli`
is the driver and `corpus` runs the end-to-end tests. See `CLAUDE.md` for the
exact, current breakdown.

## JSON authoring schema

Strand programs are authored as JSON in the **flat form**: a top-level
document declares its `version`, names a `root`, and contains a `nodes`
object whose keys are author-chosen string ids and whose values are node
records. Author ids are arbitrary strings used only inside the document to
wire nodes together; the ingester rewrites them to opaque `NodeId`s in a
single pass, and Layer 2's `Hasher.finalize` replaces those with BLAKE3
hashes. Forward references are allowed.

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

**Why flat over inline.** With inline children, a node referenced from
multiple positions would have to either be duplicated (producing distinct
hashes, contrary to ADR-003) or introduce ad-hoc reference syntax. The flat
form models the Merkle DAG directly: each node is named once and referenced by
name, which also matches the store-by-hash representation — author ids are
simply replaced by hashes at finalize time.

A more compact text projection (Layer A) compiles to this same canonical
dag-json; see the `authoring` module and `CLAUDE.md` for its status.

## Seed corpus

The seed corpus lives at the repo's top-level `../corpus/` directory so it
can be shared across reference implementations. See
[`../corpus/README.md`](../corpus/README.md) for the per-program descriptions.
The Kotlin `:corpus` module wires this in via `processResources`
(see [`corpus/build.gradle.kts`](corpus/build.gradle.kts)) so the test
classpath resolves `getResourceAsStream("/corpus/NN-...")` against the
shared directory.

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

Run the CLI against a corpus program (paths assume `impl-kotlin/` is the
working directory; the corpus lives one level up at `../corpus/`):

```sh
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli run ../corpus/02-identity-applied.json
```

The CLI subcommands are `strand verify|run|machine|group|author|grammar`.

## Versions

- Kotlin 1.9.25 (JVM target 21)
- Gradle 8.10.2
- JUnit Jupiter 5.10.2
- kotlinx-serialization-json 1.6.3
