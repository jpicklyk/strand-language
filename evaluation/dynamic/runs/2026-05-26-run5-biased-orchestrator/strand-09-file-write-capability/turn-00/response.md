```layer-a
@v=1 root=app
fsWriteT FNT [stringT] intT [writeFx]
fsWriteStub FN "strand-builtin:Filesystem.Write" fsWriteT [writeFx]
writeResult APP fsWriteStub ["/tmp/strand-eval.log"]
app APP add [writeResult 1]
```
