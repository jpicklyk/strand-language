```layer-a
@v=1 root=app
fsWriteEf EFC "Filesystem.Write" [stringT]
fsWriteT FNT [stringT] intT
fsWrite FN "strand-builtin:Filesystem.Write" fsWriteT [fsWriteEf]
app APP add [(APP fsWrite ["/tmp/strand-eval.log"]) 1]
```
