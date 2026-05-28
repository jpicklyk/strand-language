sealed class CounterEvent {
    object Increment : CounterEvent()
    object Decrement : CounterEvent()
    object Reset : CounterEvent()
}

fun transition(state: Int, event: CounterEvent): Int = when (event) {
    CounterEvent.Increment -> state + 1
    CounterEvent.Decrement -> state - 1
    CounterEvent.Reset -> 0
}

fun runMachine(events: List<CounterEvent>): Int {
    var state = 0
    for (e in events) state = transition(state, e)
    return state
}

fun main() {
    val events = listOf(
        CounterEvent.Increment,
        CounterEvent.Increment,
        CounterEvent.Decrement,
        CounterEvent.Reset,
        CounterEvent.Increment,
    )
    println(runMachine(events))
}
