# Verified LLM-output demonstration {#verified-llm-output-demo}

**Document:** `demos/verified-llm-output/README.md`
**Status:** Executable demonstration of N-045 model-output constraint + Q-047 runtime output verification
**Last revised:** 2026-06-14

## What this demonstration is

Constraining a model is not the same as guaranteeing its output. A
structured-output request narrows what a model *may* emit, but a model under a
JSON-schema constraint can still return a value that is structurally typed and
semantically wrong — a score outside its declared range, a required field empty,
a field that type-checks but means nothing. The constraint is a request to the
provider; the verification is a property of what came back. This demonstration
shows Strand's two complementary layers, on shipped material.

The constrain layer is the N-045 `ResponseSchemaSpec`. A `Generate` call carries
a `ResponseSchemaSpec` whose schema projects — through the verifier's own
`JsonSchemaProjection` — to the provider's structured-output JSON schema. That
projected schema is the constraint the provider forwards to the model; it is the
exact projection the N-045 dispatch path (`parseResponseSchemaField`) runs
internally before it hands the schema to the provider library. The demonstration
projects the program's `BoundedScore` schema and prints it.

The verify layer is the Q-047 runtime Schema invariant — the keystone. The value
the model returns flows into a Strand `Schema`-typed position whose invariant is
a pure `(Int) -> Bool` checked at runtime. A response that is structurally an
`Int` but out of range raises `InterpretError.SchemaInvariantViolation` at the
value-flow site, *before* the value reaches output. Strand verifies what came
back, so the malformed model output never escapes.

The keystone over the existing `output-by-construction` demonstration is the
*origin* of the verified value. There, the schema-checked value was hand-built.
Here it originates from the (mock) model call: a `Demo.ScoreModel` ForeignNode
typed `(String) -> Int`, declaring `LLM.Generate`, returns the model's structured
score, and *that returned value* is the one the `BoundedScore` invariant checks.
The model transport is invoked exactly once per scenario — the value is a genuine
model response, not a literal standing in for one.

The driver is an ordinary JVM caller of the shipped embedding surface: the Q-054
`StrandRuntime` facade (`verify` for the surfaced types, `run` for evaluation
with the Q-047 obligation installed), the `HostPolicy` it takes, the verifier's
`JsonSchemaProjection` (the N-045 constraint projection), and the canonical
dag-json it admits. It introduces no language feature, no node category, no
encoding change, and no verifier rule. Every property the demonstration claims is
one the runtime enforces, and the assertion net (`VerifiedLlmOutputDemoTest`)
protects each one from silently rotting.

## The program

One canonical dag-json pipeline, [`verify-model-score.json`](programs/verify-model-score.json),
shared byte-for-byte across both scenarios. The `BoundedScore` Schema wraps an
`Int` with one invariant, `score_in_0_100`: a pure `(Int) -> Bool` Lambda
requiring `0 <= score <= 100` (`Int.Ge` ∧ `Int.Le`). The same schema plays both
roles — it backs an N-045 `ResponseSchemaSpec` sibling node (the constrain layer
the driver projects) *and* it is the `paramType` of a `recordScore` Lambda (the
verify layer). A `Demo.ScoreModel` ForeignNode (`(String) -> Int`, declaring
`LLM.Generate`) is the constrained-model stand-in; its returned score flows into
the `recordScore` argument position, which the verifier re-records as a
`SchemaType<Int>` so the Q-047 obligation fires there at runtime.

