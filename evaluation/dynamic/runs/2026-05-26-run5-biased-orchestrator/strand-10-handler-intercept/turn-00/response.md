```layer-a
@v=1 root=outerHandler
zeroArgFnT FNT [] intT
innerHandle LAM [] 2
outerHandle LAM [] 1
body APP now []
innerHandler H nowFx innerHandle body
outerHandler H nowFx outerHandle innerHandler
```
