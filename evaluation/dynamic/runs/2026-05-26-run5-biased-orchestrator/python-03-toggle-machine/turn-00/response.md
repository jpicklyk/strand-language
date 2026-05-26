```python
from dataclasses import dataclass


@dataclass(frozen=True)
class ToggleOutputs:
    pass


@dataclass(frozen=True)
class TransitionResult:
    state: bool
    outputs: ToggleOutputs


def transition(state: bool, event: None) -> TransitionResult:
    return TransitionResult(state=not state, outputs=ToggleOutputs())


def main() -> None:
    state: bool = False
    events: list[None] = [None, None, None]
    for e in events:
        result = transition(state, e)
        state = result.state
    print(state)


if __name__ == "__main__":
    main()
```
