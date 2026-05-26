package org.strand.bytecode

/**
 * Strand bytecode opcodes (Q-017 step 1).
 *
 * The 28-opcode instruction set specified in `proposals/bytecode-vm-step-1.md`
 * § 4.2. Each opcode is a single byte; operand-bearing opcodes take their
 * operand as a 4-byte little-endian int immediately following the opcode
 * byte. Variable-length encoding (LEB128) is reserved for step 2's Rust
 * port — step 1 uses fixed-width for legibility and easy disassembly.
 *
 * The Layer 1 / 5 / 7 subset (literals, types-erased, lambda, application,
 * let, varref, NodeRef, TypeAbstraction, product / sum values, match,
 * fixpoint) ships in step 1's first slice; Layer 3 / 4 / 6 opcodes
 * (capability scope, handlers, foreign nodes, state machines) extend the
 * same enum as those layers reach the VM.
 */
enum class Opcode(val code: Byte) {
    // Stack manipulation
    POP(0x00),
    DUP(0x01),

    // Literal pushes — each consumes a 4-byte constant-pool index.
    PUSH_INT(0x10),
    PUSH_FLOAT(0x11),
    PUSH_STRING(0x12),
    PUSH_BOOL(0x13),
    PUSH_UNIT(0x14),
    PUSH_BYTES(0x15),

    // Variable / closure access — operand is a slot/capture index.
    LOAD_LOCAL(0x20),
    LOAD_CAPTURE(0x21),
    STORE_LOCAL(0x22),
    LOAD_HASH(0x23),

    // Calls and returns
    CALL(0x30),
    CALL_FIXPOINT(0x31),
    CALL_FOREIGN(0x32),
    RET(0x33),

    // Closures — operand is a chunk index; following operand is a count.
    MAKE_CLOSURE(0x40),
    MAKE_FIXPOINT(0x41),
    MAKE_FOREIGN(0x42),

    // Composite values
    PRODUCT_NEW(0x50),
    PRODUCT_GET(0x51),
    SUM_NEW(0x52),

    // Control flow
    MATCH_DISPATCH(0x60),
    JUMP(0x61),
    JUMP_IF_FALSE(0x62),

    // Effects and capabilities
    CAP_PUSH(0x70),
    CAP_POP(0x71),
    HANDLER_PUSH(0x72),
    HANDLER_POP(0x73),

    // Pattern-matching helpers (Layer 5 step 1 — Match dispatch). Each
    // helper produces a Bool on the operand stack for the surrounding
    // JUMP_IF_FALSE to test. SUM_PAYLOAD pushes the SumV's payload
    // value (or UnitV if the case is nullary); the surrounding case
    // body uses STORE_LOCAL to bind it to the pattern's variable slot.
    EQ(0x80.toByte()),
    SUM_CASE_IS(0x81.toByte()),
    SUM_PAYLOAD(0x82.toByte()),
    THROW_NO_MATCH(0x83.toByte()),

    // Halt
    HALT(0x7f);

    companion object {
        private val byCode: Map<Byte, Opcode> = entries.associateBy { it.code }

        /** Look up the opcode for a single byte. Throws on unknown bytes. */
        fun fromByte(b: Byte): Opcode =
            byCode[b] ?: error("Unknown opcode byte 0x%02x".format(b.toInt() and 0xff))
    }
}
