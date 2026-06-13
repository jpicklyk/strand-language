plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":core"))
    api(project(":verifier"))
    api(project(":interpreter"))
    // Q-054: the StrandRuntime facade runs the full verify -> schema-check ->
    // evaluate pipeline, so it reaches the SchemaChecker. :schema sits on the
    // same :interpreter -> :verifier -> :core spine as :runtime, so the edge is
    // acyclic (the CLI already depends on both).
    api(project(":schema"))
    // Q-058: run-by-hash reads a ProgramImage from the on-disk PersistentStore
    // and admits it through the federation path, both of which live in :hashing.
    // :hashing depends only on :core, so the :runtime -> :hashing edge is acyclic
    // (the CLI and the :corpus test sourceset already depend on :hashing).
    api(project(":hashing"))
    // kotlinx-serialization-json is already brought in transitively via :core,
    // but the runtime's EventCodec uses the parser directly — declare it as a
    // compile-time dependency rather than relying on transitive resolution.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // Q-059: ValueCodec serializes the Value.MapV / Value.SetV variants, which
    // carry kotlinx.collections.immutable PersistentMap / PersistentSet. The
    // library reaches :runtime only transitively via :interpreter's
    // `implementation` dependency, so it is not on the compile classpath —
    // declare it directly here.
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7")
    // Layer 6 step 2: per-machine coroutine actors over Channel<Value>, plus
    // select-based multi-stream merge per Q-009's nondeterministic-merge default.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // Virtual-time test dispatcher for actor-loop assertions without wall-clock dependence.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

// The containment-demonstration tenant programs (compiled canonical dag-json)
// live at the top-level demos/containment-host/programs/ as a single source of
// truth (the demo is self-contained and discoverable there). Copy them onto the
// test classpath under /demo/programs/ so ContainmentDemo and ContainmentDemoTest
// load them via getResourceAsStream without duplicating the JSON in git or
// depending on a fragile relative working-directory path.
private val demoProgramsDir =
    projectDir.parentFile.parentFile.resolve("demos/containment-host/programs")

tasks.named<ProcessResources>("processTestResources") {
    from(demoProgramsDir) {
        include("*.json")
        into("demo/programs")
    }
}

tasks.test {
    inputs.dir(demoProgramsDir)
}

// Print the containment-demonstration transcript. The driver lives in the test
// source set (it shares scenario code with ContainmentDemoTest), so it runs on
// the test runtime classpath. Usage: `./gradlew :runtime:containmentDemo -q`.
tasks.register<JavaExec>("containmentDemo") {
    group = "verification"
    description = "Print the untrusted-agent-program host containment demonstration transcript."
    dependsOn("testClasses", "processTestResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.strand.runtime.ContainmentDemo")
}
