```layer-a
@v=1 root=result
pathLit STR "/tmp/strand-eval.log"
writeFxCat EFC "Filesystem.Write" [stringT]
writeFnT FNT [stringT] intT [writeFxCat]
writeFn FN "strand-builtin:Filesystem.Write" writeFnT [writeFxCat]
writeDecl EFD writeFxCat [pathLit]
bytesWritten APP writeFn [pathLit] [] [writeDecl]
result APP add [bytesWritten 1]
```
