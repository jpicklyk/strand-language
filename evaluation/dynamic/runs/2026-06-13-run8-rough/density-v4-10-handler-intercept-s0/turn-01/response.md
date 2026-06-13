```layer-a
@v=1 root=outerHandler
timeNowEf EFC "Time.Now" []
timeNowFnT FNT [] intT [timeNowEf]
timeNow FN "strand-builtin:Time.Now" timeNowFnT [timeNowEf]
nowEfd EFD timeNowEf []
body APP timeNow [] [] [nowEfd]
innerHandleLam LAM [] 2
innerHandler H timeNowEf innerHandleLam body
outerHandleLam LAM [] 1
outerHandler H timeNowEf outerHandleLam innerHandler
```
