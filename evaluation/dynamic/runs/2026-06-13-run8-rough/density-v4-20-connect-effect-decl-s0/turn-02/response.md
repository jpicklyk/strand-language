```layer-a
@v=1 root=result

# Handler lambda: intercepts connectFx(host, port) and returns 7
handlerLam LAM [h:stringT p:intT] 7

# The host and port argument nodes (same ids used in EFD and APP)
hostStr STR "db.internal"
portInt ILT 5432

# Explicit EffectDecl for connectFx, referencing the exact argument nodes
connEfd EFD connectFx [hostStr portInt]

# netConnect application with explicit effect instance
handle APP netConnect [hostStr portInt] [] [connEfd]

# Add 1 to the handle
addOne APP add [handle 1]

# Wrap in Handler so no real connection is attempted
result H connectFx handlerLam addOne
```
