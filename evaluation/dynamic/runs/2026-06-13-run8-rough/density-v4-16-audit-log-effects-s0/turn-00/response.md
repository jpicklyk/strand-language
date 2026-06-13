```layer-a
@v=1 root=result

# Effect declarations for now and logInfo
nowEfd EFD nowFx
logEfd EFD logFx

# Helper lambda: (Int) -> Int
# Body chains: get time, build message, log it, then return doubled n
helperBody LET "_" (APP logInfo [(APP concat ["audit:" (APP intToStr [(APP now [])])])]) (APP mul [n 2])
helperLam LAM [n:intT] helperBody [nowEfd logEfd]

# Apply helper to 21
result APP helperLam [21] @auto
```
