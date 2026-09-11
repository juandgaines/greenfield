// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Pins the catalog's Kotlin on the build's plugin classpath. AGP 9 carries
    // its own older embedded KGP; without this the tooling modules compile
    // against it and fail on detekt 2.0's Kotlin 2.4 metadata.
    alias(libs.plugins.kotlin.jvm) apply false
    id("prism.static-analysis")
}
