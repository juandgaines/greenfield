import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Gate 0, assembled on the root project.
 *
 * Two of the engine's scripts run `./gradlew staticAnalysis ktlintToolingClasspath`
 * by name — `stop-gate.sh` and `push-gate.sh` — and neither task is a Gradle
 * built-in. They were hand-written in the origin repository's root build file,
 * which meant the framework shipped gates calling tasks it did not supply: a
 * consumer who installed everything else got `Task 'staticAnalysis' not found`,
 * and gate 0 became a no-op the first time anything invoked it.
 *
 * Registering them here rather than in a template the consumer merges keeps the
 * wiring beside the plugins it matches on. The `plugins.withId` strings below are
 * the ids `build.gradle.kts` registers for `KtlintConventionPlugin` and
 * `DetektConventionPlugin`; getting one wrong silently drops every module of that
 * kind out of the aggregate, which is the framework's documented false-green
 * failure. In one file the ids and their consumers cannot drift apart.
 *
 * The consumer applies it to their root project and nothing else:
 *
 *     plugins { id("prism.static-analysis") }
 */
class StaticAnalysisConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            check(this == rootProject) {
                "prism.static-analysis belongs on the root project: it aggregates every " +
                    "subproject's analyser tasks and writes the ktlint classpath the " +
                    "per-edit hook reads. Applied to $path it would aggregate nothing."
            }

            registerKtlintToolingClasspath()

            val staticAnalysis =
                tasks.register(STATIC_ANALYSIS_TASK) {
                    group = VERIFICATION_GROUP
                    description =
                        "Runs every static analyser over the whole repository. " +
                        "Gate 0 of the Definition of Done."

                    // build-logic is an included build, so its own sources are invisible to
                    // the subprojects loop below. They are Kotlin the consumer can break
                    // like any other, and leaving them out means the convention plugins
                    // that define the gates are the one thing the gates never read.
                    dependsOn(buildLogicKtlintTask())
                }

            wireKonsist(staticAnalysis)

            // The baseline aggregate, deliberately a MIRROR of gate 0's detekt
            // branches below rather than a task list of its own.
            //
            // If the baseline is produced by a different set of tasks than the gate
            // runs, the install writes a baseline that does not cover what blocks --
            // silently, and only on the modules whose task nobody thought of. One
            // loop, two aggregates, no way for them to disagree.
            //
            // Not wired into `staticAnalysis`: recording findings is not verifying
            // them, and a gate that could write its own baseline would be an escape
            // hatch with a task name.
            val prismBaseline =
                tasks.register(PRISM_BASELINE_TASK) {
                    group = VERIFICATION_GROUP
                    description =
                        "Records what every engine currently finds: detekt as per-task " +
                        "fragments to merge with .prism/verify/lib/baseline.py collect, " +
                        "ktlint as one ktlint.baseline.xml per module."
                }

            subprojects {
                // Bound here, deliberately. Inside `staticAnalysis.configure` the
                // receiver is the aggregate Task, so a bare `path` there reads
                // ":staticAnalysis" and every dependency points at a project that does
                // not exist.
                val module = path

                plugins.withId(KTLINT_PLUGIN_ID) {
                    staticAnalysis.configure { dependsOn("$module:$KTLINT_CHECK_TASK") }
                    // The same mirror as detekt below, for the same reason: a
                    // baseline produced by a different set of tasks than the
                    // gate runs covers less than what blocks, silently.
                    prismBaseline.configure { dependsOn("$module:$KTLINT_BASELINE_TASK") }
                }
                plugins.withId(DETEKT_PLUGIN_ID) {
                    staticAnalysis.configure { dependsOn("$module:detekt") }
                    prismBaseline.configure { dependsOn("$module:detektBaseline") }
                    // The plain `detekt` task is syntax-only and skips every rule that
                    // needs the Analysis API, so the resolution-backed rules run through
                    // the plugin's type-resolving main-source task as well: one variant
                    // on Android modules, `detektMain` on JVM modules. The plain task
                    // above is what keeps the test source sets covered.
                    plugins.withId(ANDROID_BASE_PLUGIN_ID) {
                        staticAnalysis.configure { dependsOn("$module:detektDebug") }
                        prismBaseline.configure { dependsOn("$module:detektBaselineDebug") }
                    }
                    plugins.withId(KOTLIN_JVM_PLUGIN_ID) {
                        staticAnalysis.configure { dependsOn("$module:detektMain") }
                        prismBaseline.configure { dependsOn("$module:detektBaselineMain") }
                    }
                }
            }
        }
    }

    /**
     * Resolves the ktlint CLI and writes its classpath where the per-edit hook reads
     * it, at `build/ktlint/cli-classpath.txt`.
     *
     * The hook runs ktlint directly rather than through Gradle — a Gradle invocation
     * per edited file is the cost the file exists to avoid — so it needs the resolved
     * classpath on disk. The shadowed variant is asked for because it is a single
     * self-contained jar, which keeps that file down to one line.
     */
    private fun Project.registerKtlintToolingClasspath() {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        configurations.resolvable(KTLINT_CONFIGURATION) {
            attributes {
                attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
            }
        }
        dependencies.add(KTLINT_CONFIGURATION, libs.findLibrary("ktlint-cli").get())

        tasks.register(KTLINT_CLASSPATH_TASK) {
            group = VERIFICATION_GROUP
            description =
                "Writes the resolved ktlint CLI classpath so tooling can run it without Gradle."

            val classpath: FileCollection = configurations.getByName(KTLINT_CONFIGURATION)
            val classpathFile = layout.buildDirectory.file("ktlint/cli-classpath.txt")
            inputs.files(classpath)
            outputs.file(classpathFile)

            doLast {
                val output = classpathFile.get().asFile
                output.parentFile.mkdirs()
                output.writeText(classpath.joinToString(File.pathSeparator) { it.absolutePath })
            }
        }
    }

    /**
     * Adds the architecture rules, which are a test suite rather than a plugin and so
     * cannot be picked up by the `plugins.withId` loop.
     *
     * They run only once their scope has been rendered. `ProjectScope.kt` is generated
     * from `ProjectScope.kt.in` at install, and the package root it carries is a
     * required parameter with no default — precisely because a guessed one yields an
     * empty scope in which every rule passes over nothing. An unrendered module is
     * therefore not a module whose rules are being skipped; it is one that has no
     * rules yet, and the only tree in which that state can exist is the framework's
     * own, where there is no consumer project to analyse. In an install, rendering is
     * fail-closed: `render.py` writes no file it could not fully resolve, so the
     * source is either there and correct or the install stopped.
     */
    private fun Project.wireKonsist(staticAnalysis: org.gradle.api.tasks.TaskProvider<*>) {
        val konsist = findProject(KONSIST_PROJECT) ?: return
        val scope = File(konsist.projectDir, KONSIST_SCOPE_SOURCE)
        if (!scope.isFile) return
        staticAnalysis.configure { dependsOn("$KONSIST_PROJECT:test") }

        // KONSIST CANNOT BE MADE NON-BLOCKING PER MODULE THE WAY THE OTHERS
        // CAN. It is one module analysing the whole graph: one test task, one
        // exit status. So the observed modules are handed to it as a system
        // property and RuleAssertions PARTITIONS its violations -- those in
        // enforced modules fail, those in observed modules are printed.
        //
        // Resolved here rather than in the konsist module's own build file so
        // that PrismScope stays the single Gradle-side interpreter of
        // scope.json, and so that a consumer-owned build file needs no edit.
        //
        // A PROJECT WITH NO BUILD FILE IS NOT A MODULE. `subprojects` includes
        // Gradle's implicit container projects -- :core exists because
        // settings.gradle.kts says include(":core:domain"), and nothing ever
        // writes include(":core"). A container holds no code and carries no
        // scope entry, so it resolves to `default`, joins this list when the
        // default is observe, and becomes the directory prefix "/core/" --
        // which swallows "/core/domain/" whole. A module promoted to
        // konsist: enforce then reported `enforce` in status, found its
        // violations, printed them, and passed the build.
        //
        // RuleAssertions guards the SIBLING collision with a trailing slash
        // (:core:data must not swallow :core:database) and says so. That guard
        // cannot help here: a parent genuinely is a prefix of its children.
        // Every other reader of the module set -- ScopeIntegrityTest,
        // scope.py, affected.sh -- derives it from settings.gradle.kts and so
        // excludes containers for free. `subprojects` was the sole outlier.
        val observedModules =
            subprojects
                .filter { it.buildFile.isFile }
                // Filtered as Projects, then mapped: PrismScope needs a Project
                // so it can read scope.json through a provider, which is what
                // makes the file a configuration-cache input.
                .filter { PrismScope.observes(it, it.path, "konsist") }
                .map { it.path }
                .sorted()
        konsist.tasks.withType(Test::class.java).configureEach {
            systemProperty(KONSIST_OBSERVE_PROPERTY, observedModules.joinToString(","))
        }
    }

    /**
     * build-logic's own ktlint task, found rather than assumed.
     *
     * TWO ASSUMPTIONS USED TO BE HARDCODED HERE, and a real install into a
     * 26-module repository broke on both. `gradle.includedBuild("build-logic")
     * .task(":ktlintCheck")` requires that the included build be *named*
     * build-logic and that PRISM's plugins sit on its ROOT project.
     *
     * Neither holds in the shape SETUP.md itself names. A repository that
     * already has a `build-logic` — the common case, and the shape every
     * project derived from Now-in-Android has — cannot have PRISM's four
     * plugins on that root,
     * because the root is already theirs. The merge puts them in a subproject,
     * the task becomes `:prism:ktlintCheck`, and gate 0 then fails at
     * dependency resolution with `Task with name 'ktlintCheck' not found in
     * project ':build-logic'` — before running a single analyser. Measured.
     *
     * So: the build is located by name and, failing that, by being the only one
     * included; and the task path inside it is a property, defaulting to the
     * root so a repository with no build-logic of its own is unchanged. The
     * install writes the property when it puts the plugins in a subproject.
     */
    private fun Project.buildLogicKtlintTask() =
        with(gradle) {
            val build =
                includedBuilds.firstOrNull { it.name == BUILD_LOGIC }
                    ?: includedBuilds.singleOrNull()
                    ?: error(
                        "prism.static-analysis found no included build to take " +
                            "build-logic's own ktlint run from. Included builds: " +
                            includedBuilds.joinToString { it.name }.ifEmpty { "none" } +
                            ". PRISM's convention plugins live in an included build; " +
                            "settings.gradle.kts must includeBuild it.",
                    )
            val path =
                providers.gradleProperty(BUILD_LOGIC_KTLINT_PROPERTY).orNull
                    ?: ":$KTLINT_CHECK_TASK"
            build.task(path)
        }

    private companion object {
        const val VERIFICATION_GROUP = "verification"
        const val STATIC_ANALYSIS_TASK = "staticAnalysis"
        const val PRISM_BASELINE_TASK = "prismBaseline"
        const val KONSIST_OBSERVE_PROPERTY = "prism.konsist.observe"
        const val KTLINT_CLASSPATH_TASK = "ktlintToolingClasspath"
        const val KTLINT_CHECK_TASK = "ktlintCheck"
        const val KTLINT_BASELINE_TASK = "prismKtlintBaseline"
        const val KTLINT_CONFIGURATION = "ktlint"
        const val BUILD_LOGIC = "build-logic"
        const val BUILD_LOGIC_KTLINT_PROPERTY = "prism.buildLogic.ktlintTask"
        const val KTLINT_PLUGIN_ID = "prism.ktlint"
        const val DETEKT_PLUGIN_ID = "prism.detekt"
        const val ANDROID_BASE_PLUGIN_ID = "com.android.base"
        const val KOTLIN_JVM_PLUGIN_ID = "org.jetbrains.kotlin.jvm"
        const val KONSIST_PROJECT = ":tooling:konsist"
        const val KONSIST_SCOPE_SOURCE =
            "src/test/kotlin/com/plcoding/prism/tooling/konsist/ProjectScope.kt"
    }
}
