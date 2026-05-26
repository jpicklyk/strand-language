plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // The authoring layer parses Layer A text and emits canonical dag-json
    // bytes that the existing :core JsonIngest consumes. No type-checking is
    // performed here — that remains the verifier's job downstream. The
    // dependency on kotlinx-serialization-json is for the emitter's JSON
    // assembly; the parser is hand-written (no serialization dependency).
    //
    // :hashing is needed by Q-036's reverse-projection probe (Step 3): the
    // probe accepts a borderline-field strip only when the candidate
    // document's canonical hash matches the baseline's. Comparing raw
    // JsonObjects misses the canonical encoder's empty-field gating, so we
    // ingest + finalize and compare hashes.
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(project(":hashing"))
    testImplementation(project(":hashing"))
    testImplementation(project(":verifier"))
}
