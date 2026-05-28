# State machines

State machines in Strand are typed Mealy machines: a pure transition function `(State, Event) -> (State, Outputs)` plus declared input/output event streams.

## The codes

- `SM transitionFn:ref initialState:ref inputStreams:[refs] [outputStreams:[refs]] [effects:[refs]]` — StateMachine.
- `ESE eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (external).
- `ESI eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (internal — wires between machines).
- `ESO eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (output, observable externally).
- `TR guard:nullable-ref body:ref` — Transition (rarely used; current corpus encodes transitions inline in the transitionFn Lambda body).

## The transition function shape

The `transitionFn` is a Lambda with signature `(State, Event) -> Result`. The `Result` can take one of two shapes — the verifier accepts both.

### Shape 1 — OutputBatch (recommended for single-output and zero-output machines)

```
Result = {state: State, outputs: OutputBatch}
OutputBatch = {output_0: Option<O_0>, output_1: Option<O_1>, ...}
```

`OutputBatch` is a ProductType with one field per output stream, named `output_0`, `output_1`, ... (positional, matching the `outputStreams` declaration order). Each field is an `Option<eventType>` — `Some(payload)` to emit on that step, `None` to emit nothing. With zero output streams, `OutputBatch` is the empty product `{}`.

### Shape 2 — Tagged list (use when emitting variable numbers of events per step)

```
Result = {state: State, outputs: μ. Cons({head: TaggedOutput, tail: <self>}) | Nil}
TaggedOutput = output_0(O_0) | output_1(O_1) | ... | output_{n-1}(O_{n-1})
```

The tagged list is a recursive Cons/Nil list of `TaggedOutput` sums — each entry names the destination stream by case and carries the payload.

For most agent emissions, OutputBatch is simpler. Use the tagged list only when a single event needs to produce multiple outputs.

## Required effects

Every StateMachine with inputs must declare `receiveFx` (`"StateMachine.Receive"`). Every machine with outputs must also declare `sendFx` (`"StateMachine.Send"`). Both are in the implicit prelude.

```layer-a
toggle SM transFn initialState [inStream] [outStream] [receiveFx sendFx]
```

If you omit one of these, the verifier returns `StateMachineMissingImplicitEffect(at=#sm, missing={"StateMachine.Receive"})`.

## A toggle machine — full example

State is `Bool`, event is `Unit`, the transition flips the state and emits no output:

```layer-a
@v=1 root=toggle
emptyOut PRD []                              -- empty OutputBatch
stateF PRF "state" boolT
outsF PRF "outputs" emptyOut
resultT PRD [stateF outsF]
emptyOutV PV emptyOut []
transFn LAM [s:boolT e:unitT] (PV resultT [state=(APP not [s]) outputs=emptyOutV])
inStream ESE unitT
toggle SM transFn false [inStream] [] [receiveFx]
```

Notes:
- `boolT` and `unitT` are from the prelude.
- `not` is the prelude `Bool.Not` builtin.
- `false` is an inline BoolLit (density-v4 sugar).
- The transition's result type is a product with `state` and `outputs` fields. `outputs` is the empty `OutputBatch` since this machine has no output streams.

## A counter machine — emits an output

Counter that increments its Int state and emits the new count on each event:

```layer-a
@v=1 root=counter
someCase SCS "Some" intT
noneCase SCS "None" _
optIntT SUM [someCase noneCase]

stateF PRF "state" intT
out0F PRF "output_0" optIntT
batchT PRD [out0F]
resultT PRD [stateF (PRF "outputs" batchT)]

newState LAM [s:intT e:unitT] (APP add [s 1])
emit LAM [s:intT e:unitT] (SV optIntT "Some" (APP add [s 1]))

transFn LAM [s:intT e:unitT] (PV resultT [
  state=(APP newState [s e])
  outputs=(PV batchT [output_0=(APP emit [s e])])
])

inStream ESE unitT
outStream ESO intT
counter SM transFn 0 [inStream] [outStream] [receiveFx sendFx]
```

In practice agents often inline the helper Lambdas, but the structure above shows the explicit per-field flow.

## Multi-input machines

If a machine has multiple input streams, the verifier synthesizes a tagged InputEvent sum:

```
InputEvent = stream_0(T_0) | stream_1(T_1) | ... | stream_{n-1}(T_{n-1})
```

The transition function's event parameter becomes `InputEvent`. Match on the sum to discriminate:

```layer-a
inputEvT SUM [(SCS "stream_0" intT) (SCS "stream_1" stringT)]
transFn LAM [s:stateT e:inputEvT] (WHEN e inputEvT "stream_0(i) -> ... | stream_1(s) -> ...")
```

## EventStream variants

- `ESE` — external input. The machine's input wires to an external producer (driven by the eval framework's `--events` file or by a host caller via `MachineGroupHandle.externalInputs`).
- `ESI` — internal. Used to wire machines together: one machine's `outputStreams` lists an internal stream, another machine's `inputStreams` lists the same NodeId. Strand's content-addressing means structurally equal `ESI` declarations refer to the same channel.
- `ESO` — external output. Drained by the host.

## Overflow policies (rare in practice)

`ESE/ESI/ESO` accept optional `bufferSize: Int` and `overflowPolicy: Keyword` arguments:

- `BlockProducer` (default) — backpressure
- `DropNewest`, `DropOldest` — drop on overflow
- `Sample` — rate-limit (requires `intervalNanos`, an object form)

```layer-a
outStream ESO intT 100 DropOldest
```

## Running a state machine

`strand machine <file.json> --events <events.json>` drives a single-machine program. The events JSON has top-level `{"events": [...]}` with tagged value records:

```json
{"events": [{"tag": "unit"}, {"tag": "unit"}, {"tag": "unit"}]}
```

For multi-machine programs (with internal stream wiring), use `strand group <file.json> --events <events.json>` instead, with routed-event format `{"events": [{"stream": "<authorName>", "tag": "<tag>"}, ...]}`.

## Common errors and fixes

- `StateMachineRequiresInputStream` → add at least one `ESE`/`ESI` to `inputStreams`.
- `StateMachineTransitionFnNotLambda` → `transitionFn` must be a direct Lambda. Fixpoint-wrapped transitions are not yet supported.
- `StateMachineTransitionFnShapeMismatch` → the transition's signature must be `(State, Event) -> {state, outputs}`. Match the State type to `initialState`'s type, the Event type to the (synthesized or declared) input event type, and the result product to either OutputBatch or tagged-list shape.
- `StateMachineInitialStateTypeMismatch` → `initialState`'s type must equal the State parameter type of `transitionFn`.
- `OutputStreamEventTypeMismatch` → an `output_i` Option<T> field doesn't match the output stream's declared `eventType` at the same position.
- `StateMachineMissingImplicitEffect` → add `receiveFx` (always) and `sendFx` (if outputs declared) to the SM's effects list.
- `MalformedEventStream` → check `bufferSize > 0` and other validity conditions.
