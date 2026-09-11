package com.plcoding.prism.tooling.konsist

/**
 * The package root each Gradle module owns.
 *
 * Written out rather than derived, because the mapping is not mechanical: a
 * module path is not always its package. An application module often owns the
 * bare root package, and a hyphenated directory (`core/design-system`,
 * `core/files-db`) usually maps to a package with the hyphen dropped rather
 * than preserved. Deriving it would be right most of the time, and silently
 * wrong for exactly the modules whose naming is irregular.
 *
 * `StructureRulesTest` asserts this map's keys are exactly the modules declared
 * in `settings.gradle.kts`, so a new module cannot land without an entry here.
 *
 * GENERATED FILE — edit `ModulePackageRoots.kt.in`, not this. The consumer
 * entries are rendered from the target repository at install; re-render rather
 * than hand-edit when the module list changes, or the next render silently
 * reverts the edit.
 *
 * PRISM's own two tooling modules used to be listed here as literals. They are
 * not any more: `ProjectScope` slices them out of the scope entirely, so no file
 * from them ever reaches a rule that would consult this map, and an entry for a
 * module the scope cannot yield is a line that can only mislead.
 */
internal val MODULE_PACKAGE_ROOTS =
    mapOf(
        "app" to "com.juandgaines.prismgreen",
    )

/**
 * `build-logic` is an included build, not a module in `settings.gradle.kts`. Its
 * convention plugins are declared in the default package because Gradle resolves
 * `implementationClass` by simple name, so it is outside every package rule here.
 *
 * A PATH PREFIX, NOT A MODULE NAME, and a real install is what settled it. This
 * used to be matched as `file.moduleName == "build-logic"`, which holds only when
 * the included build is a single flat project — the shape PRISM ships to a
 * repository that had no build-logic of its own. A repository that already has
 * one keeps it, and PRISM's four plugins land in a subproject beside theirs; the
 * module names are then `convention` and `prism`, the exemption matches neither,
 * and `module owns its package root` reports every convention plugin in the
 * repository — including PRISM's own five, which are in the default package
 * because Gradle requires it. Measured on a 26-module install: 21 of 206
 * declarations, none of them the consumer's application code.
 */
internal const val INCLUDED_BUILD_PATH = "/build-logic/"
