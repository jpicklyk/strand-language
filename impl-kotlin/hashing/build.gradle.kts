plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    // Pure-JVM BLAKE3 implementation. Reference Java port of the BLAKE3 spec.
    // We use only the standard `Blake3.newInstance().update(bytes).digest()`
    // API surface; any compliant BLAKE3 library would produce identical
    // output if we needed to swap.
    implementation("io.github.rctcwyvrn:blake3:1.3")
}
