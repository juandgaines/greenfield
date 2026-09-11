import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Wires ktlint into whichever project applies this plugin.
 *
 * The CLI is resolved into a dedicated `ktlint` configuration from the version
 * catalog, and two tasks run it over the project's own Kotlin sources:
 *
 *  - `ktlintCheck` — read-only, fails the build on any violation. This is what
 *    the root `staticAnalysis` aggregate depends on.
 *  - `ktlintFormat` — the same scan with `--format`, autocorrecting in place.
 *
 * Neither task passes a rule id or a rule-configuration flag: the whole rule
 * set lives in the root `.editorconfig` so that Gradle, the per-edit hook, and
 * the IDE all see the identical configuration.
 *
 * A BASELINE IS PASSED, AND IT IS PER MODULE. ktlint had no baseline at all
 * until this, which meant a repository with violations `ktlintFormat` cannot
 * autocorrect -- 54 of them on the measured install, 35 being `package-name`
 * -- could never reach a green gate 0 and therefore could never enforce.
 * detekt had an on-ramp and ktlint did not, so "install in observe and burn it
 * down" only ever worked for one of the two.
 *
 * It is per module rather than one root file because ktlint records baseline
 * paths relative to its working directory, and every module runs with
 * `workingDir = projectDir`. A single root file would collide
 * `src/main/kotlin/Foo.kt` from every module onto one entry.
 *
 * The baseline is passed ONLY when it already exists. `--baseline` pointed at
 * a missing file makes ktlint CREATE it and report success, so passing it
 * unconditionally would turn the first `ktlintCheck` on a dirty repository
 * into a silent, permanent amnesty. `prismKtlintBaseline` is the task that
 * creates one, deliberately and by name.
 */
class KtlintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            configurations.resolvable(KTLINT_CONFIGURATION) {
                // ktlint-cli publishes both a plain and a shadowed runtime variant.
                // The shadowed one is a single self-contained jar.
                attributes {
                    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
                }
            }
            dependencies.add(KTLINT_CONFIGURATION, libs.findLibrary("ktlint-cli").get())

            val baselineFile = File(projectDir, BASELINE_FILE)

            // Bound HERE, at project scope. Inside `tasks.register<JavaExec> {}`
            // the receiver is the TASK, so a bare `path` there reads
            // ":module:ktlintCheck" -- which matches no module in scope.json,
            // falls back to the default, and silently enforces a module the
            // consumer set to observe. Same trap as the one documented in
            // StaticAnalysisConventionPlugin's subprojects loop, and it was
            // walked into again here.
            val modulePath = path
            val observed = PrismScope.observes(project, modulePath, "ktlint")

            tasks.register<JavaExec>("ktlintCheck") {
                group = "verification"
                description = "Checks Kotlin sources in this project against the root .editorconfig rule set."
                classpath = configurations.getByName(KTLINT_CONFIGURATION)
                mainClass.set(KTLINT_MAIN_CLASS)
                workingDir = projectDir
                args = SOURCE_PATTERNS + REPORTING_ARGS
                // Resolved at EXECUTION time, not configuration time: the
                // baseline is usually written by prismKtlintBaseline in the
                // same invocation that then checks against it.
                argumentProviders.add {
                    if (baselineFile.isFile) listOf("--baseline=$BASELINE_FILE") else emptyList()
                }
                // OBSERVE RUNS THE ENGINE AND DOES NOT FAIL THE BUILD. Every
                // violation is still printed; only the exit status changes.
                isIgnoreExitValue = observed
            }

            // Seeding is its own task and is never wired into staticAnalysis --
            // a gate that could write its own baseline would be an escape hatch
            // with a task name.
            tasks.register<JavaExec>("prismKtlintBaseline") {
                group = "verification"
                description = "Records this project's current ktlint violations in $BASELINE_FILE."
                classpath = configurations.getByName(KTLINT_CONFIGURATION)
                mainClass.set(KTLINT_MAIN_CLASS)
                workingDir = projectDir
                args = SOURCE_PATTERNS + REPORTING_ARGS + "--baseline=$BASELINE_FILE"
                // ktlint exits non-zero while reporting the violations it is
                // recording. That is the task succeeding, not failing.
                isIgnoreExitValue = true
            }

            tasks.register<JavaExec>("ktlintFormat") {
                group = "formatting"
                description = "Autocorrects Kotlin sources in this project and reports what could not be fixed."
                classpath = configurations.getByName(KTLINT_CONFIGURATION)
                mainClass.set(KTLINT_MAIN_CLASS)
                workingDir = projectDir
                args = SOURCE_PATTERNS + REPORTING_ARGS + "--format"
                // Formatting must not stop at the first project with a violation that
                // has no autocorrect; ktlintCheck is the gate, this task only repairs.
                isIgnoreExitValue = true
            }
        }
    }

    private companion object {
        const val KTLINT_CONFIGURATION = "ktlint"
        const val KTLINT_MAIN_CLASS = "com.pinterest.ktlint.Main"
        const val BASELINE_FILE = "ktlint.baseline.xml"

        val SOURCE_PATTERNS =
            listOf(
                "src/**/*.kt",
                "src/**/*.kts",
                "*.kts",
                "!**/build/**",
                "!**/generated/**",
            )

        val REPORTING_ARGS =
            listOf(
                "--relative",
                "--reporter=plain",
            )
    }
}
