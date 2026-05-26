```layer-a
@v=1 root=app
pathParam PRC "path" stringT
writeFx EFC "Filesystem.Write" [pathParam]
writeFnT FNT [stringT] intT
fsWrite FN "strand-builtin:Filesystem.Write" writeFnT [writeFx]
app APP add [(APP fsWrite ["/tmp/strand-eval.log"]) 1]
```
