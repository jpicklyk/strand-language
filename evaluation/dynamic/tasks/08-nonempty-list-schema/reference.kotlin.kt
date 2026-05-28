sealed class IntList {
    object Nil : IntList()
    data class Cons(val head: Int, val tail: IntList) : IntList()
}

data class NonEmptyIntList(val list: IntList) {
    init {
        require(list is IntList.Cons) { "NonEmptyIntList requires at least one element" }
    }
}

fun main() {
    val ne = NonEmptyIntList(IntList.Cons(1, IntList.Cons(2, IntList.Nil)))
    println(ne)
}
