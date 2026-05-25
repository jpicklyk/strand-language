```layer-a
@v=1 root=schemaClaim
predLam LAM [x:intT] (APP gt [x 0])
positiveInvariant INV "x_positive" positiveInt predLam
positiveInt SCH "PositiveInt" intT [positiveInvariant]
identityOfPositiveInt LAM [p:positiveInt] p
schemaClaim APP identityOfPositiveInt [5]
```
