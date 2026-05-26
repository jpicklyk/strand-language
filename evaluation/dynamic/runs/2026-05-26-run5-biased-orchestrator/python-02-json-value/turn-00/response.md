```python
from dataclasses import dataclass
from typing import Union


@dataclass(frozen=True)
class JsonNull:
    pass


@dataclass(frozen=True)
class JsonBool:
    value: bool


@dataclass(frozen=True)
class JsonNumber:
    value: int


@dataclass(frozen=True)
class JsonString:
    value: str


JsonValue = Union[JsonNull, JsonBool, JsonNumber, JsonString]


def main() -> None:
    value: JsonValue = JsonNumber(42)
    print(value)


if __name__ == "__main__":
    main()
```
