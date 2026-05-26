package org.strand.bytecode

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builder for a [Chunk] used during lowering. Accumulates emitted
 * instructions in a [ByteArrayOutputStream]; the constant pool grows as
 * literals and chunk references are interned. Local-slot allocation is
 * sequential — every `allocLocal` advances a counter, and the final
 * [Chunk.locals] count equals the high-water mark.
 *
 * Encoding: each opcode is one byte, followed by zero or more 4-byte
 * little-endian int operands (one per operand the opcode declares). The
 * step-1 encoding is intentionally fixed-width — LEB128 / variable-length
 * is reserved for the Rust step 2 port.
 */
internal class MutableChunk(val name: String) {
    private val code = ByteArrayOutputStream()
    private val constantsList = mutableListOf<Constant>()
    private var localCount: Int = 0

    /**
     * Append [op] to the bytecode stream, optionally followed by [operands]
     * as 4-byte little-endian ints.
     */
    fun emit(op: Opcode, vararg operands: Int) {
        code.write(op.code.toInt())
        for (operand in operands) {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(operand).array()
            code.write(buf)
        }
    }

    /** Intern [c] in the constant pool; return its index. */
    fun constant(c: Constant): Int {
        val existing = constantsList.indexOf(c)
        if (existing >= 0) return existing
        constantsList += c
        return constantsList.size - 1
    }

    /** Allocate the next sequential local slot index. */
    fun allocLocal(): Int = localCount++

    /**
     * Emit a JUMP or JUMP_IF_FALSE with a placeholder 4-byte operand and
     * return the byte offset of the operand. The caller later resolves
     * the jump target via [patchJump]. Used by Match lowering where the
     * jump target's position isn't known until the body bytecode is
     * complete.
     */
    fun emitJumpWithPlaceholder(op: Opcode): Int {
        code.write(op.code.toInt())
        val operandOffset = code.size()
        // Placeholder 4 bytes; backpatched later.
        code.write(byteArrayOf(0, 0, 0, 0))
        return operandOffset
    }

    /**
     * Backpatch the JUMP operand at [operandOffset] to encode a jump from
     * the END of the operand to the current emission position. JUMP
     * operands are relative to the instruction following the operand
     * (i.e., the post-operand pc) so a forward jump is positive and a
     * backward jump is negative.
     */
    fun patchJump(operandOffset: Int) {
        // Target is the current code size (where the next instruction
        // will be emitted). Relative offset = target - (operandOffset + 4).
        val target = code.size()
        val relative = target - (operandOffset + 4)
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(relative).array()
        val bytes = code.toByteArray()
        for (i in 0 until 4) bytes[operandOffset + i] = buf[i]
        code.reset()
        code.write(bytes)
    }

    /** Current byte position in the code stream (used to mark jump targets). */
    val codePosition: Int get() = code.size()

    /**
     * Allocate slot at a SPECIFIC index (used by lambda parameter setup
     * where parameters sit at slots 0..N-1 by convention). Returns the
     * supplied index; bumps [localCount] if [index] >= current count.
     */
    fun allocLocalAt(index: Int): Int {
        if (index >= localCount) localCount = index + 1
        return index
    }

    fun toChunk(): Chunk = Chunk(
        name = name,
        code = code.toByteArray(),
        constants = constantsList.toList(),
        locals = localCount,
    )
}
