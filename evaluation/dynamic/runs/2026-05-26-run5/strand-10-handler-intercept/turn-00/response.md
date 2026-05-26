```layer-a
@v=1 root=outerH
timeNowFx EFC "Time.Now"
nowFnT FNT [] intT [timeNowFx]
nowFn FN "strand-builtin:Time.Now" nowFnT [timeNowFx]
body APP nowFn []
innerHandle LAM [] 2
outerHandle LAM [] 1
innerH H timeNowFx innerHandle body
outerH H timeNowFx outerHandle innerH
```
