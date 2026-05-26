plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    api(project(":verifier"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(project(":hashing"))
}
