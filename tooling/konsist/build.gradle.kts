plugins {
    // Applied directly rather than through a PRISM convention plugin. This file
    // installs into a CONSUMER's repository, where PRISM ships only the four
    // verification plugins -- it does not ship, and must not impose, a way of
    // configuring Kotlin/JVM modules. The consumer's own conventions are theirs.
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    // Coverage is opted into explicitly here, because nothing else applies it
    // any more: prism.jacoco used to ride in on the deleted convention plugins.
    id("prism.jacoco")
    id("prism.ktlint")
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertk.jvm)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
