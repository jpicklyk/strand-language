```layer-a
@v=1 root=main
fsWriteFx EFC "Filesystem.Write" [stringT]
fsWriteT FNT [stringT] intT [fsWriteFx]
fsWriteFn FN "strand-builtin:Filesystem.Write" fsWriteT [fsWriteFx]
logPath STR "/tmp/app.log"
writeDecl EFD fsWriteFx [logPath]
appendLogBody APP add [(APP fsWriteFn [logPath] _ [writeDecl]) 1]
appendLog LAM [] appendLogBody [fsWriteFx]
mainDecl EFD fsWriteFx [logPath]
main APP add [(APP appendLog [] _ [mainDecl]) 10]
```
