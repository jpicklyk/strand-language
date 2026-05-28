```layer-a
@v=1 root=program
handleT FNT [] intT
handleLam LAM [] 99
body APP add [(APP now [] _ [(EFD nowFx [])]) 1]
program H nowFx handleLam body
```
