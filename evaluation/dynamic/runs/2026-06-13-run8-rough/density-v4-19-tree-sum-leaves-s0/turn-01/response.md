```layer-a
@v=1 root=result

# RecursiveSelf node — must be declared standalone, then referenced
selfRef RS

# Recursive tree type: Tree = μ. Leaf(Int) | Node({left: Tree, right: Tree})
leftField PRF "left" selfRef
rightField PRF "right" selfRef
nodeProduct PRD [leftField rightField]
leafCase SCS "Leaf" intT
nodeCase SCS "Node" nodeProduct
treeSum SUM [leafCase nodeCase]
treeT RT treeSum

# FunctionType for sumLeaves: Tree -> Int
sumLeavesFnT FNT [treeT] intT

# sumLeaves via Fixpoint
# body lambda: first param is recursive call slot (sumLeavesFnT), second is the tree (treeT)
nodeResult APP add [(APP recurse [(PFG nodeVal "left")]) (APP recurse [(PFG nodeVal "right")])]
matchExpr WHEN treeArg treeT "Leaf(leafVal) -> leafVal | Node(nodeVal) -> nodeResult"
bodyLam LAM [recurse:sumLeavesFnT treeArg:treeT] matchExpr
sumLeaves FIX sumLeavesFnT bodyLam

# Construct the tree: Node(Leaf(3), Node(Leaf(4), Leaf(5)))
leaf3 SV treeT "Leaf" 3
leaf4 SV treeT "Leaf" 4
leaf5 SV treeT "Leaf" 5
innerNodePV PV nodeProduct [left=leaf4 right=leaf5]
innerNode SV treeT "Node" innerNodePV
outerNodePV PV nodeProduct [left=leaf3 right=innerNode]
outerNode SV treeT "Node" outerNodePV

# Apply sumLeaves to the tree
result APP sumLeaves [outerNode]
```
