```layer-a
@v=1 root=outerHandler
nowDecl EFD nowFx []
bodyCall APP now [] _ [nowDecl]
innerHandleLam LAM [] 2
innerHandler H nowFx innerHandleLam bodyCall
outerHandleLam LAM [] 1
outerHandler H nowFx outerHandleLam innerHandler
```
