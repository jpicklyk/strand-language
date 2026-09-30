package org.strand.interpreter

import org.strand.core.ResourceSource

/**
 * Resolves the registry's resource projections
 * ([org.strand.core.BuiltinEffectTable.resourceProjections]) against the
 * argument values of one dispatch. Shared by the tree-walking interpreter
 * and the bytecode VM so both take the refinement a capability is matched
 * against from the same place.
 *
 * Every source is read with the code the builtin itself uses to read its
 * argument (the URL parser of `Http.RequestFromUrl`, the handle lookup of
 * the vector providers), so the value checked is the value acted on. A
 * source that cannot be resolved (a missing argument, an argument of the
 * wrong shape, a URL that does not parse, a closed handle) yields null for
 * the whole projection: the builtin rejects the same arguments before it
 * performs anything, and the capability check falls back to what the graph
 * declared.
 */
object RegistryResources {

    /** The refinement values for [sources] under [args], or null when any source is unresolvable. */
    fun resolve(sources: List<ResourceSource>, args: List<Value>): List<Value>? {
        val out = ArrayList<Value>(sources.size)
        for (source in sources) out += resolveOne(source, args) ?: return null
        return out
    }

    private fun resolveOne(source: ResourceSource, args: List<Value>): Value? = when (source) {
        is ResourceSource.Arg -> args.getOrNull(source.index)
        is ResourceSource.Const -> Value.StringV(source.value)
        is ResourceSource.Field ->
            (args.getOrNull(source.index) as? Value.ProductV)?.fields?.get(source.name) as? Value.StringV
        is ResourceSource.UrlHost -> url(args, source.index)?.let { Value.StringV(it.host) }
        is ResourceSource.UrlPort -> url(args, source.index)?.let { Value.IntV(it.port.toLong()) }
        is ResourceSource.HandleStore ->
            (args.getOrNull(source.index) as? Value.Resource)?.let { storeNameOf(it) }?.let { Value.StringV(it) }
    }

    private fun url(args: List<Value>, index: Int): NetIo.HttpUrlParts? {
        val text = (args.getOrNull(index) as? Value.StringV)?.v ?: return null
        return try {
            NetIo.parseHttpUrl(text)
        } catch (_: IoFailure) {
            null
        }
    }

    /** The store a vector-store handle was opened on, or null for any other or closed handle. */
    private fun storeNameOf(handle: Value.Resource): String? = when (val held = ResourceTable.peek(handle)) {
        is PineconeIndexHandle -> held.config.indexName
        is ChromaCollectionHandle -> held.config.collectionName
        else -> null
    }
}
