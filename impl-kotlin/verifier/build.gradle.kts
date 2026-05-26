plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    // JsonSchemaProjection (used by the N-044 ToolDef verifier rule
    // and re-exported to the interpreter for tool-parameter projection
    // at LLM.Generate dispatch sites) needs kotlinx-serialization-json
    // to build the JSON Schema objects.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(project(":hashing"))
}
