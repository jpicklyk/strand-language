```python
from dataclasses import dataclass
from typing import Union


@dataclass(frozen=True)
class Some:
    value: int


@dataclass(frozen=True)
class NoneOpt:
    pass


Option = Union[Some, NoneOpt]


def unwrap_or_default(opt: Option) -> int:
    match opt:
        case Some(value=n):
            return n
        case NoneOpt():
            return 0


def main() -> None:
    value: Option = Some(42)
    print(unwrap_or_default(value))


if __name__ == "__main__":
    main()
```