The program is hand-authored canonical dag-json — there is no paired Layer A,
mirroring the N-045 demonstrator corpus 69 (Layer A has the `RSC` code for the
wrapper, but the inline schema/invariant tower this program builds is authored
directly here for the same reason `output-by-construction`'s documents are). The
two scenarios differ only in the value the model returns, which the driver
controls by installing the canned response on the `Demo.ScoreModel` builtin via
`Builtins.installTestBuiltin` — the brief's "one program, two mock responses"
shape. No real network: the model is a host test builtin returning the scripted
score.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:verifiedLlmOutputDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.VerifiedLlmOutputDemoTest"
```

The driver `VerifiedLlmOutputDemo` and the test `VerifiedLlmOutputDemoTest` live
in the `:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one body of
scenario code, so the printed demonstration and the regression net cannot
diverge. They stay in `:runtime` because they compile against the runtime
modules. The driver loads the committed canonical dag-json from
[`programs/`](programs/) through the test classpath (`runtime/build.gradle.kts`
copies the directory in via `processTestResources`), so the artifact the driver
runs is the content-addressed graph, not a human-facing projection.

## The scenarios

### VO1 Constrain + valid

The constrain layer: the program's `BoundedScore` schema projects to the
provider's structured-output JSON schema (`{"type": "integer"}`) — the constraint
the model receives. The verify layer: the `Demo.ScoreModel` builtin returns a
valid structured score (`87`, in `0..100`); the value flows from the model call
into the `BoundedScore` position; the Q-047 invariant passes; the program
produces the validated result `87`. The transport is invoked once — the value
flowed from the model call through the Schema position and out, a genuine model
response verified before use.

### VO2 Verify the output (keystone)

The same pipeline, but the constrained model returns a response that is
structurally an `Int` and out of its declared range (`142`). A constrained model
can still return this. The value flows into the `BoundedScore` position; the
Q-047 runtime obligation fires at the value-flow site and raises the real
`InterpretError.SchemaInvariantViolation`, naming the blamed value-flow node, the
failed schema, the failed invariant, and the offending value (`IntV(v=142)`) —
*before* the value reaches output. Strand verifies what came back, so the
malformed model output is contained and never escapes. Constraining narrows what
the model *may* emit; verifying catches what a constrained model *still* returns.

### VO3 Two complementary layers

N-045 `ResponseSchemaSpec` is the constraint sent *to* the provider: the projected
JSON schema bounds what the model may emit, and a real provider enforces it
server-side. But the constraint is advisory about semantics — a `0..100` score
field can still come back as `142`. Q-047 is the in-process check on what came
*back*: the Strand Schema invariant, enforced at the value-flow site. The two
layers are complementary — constraining reduces malformed responses; verifying
guarantees a malformed one never escapes.

## Transcript

The transcript below is the output of `./gradlew :runtime:verifiedLlmOutputDemo -q`.

```
========================================================================
Strand -- constrain the model, then verify what it returns
Constraining is not guaranteeing: a model under a JSON-schema
constraint can still return a structurally-typed but semantically
invalid value. N-045 constrains what the model may emit; Q-047
verifies the value that came back, so malformed output never escapes.
The 'model' is a (String) -> Int stand-in under LLM.Generate; the
verified value ORIGINATES from the (mock) model call.
========================================================================

VO1  Constrain + valid -- the model's response satisfies the invariant
------------------------------------------------------------------------
  The constrain layer (N-045 ResponseSchemaSpec). The program's
  BoundedScore schema projects to the provider's structured-output
  JSON schema -- the constraint the model receives:
    {
        "type": "integer"
    }
  The verify layer (Q-047). The model returns a VALID structured
  score; it flows into the BoundedScore position; the invariant
  (0 <= score <= 100) PASSES:
    verified clean            = true
    model transport invoked   = 1 time(s)
    model returned score      = 87
    invariant passed          = true
    validated result          = 87
  The value flowed from the model call through the Schema position
  and out -- a genuine model response, verified before use.

VO2  Verify the output (keystone) -- the model's response violates it
------------------------------------------------------------------------
  The SAME pipeline, but the constrained model returns a response
  that is structurally an Int but out of its declared range (> 100).
  A constrained model can still return this. The Q-047 runtime
  obligation fires at the value-flow site BEFORE output:
    model transport invoked   = 1 time(s)
    model returned score      = 142
    raised at runtime         = true
    runtime error             = SchemaInvariantViolation
    blamed node (value-flow)  = #30
    failed schema             = #18
    failed invariant          = #17
    offending value           = IntV(v=142)
  Strand verifies what came back, so the malformed model output is
  contained -- it never reaches output. Constraining narrows what the
  model MAY emit; verifying catches what a constrained model STILL
  returns.

VO3  Two complementary layers -- constrain (N-045) AND verify (Q-047)
------------------------------------------------------------------------
  N-045 ResponseSchemaSpec is the constraint sent TO the provider:
  the projected JSON schema bounds what the model may emit. A real
  provider enforces it server-side. But the constraint is advisory
  about SEMANTICS -- a 0..100 score field can still come back as 142.
  Q-047 is the in-process check on what came BACK: the Strand Schema
  invariant, enforced at the value-flow site. The two layers are
  complementary: constraining reduces malformed responses; verifying
  guarantees a malformed one never escapes.

========================================================================
What this demonstrates: a value originating from a (mock) model call
is verified against a Strand Schema invariant at runtime, so a
structurally-typed-but-semantically-invalid model response is
contained before output (Q-047), complementing the N-045 constraint
on what the model may emit. NOT shown: a real provider enforcing the
constraint server-side (the model is a mock returning what the demo
scripts); the Q-047 check is interpreter-only (the VM erases schemas);
the program is hand-authored; NOT first-pass correctness or cost.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows that a value originating from a model call is verified
against a Strand Schema invariant at runtime, so a structurally-typed but
semantically-invalid model response is contained before output (Q-047),
complementing the N-045 constraint on what the model may emit. The constrain
layer and the verify layer share one schema; the difference between the two
scenarios is only the value the model returns.

It does **not** use a real provider. The model is a `Demo.ScoreModel` host test
builtin returning the score the demo scripts; a real provider would enforce the
N-045 constraint server-side, and the mock simply returns what the demonstration
specifies. The point of the mock is to make the *verify* side observable on a
known input — a valid value that passes and an invalid value that a constrained
model could still have returned. The N-045 constraint is genuine: it is projected
through the verifier's own `JsonSchemaProjection`, the exact projection the
dispatch path forwards to the provider.

The Q-047 runtime check is **interpreter-only**. The bytecode VM erases schemas
before lowering (Q-017), so a runtime-violating program raises under the
tree-walking interpreter but would run under the VM — a bounded divergence
documented in the runtime-schema-enforcement proposal, the same caveat
`output-by-construction`'s W3 records. VO2 runs the interpreter path, and the
demonstration states this plainly rather than implying VM parity.

The program is **hand-authored** canonical dag-json — the demonstration isolates
the constrain-then-verify property from the separate question of how an agent
generates a program. And it does **not** show first-pass correctness — whether
`87` is the *right* score for the query — nor inference cost; those are the
deferred Q-021 dynamic study.

This is the full end-to-end the brief preferred over the two-halves fallback: the
schema-checked value genuinely originates from the (mock) model call, so VO1 and
VO2 are one pipeline rather than two narrated halves. The N-045 projection is the
real constraint and the Q-047 violation is the real one the interpreter
constructed at the value-flow site; nothing is staged.

## References

**Outgoing references:**
- [`proposals/implemented/runtime-schema-enforcement.md`](../../proposals/implemented/runtime-schema-enforcement.md)
  — Q-047 runtime evaluation of invariants on dynamic values, the
  `InterpretError.SchemaInvariantViolation` VO2 exercises at the value-flow site
  and the interpreter-only caveat the demonstration states. This is the keystone
  layer: the verified value originates from the model call.
- [`proposals/implemented/schema-and-invariant.md`](../../proposals/implemented/schema-and-invariant.md)
  — Q-035 N-032 Schema + N-033 Invariant, the `BoundedScore` schema and its pure
  `score_in_0_100` invariant body whose shape this program reuses.
- [`proposals/implemented/agent-native-capabilities.md`](../../proposals/implemented/agent-native-capabilities.md)
  — N-045 `ResponseSchemaSpec` and `JsonSchemaProjection`, the constrain layer
  VO1 projects; corpus 69 is the worked N-045 demonstrator whose hand-authored
  shape this program mirrors.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade (`verify` / `run`) and `HostPolicy` the
  driver is built on.
- [`demos/output-by-construction/README.md`](../output-by-construction/README.md)
  — the reference for the Q-047 runtime Schema/Invariant enforcement half; this
  demonstration's keystone is that the verified value originates from the model
  call rather than being hand-built as it is there (W1/W3).

**Incoming references:**
- [`demos/README.md`](../README.md) — index entry.
