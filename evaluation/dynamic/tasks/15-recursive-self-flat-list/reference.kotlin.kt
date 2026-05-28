// Strand probes UnboundRecursiveSelf when constructing values of a
// recursive sum type. Kotlin uses a sealed class hierarchy with a
// recursive data class field.
sealed class IntList {
    object Nil : IntList()
    data class Cons(val head: Int, val tail: IntList) : IntList()
}

fun length(xs: IntList): Int = when (xs) {
    is IntList.Cons -> 1 + length(xs.tail)
    IntList.Nil -> 0
}

fun main() {
    val xs: IntList = IntList.Cons(7, IntList.Cons(8, IntList.Nil))
    println(length(xs))
}
