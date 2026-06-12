// Strand probes UnboundRecursiveSelf at recursive-value construction
// inside the Fixpoint body (append rebuilds Cons cells). Kotlin's
// sealed-class recursion has no inner/outer discipline.
sealed class LinkedList
object Nil : LinkedList()
data class Cons(val head: Int, val tail: LinkedList) : LinkedList()

fun append(xs: LinkedList, ys: LinkedList): LinkedList = when (xs) {
    is Nil -> ys
    is Cons -> Cons(xs.head, append(xs.tail, ys))
}

fun listSum(zs: LinkedList): Int = when (zs) {
    is Nil -> 0
    is Cons -> zs.head + listSum(zs.tail)
}

fun main() {
    val list12 = Cons(1, Cons(2, Nil))
    val list3 = Cons(3, Nil)
    println(listSum(append(list12, list3)))
}
