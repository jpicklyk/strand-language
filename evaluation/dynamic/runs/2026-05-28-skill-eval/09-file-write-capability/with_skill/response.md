```layer-a
@v=1 root=result
writeFx EFC "Filesystem.Write" [stringT]
writeT FNT [stringT] intT [writeFx]
write FN "strand-builtin:Filesystem.Write" writeT [writeFx]
path STR "/tmp/strand-eval.log"
writeDecl EFD writeFx [path]
bytesWritten APP write [path] [] [writeDecl]
result APP add [bytesWritten 1]
```
