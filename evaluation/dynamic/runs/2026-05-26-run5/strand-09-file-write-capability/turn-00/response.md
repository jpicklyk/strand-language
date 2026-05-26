```layer-a
@v=1 root=app
fsWriteEf EFC "Filesystem.Write"
fsWriteFnT FNT [stringT] intT
fsWriteFn FN "strand-builtin:Filesystem.Write" fsWriteFnT [fsWriteEf]
writeResult APP fsWriteFn ["/tmp/strand-eval.log"]
app APP add [writeResult 1]
```
