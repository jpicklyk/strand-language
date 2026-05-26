```python
from dataclasses import dataclass


@dataclass(frozen=True)
class PositiveInt:
    value: int

    def __post_init__(self) -> None:
        if not (self.value > 0):
            raise ValueError(f"PositiveInt invariant violated: {self.value} is not > 0")


def main() -> None:
    n = PositiveInt(5)
    print(n)


if __name__ == "__main__":
    main()
```
