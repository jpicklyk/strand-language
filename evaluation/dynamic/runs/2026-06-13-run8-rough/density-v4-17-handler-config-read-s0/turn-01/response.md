```layer-a
@v=1 root=result
fixedBytes BYT "ZGVidWc9dHJ1ZQ=="
handleLam LAM [path:stringT] fixedBytes
pathStr STR "/etc/app.conf"
readApp APP fsRead [pathStr] @auto
result H readFx handleLam (APP bytesLen [readApp])
```
