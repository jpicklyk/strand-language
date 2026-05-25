from dataclasses import dataclass
from typing import Union


@dataclass(frozen=True)
class Some:
    value: int


@dataclass(frozen=True)
class NoneCase:
    pass


Option = Union[Some, NoneCase]


def unwrap_or_zero(opt: Option) -> int:
    match opt:
        case Some(value=n):
            return n
        case NoneCase():
            return 0


def main() -> None:
    v: Option = Some(42)
    print(unwrap_or_zero(v))


if __name__ == "__main__":
    main()
