sealed class Option<out T> {
    object None : Option<Nothing>()
    data class Some<T>(val value: T) : Option<T>()
}

fun unwrapOrZero(opt: Option<Int>): Int = when (opt) {
    is Option.Some -> opt.value
    Option.None -> 0
}

fun main() {
    val v: Option<Int> = Option.Some(42)
    println(unwrapOrZero(v))
}
