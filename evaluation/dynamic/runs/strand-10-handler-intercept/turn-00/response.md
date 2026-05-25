```layer-a
@v=1 root=outer
callNow APP now []
innerLam LAM [] 2
inner H nowFx innerLam callNow
outerLam LAM [] 1
outer H nowFx outerLam inner
```
