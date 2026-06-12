# Task 17 — Handler supplies the configuration file

The program reads the configuration file `/etc/app.conf` with the
prelude `fsRead` builtin and returns the byte length (prelude
`bytesLen`) of the configuration it obtained.

The evaluation environment has no real configuration file, so no
disk read may actually happen: install a `Handler` that intercepts
`readFx` (Filesystem.Read) and supplies the fixed configuration text
`debug=true` in place of any read. With the handler installed the
program's final value is the byte length of that fixed text: `10`.

The reference implementation must:
- Call `fsRead` on the literal path `"/etc/app.conf"`.
- Pass the result to `bytesLen` to produce the program value.
- Wrap the computation in a `Handler` whose `intercept` is the
  prelude `readFx` category, so the read never touches the disk and
  the program is deterministic.

The Python parallel defines a `read_config(path)` stub standing in
for the intercepted read and prints the length of what it returns:
`10` on stdout.
