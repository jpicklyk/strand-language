```python
from dataclasses import dataclass
from typing import Union


@dataclass(frozen=True)
class Some:
    value: int


@dataclass(frozen=True)
class NoneT:
    pass


Option = Union[Some, NoneT]


def unwrap_or_default(opt: Option) -> int:
    match opt:
        case Some(n):
            return n
        case NoneT():
            return 0


def main() -> None:
    value: Option = Some(42)
    print(unwrap_or_default(value))


if __name__ == "__main__":
    main()
```
