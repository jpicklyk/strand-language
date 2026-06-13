```layer-a
@v=1 root=result

# Recursive list type: List = μ. Cons(head: Int, tail: List) | Nil
rsSelf RS
headField PRF "head" intT
tailField PRF "tail" rsSelf
consPayloadT PRD [headField tailField]
consCase SCS "Cons" consPayloadT
nilCase SCS "Nil" _
listSumT SUM [consCase nilCase]
listT RT listSumT

# append: (List, List) -> List
appendT FNT [listT listT] listT
appendMatchBody WHEN first listT "Cons(cv) -> (SV listT \"Cons\" (PV consPayloadT [head=(PFG cv \"head\") tail=(APP appendRec [(PFG cv \"tail\") second])])) | Nil -> second"
appendBody LAM [appendRec:appendT first:listT second:listT] appendMatchBody
append FIX appendT appendBody

# sum: List -> Int
sumT FNT [listT] intT
sumMatchBody WHEN lst listT "Cons(cv) -> (APP add [(PFG cv \"head\") (APP sumRec [(PFG cv \"tail\")])]) | Nil -> 0"
sumBody LAM [sumRec:sumT lst:listT] sumMatchBody
sum FIX sumT sumBody

# Construct Cons(1, Cons(2, Nil))
nil2 SV listT "Nil" _
cons2 SV listT "Cons" (PV consPayloadT [head=2 tail=nil2])
cons1 SV listT "Cons" (PV consPayloadT [head=1 tail=cons2])

# Construct Cons(3, Nil)
nil3 SV listT "Nil" _
cons3 SV listT "Cons" (PV consPayloadT [head=3 tail=nil3])

# sum(append(list12, list3))
appended APP append [cons1 cons3]
result APP sum [appended]
```
