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
    // For AsyncCorpusTest's runTest virtual-time dispatcher (Layer 6 step 2).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

tasks.test {
    // Make seed corpus JSON files available to tests via the classpath.
    inputs.dir("src/main/resources")
}
