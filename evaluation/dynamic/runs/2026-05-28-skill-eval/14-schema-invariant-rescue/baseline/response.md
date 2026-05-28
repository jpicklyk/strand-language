```layer-a
@v=1 root=app
isPosLambda LAM [x:intT] (APP gt [x 0])
posInt SCH "PositiveInt" intT [posInv]
posInv INV "positive" posInt isPosLambda
identityFn LAM [p:posInt] p
app APP identityFn [1]
```
