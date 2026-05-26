```layer-a
@v=1 root=app
isPosLambda LAM [x:intT] (APP gt [x 0])
posInv INV "positive" posInt isPosLambda
posInt SCH "PositiveInt" intT [posInv]
identityOfPosInt LAM [n:posInt] n
app APP identityOfPosInt [5]
```
