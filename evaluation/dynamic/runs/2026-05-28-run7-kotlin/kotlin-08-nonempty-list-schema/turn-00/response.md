```kotlin
sealed class IntList {
    object Nil : IntList()
    data class Cons(val head: Int, val tail: IntList) : IntList()
}

data class NonEmptyList(val value: IntList) {
    init {
        require(invariant(value)) {
            "NonEmptyList requires non-empty list (Cons), got Nil"
        }
    }

    companion object {
        fun invariant(xs: IntList): Boolean = when (xs) {
            is IntList.Cons -> true
            is IntList.Nil -> false
        }
    }
}

fun identity(xs: NonEmptyList): NonEmptyList = xs

fun render(xs: IntList): String = when (xs) {
    is IntList.Nil -> "Nil"
    is IntList.Cons -> "Cons(${xs.head}, ${render(xs.tail)})"
}

fun main() {
    val one = IntList.Cons(1, IntList.Nil)
    val schemed = NonEmptyList(one)
    val result = identity(schemed)
    println(render(result.value))
}
```
