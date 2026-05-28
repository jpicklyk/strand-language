```kotlin
sealed class Event {
    object Increment : Event()
    object Decrement : Event()
    object Reset : Event()
}

data class StepResult(val state: Int, val outputs: List<Nothing> = emptyList())

class CounterMachine {
    var state: Int = 0

    fun step(event: Event): StepResult {
        state = when (event) {
            is Event.Increment -> state + 1
            is Event.Decrement -> state - 1
            is Event.Reset -> 0
        }
        return StepResult(state)
    }
}

fun main() {
    val machine = CounterMachine()
    val events = listOf(
        Event.Increment,
        Event.Increment,
        Event.Decrement,
        Event.Reset,
        Event.Increment
    )
    for (event in events) {
        machine.step(event)
    }
    println(machine.state)
}
```
