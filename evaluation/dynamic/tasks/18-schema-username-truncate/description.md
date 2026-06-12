# Task 18 — Username under a ShortName schema

Declare a `ShortName` Schema over `String` whose invariant accepts
only strings of at most 8 characters (prelude `strLen` and `le`).

Derive a username at runtime by concatenating the team prefix
`"strand"` with the role suffix `"_agent"` (prelude `concat`).
Usernames keep only their first 8 characters when the raw
concatenation is longer (prelude `subStr`; its arguments are the
string, the start index, and the end index, end-exclusive).

Flow the username through an identity lambda whose parameter type is
the `ShortName` schema, and return the length of the result as the
program value: `8`.

The reference implementation must:
- Declare an `Invariant` whose body is a pure lambda
  `(s: String) -> Bool` checking the length bound.
- Declare the `ShortName` Schema over `String` carrying that
  invariant.
- Build the username from the two literals at runtime and flow it
  through `(p: ShortName) -> ShortName` identity.
- Return `strLen` of the identity lambda's result.

The Python parallel uses a `ShortName` dataclass whose
`__post_init__` raises `ValueError` when the wrapped string exceeds
8 characters, and prints the final length `8`.
