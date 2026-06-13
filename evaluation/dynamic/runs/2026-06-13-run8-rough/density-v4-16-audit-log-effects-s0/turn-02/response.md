```layer-a
@v=1 root=result

# Declare PRC for the lambda parameter explicitly
nPrc PRC "n" intT

# Effect declarations referenced by the lambda
nowEfd EFD nowFx []
logEfd EFD logFx []

# Build the body as standalone nodes referencing nPrc as binder
timeVal APP now [] [] [nowEfd]
timeStr APP intToStr [timeVal]
auditMsg APP concat ["audit:" timeStr]
logResult LET "_" (APP logInfo [auditMsg] [] [logEfd]) (APP mul [nPrc 2])

# Lambda referencing the PRC and body node; effects declared explicitly
helperLam LAM [nPrc] logResult [nowEfd logEfd]

# Apply helper to 21
outerNowEfd EFD nowFx []
outerLogEfd EFD logFx []
result APP helperLam [21] [] [outerNowEfd outerLogEfd]
```
