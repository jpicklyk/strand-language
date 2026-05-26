```layer-a
@v=1 root=app
matchBody IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])])
bodyLam LAM [recurse n] matchBody
fact FIX factT bodyLam
app APP fact [5]
```
