```layer-a
@v=1 root=result

# Effect declarations for nowFx and logFx
nowEfd EFD nowFx []
logEfd EFD logFx []

# Helper lambda body uses nested inline expressions to stay in scope of n.
# LET chains: bind time, bind msg, bind log result (discard), return n*2
helperBody LET "t" (APP now [] [] [nowEfd]) (LET "msg" (APP concat ["audit:" (APP intToStr [t])]) (LET "_" (APP logInfo [msg] [] [logEfd]) (APP mul [n 2])))
helperLam LAM [n:intT] helperBody [nowEfd logEfd]

# Outer effect declarations for the call site
outerNowEfd EFD nowFx []
outerLogEfd EFD logFx []

# Apply helper to 21 to get 42
result APP helperLam [21] [] [outerNowEfd outerLogEfd]
```
