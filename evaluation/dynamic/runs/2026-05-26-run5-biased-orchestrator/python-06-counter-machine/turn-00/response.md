```python
from dataclasses import dataclass
from typing import Union


@dataclass(frozen=True)
class Increment:
    pass


@dataclass(frozen=True)
class Decrement:
    pass


@dataclass(frozen=True)
class Reset:
    pass


Event = Union[Increment, Decrement, Reset]


@dataclass(frozen=True)
class CounterOutputs:
    pass


@dataclass(frozen=True)
class TransitionResult:
    state: int
    outputs: CounterOutputs


def transition(state: int, event: Event) -> TransitionResult:
    match event:
        case Increment():
            return TransitionResult(state=state + 1, outputs=CounterOutputs())
        case Decrement():
            return TransitionResult(state=state - 1, outputs=CounterOutputs())
        case Reset():
            return TransitionResult(state=0, outputs=CounterOutputs())


def main() -> None:
    state: int = 0
    events: list[Event] = [Increment(), Increment(), Decrement(), Reset(), Increment()]
    for e in events:
        result = transition(state, e)
        state = result.state
    print(state)


if __name__ == "__main__":
    main()
```
