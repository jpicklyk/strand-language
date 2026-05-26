```layer-a
@v=1 root=app
writeFnT FNT [stringT] intT
fsWrite FN "strand-builtin:Filesystem.Write" writeFnT [writeFx]
app APP add [(APP fsWrite ["/tmp/strand-eval.log"]) 1]
```
