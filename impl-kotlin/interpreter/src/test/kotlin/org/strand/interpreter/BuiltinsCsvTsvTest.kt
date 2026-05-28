package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 5, Group D — Csv.Parse / Csv.Stringify /
 * Tsv.Parse / Tsv.Stringify. Csv.* follows RFC 4180 basic rules;
 * Tsv.* is simpler (no quoting).
 */
class BuiltinsCsvTsvTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    private fun rowsValue(rows: List<List<String>>): Value {
        var outer: Value = Value.SumV("Nil", null)
        for (row in rows.reversed()) {
            var inner: Value = Value.SumV("Nil", null)
            for (cell in row.reversed()) {
                inner = Value.SumV("Cons", Value.ProductV(mapOf(
                    "head" to Value.StringV(cell), "tail" to inner,
                )))
            }
            outer = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to inner, "tail" to outer,
            )))
        }
        return outer
    }

    private fun walkRows(v: Value): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var cur: Value = v
        while (cur is Value.SumV && cur.case == "Cons") {
            val rowPayload = cur.payload as Value.ProductV
            val row = mutableListOf<String>()
            var cellCur: Value = rowPayload.fields.getValue("head")
            while (cellCur is Value.SumV && cellCur.case == "Cons") {
                val cellPayload = cellCur.payload as Value.ProductV
                row += (cellPayload.fields.getValue("head") as Value.StringV).v
                cellCur = cellPayload.fields.getValue("tail")
            }
            rows += row
            cur = rowPayload.fields.getValue("tail")
        }
        return rows
    }

    // ---------- Csv.Parse ----------

    @Test
    fun `Csv_Parse simple rows`() {
        val fn = lookup("strand-builtin:Csv.Parse")
        val result = fn.invoke(listOf(Value.StringV("a,b,c\nd,e,f")))
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e", "f")), walkRows(result))
    }

    @Test
    fun `Csv_Parse handles quoted cell with comma`() {
        val fn = lookup("strand-builtin:Csv.Parse")
        val result = fn.invoke(listOf(Value.StringV("a,\"b,c\",d")))
        assertEquals(listOf(listOf("a", "b,c", "d")), walkRows(result))
    }

    @Test
    fun `Csv_Parse handles escaped quote inside quoted cell`() {
        val fn = lookup("strand-builtin:Csv.Parse")
        val result = fn.invoke(listOf(Value.StringV("\"she said \"\"hi\"\"\",ok")))
        assertEquals(listOf(listOf("she said \"hi\"", "ok")), walkRows(result))
    }

    @Test
    fun `Csv_Parse handles CRLF row separators`() {
        val fn = lookup("strand-builtin:Csv.Parse")
        val result = fn.invoke(listOf(Value.StringV("a,b\r\nc,d")))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), walkRows(result))
    }

    @Test
    fun `Csv_Parse empty input is empty rows`() {
        val fn = lookup("strand-builtin:Csv.Parse")
        assertEquals(emptyList<List<String>>(), walkRows(fn.invoke(listOf(Value.StringV("")))))
    }

    @Test
    fun `Csv_Parse trailing newline does not produce phantom row`() {
        val fn = lookup("strand-builtin:Csv.Parse")
        val result = fn.invoke(listOf(Value.StringV("a,b\n")))
        assertEquals(listOf(listOf("a", "b")), walkRows(result))
    }

    @Test
    fun `Csv_Parse newline inside quoted cell is preserved`() {
        val fn = lookup("strand-builtin:Csv.Parse")
        val result = fn.invoke(listOf(Value.StringV("\"line1\nline2\",ok")))
        assertEquals(listOf(listOf("line1\nline2", "ok")), walkRows(result))
    }

    // ---------- Csv.Stringify ----------

    @Test
    fun `Csv_Stringify simple rows`() {
        val fn = lookup("strand-builtin:Csv.Stringify")
        val result = fn.invoke(listOf(rowsValue(listOf(
            listOf("a", "b", "c"),
            listOf("d", "e", "f"),
        ))))
        assertEquals(Value.StringV("a,b,c\r\nd,e,f"), result)
    }

    @Test
    fun `Csv_Stringify quotes cells containing comma quote or newline`() {
        val fn = lookup("strand-builtin:Csv.Stringify")
        val result = fn.invoke(listOf(rowsValue(listOf(
            listOf("plain", "has,comma", "has\"quote", "has\nnewline"),
        ))))
        assertEquals(
            Value.StringV("plain,\"has,comma\",\"has\"\"quote\",\"has\nnewline\""),
            result,
        )
    }

    @Test
    fun `Csv round-trip preserves data`() {
        val parse = lookup("strand-builtin:Csv.Parse")
        val stringify = lookup("strand-builtin:Csv.Stringify")
        val original = listOf(
            listOf("name", "age", "city"),
            listOf("Alice", "30", "NYC"),
            listOf("Bob", "25", "SF, CA"),  // has comma
            listOf("Charlie", "35", "\"PDX\""),  // has quotes
        )
        val text = stringify.invoke(listOf(rowsValue(original))) as Value.StringV
        val reparsed = parse.invoke(listOf(text))
        assertEquals(original, walkRows(reparsed))
    }

    // ---------- Tsv.Parse / Stringify ----------

    @Test
    fun `Tsv_Parse splits on tab`() {
        val fn = lookup("strand-builtin:Tsv.Parse")
        val result = fn.invoke(listOf(Value.StringV("a\tb\tc\nd\te\tf")))
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e", "f")), walkRows(result))
    }

    @Test
    fun `Tsv_Stringify joins with tab and newline`() {
        val fn = lookup("strand-builtin:Tsv.Stringify")
        val result = fn.invoke(listOf(rowsValue(listOf(
            listOf("a", "b", "c"),
            listOf("d", "e", "f"),
        ))))
        assertEquals(Value.StringV("a\tb\tc\nd\te\tf"), result)
    }

    @Test
    fun `Tsv round-trip on simple data`() {
        val parse = lookup("strand-builtin:Tsv.Parse")
        val stringify = lookup("strand-builtin:Tsv.Stringify")
        val original = listOf(
            listOf("name", "age"),
            listOf("Alice", "30"),
            listOf("Bob", "25"),
        )
        val text = stringify.invoke(listOf(rowsValue(original))) as Value.StringV
        val reparsed = parse.invoke(listOf(text))
        assertEquals(original, walkRows(reparsed))
    }

    @Test
    fun `Tsv_Parse empty input is empty`() {
        val fn = lookup("strand-builtin:Tsv.Parse")
        assertEquals(emptyList<List<String>>(), walkRows(fn.invoke(listOf(Value.StringV("")))))
    }
}
