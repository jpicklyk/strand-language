plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    api(project(":verifier"))
    api(project(":interpreter"))
    testImplementation(project(":hashing"))
}
