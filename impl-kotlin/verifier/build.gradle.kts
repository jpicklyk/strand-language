plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    testImplementation(project(":hashing"))
}
