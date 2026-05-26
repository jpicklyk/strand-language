```layer-a
@v=1 root=app
isPosLambda LAM [x:intT] (APP gt [x 0])
posInv INV "positive" posInt isPosLambda
posInt SCH "PositiveInt" intT [posInv]
identityLam LAM [p:posInt] p
app APP identityLam [5]
```
