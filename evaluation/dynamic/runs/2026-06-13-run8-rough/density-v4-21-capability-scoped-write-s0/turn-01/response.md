```layer-a
@v=1 root=result

# Declare the writeFx effect category (already in prelude, but needed explicitly for EFD)
# writeFx is pre-bound in the implicit prelude

# Declare the ForeignNode for Filesystem.Write with type (String) -> Int and writeFx effect
fsWriteStubT FNT [stringT] intT [writeFx]
fsWriteStub FN "strand-builtin:Filesystem.Write" fsWriteStubT [writeFx]

# Derive timestamped path: concat("audit-", intToStr(now()))
nowVal APP now []
nowStr APP intToStr [nowVal]
prefix STR "audit-"
auditPath APP concat [prefix nowStr]

# Declare the EFD for the write effect inside the CAP body
writeDecl EFD writeFx [auditPath]

# Apply the stub inside a CapabilityScope
writeResult APP fsWriteStub [auditPath] [] [writeDecl]
scopedWrite CAP [writeDecl] writeResult

# Add 7 to the scope's value
seven ILT 7
result APP add [scopedWrite seven]
```
