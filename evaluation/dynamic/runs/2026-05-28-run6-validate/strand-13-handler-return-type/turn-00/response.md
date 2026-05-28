```layer-a
@v=1 root=program
nowDecl EFD nowFx []
nowCall APP now [] _ [nowDecl]
bodyExpr APP add [nowCall 1]
handleT FNT [] intT
handleLam LAM [] 99 [nowFx]
program H nowFx handleLam bodyExpr
```
