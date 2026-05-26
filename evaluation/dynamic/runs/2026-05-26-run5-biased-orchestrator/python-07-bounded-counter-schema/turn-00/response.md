```python
from dataclasses import dataclass


@dataclass(frozen=True)
class PositiveInt:
    value: int

    def __post_init__(self) -> None:
        if not self.value > 0:
            raise ValueError(f"PositiveInt invariant violated: {self.value}")


def main() -> None:
    v: PositiveInt = PositiveInt(5)
    print(v.value)


if __name__ == "__main__":
    main()
```
