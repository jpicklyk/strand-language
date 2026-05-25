```layer-a
@v=1 root=app
writeFx EFC "Filesystem.Write" [stringT]
writeT FNT [stringT] intT [writeFx]
write FN "strand-builtin:Filesystem.Write" writeT [writeFx]
path STR "/tmp/strand-eval.log"
writeDecl EFD writeFx [path]
callWrite APP write [path] [] [writeDecl]
app APP add [callWrite 1]
```
