from dataclasses import dataclass
from typing import Tuple


@dataclass(frozen=True)
class TransitionResult:
    state: bool
    outputs: Tuple[()] = ()


def transition(state: bool, event: None) -> TransitionResult:
    return TransitionResult(state=not state)


def run_machine(events: list[None]) -> bool:
    state = False
    for e in events:
        state = transition(state, e).state
    return state


def main() -> None:
    print(run_machine([None, None, None]))


if __name__ == "__main__":
    main()
