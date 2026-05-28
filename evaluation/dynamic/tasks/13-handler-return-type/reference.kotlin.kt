// Strand probes HandlerSignatureMismatch: a Handler whose handle
// lambda's return type differs from the intercepted function. Kotlin
// has no effect-handler primitive; the parallel uses a stack-based
// handler shim with a typed return (Int).
private val handlerStack: MutableList<() -> Int> = mutableListOf()

fun now(): Int {
    if (handlerStack.isEmpty()) error("Time.Now requested but no handler installed")
    return handlerStack.last()()
}

fun <R> withHandler(handler: () -> Int, body: () -> R): R {
    handlerStack.add(handler)
    try { return body() } finally { handlerStack.removeAt(handlerStack.size - 1) }
}

fun body(): Int = now() + 1

fun main() {
    val result = withHandler({ 99 }) { body() }
    println(result)
}
