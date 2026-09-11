package com.plcoding.prism.tooling.konsist

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The suite's own coverage check.
 *
 * Every other rule here reports nothing on conformant code, so a scope that has
 * quietly shrunk — a module added, a source set moved, a Konsist default changed —
 * would turn the whole suite into a green no-op with no visible symptom. The detekt
 * change found exactly this shape of bug: detekt's default source set silently
 * omitted `src/androidTest`, and it only surfaced by seeding a violation there.
 */
class ScopeIntegrityTest {
    @Test
    fun `the scope covers every module declared in settings-gradle-kts`() {
        val declaredModulePaths = consumerModulePaths()
        assertThat(declaredModulePaths).isNotEmpty()

        val scannedPaths = ProjectScope.files.map { it.projectPath }
        val uncovered =
            declaredModulePaths.filterNot { modulePath ->
                scannedPaths.any { it.startsWith("/$modulePath/") }
            }

        if (uncovered.isNotEmpty()) {
            throw AssertionError(
                "The analysis scope contains no source from ${uncovered.size} declared " +
                    "module(s): ${uncovered.joinToString()}. Every rule in this suite would " +
                    "pass over that code without reading it.",
            )
        }
    }

    /**
     * Every source set that HAS Kotlin on disk must be visible to the scope.
     *
     * Derived from the tree rather than hardcoded. The set used to be a literal
     * `setOf("main", "test", "androidTest")`, which asserted two different things
     * at once: that the analyser can see every source set, and that the consumer
     * owns an `androidTest` one. Only the first is this check's business -- it
     * exists because detekt's default source set once silently omitted
     * `src/androidTest`. A pure Kotlin/JVM consumer, or an Android one without
     * instrumentation tests, failed it forever over a source set they never had,
     * and the message talked about analysis scope while the real complaint was
     * about project layout.
     */
    @Test
    fun `the scope covers every source set the project uses`() {
        val onDisk = sourceSetsWithKotlinOnDisk()
        // A consumer with no Kotlin at all is not a scope defect, it is an empty
        // project -- and the module-coverage check above already says so more
        // precisely. Asserting non-empty here made a greenfield install fail on
        // its own emptiness.
        if (onDisk.isEmpty()) return

        val scanned = ProjectScope.files.map { it.sourceSetName }.toSet()
        val missing = onDisk - scanned

        if (missing.isNotEmpty()) {
            throw AssertionError(
                "The analysis scope contains no file from source set(s): " +
                    "${missing.joinToString()}. Those source sets hold Kotlin on disk, so " +
                    "the analyser is not reading everything the project compiles.",
            )
        }
    }

    @Test
    fun `the scope excludes generated sources`() {
        val generated = ProjectScope.files.filter { it.projectPath.contains("/build/") }

        assertThat(generated.map { it.projectPath }).isEmpty()
    }

    /**
     * The companion of the shrink check above, and the one relocation can break.
     *
     * A scope that quietly widens is the same class of error as one that quietly
     * shrinks, and it arrives the same way: the engine moved out of the harness's
     * directory, so an exclusion naming only that directory stopped covering it.
     * The rules would then report on the framework's own sources -- findings the
     * consumer did not cause and cannot act on -- while looking like they were
     * doing more work, not less.
     *
     * Asserted against `ProjectScope.EXCLUDED_ROOTS` rather than against a
     * literal, so a root added there is covered here without a second edit, and a
     * root removed there fails here rather than passing silently.
     */
    @Test
    fun `the scope excludes every framework-owned and harness-owned directory`() {
        assertThat(ProjectScope.EXCLUDED_ROOTS).isNotEmpty()

        ProjectScope.EXCLUDED_ROOTS.forEach { root ->
            val leaked =
                ProjectScope.files
                    .map { it.projectPath }
                    .filter { it.startsWith("/$root/") || it.contains("/$root/") }

            if (leaked.isNotEmpty()) {
                throw AssertionError(
                    "The analysis scope contains ${leaked.size} file(s) under '$root', which " +
                        "the framework or a harness owns rather than the project: " +
                        "${leaked.take(LEAKED_SHOWN).joinToString()}. Every rule in this suite " +
                        "would report on code the consumer did not write.",
                )
            }
        }
    }

    /**
     * The DIRECTORY each declared module lives in.
     *
     * A Gradle project path is not always its directory. `settings.gradle.kts` may
     * reassign one -- `project(":tooling:konsist").projectDir = file(...)` -- and
     * reading only the `include(...)` lines meant looking for a module's sources
     * where they are not, then reporting that every rule "would pass over that code
     * without reading it" about code the scope had in fact read. A false alarm on a
     * consumer's first install is worse than none: it teaches them the gate cries
     * wolf.
     */
    private fun declaredModulePaths(): List<String> {
        val settings = File(Konsist.projectRootPath, "settings.gradle.kts").readText()
        val relocated =
            PROJECT_DIR_PATTERN
                .findAll(settings)
                .associate { it.groupValues[1] to it.groupValues[2].trim('/') }

        return INCLUDE_PATTERN
            .findAll(settings)
            .map { it.groupValues[1] }
            .map { path -> relocated[path] ?: path.removePrefix(":").replace(':', '/') }
            .toList()
    }

    /**
     * The declared modules MINUS PRISM's own two.
     *
     * Both integrity checks are about the CONSUMER's project: does the scope
     * reach everything they compile. PRISM's modules are deliberately sliced out
     * of the scope, so asking the scope to cover them inverts the question and
     * fails on a correct install. On a greenfield app it failed in a way that
     * reads like a scope defect and is not one: the only `src/test` directory in
     * the whole repository belonged to `tooling/konsist`, so excluding that
     * module left "the project uses a test source set the analyser cannot see" --
     * about a source set the consumer does not have.
     */
    private fun consumerModulePaths(): List<String> = declaredModulePaths().filterNot { it in ProjectScope.PRISM_OWNED_MODULES }

    /** Source-set directories under a consumer module that actually hold Kotlin. */
    private fun sourceSetsWithKotlinOnDisk(): Set<String> =
        consumerModulePaths()
            .flatMap { modulePath ->
                File(Konsist.projectRootPath, "$modulePath/src")
                    .listFiles { candidate: File -> candidate.isDirectory }
                    .orEmpty()
                    .filter { sourceSet ->
                        sourceSet.walkTopDown().any { it.isFile && it.extension == KOTLIN_EXTENSION }
                    }.map { it.name }
            }.toSet()

    private companion object {
        const val KOTLIN_EXTENSION = "kt"

        val INCLUDE_PATTERN = Regex("""include\("([^"]+)"\)""")

        val PROJECT_DIR_PATTERN =
            Regex("""project\("([^"]+)"\)\s*\.projectDir\s*=\s*file\("([^"]+)"\)""")

        /** Enough leaked paths to identify the directory, not the whole listing. */
        const val LEAKED_SHOWN = 5
    }
}
