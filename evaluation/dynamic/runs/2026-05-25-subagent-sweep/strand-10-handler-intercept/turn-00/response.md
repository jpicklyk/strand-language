```layer-a
@v=1 root=outerHandler
nowFn FN "strand-builtin:Time.Now" nowT [nowFx]
body APP nowFn []
innerHandle LAM [] 2
innerHandler H nowFx innerHandle body
outerHandle LAM [] 1
outerHandler H nowFx outerHandle innerHandler
```
