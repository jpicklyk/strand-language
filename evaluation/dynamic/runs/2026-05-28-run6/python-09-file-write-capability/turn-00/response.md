```python
def filesystem_write(path: str) -> int:
    # Reference stub: returns 0 bytes written, mirroring the Strand
    # Filesystem.Write builtin which is a no-op stub in the reference
    # implementation.
    return 0


def int_add(a: int, b: int) -> int:
    return a + b


def main() -> None:
    bytes_written: int = filesystem_write("/tmp/strand-eval.log")
    result: int = int_add(bytes_written, 1)
    print(result)


if __name__ == "__main__":
    main()
```
