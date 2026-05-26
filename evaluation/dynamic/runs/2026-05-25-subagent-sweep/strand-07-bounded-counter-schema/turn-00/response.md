```layer-a
@v=1 root=schemaClaim
xParam PRC "x" intT
isPosBody APP gt [x 0]
isPosLambda LAM [xParam] isPosBody
positiveInvariant INV "positive" positiveIntSchema isPosLambda
positiveIntSchema SCH "PositiveInt" intT [positiveInvariant]
identityOfPositive LAM [v:positiveIntSchema] v
schemaClaim APP identityOfPositive [5]
```
