```layer-a
@v=1 root=app
posBody LAM [x:intT] (APP gt [x 0])
posInv INV "positive" posSchema posBody
posSchema SCH "PositiveInt" intT [posInv]
idLam LAM [p:posSchema] p
app APP idLam [1]
```
