```python
from dataclasses import dataclass
from typing import Union


@dataclass(frozen=True)
class Some:
    value: int


@dataclass(frozen=True)
class NoneCase:
    pass


Option = Union[Some, NoneCase]


def unwrap_or_default(opt: Option) -> int:
    match opt:
        case Some(value=n):
            return n
        case NoneCase():
            return 0


def main() -> None:
    print(unwrap_or_default(Some(42)))


if __name__ == "__main__":
    main()
```
