```python
def filesystem_write(path: str) -> int:
    # Stub: returns 0 bytes written, matching the reference's no-op behaviour.
    _ = path
    return 0


def main() -> None:
    written: int = filesystem_write("/tmp/strand-eval.log")
    result: int = written + 1
    print(result)


if __name__ == "__main__":
    main()
```
