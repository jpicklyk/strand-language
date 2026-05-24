package org.strand.runtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.selects.select
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value

/**
 * Per-machine coroutine actor (Layer 6 step 2).
 *
 * Each actor runs in its own coroutine inside a [MachineGroup]. The loop:
 *
 *  1. `select` across the machine's input channels (FIFO per stream,
 *     nondeterministic merge across streams — Q-009 default).
 *  2. Wrap the received event in the synthesized InputEvent sum case when
 *     the machine has multiple input streams; pass through unchanged for
 *     single-stream machines (preserves step 1 semantics).
 *  3. Record the wrapped event when the group has [MachineGroup.recordInputs]
 *     enabled.
 *  4. Apply the cached transition function via [Interpreter.applyCallable].
 *  5. Decompose the result into `(newState, outputs)`. Two output shapes are
 *     supported per the proposal: OutputBatch (step 1's `{output_0: Option,
 *     ...}` product, preserved for single-stream machines) and tagged-list
 *     `(State, List<TaggedOutput>)` (step 2's recursive-list form for
 *     multi-stream or explicit use). Dispatch detects which shape by
 *     examining the `outputs` field type.
 *  6. Send each emitted payload to the corresponding output channel.
 *  7. When all input channels close, halt and close output channels.
 *
 * The interpreter call is synchronous — transitions are pure by spec
 * (ADR-007); only the actor loop suspends. This keeps `Interpreter.eval`
 * and `Interpreter.applyCallable` unchanged from step 1 and lets the
 * verifier reason about transitions exactly as today.
 */
