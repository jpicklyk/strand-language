plugins {
    kotlin("jvm")
}

dependencies {
    // The VM consumes the bytecode produced by :bytecode. It depends on
    // :interpreter for the Value ADT (the VM's runtime values reuse
    // [org.strand.interpreter.Value] so existing tests that compare
    // interpreter vs VM results can use the same equality).
    api(project(":bytecode"))
    api(project(":interpreter"))
    testImplementation(project(":hashing"))
}
