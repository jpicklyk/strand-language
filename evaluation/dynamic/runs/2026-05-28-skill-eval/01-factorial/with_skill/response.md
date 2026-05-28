```layer-a
@v=1 root=app
factT FNT [intT] intT
factBody LAM [recurse:factT n:intT] (IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])]))
fact FIX factT factBody
app APP fact [5]
```
