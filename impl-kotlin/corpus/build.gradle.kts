plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":verifier"))
    implementation(project(":interpreter"))

    testImplementation(project(":core"))
    testImplementation(project(":verifier"))
    testImplementation(project(":interpreter"))
    testImplementation(project(":hashing"))
    testImplementation(project(":runtime"))
    testImplementation(project(":schema"))
    testImplementation(project(":authoring"))
    testImplementation(project(":bytecode"))
    testImplementation(project(":vm"))
    // For AsyncCorpusTest's runTest virtual-time dispatcher (Layer 6 step 2).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

// The seed-corpus JSON + Layer A programs live at the repo's top-level `corpus/`
// directory so they can be shared by future implementations (e.g. impl-rust).
// `processResources` copies them under `corpus/` on the test classpath so the
// existing `getResourceAsStream("/corpus/NN-...")` lookups keep working without
// any change to the Kotlin test code.
private val sharedCorpusDir = rootProject.projectDir.parentFile.resolve("corpus")

tasks.processResources {
    from(sharedCorpusDir) {
        into("corpus")
    }
}

tasks.test {
    inputs.dir(sharedCorpusDir)
}
