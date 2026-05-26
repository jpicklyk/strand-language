```layer-a
@v=1 root=app
someCase SCS "Some" intT
noneCase SCS "None" _
optT SUM [someCase noneCase]
unwrap LAM [opt:optT] (WHEN opt optT "Some(n) -> n | None -> 0")
app APP unwrap [(SV optT "Some" 42)]
```
