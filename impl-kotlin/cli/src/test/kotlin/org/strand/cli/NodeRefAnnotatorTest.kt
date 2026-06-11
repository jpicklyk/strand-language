package org.strand.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.strand.core.NodeId

/**
 * Unit coverage for [NodeRefAnnotator] — the CLI-side rendering helper
 * that maps `#N` NodeId references in verifier/interpreter/schema error
 * output back to the author ids the agent wrote.
 */
class NodeRefAnnotatorTest {

    private val nameMap = mapOf(
        "togglefn" to NodeId(23),
        "__if0_match" to NodeId(41),
        "intT" to NodeId(77),
        "state" to NodeId(5),
    )

    @Test
    fun `authored node reference is annotated inline with its author id`() {
        val annotator = NodeRefAnnotator(nameMap)
        assertEquals(
            "UnboundVarRef(at=#23 'togglefn')",
            annotator.annotate("UnboundVarRef(at=#23)"),
        )
    }

    @Test
    fun `synthesized node is flagged as an expansion, not an authored line`() {
        val annotator = NodeRefAnnotator(nameMap)
        assertEquals(
            "TypeMismatch(at=#41 (synthesized '__if0_match'))",
            annotator.annotate("TypeMismatch(at=#41)"),
        )
    }

    @Test
    fun `prelude node is flagged as prelude when prelude names are supplied`() {
        val annotator = NodeRefAnnotator(nameMap, preludeNames = setOf("intT"))
        assertEquals(
            "expected #77 (prelude 'intT')",
            annotator.annotate("expected #77"),
        )
    }

    @Test
    fun `prelude-looking name without prelude set is treated as authored`() {
        // verify/run on hand-authored dag-json: a node named intT was
        // genuinely authored, so no prelude flag.
        val annotator = NodeRefAnnotator(nameMap)
        assertEquals("expected #77 'intT'", annotator.annotate("expected #77"))
    }

    @Test
    fun `multiple references in one message are each annotated`() {
        val annotator = NodeRefAnnotator(nameMap, preludeNames = setOf("intT"))
        assertEquals(
            "ArgTypeMismatch(app=#23 'togglefn', expected=#77 (prelude 'intT'), " +
                "got=#41 (synthesized '__if0_match'))",
            annotator.annotate("ArgTypeMismatch(app=#23, expected=#77, got=#41)"),
        )
    }

    @Test
    fun `unmapped reference is left untouched`() {
        val annotator = NodeRefAnnotator(nameMap)
        assertEquals(
            "UnboundVarRef(at=#999)",
            annotator.annotate("UnboundVarRef(at=#999)"),
        )
    }

    @Test
    fun `source line is appended for authored nodes when known`() {
        val annotator = NodeRefAnnotator(nameMap, sourceLines = mapOf("togglefn" to 5))
        assertEquals(
            "UnboundVarRef(at=#23 'togglefn' (line 5))",
            annotator.annotate("UnboundVarRef(at=#23)"),
        )
    }

    @Test
    fun `synthesized node with an inherited source line carries the line too`() {
        val annotator = NodeRefAnnotator(nameMap, sourceLines = mapOf("__if0_match" to 9))
        assertEquals(
            "TypeMismatch(at=#41 (synthesized '__if0_match', line 9))",
            annotator.annotate("TypeMismatch(at=#41)"),
        )
    }

    @Test
    fun `message without node references passes through unchanged`() {
        val annotator = NodeRefAnnotator(nameMap)
        assertEquals("no refs here", annotator.annotate("no refs here"))
    }
}
