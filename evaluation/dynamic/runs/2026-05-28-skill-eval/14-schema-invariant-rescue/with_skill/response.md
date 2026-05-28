```layer-a
@v=1 root=app
posBody LAM [x:intT] (APP gt [x 0])
posInv INV "positive" posInt posBody
posInt SCH "PositiveInt" intT [posInv]
idBody LAM [p:posInt] p
app APP idBody [1]
```
