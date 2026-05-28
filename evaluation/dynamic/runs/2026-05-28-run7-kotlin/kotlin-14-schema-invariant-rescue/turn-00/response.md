```kotlin
data class PositiveInt(val value: Int) {
    init { require(value > 0) { "PositiveInt requires value > 0, got $value" } }
}

fun main() {
    val identity: (PositiveInt) -> PositiveInt = { p -> p }
    val result = identity(PositiveInt(1))
    println(result.value)
}
```
