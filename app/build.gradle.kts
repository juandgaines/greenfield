plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("prism.ktlint")
    id("prism.detekt")
    id("prism.jacoco")
}

android {
    namespace = "com.juandgaines.prismgreen"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.juandgaines.prismgreen"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // JUnit5 + assertK, which PRISM's JUnit4InJvmUnitTest and NonAssertKAssertion
    // rules require of every NEW jvm test. The version catalog already carries
    // them; the module has to declare them.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.assertk.jvm)
    // Gradle needs the launcher on the test runtime classpath to start the
    // JUnit Platform at all: without it the run fails before any test does.
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// JUnit5 needs the platform runner switched on explicitly.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
