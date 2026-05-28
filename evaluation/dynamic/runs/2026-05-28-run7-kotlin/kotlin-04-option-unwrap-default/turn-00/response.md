```kotlin
sealed class Option {
    data class Some(val value: Int) : Option()
    object None : Option()
}

fun unwrapOr(option: Option, default: Int): Int = when (option) {
    is Option.Some -> option.value
    is Option.None -> default
}

fun main() {
    val value: Option = Option.Some(42)
    println(unwrapOr(value, 0))
}
```
