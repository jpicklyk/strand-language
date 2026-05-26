```python
from dataclasses import dataclass


@dataclass(frozen=True)
class PositiveInt:
    value: int

    def __post_init__(self) -> None:
        if not (self.value > 0):
            raise ValueError(f"PositiveInt invariant violated: {self.value} > 0 is false")


def identity(x: PositiveInt) -> PositiveInt:
    return x


def main() -> None:
    v: PositiveInt = identity(PositiveInt(5))
    print(v)


if __name__ == "__main__":
    main()
```
