```layer-a
@v=1 root=result
fsWriteEffect EFC "Filesystem.Write" [stringT]
fsWriteEfd EFD fsWriteEffect [path]
fsWriteFnT FNT [stringT] intT [fsWriteEffect]
fsWriteFn FN "strand-builtin:Filesystem.Write" fsWriteFnT [fsWriteEffect]
path STR "/tmp/strand-eval.log"
bytesWritten APP fsWriteFn [path] [] [fsWriteEfd]
result APP add [bytesWritten 1]
```
