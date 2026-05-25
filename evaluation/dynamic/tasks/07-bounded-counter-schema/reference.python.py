from dataclasses import dataclass


@dataclass(frozen=True)
class PositiveInt:
    value: int

    def __post_init__(self) -> None:
        if self.value <= 0:
            raise ValueError(f"PositiveInt requires value > 0, got {self.value}")


def main() -> None:
    p = PositiveInt(5)
    print(p)


if __name__ == "__main__":
    main()
