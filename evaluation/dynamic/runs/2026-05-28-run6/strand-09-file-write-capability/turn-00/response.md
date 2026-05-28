```layer-a
@v=1 root=app
writeFxCat EFC "Filesystem.Write" [stringT]
writeT FNT [stringT] intT [writeFxCat]
fsWriteFn FN "strand-builtin:Filesystem.Write" writeT [writeFxCat]
pathLit STR "/tmp/strand-eval.log"
writeDecl EFD writeFxCat [pathLit]
writeResult APP fsWriteFn [pathLit] _ [writeDecl]
app APP add [writeResult 1]
```
