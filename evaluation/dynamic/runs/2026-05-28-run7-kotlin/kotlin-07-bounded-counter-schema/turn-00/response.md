```kotlin
data class PositiveInt(val value: Int) {
    init { require(value > 0) { "PositiveInt requires value > 0, got $value" } }
}

fun main() {
    val identity: (PositiveInt) -> PositiveInt = { x -> x }
    val result = identity(PositiveInt(5))
    println(result.value)
}
```
