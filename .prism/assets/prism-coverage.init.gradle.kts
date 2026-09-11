// PRISM JVM unit-test coverage — applied at runtime via `--init-script`.
//
// This is deliberately non-invasive: it does NOT write anything to the project's
// committed build files. It attaches JaCoCo to each subproject's JVM unit-test
// task (so the test run records execution data) and registers a `prismCoverage`
// task that emits a JaCoCo XML report. A bundled Python script then renders that
// XML as the Android-Studio-style module → package → class table.
//
// Why JaCoCo: `testDebugUnitTest` / `test` produce no coverage on their own —
// Gradle has no built-in coverage engine. JaCoCo instruments the compiled
// bytecode in-process during the normal JVM test run (no device, no Android
// instrumented tests). This is the same engine AGP's `enableUnitTestCoverage`
// uses under the hood.

import java.io.File
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

allprojects {
    afterEvaluate {
        // Pick the JVM unit-test task by applied plugin, NOT by task existence.
        // AGP creates `testDebugUnitTest` in its own afterEvaluate, which may run
        // after this one — so `tasks.findByName(...)` would miss it. Plugins, by
        // contrast, are already applied by the time any afterEvaluate runs.
        // We depend on the task by name (a String dependency Gradle resolves
        // lazily at execution time), so the task need not exist yet here.
        val isAndroid = pluginManager.hasPlugin("com.android.base")
        val isKotlinJvm = pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") ||
            pluginManager.hasPlugin("java")
        val unitTestTaskName = when {
            isAndroid -> "testDebugUnitTest"
            isKotlinJvm -> "test"
            else -> return@afterEvaluate // no JVM unit-test task (e.g. an umbrella module)
        }

        // Applying `jacoco` makes the JaCoCo plugin attach its agent to every
        // `Test` task (enabled by default), so the unit-test run writes a `.exec`
        // execution-data file at build/jacoco/<taskName>.exec.
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

        // Exclude generated / non-meaningful code so the numbers match what a human
        // would consider "their code" — the same things Android Studio omits.
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

        // CLASS OUTPUT DIRECTORIES — ASKED FOR, NOT GUESSED.
        //
        // This was a flat list of six plausible paths, unioned, annotated "we
        // list every plausible location and keep only the ones that exist".
        // The first half is true of Gradle's behaviour — a fileTree over a
        // missing directory contributes nothing — but it rested on an unstated
        // assumption: that only ONE of them would exist. That does not hold.
        //
        // Measured on a repository upgraded from AGP 8.x to AGP 9:
        // build/tmp/kotlin-classes/debug was still there, ten months old,
        // holding the same class NAMES compiled by a different compiler. AGP 9
        // writes only intermediates/built_in_kotlinc/..., and nothing had ever
        // cleaned the old tree. The union handed JaCoCo two copies of
        // RoomLocalRunDataSource$upsertRuns$1 and CoverageBuilder refused
        // them — "Can't add different class with same name". Every Android
        // module in that repository failed, so coverage could not be measured,
        // and therefore not enforced, on 20 of its 26 modules.
        //
        // Ordering the list is not a fix. It only decides which of two
        // compilations wins, silently, and a wrong coverage number passes a
        // gate where a crash at least stops one.
        //
        // So the directories come from the compile tasks, which are the only
        // things that know where they actually wrote. The literal paths remain
        // as a fallback for a build whose tasks are named something else, and
        // there two live candidates FAIL rather than merge.
        val kotlinTask = if (isAndroid) "compileDebugKotlin" else "compileKotlin"
        val javaTask = if (isAndroid) "compileDebugJavaWithJavac" else "compileJava"

        val legacyKotlin = if (isAndroid) {
            listOf(
                "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes", // AGP 9.x
                "tmp/kotlin-classes/debug",                                        // AGP 8.x
            )
        } else {
            listOf("classes/kotlin/main")
        }
        val legacyJava = if (isAndroid) {
            listOf(
                "intermediates/javac/debug/compileDebugJavaWithJavac/classes",
                "intermediates/javac/debug/classes",
            )
        } else {
            listOf("classes/java/main")
        }

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

        // Resolved LAZILY. AGP registers its tasks in its own afterEvaluate,
        // which may run after this one — the same reason unitTestTaskName above
        // is depended on as a String. A provider defers the lookup to execution
        // time, when every task exists.
        val classDirectoriesTree = provider {
            val declared = listOf(kotlinTask, javaTask)
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

        // Two possible homes for the unit-test execution data, and which one is
        // used depends on whether AGP is also instrumenting the module:
        //
        //   build/jacoco/<task>.exec
        //       written by the JaCoCo plugin's own agent, applied above. This is
        //       where a pure Kotlin/JVM module's data lands.
        //   build/outputs/unit_test_code_coverage/debugUnitTest/*.exec
        //       written when AGP's `enableUnitTestCoverage` is on. AGP takes over
        //       the agent configuration and RELOCATES the output, so the first
        //       path is never created for such a module.
        //
        // Since coverage configuration became universal, every Android module has
        // that AGP flag set, so an Android module with unit tests only — where
        // this script is the one the push gate runs — writes exclusively to the
        // second path. Looking only at the first made the report task resolve to
        // no execution data, SKIP, and emit nothing, which the gate then reported
        // as an unevaluable module rather than as a coverage number.
        //
        // A fileTree tolerates the missing one instead of failing on it.
        val executionDataFile = fileTree(layout.buildDirectory) {
            include("jacoco/$unitTestTaskName.exec")
            include("outputs/unit_test_code_coverage/debugUnitTest/*.exec")
        }

        tasks.register("prismCoverage", JacocoReport::class.java) {
            dependsOn(unitTestTaskName)
            group = "verification"
            description = "PRISM JVM unit-test coverage (JaCoCo XML for the render script)."

            sourceDirectories.setFrom(sourceDirs)
            classDirectories.setFrom(classDirectoriesTree)
            executionData.setFrom(executionDataFile)

            reports {
                xml.required.set(true)
                html.required.set(false)
                csv.required.set(false)
                xml.outputLocation.set(
                    layout.buildDirectory.file("reports/prism-coverage/coverage.xml")
                )
            }
        }
    }
}
