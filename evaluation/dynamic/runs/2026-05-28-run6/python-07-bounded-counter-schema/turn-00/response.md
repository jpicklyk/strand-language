```python
from dataclasses import dataclass


@dataclass(frozen=True)
class PositiveInt:
    value: int

    def __post_init__(self) -> None:
        if not (self.value > 0):
            raise ValueError(
                f"PositiveInt invariant violated: expected x > 0, got {self.value}"
            )


def identity(x: PositiveInt) -> PositiveInt:
    return x


def main() -> None:
    result: PositiveInt = identity(PositiveInt(5))
    print(result)


if __name__ == "__main__":
    main()
```
