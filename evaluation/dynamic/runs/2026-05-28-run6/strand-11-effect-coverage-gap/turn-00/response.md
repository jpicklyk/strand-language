```layer-a
@v=1 root=app
writeEf EFC "Filesystem.Write" [stringT]
writeT FNT [stringT] intT [writeEf]
fsWriteOne FN "strand-builtin:Filesystem.Write" writeT [writeEf]
appendLog LAM [] (APP add [(APP fsWriteOne ["/tmp/log"]) 1]) [writeEf]
app APP add [(APP appendLog []) 10]
```
