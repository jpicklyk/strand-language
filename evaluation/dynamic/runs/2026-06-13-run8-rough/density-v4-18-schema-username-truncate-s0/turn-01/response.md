```layer-a
@v=1 root=result
invBody IF (APP le [(APP strLen [s]) 8]) true false
invLam LAM [s:stringT] invBody
shortNameInv INV invLam
shortNameSchema SCH "ShortName" stringT [shortNameInv]
rawUsername APP concat ["strand" "_agent"]
username APP subStr [rawUsername 0 8]
identityResult APP (LAM [p:shortNameSchema] p) [username]
result APP strLen [identityResult]
```
