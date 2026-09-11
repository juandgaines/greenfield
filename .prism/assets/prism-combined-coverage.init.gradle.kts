// PRISM COMBINED unit + instrumentation coverage — applied at runtime via `--init-script`.
//
// Non-invasive: writes nothing to committed build files, produces no git diff.
// It registers a `prismCombinedCoverage` JacocoReport task whose executionData
// merges BOTH execution-data sets for a module:
//
//   - the JVM unit-test `.exec`  (build/jacoco/testDebugUnitTest.exec), written by the
//     JaCoCo agent during `testDebugUnitTest`, and
//   - the on-device androidTest `.ec` files (build/outputs/code_coverage/**/*.ec),
//     pulled back from the emulator by AGP during `connectedDebugAndroidTest` when
//     `enableAndroidTestCoverage = true`.
//
// JaCoCo merges the two coverage sets so a line/branch covered by EITHER suite counts
// as covered. The same bundled render_coverage.py renders the resulting XML.
//
// The caller must run BOTH test suites before this report task:
//   ./gradlew :module:testDebugUnitTest
//   ANDROID_SERIAL=<serial> ./gradlew :module:connectedDebugAndroidTest
// This task only depends on `testDebugUnitTest` (cheap, deviceless); the on-device run
// is left explicit so re-rendering never silently redeploys to a device.

import java.io.File
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

allprojects {
    afterEvaluate {
        // Only Android library/application modules produce androidTest coverage.
        val isAndroid = pluginManager.hasPlugin("com.android.base")
        if (!isAndroid) return@afterEvaluate

        // Ensure the JaCoCo agent is attached to the unit-test task so it emits .exec.
        pluginManager.apply("jacoco")

        // Robolectric loads the classes under test through its OWN instrumenting
        // classloader, and JaCoCo's on-the-fly agent then sees them as classes with
        // no source location and drops them from the report — so a module whose
        // unit tests are Robolectric-based measures 0% while its tests pass. That is
        // a measurement artefact, and seeding a ratchet floor from it would record a
        // module as untested when it is not.
        //
        // `isIncludeNoLocationClasses` tells the agent to keep those classes;
        // excluding `jdk.internal.*` is the companion setting that stops the agent
        // choking on JDK-internal classes it must not instrument.
        tasks.withType(Test::class.java).configureEach {
            extensions.configure(JacocoTaskExtension::class.java) {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }

        // Exclude generated / non-meaningful code, matching what Android Studio omits.
        val coverageExcludes = listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*_Factory.*",
            "**/*_MembersInjector.*",
            "**/Dagger*.*",
            "**/di/**",
            "**/*ComposableSingletons*.*",
            "**/*\$\$serializer.*",
            // Room writes the DAO and database implementations, and they are
            // the bulk of a persistence module: in :core:files-db they are 376
            // of 409 lines, so measuring them dragged a module whose own code
            // was 85% covered down to 54% and made the threshold unreachable
            // by any test anyone could write. The second pattern is required
            // for the synthetic nested classes (FilesDao_Impl$4,
            // FilesDatabase_Impl$createOpenDelegate$_openDelegate$1), which
            // carry most of those lines.
            "**/*_Impl.*",
            "**/*_Impl\$*.*",
        )

        // CLASS OUTPUT DIRECTORIES — ASKED FOR, NOT GUESSED. The same defect and
        // the same reasoning as prism-coverage.init.gradle.kts; see the long
        // note there. In short: a flat list of plausible paths, unioned,
        // assumed only one would exist. After an AGP 8.x -> 9 upgrade both do,
        // because nothing cleaned the old tree, and JaCoCo refuses two
        // compilations of the same class name. Ordering them would only make
        // the wrong answer silent.
        //
        // This module is always Android — the non-Android case returned above.
        val legacyKotlin = listOf(
            "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes", // AGP 9.x
            "tmp/kotlin-classes/debug",                                        // AGP 8.x
        )
        val legacyJava = listOf(
            "intermediates/javac/debug/compileDebugJavaWithJavac/classes",
            "intermediates/javac/debug/classes",
        )

        fun legacyDirs(label: String, candidates: List<String>): List<File> {
            val present = candidates
                .map { layout.buildDirectory.dir(it).get().asFile }
                .filter { it.isDirectory }
            if (present.size > 1) {
                throw GradleException(
                    buildString {
                        append("prism coverage: $path has more than one $label class ")
                        append("output directory, and they are different compilations ")
                        append("of the same classes:\n\n")
                        present.forEach {
                            append("  ${it.relativeTo(projectDir)}  (modified ${java.util.Date(it.lastModified())})\n")
                        }
                        append("\nThis is normally left behind by an AGP upgrade: the newer ")
                        append("compiler writes\nsomewhere else and nothing removed the old ")
                        append("tree. Measuring both would hand\nJaCoCo two copies of every ")
                        append("class. Run:\n\n  ./gradlew $path:clean\n\nthen take the ")
                        append("measurement again.")
                    }
                )
            }
            return present
        }

        // Resolved LAZILY: AGP registers its tasks in its own afterEvaluate,
        // which may run after this one.
        val classDirectoriesTree = provider {
            val declared = listOf("compileDebugKotlin", "compileDebugJavaWithJavac")
                // EACH LOOKUP IS GUARDED, and the guard has to cover the
                // OUTPUTS, not just the lookup. Under AGP 9
                // `compileDebugJavaWithJavac` still exists as a task object
                // for a Kotlin-only Android module, but asking it for its
                // outputs raises "Key compileDebugJavaWithJavac is missing in
                // the map" -- and it raises it while Gradle is computing THIS
                // task's dependencies, so the build fails with a message that
                // says nothing about coverage. Checking `tasks.names` does not
                // help: the name is there. A task that cannot name its own
                // outputs compiled nothing, so there is nothing to measure.
                .flatMap { name ->
                    runCatching {
                        tasks.findByName(name)?.outputs?.files?.files.orEmpty()
                    }.getOrElse { emptySet() }
                }
                .filter { it.isDirectory }

            val roots = if (declared.isNotEmpty()) {
                declared
            } else {
                legacyDirs("Kotlin", legacyKotlin) + legacyDirs("Java", legacyJava)
            }

            files(roots.map { root -> fileTree(root) { exclude(coverageExcludes) } })
        }

        val sourceDirs = files("src/main/java", "src/main/kotlin")

        // Merge both execution-data sets. Use fileTrees so missing files are tolerated
        // (a module may have only one suite) and any device sub-directory is captured.
        val unitExec = fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec")
            include("outputs/unit_test_code_coverage/debugUnitTest/*.exec")
        }
        val androidTestExec = fileTree(layout.buildDirectory) {
            include("outputs/code_coverage/**/*.ec")
        }

        tasks.register("prismCombinedCoverage", JacocoReport::class.java) {
            dependsOn("testDebugUnitTest")
            group = "verification"
            description = "PRISM combined unit + instrumentation coverage (merged JaCoCo XML)."

            sourceDirectories.setFrom(sourceDirs)
            classDirectories.setFrom(classDirectoriesTree)
            executionData.setFrom(unitExec, androidTestExec)

            reports {
                xml.required.set(true)
                html.required.set(false)
                csv.required.set(false)
                xml.outputLocation.set(
                    layout.buildDirectory.file("reports/prism-combined-coverage/coverage.xml")
                )
            }
        }
    }
}
