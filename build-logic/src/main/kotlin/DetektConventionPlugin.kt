import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.project

/**
 * Wires detekt into whichever project applies this plugin.
 *
 * The gate runs the plugin's plain `detekt` task — syntax-only, no type
 * resolution — against the single rule set in the root `detekt.yml`. No rule is
 * enabled, disabled, or parameterised here: the configuration file is the only
 * surface, mirroring how `.editorconfig` is the only surface for ktlint.
 *
 * The detekt version is pinned exactly in `libs.versions.toml`. While the
 * pinned line is a 2.0 pre-release, a version bump is its own commit, gated by
 * the `:tooling:prism-rules` suite and a zero-finding `staticAnalysis` run,
 * and reverted if either fails.
 *
 * Third-party and project-specific rule sets are added to the `detektPlugins`
 * configuration so that every module picks them up without repeating itself.
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            pluginManager.apply("dev.detekt")

            // Bound at project scope on purpose -- see the note in
            // KtlintConventionPlugin. Inside a configure block the receiver is
            // usually not the Project, and a bare `path` then names something
            // that is not a module.
            val modulePath = path
            val observed = PrismScope.observes(project, modulePath, "detekt")

            extensions.configure<DetektExtension> {
                config.setFrom(rootProject.layout.projectDirectory.file("detekt.yml"))
                buildUponDefaultConfig.set(true)
                // detekt's default source set is src/{main,test}/{java,kotlin} and omits
                // src/androidTest/java, where every instrumentation suite in this repo
                // lives. Left to the default the gate reports success over code it never
                // read, so the set is declared rather than inherited.
                source.setFrom(
                    files(
                        "src/main/java",
                        "src/main/kotlin",
                        "src/test/java",
                        "src/test/kotlin",
                        "src/androidTest/java",
                        "src/androidTest/kotlin",
                    ),
                )
                basePath.set(rootProject.layout.projectDirectory)

                // ONE BASELINE PER MODULE, beside that module's build script.
                //
                // This line used to point every module at one file at the
                // repository root, and the comment justifying it said baseline
                // ids embed the file path relative to basePath "so entries from
                // fifteen modules cannot collide". THAT WAS FALSE. An id carries
                // the BARE FILENAME -- `MagicNumber:RunningTracker.kt:...` -- and
                // basePath does not change it. Measured on one 26-module
                // repository's own baseline: 193 ids, 0 containing a path
                // separator, 36 file names appearing more than once. Two modules
                // holding a `RunningTracker.kt` produced the same id, the union
                // deduplicated it, and TWO findings were recorded as ONE entry.
                //
                // The consequence was a false green, which is worse than a false
                // denial: promoting one of those modules could not tell which
                // entries were its own, correctly refused to guess, kept them --
                // and the kept entries were suppressing six real findings in the
                // module being promoted. An entry in this module's file can only
                // ever be read while this module is analysed, so the question
                // does not arise. ktlint has always worked this way.
                //
                // A MISSING file is a complete no-op: detekt emits `--baseline`
                // only when the file exists. An enforcing install that never
                // generated one therefore behaves exactly as it did before this
                // line, which is why no placeholder guards it.
                baseline.set(layout.projectDirectory.file(BASELINE_FILE))

                // OBSERVE RUNS THE ENGINE AND DOES NOT FAIL THE BUILD. It is
                // not `active: false` and it is not an exclusion: every rule
                // still runs, every finding still lands in
                // build/reports/detekt/, and only the exit status changes.
                // That distinction is the whole point -- a module nobody has
                // got to yet should still be able to tell you how much work is
                // in it.
                //
                // Absent scope file == enforce, so an enforcing install reaches
                // this line with `false` and behaves exactly as it did before
                // per-module posture existed.
                ignoreFailures.set(observed)
            }

            // Creation tasks write per-task FRAGMENTS, never the baseline itself.
            //
            // DetektCreateBaselineTask REPLACES currentIssues rather than merging
            // them -- it keeps only manuallySuppressedIssues from the file it finds
            // and writes this run's ids as the whole set. A module runs two or
            // three of these tasks (`detekt` for the test sources, plus
            // `detektMain` or `detektDebug` for the resolution-backed rules), so
            // pointing them at one file leaves whichever ran LAST and drops the
            // others: a baseline that looks complete and covers one source set.
            // lib/baseline.py unions each module's fragments into that module's
            // baseline instead.
            val fragments = layout.buildDirectory.dir(BASELINE_FRAGMENT_DIR)
            tasks.withType(DetektCreateBaselineTask::class.java).configureEach {
                baseline.set(fragments.map { dir -> dir.file("$name.xml") })
            }

            dependencies.add(DETEKT_PLUGINS_CONFIGURATION, libs.findLibrary("compose-rules-detekt").get())
            dependencies.add(DETEKT_PLUGINS_CONFIGURATION, dependencies.project(RULES_PROJECT))
        }
    }

    private companion object {
        const val DETEKT_PLUGINS_CONFIGURATION = "detektPlugins"
        const val RULES_PROJECT = ":tooling:prism-rules"
        const val BASELINE_FILE = "detekt.baseline.xml"
        const val BASELINE_FRAGMENT_DIR = "prism/baseline"
    }
}
