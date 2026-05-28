```python
from dataclasses import dataclass
from typing import Callable


@dataclass(frozen=True)
class PositiveInt:
    value: int

    def __post_init__(self) -> None:
        if not (self.value > 0):
            raise ValueError(
                f"SchemaInvariantViolation: PositiveInt requires x > 0, got {self.value}"
            )


def positive_int_invariant(x: int) -> bool:
    return x > 0


identity: Callable[[PositiveInt], PositiveInt] = lambda p: p


def main() -> None:
    smallest: PositiveInt = PositiveInt(1)
    result: PositiveInt = identity(smallest)
    print(result)


if __name__ == "__main__":
    main()
```
