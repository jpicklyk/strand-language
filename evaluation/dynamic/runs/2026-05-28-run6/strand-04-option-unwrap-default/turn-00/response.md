```layer-a
@v=1 root=result
someCase SCS "Some" intT
noneCase SCS "None" _
optionT SUM [someCase noneCase]
val SV optionT "Some" 42
result WHEN val optionT "Some(n) -> n | None -> 0"
```
