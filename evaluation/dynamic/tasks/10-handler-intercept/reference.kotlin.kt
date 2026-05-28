// Kotlin has no effect-handler primitive. Strand's Time.Now handler
// stack maps to a global mutable list scanned innermost-first.
private val handlerStack: MutableList<() -> Int> = mutableListOf()

fun now(): Int {
    if (handlerStack.isEmpty()) error("Time.Now requested but no handler installed")
    return handlerStack.last()()
}

fun <R> withHandler(handler: () -> Int, body: () -> R): R {
    handlerStack.add(handler)
    try { return body() } finally { handlerStack.removeAt(handlerStack.size - 1) }
}

fun body(): Int = now()

fun main() {
    val result = withHandler({ 1 }) {
        withHandler({ 2 }) {
            body()
        }
    }
    println(result)
}
