plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    api(project(":verifier"))
    testImplementation(project(":hashing"))
}
