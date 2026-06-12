# Reference: state-machines — SM / event streams / transitions

## Codes

- `SM transitionFn:ref initialState:ref inputStreams:[refs] [outputStreams:[refs]] [effects:[refs]]` — StateMachine. `m SM tfn s0 [in] [out] [recvEf sendEf]`
- `ESE eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (external). `in ESE intT`
- `ESI eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (internal). `mid ESI intT`
- `ESO eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (output). `out ESO intT`
- `TR guard:nullable-ref body:ref` — Transition.

## Required effects

A state machine with input streams must declare `receiveFx`
(StateMachine.Receive) in its `effects` list. A state machine with
output streams must also declare `sendFx` (StateMachine.Send). These
are the prelude effect-category names; missing either raises
`StateMachineMissingImplicitEffect`. Note `sendFx` / `receiveFx` are
distinct from the network categories `netSendFx` / `netRecvFx`.

The related machine-lifecycle categories `spawnFx`
(StateMachine.Spawn) and `terminateFx` (StateMachine.Terminate) are
also preluded.

## Transition function shape

The transition function's type is `(State, Event) -> (State, Outputs)`,
expressed as a FunctionType whose result is a ProductType with the
`state` field FIRST and the `outputs` field SECOND. Two result shapes
verify:

- the OutputBatch product shape (single-stream machines): `outputs` is
  a product with one `output_i: Option<...>` slot per output stream,
  and each output stream's eventType must agree with the corresponding
  slot (else `OutputStreamEventTypeMismatch`);
- the tagged-list recursive shape (multi-stream machines): `outputs`
  is a recursive list of tagged output events.

The SM's `initialState` type must match the State half of the
transition signature (else `StateMachineInitialStateTypeMismatch`);
shape violations raise `StateMachineTransitionFnShapeMismatch`.

Compact-LAM parameters on the transition lambda may elide their types —
the Elaborator picks them up from the SM's transitionFn signature
(see the toggle example in the core prompt):

    transitionFnT FNT [boolT unitT] resultT
    transitionResult PV resultT [state=(APP not [s]) outputs=(PV emptyOutputsT [])]
    transitionLambda LAM [s e] transitionResult
    inputStream ESE unitT
    toggleMachine SM transitionLambda false [inputStream] [] [receiveFx]

## Stream kinds

External streams (`ESE`) receive events from the host (or, with the
Q-046 source binding in canonical dag-json, from an IO-backed feeder
draining a streaming handle); internal streams (`ESI`) wire machines to
each other inside a group; output streams (`ESO`) carry a machine's
emissions outward. The optional `bufferSize` / `overflowPolicy` /
`consumerMode` arguments tune the bounded-queue behavior of the async
group runtime; omit them for the defaults.
