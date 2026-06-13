```layer-a
@v=1 root=result

# Effect declaration for connectFx
connEfd EFD connectFx [hostStr portInt]

# The netConnect call with explicit effect instance
hostStr STR "db.internal"
portInt ILT 5432
handle APP netConnect [hostStr portInt] [] [connEfd]

# Add 1 to the handle
result APP add [handle 1]
```
