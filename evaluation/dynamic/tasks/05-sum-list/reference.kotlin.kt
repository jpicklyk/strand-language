sealed class IntList {
    object Nil : IntList()
    data class Cons(val head: Int, val tail: IntList) : IntList()
}

fun sum(xs: IntList): Int = when (xs) {
    is IntList.Cons -> xs.head + sum(xs.tail)
    IntList.Nil -> 0
}

fun main() {
    val xs: IntList = IntList.Cons(1, IntList.Cons(2, IntList.Cons(3, IntList.Nil)))
    println(sum(xs))
}
