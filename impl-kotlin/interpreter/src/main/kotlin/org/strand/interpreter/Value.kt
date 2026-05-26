package org.strand.interpreter

import org.strand.core.Node
import org.strand.core.NodeId

/**
 * Runtime values produced by the tree-walking interpreter.
 *
 * Layer 1 has no effects, no IO, no foreign nodes, no recursion (Fixpoint
 * is a Layer beyond). Closures capture their environment lexically; mutation
 * is not exposed.
 */
sealed class Value {
    data class IntV(val v: Long) : Value()
    data class FloatV(val v: Double) : Value()
    data class StringV(val v: String) : Value()
    data class BoolV(val v: Boolean) : Value()
    object UnitV : Value() {
        override fun toString(): String = "()"
    }
    data class BytesV(val v: ByteArray) : Value() {
        override fun equals(other: Any?): Boolean = other is BytesV && v.contentEquals(other.v)
        override fun hashCode(): Int = v.contentHashCode()
    }

    /**
     * A closure: a Lambda node together with the environment captured at its
     * definition site. Parameter binders are the ParameterDecl NodeIds of the
     * lambda; calling the closure extends the env with arg values bound to
     * those ids.
     */
    data class Closure(
        val lambda: Node.Lambda,
        val env: Map<NodeId, Value>,
        val self: NodeId
    ) : Value()

    /**
     * A foreign callable: a ForeignNode evaluated to a value, ready to be
     * applied. Calling it dispatches to the [target]-named builtin in the
     * runtime's [org.strand.interpreter.Builtins] registry; the [effects]
     * are checked against the calling capability context first.
     */
    data class ForeignFn(
        val node: Node.ForeignNode,
        val self: NodeId
    ) : Value()

    /**
     * A fixed-point callable: a Fixpoint evaluated to a value, ready to be
     * applied. When called with arguments, the runtime evaluates the body
     * Lambda with its first parameter bound to *this same value*, supplying
     * the recursive call slot.
     *
     * Equality is intentionally by identity (the underlying [bodyLambda]
     * NodeId plus the captured [env] reference): two FixpointFns are
     * "equal" only if they refer to the same body and captured environment.
     * This is good enough because FixpointFn is an internal runtime value
     * that is never compared by user code in Layer 5 step 2.
     */
    data class FixpointFn(
        val fixpoint: Node.Fixpoint,
        val bodyLambda: Node.Lambda,
        val env: Map<NodeId, Value>,
        val self: NodeId
    ) : Value()

    /** A constructed product (record) value. */
    data class ProductV(val fields: Map<String, Value>) : Value()

    /** A constructed sum (variant) value. */
    data class SumV(val case: String, val payload: Value?) : Value()

    /**
     * An opaque runtime handle to an OS resource (file descriptor,
     * socket, process, ...). The [id] is a counter-minted long; the
     * [kind] tags the resource family so per-resource builtins can
     * validate at dispatch ("socket" vs "process" vs "file"). The
     * runtime [ResourceTable] holds the actual JVM-side object.
     *
     * Resource values never enter the canonical store — they're
     * ephemeral OS state, not content-addressable. The Strand-level
     * surface type for a resource is currently `Int` (Layer 4 step
     * 2 design call: opaque-handle representation, with the runtime
     * doing the per-kind dispatch on this Resource wrapping).
     */
    data class Resource(val id: Long, val kind: String) : Value()

    /**
     * Opaque persistent map value. Backed by a
     * [kotlinx.collections.immutable.PersistentMap]; the per-operation
     * `Map.*` builtins (Map.Empty / Get / Put / Remove / Has / Size /
     * Keys / Values / Fold) preserve persistence via path-copy on
     * writes.
     *
     * **Surface-type caveat.** Strand has no parametric `Map<K, V>`
     * primitive today. Agents declare `Map.*` builtin types using
     * `bytesT` as the surface placeholder for the Map argument /
     * result (mirrors the opaque-handle pattern Resource uses for
     * sockets/processes). The runtime checks at dispatch that the
     * value is actually a MapV. A first-class parametric Map type
     * is a future node-algebra extension.
     *
     * Equality is by content (PersistentMap's equals walks the
     * structure). Two Maps with the same key/value pairs in any
     * insertion order compare equal.
     *
     * MapV values never enter the canonical store — they're runtime
     * values like Closure / FixpointFn / Resource. Programs that
     * need to persist a Map across runs serialize it through an
     * association list `List<{key, value}>` shape (Map.Entries /
     * inverse construction).
     */
    data class MapV(val entries: kotlinx.collections.immutable.PersistentMap<Value, Value>) : Value()
}
