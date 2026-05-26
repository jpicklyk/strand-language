```layer-a
@v=1 root=app
posIntT SCH "PositiveInt" intT [posInv]
isPosLam LAM [x:intT] (APP gt [x 0])
posInv INV "positive" posIntT isPosLam
identityFn LAM [v:posIntT] v
app APP identityFn [5]
```
