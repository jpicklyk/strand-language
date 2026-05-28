```kotlin
data class MachineResult(val state: Boolean, val outputs: List<Unit>)

class ToggleMachine {
    var state: Boolean = false
        private set

    fun step(event: Unit): MachineResult {
        state = !state
        return MachineResult(state, emptyList())
    }
}

fun main() {
    val machine = ToggleMachine()
    val events: List<Unit> = listOf(Unit, Unit, Unit)
    for (event in events) {
        machine.step(event)
    }
    println(machine.state)
}
```
