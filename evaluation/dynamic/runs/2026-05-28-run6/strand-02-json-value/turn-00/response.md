```layer-a
@v=1 root=schemaClaim
jsonNullCase SCS "JsonNull" _
jsonBoolCase SCS "JsonBool" boolT
jsonNumberCase SCS "JsonNumber" intT
jsonStringCase SCS "JsonString" stringT
jsonValueT SUM [jsonNullCase jsonBoolCase jsonNumberCase jsonStringCase]
jsonValueSchema SCH "JsonValue" jsonValueT []
identityOfJsonValue LAM [jv:jsonValueSchema] jv
schemaClaim APP identityOfJsonValue [(SV jsonValueT "JsonNumber" 42)]
```
