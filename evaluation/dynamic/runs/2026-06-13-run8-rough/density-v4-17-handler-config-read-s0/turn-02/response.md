```layer-a
@v=1 root=result
configBytes BYT "64656275673d74727565"
handleLam LAM [path:stringT] configBytes
pathStr STR "/etc/app.conf"
readApp APP fsRead [pathStr] @auto
result H readFx handleLam (APP bytesLen [readApp])
```