internal class MachineActor(
    private val instance: MachineInstance,
    private val interpreter: Interpreter,
) {
    /**
     * The input stream NodeId → position-in-`inputStreams` map. Used when
     * wrapping a payload into the synthesized InputEvent sum case for
     * multi-stream machines. Computed once at actor construction.
     */
    private val inputStreamIndex: Map<NodeId, Int> =
        instance.node.inputStreams.withIndex().associate { (i, id) -> id to i }

    /** The main actor loop. Runs until all input channels close or [MachineInstance.halted] becomes true. */
    suspend fun run() {
        val openChannels = instance.inputChannels.toMutableMap()
        try {
            while (openChannels.isNotEmpty() && !instance.halted) {
                val pick = selectNext(openChannels)
                if (pick == null) {
                    // All open channels closed in the same select — done.
                    break
                }
                val (streamId, channelResult) = pick
                if (channelResult.isClosed) {
                    openChannels.remove(streamId)
                    continue
                }
                val payload = channelResult.getOrThrow()
                val event = wrapEvent(streamId, payload)
                instance.recorder?.record(event)
                stepOnce(event)
            }
            instance.halted = true
        } finally {
            // Close output channels so any downstream consumers see EOF.
            for (channel in instance.outputChannels.values) {
                channel.close()
            }
        }
    }

    private suspend fun selectNext(
        openChannels: Map<NodeId, Channel<Value>>,
    ): Pair<NodeId, ChannelResult<Value>>? = select {
        for ((streamId, channel) in openChannels) {
            channel.onReceiveCatching { result -> streamId to result }
        }
    }

    /**
     * For multi-stream machines, wrap the payload in a SumValue with case
     * `stream_i` (matching the verifier's synthesized InputEvent sum
     * naming). For single-stream machines, return the payload unchanged so
     * the existing step-1 corpus semantics is preserved.
     */
    private fun wrapEvent(streamId: NodeId, payload: Value): Value {
        if (instance.node.inputStreams.size <= 1) return payload
        val index = inputStreamIndex.getValue(streamId)
        return Value.SumV(case = "stream_$index", payload = payload)
    }

    /**
     * Single step: apply the cached transition function, decompose the
     * result, update [MachineInstance.currentState], dispatch outputs.
     * Mirrors [StateMachineRuntime.stepOnce] but writes outputs to channels
     * instead of in-memory sinks, and supports both OutputBatch and
     * tagged-list result shapes.
     */
    private suspend fun stepOnce(event: Value) {
        val result = interpreter.applyCallable(
            fn = instance.transitionFnValue,
            args = listOf(instance.currentState, event),
            capabilities = instance.capabilities,
        )
        val resultProduct = result as? Value.ProductV
            ?: error(
                "transition function returned a non-product value " +
                    "(${result::class.simpleName}); expected {state, outputs}. " +
                    "Verifier should have rejected this graph."
            )
        val newState = resultProduct.fields["state"]
            ?: error("transition function result missing 'state' field; got fields ${resultProduct.fields.keys}")
        val outputs = resultProduct.fields["outputs"]
            ?: error("transition function result missing 'outputs' field; got fields ${resultProduct.fields.keys}")
        instance.currentState = newState

        when (outputs) {
            is Value.ProductV -> dispatchOutputBatch(outputs)
            is Value.SumV -> dispatchTaggedList(outputs)
            else -> error(
                "transition function 'outputs' field has unexpected shape " +
                    "(${outputs::class.simpleName}); expected ProductV (OutputBatch) " +
                    "or SumV (tagged-list Cons/Nil)."
            )
        }
    }

    private suspend fun dispatchOutputBatch(outputBatch: Value.ProductV) {
        for ((i, outputStreamId) in instance.node.outputStreams.withIndex()) {
            val slotName = "output_$i"
            val slotValue = outputBatch.fields[slotName] as? Value.SumV
                ?: error(
                    "OutputBatch missing slot '$slotName' (or wrong type); " +
                        "got fields ${outputBatch.fields.keys}."
                )
            when (slotValue.case) {
                "Some" -> {
                    val payload = slotValue.payload
                        ?: error("Some output slot '$slotName' has null payload")
                    instance.outputChannels.getValue(outputStreamId).send(payload)
                }
                "None" -> Unit
                else -> error(
                    "OutputBatch slot '$slotName' has unexpected case " +
                        "'${slotValue.case}'; expected Some or None."
                )
            }
        }
    }

    /**
     * Walk a tagged-output list (`Nil` / `Cons(head: TaggedOutput, tail)`),
     * dispatching each head's payload to the output channel named by its
     * `output_i` case. The list is recursive; we iterate by chasing `tail`.
     */
    private suspend fun dispatchTaggedList(outputs: Value.SumV) {
        var cursor: Value = outputs
        while (true) {
            val sum = cursor as? Value.SumV
                ?: error("tagged-output list expected SumV, got ${cursor::class.simpleName}")
            when (sum.case) {
                "Nil" -> return
                "Cons" -> {
                    val cons = sum.payload as? Value.ProductV
                        ?: error("tagged-output Cons payload must be a product {head, tail}")
                    val head = cons.fields["head"]
                        ?: error("tagged-output Cons missing 'head' field")
                    val tail = cons.fields["tail"]
                        ?: error("tagged-output Cons missing 'tail' field")
                    dispatchTaggedOutput(head as? Value.SumV
                        ?: error("tagged-output head must be a SumV (output_i tag)"))
                    cursor = tail
                }
                else -> error(
                    "tagged-output list has unexpected case '${sum.case}'; expected Nil or Cons."
                )
            }
        }
    }

    /**
     * Dispatch one TaggedOutput value. The case name is `output_i` where
     * `i` is the position in the machine's `outputStreams`; the payload is
     * the actual value to emit on that stream.
     */
    private suspend fun dispatchTaggedOutput(tagged: Value.SumV) {
        val caseName = tagged.case
        require(caseName.startsWith("output_")) {
            "TaggedOutput case '$caseName' must start with 'output_'"
        }
        val index = caseName.removePrefix("output_").toIntOrNull()
            ?: error("TaggedOutput case '$caseName' has malformed index")
        require(index in instance.node.outputStreams.indices) {
            "TaggedOutput case '$caseName' index out of range " +
                "(machine has ${instance.node.outputStreams.size} output streams)"
        }
        val outputStreamId = instance.node.outputStreams[index]
        val payload = tagged.payload
            ?: error("TaggedOutput case '$caseName' has null payload")
        instance.outputChannels.getValue(outputStreamId).send(payload)
    }
}
