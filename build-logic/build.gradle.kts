plugins {
    `kotlin-dsl`
}

// build-logic is an included build, so it cannot apply its own `prism.ktlint`
// plugin. The tasks are registered here by hand against the same rule set.
val ktlint: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    // ktlint-cli publishes both a plain and a shadowed runtime variant. The
    // shadowed one is a single self-contained jar, which keeps the classpath
    // file the hook reads down to one line.
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
}

dependencies {
    ktlint(libs.ktlint.cli)
    implementation(libs.detekt.gradle.plugin)

    // compileOnly, not implementation. JacocoConventionPlugin references AGP's
    // LibraryExtension and ApplicationExtension, so it needs the types to
    // compile -- but it reaches them only inside pluginManager.withPlugin
    // callbacks, which never fire in a project that has no AGP. Requiring AGP
    // on the runtime classpath would make PRISM uninstallable in a pure
    // Kotlin/JVM repository for the sake of a branch that would not execute.
    // COORDINATES, NOT LIBRARY ALIASES, and a real install is why.
    //
    // A version catalog alias becomes a Kotlin accessor by splitting on -, _ and
    // . and camel-casing, so `android-gradle-plugin` and the `android-gradlePlugin`
    // that every project derived from Now-in-Android already declares both
    // generate `getAndroidGradlePlugin()`. Merging ours in beside theirs is not a
    // duplicate entry, it is a NAME CLASH: Gradle refuses to generate accessors
    // for the whole catalog and no project in the build can configure. Measured
    // on a 26-module install, where the merge rule "add only absent aliases" made
    // the repository unbuildable because the alias was absent and the accessor was
    // not.
    //
    // The version refs do not collide (`agp`, `kotlin` are single words), and
    // these two are compileOnly plugin jars whose coordinates are fixed, so
    // naming them directly removes the only two aliases that clash in practice.
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}

gradlePlugin {
    plugins {
        register("jacoco") {
            id = "prism.jacoco"
            implementationClass = "JacocoConventionPlugin"
        }
        register("ktlint") {
            id = "prism.ktlint"
            implementationClass = "KtlintConventionPlugin"
        }
        register("detekt") {
            id = "prism.detekt"
            implementationClass = "DetektConventionPlugin"
        }
        register("staticAnalysis") {
            id = "prism.static-analysis"
            implementationClass = "StaticAnalysisConventionPlugin"
        }
    }
}

val ktlintSourcePatterns =
    listOf(
        "src/**/*.kt",
        "src/**/*.kts",
        "*.kts",
        "!**/build/**",
        "!**/generated/**",
    )
val ktlintReportingArgs = listOf("--relative", "--reporter=plain")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks build-logic sources against the root .editorconfig rule set."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = projectDir
    args = ktlintSourcePatterns + ktlintReportingArgs
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Autocorrects build-logic sources and reports what could not be fixed."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = projectDir
    args = ktlintSourcePatterns + ktlintReportingArgs + "--format"
    isIgnoreExitValue = true
}
