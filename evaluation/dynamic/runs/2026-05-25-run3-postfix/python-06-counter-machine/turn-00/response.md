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
class TransitionResult:
    state: int


def transition(state: int, event: Event) -> TransitionResult:
    match event:
        case Increment():
            return TransitionResult(state=state + 1)
        case Decrement():
            return TransitionResult(state=state - 1)
        case Reset():
            return TransitionResult(state=0)


def run_machine(events: list[Event]) -> int:
    state = 0
    for e in events:
        state = transition(state, e).state
    return state


def main() -> None:
    events: list[Event] = [Increment(), Increment(), Decrement(), Reset(), Increment()]
    print(run_machine(events))


if __name__ == "__main__":
    main()
```
