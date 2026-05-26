plugins {
    kotlin("jvm")
}

dependencies {
    // The bytecode module lowers verified canonical NodeStores into a
    // chunk-keyed bytecode representation. It depends on :verifier (to
    // get the verified types alongside the store) and :core (for the
    // Node ADT). Tests use :hashing to finalize ingested programs.
    api(project(":core"))
    api(project(":verifier"))
    testImplementation(project(":hashing"))
}
