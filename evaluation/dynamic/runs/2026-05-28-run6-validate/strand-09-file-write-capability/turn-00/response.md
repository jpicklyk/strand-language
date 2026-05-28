```layer-a
@v=1 root=result
writeFx EFC "Filesystem.Write" [stringT]
writeT FNT [stringT] intT [writeFx]
fsWriteStub FN "strand-builtin:Filesystem.Write" writeT [writeFx]
path STR "/tmp/strand-eval.log"
writeDecl EFD writeFx [path]
writeResult APP fsWriteStub [path] _ [writeDecl]
result APP add [writeResult 1]
```
