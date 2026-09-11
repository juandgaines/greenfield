package com.plcoding.prism.tooling.konsist

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Rules over where code lives: a file's package must match its directory, and a
 * module's sources must stay under that module's own package root.
 */
class StructureRulesTest {
    @Test
    fun `every file's package matches its directory`() {
        ProjectScope.all.packages.assertAll(
            rule = "package matches directory",
            requirement =
                "a file's package declaration must correspond to its path under the " +
                    "source set root, so a declaration can be found from its fully " +
                    "qualified name and vice versa",
        ) { it.hasMatchingPath }
    }

    @Test
    fun `every module's sources reside under that module's package root`() {
        val subjects =
            ProjectScope.files.filterNot { it.projectPath.startsWith(INCLUDED_BUILD_PATH) }

        subjects.assertAll(
            rule = "module owns its package root",
            requirement =
                "every file must declare a package under its own module's root, so the " +
                    "module graph and the package graph agree: ${MODULE_PACKAGE_ROOTS.size} " +
                    "modules are mapped",
        ) { file ->
            val root =
                MODULE_PACKAGE_ROOTS[file.moduleName]
                    ?: return@assertAll false
            val packageName = file.packagee?.name ?: return@assertAll false
            packageName == root || packageName.startsWith("$root.")
        }
    }

    @Test
    fun `every declared module has a package root mapping`() {
        val declared =
            INCLUDE_PATTERN
                .findAll(File(Konsist.projectRootPath, "settings.gradle.kts").readText())
                .map { it.groupValues[1].removePrefix(":").replace(':', '/') }
                .toSet()

        // PRISM's own two modules are declared in settings.gradle.kts but are
        // sliced out of the analysis scope, so they carry no mapping and must not
        // be demanded to. See ProjectScope.PRISM_OWNED_MODULES.
        val consumerModules = declared - ProjectScope.PRISM_OWNED_MODULES.toSet()

        val unmapped = consumerModules - MODULE_PACKAGE_ROOTS.keys
        val stale = MODULE_PACKAGE_ROOTS.keys - consumerModules
        if (unmapped.isNotEmpty() || stale.isNotEmpty()) {
            throw AssertionError(
                "MODULE_PACKAGE_ROOTS is out of step with settings.gradle.kts. " +
                    "Unmapped modules: ${unmapped.ifEmpty { "none" }}. " +
                    "Stale entries: ${stale.ifEmpty { "none" }}.",
            )
        }
    }

    private companion object {
        val INCLUDE_PATTERN = Regex("""include\("([^"]+)"\)""")
    }
}
