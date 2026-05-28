```kotlin
// Nested effect handlers for Time.Now — innermost wins.
// The active-handler stack is a LIFO; now() consults the top of the stack.
// Outer handler returns 1, inner handler returns 2; the body calls now()
// while both are active, so the inner handler intercepts and the program
// prints 2.

val activeHandlers: ArrayDeque<() -> Int> = ArrayDeque()

fun now(): Int {
    val handler = activeHandlers.lastOrNull()
        ?: error("Time.Now requires a handler under empty capabilities")
    return handler()
}

fun <T> withHandler(handle: () -> Int, body: () -> T): T {
    activeHandlers.addLast(handle)
    try {
        return body()
    } finally {
        activeHandlers.removeLast()
    }
}

fun main() {
    val result = withHandler(handle = { 1 }) {
        withHandler(handle = { 2 }) {
            now()
        }
    }
    println(result)
}
```
