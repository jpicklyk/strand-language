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
    // Q-043: CliFederationTest drives the real `strand` CLI entry point on happy
    // paths. :cli does not depend on :corpus, so this test-only edge is acyclic.
    testImplementation(project(":cli"))
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
    // Forward the golden-hash regeneration flag from the Gradle invocation to
    // the test JVM (CorpusGoldenHashTest). When true, the test rewrites
    // corpus/golden-hashes.json from the live implementation; see
    // corpus/README.md for the exact command.
    systemProperty(
        "strand.regenerateGoldenHashes",
        System.getProperty("strand.regenerateGoldenHashes") ?: "false",
    )
    // Forward the Q-063 prelude-module regeneration flag (PreludeModuleConformanceTest).
    // When true, the test rewrites corpus/prelude-manifest.json and the bundled
    // authoring snapshot from the live reserved-spec table; see corpus/README.md.
    systemProperty(
        "strand.regeneratePreludeModule",
        System.getProperty("strand.regeneratePreludeModule") ?: "false",
    )
    // Forward the Q-063 legacy prelude-synthesis flag (equivalence-suite escape
    // hatch): when true, the Layer A emitter synthesizes reserved nodes from the
    // in-memory table instead of resolving them through the bundled module.
    System.getProperty("strand.prelude.legacySynthesis")?.let {
        systemProperty("strand.prelude.legacySynthesis", it)
    }
    // Forward the Q-066 fuzz iteration count (CorpusMutationFuzzTest).
    // Unset means the test's default (25 mutants per corpus program);
    // deeper local campaigns run e.g. -Dstrand.fuzzIterations=500.
    System.getProperty("strand.fuzzIterations")?.let {
        systemProperty("strand.fuzzIterations", it)
    }
}
