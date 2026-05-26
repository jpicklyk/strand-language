```layer-a
@v=1 root=outerHandler
nowFnT FNT [] intT
nowBuiltin FN "strand-builtin:Time.Now" nowFnT [nowFx]
body APP nowBuiltin []
innerHandleFn LAM [] 2
innerHandler H nowFx innerHandleFn body
outerHandleFn LAM [] 1
outerHandler H nowFx outerHandleFn innerHandler
```
