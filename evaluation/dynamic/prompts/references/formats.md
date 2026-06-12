# Reference: formats — Json, Markdown, Csv/Tsv format libraries

None of these are in the prelude (each is typed against an agent-chosen
or blessed schema, or returns List<List<String>>). Since density v5 the
bare dotted names work in callee position; the explicit FNT + FN form
remains available where you want to pin the concrete type yourself.

## Json (`Json.Parse` / `Json.Stringify`)

    strand-builtin:Json.Parse(s: String) -> Option<JsonValue>
    strand-builtin:Json.Stringify(j: JsonValue) -> String

`Json.Parse` recognizes the four primitive cases (null → JsonNull, true/
false → JsonBool, integer → JsonNumber, "string" → JsonString) and
builds arrays and objects in the spliced JsonValueFull encoding (below).
`Json.Stringify` is the inverse — it walks the structure back to
canonical JSON text. Use `Option<JsonValue>` for fallible parse
handling (the canonical Option encoding is in the core prompt).

The flat `JsonValue` (corpus 54) has exactly the four primitive cases:

    JsonValue = JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String)

## JsonValueFull and the spliced-variants pattern

The blessed `JsonValueFull` schema (corpus 66) extends the original
flat `JsonValue` (corpus 54, four primitive cases) to handle arrays
and objects without nested μ-types. Eight cases:

    JsonValueFull = μ jv.
        JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String) |
        JsonArrayCons(head: jv, tail: jv) | JsonArrayNil |
        JsonObjectCons(key: String, value: jv, tail: jv) | JsonObjectNil

The four primitives match the corpus-54 shape. Arrays use spliced
`JsonArrayCons` / `JsonArrayNil` instead of a separate Cons/Nil μ.
Objects use `JsonObjectCons(key, value, tail) | JsonObjectNil`.

`Json.Parse` builds the spliced encoding directly: `[1,2]` becomes
`JsonArrayCons(JsonNumber(1), JsonArrayCons(JsonNumber(2), JsonArrayNil))`.
`Json.Stringify` walks the chain back to canonical JSON text. Both
round-trip cleanly for arbitrary nesting.

The four primitive cases of corpus 54 stay legal under both blessed
shapes — agents that only handle primitives can keep using the flat
`JsonValue`; agents that need arrays / objects use `JsonValueFull`.
The spliced pattern exists because nested μ-types do not compose with
value construction (see the RecursiveSelf depth caveat in the
grammar-codes reference).

## Markdown (`Markdown.Parse` / `Markdown.Stringify`)

    strand-builtin:Markdown.Parse(s: String) -> Option<MarkdownDocument>
    strand-builtin:Markdown.Stringify(doc: MarkdownDocument) -> String

Both are typed against the canonical corpus-61 MarkdownDocument shape:

    MarkdownDocument = μ. Cons({head: MarkdownBlock, tail: <self>}) | Nil
    MarkdownBlock = Heading{level: Int, text: String}
                  | Paragraph{text: String}
                  | CodeBlock{language: String, code: String}
                  | HorizontalRule

Heading level is clamped to 1-6 on output. Multiple blocks are
joined by `\n\n` (blank line). Backward compat: a Paragraph block
whose payload is a bare StringV (the shape `Markdown.Parse`
currently produces) is treated as the text directly, so
`Markdown.Parse → Markdown.Stringify` round-trips a single
paragraph verbatim.

## CSV / TSV (`Csv.*` / `Tsv.*`)

Tabular parsing and stringification. Csv.* implements RFC 4180
basic rules (comma cells, double-quote quoting, `""` as escaped
quote, CRLF + LF row separators). Tsv.* is simpler — tab cells,
no quoting (tabs and newlines inside cells are unsupported by the
TSV convention).

    strand-builtin:Csv.Parse(s: String) -> List<List<String>>
    strand-builtin:Csv.Stringify(rows: List<List<String>>) -> String
        -- Quotes any cell containing , " \r or \n; doubles embedded
        -- quotes per RFC 4180. Rows joined by CRLF.
    strand-builtin:Tsv.Parse(s: String) -> List<List<String>>
    strand-builtin:Tsv.Stringify(rows: List<List<String>>) -> String
