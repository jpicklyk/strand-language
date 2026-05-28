```layer-a
@v=1 root=app
appendLogT FNT [] intT [writeFx]
appendLog LAM [] (APP add [(APP fsWrite ["log.txt"]) 1]) [writeFx]
app APP add [(APP appendLog []) 10]
```
