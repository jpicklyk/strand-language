```layer-a
@v=1 root=schemaClaim
isPosLambda LAM [x:intT] (APP gt [x 0])
posInv INV "positive" posIntSchema isPosLambda
posIntSchema SCH "PositiveInt" intT [posInv]
identityOfPosInt LAM [p:posIntSchema] p
schemaClaim APP identityOfPosInt [5]
```
