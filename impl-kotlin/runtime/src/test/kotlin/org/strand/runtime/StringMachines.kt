package org.strand.runtime

import org.strand.core.ConsumerMode
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.OverflowPolicy
import org.strand.core.Primitive
import org.strand.core.StreamKind
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Value

/**
 * Programmatic String-event machine builders shared by the actor-runtime
 * robustness tests (halted-consumer backpressure, supervision, snapshot
 * atomicity, dynamic spawn). Every machine has state `String` and consumes
 * `String` events, so they compose into pipelines over one stream type.
 */
internal class StringMachines(val store: NodeStore = NodeStore()) {
    val strT: NodeId = store.add(Node.PrimitiveType(Primitive.String))
    val intT: NodeId = store.add(Node.PrimitiveType(Primitive.Int))
    private val emptyT: NodeId = store.add(Node.ProductType(emptyList()))
    private val someCase = store.add(Node.SumTypeCase("Some", strT))
    private val noneCase = store.add(Node.SumTypeCase("None", null))
    private val optionStr = store.add(Node.SumType(listOf(someCase, noneCase)))
    private val output0Field = store.add(Node.ProductTypeField("output_0", optionStr))
    private val batchT = store.add(Node.ProductType(listOf(output0Field)))
    val fsWriteFx: NodeId = store.add(Node.EffectCategory("Filesystem.Write", parameters = listOf(strT)))

    fun externalStream(): NodeId =
        store.add(Node.EventStream(eventType = strT, streamKind = StreamKind.External))

    fun internalStream(
        mode: ConsumerMode? = null,
        bufferSize: Int? = null,
        policy: OverflowPolicy? = null,
    ): NodeId = store.add(Node.EventStream(
        eventType = strT,
        streamKind = StreamKind.Internal,
        consumerMode = mode,
        bufferSize = bufferSize,
        overflowPolicy = policy,
    ))

    fun outputStream(): NodeId =
        store.add(Node.EventStream(eventType = strT, streamKind = StreamKind.Output))

    private fun resultType(outputsT: NodeId): NodeId {
        val sf = store.add(Node.ProductTypeField("state", strT))
        val of = store.add(Node.ProductTypeField("outputs", outputsT))
        return store.add(Node.ProductType(listOf(sf, of)))
    }

    /** `\(s, e) -> {state: e, outputs: {output_0: Some(e)}}` — forwards each event. */
    fun forwarder(input: NodeId, output: NodeId): NodeId {
        val sP = store.add(Node.ParameterDecl("s", strT))
        val eP = store.add(Node.ParameterDecl("e", strT))
        val eRef = store.add(Node.VarRef(eP))
        val someE = store.add(Node.SumValue(optionStr, "Some", eRef))
        val o0 = store.add(Node.ProductFieldValue("output_0", someE))
        val batch = store.add(Node.ProductValue(batchT, listOf(o0)))
        val body = store.add(Node.ProductValue(resultType(batchT), listOf(
            store.add(Node.ProductFieldValue("state", eRef)),
            store.add(Node.ProductFieldValue("outputs", batch)),
        )))
        val lam = store.add(Node.Lambda(parameters = listOf(sP, eP), body = body))
        return store.add(Node.StateMachine(
            transitionFn = lam,
            initialState = store.add(Node.StringLit("")),
            inputStreams = listOf(input),
            outputStreams = listOf(output),
            effects = emptyList(),
        ))
    }

    /** `\(s, e) -> {state: e, outputs: {}}` — records the last event, emits nothing. */
    fun sink(input: NodeId): NodeId = sinkWithBody(input) { eRef -> eRef }

    /**
     * A sink whose new state is `String.FromInt(Test.EffectfulNoOp(e))` under
     * a `Filesystem.Write{e}` refinement: granted [okOnlyGrant], an "ok" event
     * transitions (state becomes "0") and any other event is denied.
     */
    fun writer(input: NodeId): NodeId {
        val writeT = store.add(Node.FunctionType(parameters = listOf(strT), result = intT, effects = listOf(fsWriteFx)))
        val write = store.add(Node.ForeignNode(
            target = "strand-builtin:Test.EffectfulNoOp", foreignType = writeT, effects = listOf(fsWriteFx),
        ))
        val toStrT = store.add(Node.FunctionType(parameters = listOf(intT), result = strT))
        val toStr = store.add(Node.ForeignNode(target = "strand-builtin:String.FromInt", foreignType = toStrT))
        return sinkWithBody(input, effects = listOf(fsWriteFx)) { eRef ->
            val decl = store.add(Node.EffectDecl(effectType = fsWriteFx, parameters = listOf(eRef)))
            val call = store.add(Node.Application(
                function = write, arguments = listOf(eRef), effectInstances = listOf(decl),
            ))
            store.add(Node.Application(function = toStr, arguments = listOf(call)))
        }
    }

    /**
     * A sink whose new state is `<target>(e)` for a `(String) -> String`
     * foreign target — used with a test-overlay builtin that throws a raw JVM
     * exception to exercise group supervision.
     */
    fun foreignSink(input: NodeId, target: String): NodeId {
        val fT = store.add(Node.FunctionType(parameters = listOf(strT), result = strT))
        val f = store.add(Node.ForeignNode(target = target, foreignType = fT))
        return sinkWithBody(input) { eRef -> store.add(Node.Application(function = f, arguments = listOf(eRef))) }
    }

    fun sinkWithBody(input: NodeId, effects: List<NodeId> = emptyList(), newState: (NodeId) -> NodeId): NodeId {
        val sP = store.add(Node.ParameterDecl("s", strT))
        val eP = store.add(Node.ParameterDecl("e", strT))
        val eRef = store.add(Node.VarRef(eP))
        val empty = store.add(Node.ProductValue(emptyT, emptyList()))
        val body = store.add(Node.ProductValue(resultType(emptyT), listOf(
            store.add(Node.ProductFieldValue("state", newState(eRef))),
            store.add(Node.ProductFieldValue("outputs", empty)),
        )))
        val lam = store.add(Node.Lambda(parameters = listOf(sP, eP), body = body, effects = effects))
        return store.add(Node.StateMachine(
            transitionFn = lam,
            initialState = store.add(Node.StringLit("")),
            inputStreams = listOf(input),
            outputStreams = emptyList(),
            effects = effects,
        ))
    }

    fun okOnlyGrant(): CapabilitySet = CapabilitySet(mapOf(
        fsWriteFx to listOf(CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("ok"))))),
    ))
}
