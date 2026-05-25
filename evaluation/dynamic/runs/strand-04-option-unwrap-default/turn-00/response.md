```layer-a
@v=1 root=result
someCase SCS "Some" _
noneCase SCS "None" _
optionT SUM [someCase noneCase]
someValue SV optionT "Some" 42
result WHEN someValue optionT "Some(n) -> n | None -> 0"
```
