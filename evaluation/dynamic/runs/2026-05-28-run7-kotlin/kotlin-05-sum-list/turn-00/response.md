```kotlin
sealed class IntList
data class Cons(val head: Int, val tail: IntList) : IntList()
object Nil : IntList()

fun sum(list: IntList): Int = when (list) {
    is Cons -> list.head + sum(list.tail)
    is Nil -> 0
}

fun main() {
    val list = Cons(1, Cons(2, Cons(3, Nil)))
    println(sum(list))
}
```
