```layer-a
@v=1 root=app
someCase SCS "Some" intT
noneCase SCS "None" _
optT SUM [someCase noneCase]
val SV optT "Some" 42
app WHEN val optT "Some(n) -> n | None -> 0"
```
