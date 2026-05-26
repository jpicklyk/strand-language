```layer-a
@v=1 root=schemaClaim
isPosLambda LAM [x:intT] (APP gt [x 0])
positiveInvariant INV "positive" positiveIntSchema isPosLambda
positiveIntSchema SCH "PositiveInt" intT [positiveInvariant]
identityOfPositive LAM [v:positiveIntSchema] v
schemaClaim APP identityOfPositive [5]
```
