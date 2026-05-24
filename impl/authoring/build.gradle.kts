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
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(project(":hashing"))
    testImplementation(project(":verifier"))
}
