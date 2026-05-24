plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":verifier"))
    implementation(project(":interpreter"))
    implementation(project(":hashing"))
    implementation(project(":runtime"))
    implementation(project(":schema"))
}

application {
    mainClass.set("org.strand.cli.MainKt")
}
