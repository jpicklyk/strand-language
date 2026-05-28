```python
def factorial(n: int) -> int:
    match n:
        case 0:
            return 1
        case _:
            return n * factorial(n - 1)


def main() -> None:
    print(factorial(5))


if __name__ == "__main__":
    main()
```
