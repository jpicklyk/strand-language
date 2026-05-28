```layer-a
@v=1 root=app
# Recursive list type: μ. Cons(head: Int, tail: List) | Nil
selfRef RS
headFieldInner PRF "head" intT
tailFieldInner PRF "tail" selfRef
consInner PRD [headFieldInner tailFieldInner]
consCase SCS "Cons" consInner
nilCase SCS "Nil" _
listSum SUM [consCase nilCase]
listT RT listSum

# Outer product for value construction sites
headFieldOuter PRF "head" intT
tailFieldOuter PRF "tail" listT
consOuter PRD [headFieldOuter tailFieldOuter]

# Recursive sum function: List -> Int
sumT FNT [listT] intT
sumBody WHEN xs listT "Cons(p) -> (APP add [(PFG p \"head\") (APP recurse [(PFG p \"tail\")])]) | Nil -> 0"
sumLam LAM [recurse xs] sumBody
sum FIX sumT sumLam

# Build the list Cons(1, Cons(2, Cons(3, Nil)))
nilV SV listT "Nil" _
list3 SV listT "Cons" (PV consOuter [head=3 tail=nilV])
list2 SV listT "Cons" (PV consOuter [head=2 tail=list3])
list1 SV listT "Cons" (PV consOuter [head=1 tail=list2])

# Apply sum to the list
app APP sum [list1]
```
