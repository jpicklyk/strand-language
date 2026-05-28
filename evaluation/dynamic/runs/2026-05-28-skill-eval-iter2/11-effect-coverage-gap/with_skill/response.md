```layer-a
@v=1 root=app
writeFx EFC "Filesystem.Write" [stringT]
writeT FNT [stringT] intT [writeFx]
write FN "strand-builtin:Filesystem.Write" writeT [writeFx]
path STR "/tmp/append.log"
writeDecl EFD writeFx [path]
appendLogBody APP add [(APP write [path] [] [writeDecl]) 1]
appendLog LAM [] appendLogBody [writeFx]
app APP add [(APP appendLog []) 10]
```
