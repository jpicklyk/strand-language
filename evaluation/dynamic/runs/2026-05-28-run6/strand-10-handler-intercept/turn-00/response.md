```layer-a
@v=1 root=outerHandler
nowEf EFC "Time.Now"
nowFnT FNT [] intT [nowEf]
nowFn FN "strand-builtin:Time.Now" nowFnT [nowEf]
nowDecl EFD nowEf []
body APP nowFn [] _ [nowDecl]
innerHandle LAM [] 2
innerHandler H nowEf innerHandle body
outerHandle LAM [] 1
outerHandler H nowEf outerHandle innerHandler
```
