```kotlin
sealed class IntList
data class Cons(val head: Int, val tail: IntList) : IntList()
object Nil : IntList()

fun length(list: IntList): Int = when (list) {
    is Nil -> 0
    is Cons -> 1 + length(list.tail)
}

fun main() {
    val list = Cons(7, Cons(8, Nil))
    println(length(list))
}
```
