package org.strand.interpreter

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime table of OS resources held by the interpreter — file handles,
 * sockets, processes. Layer 4 step 2 IO builtins mint a fresh id when
 * opening a resource, store the JVM-side object under that id, and
 * return a [Value.Resource] handle to the Strand program. Subsequent
 * builtins (read, send, close, wait) look up the resource by id.
 *
 * Resources are kept alive until explicitly closed via the appropriate
 * builtin (`Network.Close`, `Filesystem.CloseFile`, `Process.Kill`,
 * ...). Programs that fail to close leak the underlying OS resource —
 * acceptable for the prototype; future work could add weak-ref-driven
 * cleanup or scope-based RAII.
 *
 * The table is thread-safe (concurrent map + atomic counter) so
 * state-machine actor threads can share it. The id is monotonically
 * increasing and never reused within a JVM run; correlating an id to
 * an actual resource that's already been closed surfaces as a clean
 * lookup miss.
 *
 * Known resource kinds (each builtin registers its handles under a
 * stable kind string for runtime validation in [get]):
 *
 *   - "socket"             java.net.Socket (Net.Connect)
 *   - "process"            java.lang.Process (Process.Spawn)
 *   - "http-server"        Builtins.HttpServerHolder (Http.Listen)
 *   - "http-pending"       Builtins.HttpPending (Http.Accept)
 *   - "pinecone_index"     PineconeIndexHandle (Pinecone.Index.Open — Q-038)
 *   - "chroma_collection"  ChromaCollectionHandle (Chroma.Collection.Open — Q-038)
 */
object ResourceTable {
    private val table = ConcurrentHashMap<Long, Holder>()
    private val nextId = AtomicLong(1)

    private data class Holder(val kind: String, val obj: Any)

    /** Register [obj] as a resource of [kind]; return a fresh Value.Resource. */
    fun register(kind: String, obj: Any): Value.Resource {
        val id = nextId.getAndIncrement()
        table[id] = Holder(kind, obj)
        return Value.Resource(id, kind)
    }

    /**
     * Look up the underlying object by handle. Throws [IoFailure] if the
     * handle is unknown (closed or never registered) or if the kind
     * doesn't match — caller passes the expected kind for runtime
     * validation since the Strand-level type for handles is opaque Int.
     * The returned object is `Any`; callers cast to the concrete JVM
     * type they registered (e.g., `java.net.Socket` for kind="socket").
     * Cast failures surface as ClassCastException — that's a runtime
     * invariant bug, not a user error.
     */
    fun get(handle: Value.Resource, expectedKind: String): Any {
        if (handle.kind != expectedKind) {
            throw IoFailure(
                "resource-kind-mismatch",
                "expected $expectedKind resource handle, got ${handle.kind} (#${handle.id})"
            )
        }
        val holder = table[handle.id]
            ?: throw IoFailure(
                "resource-not-found",
                "handle #${handle.id} (kind=${handle.kind}) is not in the resource table; " +
                    "either it was never opened or has already been closed"
            )
        return holder.obj
    }

    /** Remove and return the resource at [handle.id]. Returns null if absent. */
    fun remove(handle: Value.Resource): Any? = table.remove(handle.id)?.obj

    /** Snapshot of open resource ids — for diagnostics and tests. */
    fun openIds(): Set<Long> = table.keys.toSet()

    /** Drop all resources without invoking close. Test-only escape hatch. */
    fun resetForTest() {
        table.clear()
        nextId.set(1)
    }
}

/**
 * Thrown by IO builtins when an operation fails (permission denied,
 * disk full, broken pipe, unknown handle, etc.). Per the Layer 4 step
 * 2 design call, IO failures are exceptions rather than Result-typed
 * values. The interpreter surfaces them as [InterpretError.IoFailure].
 */
class IoFailure(val kind: String, val detail: String) : RuntimeException("$kind: $detail")
