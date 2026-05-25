```python
def filesystem_write(path: str) -> int:
    # Stand-in for Filesystem.Write — the reference implementation
    # returns 0 (bytes written; a no-op stub). Python has no effect
    # system, so the "capability" is implicit at the call site.
    _ = path
    return 0


def main() -> None:
    result = filesystem_write("/tmp/strand-eval.log") + 1
    print(result)


if __name__ == "__main__":
    main()
```
