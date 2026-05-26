plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    api(project(":verifier"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // BLAKE3 for the `strand-builtin:Hash.Blake3` stdlib builtin. Same
    // library and version the :hashing module uses for content-address
    // computation, kept in sync deliberately.
    implementation("io.github.rctcwyvrn:blake3:1.3")
    testImplementation(project(":hashing"))
}
