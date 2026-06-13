```layer-a
@v=1 root=result

# ForeignNode for Filesystem.Write: type (String) -> Int, declares writeFx
fsWriteStubT FNT [stringT] intT [writeFx]
fsWriteStub FN "strand-builtin:Filesystem.Write" fsWriteStubT [writeFx]

# Derive timestamped path inside the CAP body lambda so effects are scoped
prefix STR "audit-"
nowVal APP now []
nowStr APP intToStr [nowVal]
auditPath APP concat [prefix nowStr]

# EFD for the write at this call site (references the exact argument node auditPath)
writeDecl EFD writeFx [auditPath]

# Apply the stub with the effect declaration
writeResult APP fsWriteStub [auditPath] [] [writeDecl]

# CAP takes effect categories; body uses nowFx (from now) and writeFx (from stub)
scopedWrite CAP [nowFx writeFx] writeResult

# Add 7 to the scope's value
seven ILT 7
result APP add [scopedWrite seven]
```
