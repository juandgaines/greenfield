package com.plcoding.prism.tooling.konsist

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Test

/**
 * Layer purity *inside* a module.
 *
 * Between modules the dependency graph already enforces this — a feature's domain
 * cannot reach Ktor because it does not depend on it. The rules here cover the case
 * the graph cannot express: a `domain` and a `data` package shipping in the same
 * module, where every dependency is on the classpath of both.
 */
class LayerPurityRulesTest {
    @Test
    fun `domain packages depend on no framework`() {
        domainFiles().assertAllWhereApplicable(
            rule = "domain is framework-free",
            subject = "domain packages",
            requirement =
                "a domain declaration must not import Android, AndroidX, Ktor, Room or " +
                    "serialization APIs — the domain layer is plain Kotlin so it stays " +
                    "testable on the JVM and independent of how data arrives",
        ) { file ->
            file.imports.none { import -> FRAMEWORK_PREFIXES.any { import.name.startsWith(it) } }
        }
    }

    @Test
    fun `the named domain package is free of the wire format`() {
        val subjects =
            ProjectScope.productionFiles.filter {
                it.packagee?.name?.startsWith("com.juandgaines.prismgreen.core.domain") == true
            }

        // Not applicable and misconfigured are different facts, and this rule
        // used to treat them as one: it threw when the package matched nothing,
        // and its own message offered DELETING THE TEST as the resolution. A
        // destructive escape hatch is not an escape hatch.
        //
        // assertAllWhereApplicable aborts instead, which JUnit reports as
        // skipped with the reason attached -- visible in the report, never a
        // green check. That is also why this parameter needs no on/off switch
        // beside THEME_RULES_ACTIVE: the theme rules cannot GUESS a name, so
        // somebody has to answer; this one can simply observe that the package
        // is not there.
        subjects.assertAllWhereApplicable(
            rule = "the domain package is transport-free",
            subject = "files under com.juandgaines.prismgreen.core.domain",
            requirement =
                "a module that ships its transport and its domain model side by side has no " +
                    "dependency graph to keep them apart, so nothing but this rule stops the " +
                    "domain model from being annotated for the wire",
        ) { file ->
            file.imports.none { import ->
                import.name.startsWith("io.ktor.") || import.name.startsWith("kotlinx.serialization.")
            }
        }
    }

    @Test
    fun `presentation packages never reach the network or the database`() {
        val subjects =
            ProjectScope.productionFiles.filter {
                it.packagee?.name?.contains(".presentation") == true
            }

        subjects.assertAllWhereApplicable(
            rule = "presentation goes through the domain layer",
            subject = "presentation packages",
            requirement =
                "a presentation declaration must not import Ktor or Room — it talks to " +
                    "repositories and use cases, never to a transport or a database",
        ) { file ->
            file.imports.none { import ->
                import.name.startsWith("io.ktor.") || import.name.startsWith("androidx.room.")
            }
        }
    }

    private fun domainFiles(): List<KoFileDeclaration> =
        ProjectScope.productionFiles.filter {
            it.packagee?.name?.contains(".domain") == true
        }

    private companion object {
        val FRAMEWORK_PREFIXES =
            listOf(
                "android.",
                "androidx.",
                "io.ktor.",
                "kotlinx.serialization.",
            )
    }
}
