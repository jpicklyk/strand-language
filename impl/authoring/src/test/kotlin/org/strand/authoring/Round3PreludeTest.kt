package org.strand.authoring

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for stdlib expansion round 3 prelude entries — Log.* /
 * OS.* / System.Exit reserved names plus the three new effect
 * categories (logFx, osReadFx, exitFx).
 */
class Round3PreludeTest {

    private fun nodeOf(text: String, id: String) =
        Authoring.compileToJsonObject(text).jsonObject["nodes"]!!.jsonObject[id]!!.jsonObject

    @Test
    fun `logInfo logWarn logError wire to the right targets and share logFx`() {
        val text = "@v=1 root=a\na APP logInfo [\"hello\"]"
        val info = nodeOf(text, "logInfo")
        assertEquals("strand-builtin:Log.Info", info["target"]!!.jsonPrimitive.content)
        assertEquals(listOf("logFx"), info["effects"]!!.jsonArray.map { it.jsonPrimitive.content })
        val logFx = nodeOf(text, "logFx")
        assertEquals("Log.Write", logFx["categoryName"]!!.jsonPrimitive.content)

        // Distinct targets, same effect category.
        for ((name, target) in listOf(
            "logWarn" to "strand-builtin:Log.Warn",
            "logError" to "strand-builtin:Log.Error",
        )) {
            val node = nodeOf("@v=1 root=a\na APP $name [\"x\"]", name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            assertEquals(listOf("logFx"), node["effects"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test
    fun `OS reserved names share osReadFx and return String`() {
        for ((name, target, fntId) in listOf(
            Triple("hostname", "strand-builtin:OS.Hostname", "hostT"),
            Triple("platform", "strand-builtin:OS.Platform", "platT"),
            Triple("cwd", "strand-builtin:OS.Cwd", "cwdT"),
        )) {
            val text = "@v=1 root=a\na APP $name []"
            val node = nodeOf(text, name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            assertEquals(listOf("osReadFx"), node["effects"]!!.jsonArray.map { it.jsonPrimitive.content })
            val fnt = nodeOf(text, fntId)
            assertEquals(emptyList<String>(), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("stringT", fnt["result"]!!.jsonPrimitive.content)
        }
        val osReadFx = nodeOf("@v=1 root=a\na APP hostname []", "osReadFx")
        assertEquals("OS.Read", osReadFx["categoryName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exit wires to System_Exit with exitFx and Int to Unit type`() {
        val text = "@v=1 root=a\na APP exit [0]"
        val node = nodeOf(text, "exit")
        assertEquals("strand-builtin:System.Exit", node["target"]!!.jsonPrimitive.content)
        assertEquals(listOf("exitFx"), node["effects"]!!.jsonArray.map { it.jsonPrimitive.content })
        val fnt = nodeOf(text, "exitT")
        assertEquals(listOf("intT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("unitT", fnt["result"]!!.jsonPrimitive.content)
        val exitFx = nodeOf(text, "exitFx")
        assertEquals("System.Exit", exitFx["categoryName"]!!.jsonPrimitive.content)
    }
}
