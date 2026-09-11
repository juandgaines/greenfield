package com.plcoding.prism.tooling.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/**
 * The single analysis scope every rule class in this module reads.
 *
 * Constructing a scope parses every Kotlin source in the repository, measured at
 * roughly 0.7 s. That cost is per construction and not per query, so the scope is
 * built once here and shared; five rule classes each building their own would be
 * five parses for identical results.
 */
internal object ProjectScope {
    /**
     * The directories whose Kotlin files are not the consumer's source.
     *
     * `scopeFromProject()` correctly excludes `build/` and the KSP output, but it
     * walks every other directory in the repository -- including ones holding
     * Kotlin that is tooling or documentation rather than project code. Those are
     * sliced out here once rather than exempted rule by rule.
     *
     * Expressed as a list of owned roots, not as one hardcoded path, because the
     * engine no longer lives inside the harness's directory. When it moved, a
     * single `/.claude/` exclusion stopped covering it, and the failure mode of
     * that is silent in the dangerous direction: the scope *widens* to include
     * the framework's own Gradle init scripts and rule sources, and the rules
     * then report on code the consumer did not write and cannot fix.
     *
     * `.prism` is the framework's -- the engine, its assets, its state. `.claude`
     * is a harness's. The harness side gains entries in Phase 4, when the
     * remaining four adapters ship; each is a directory a harness owns inside the
     * consumer's repository, and none of them is project source.
     *
     * `prism-setup` is the extraction staging directory, and it is here because
     * of an ordering nobody would guess: setup runs `./gradlew staticAnalysis`
     * at step 8 to prove the wiring works, and does not delete the staging
     * directory until step 11. So the FIRST static-analysis run of every install
     * sees a second, complete copy of the payload sitting in the repository root
     * -- 152 declarations of it, on a project with two source files -- and
     * reports on all of it. The consumer's first impression of the framework
     * would be a wall of violations in files they had never opened.
     */
    val EXCLUDED_ROOTS = listOf(".prism", ".claude", "prism-setup")

    /**
     * PRISM's own two modules, sliced out for the same reason as `.prism`.
     *
     * `tooling/prism-rules` and `tooling/konsist` install as SOURCE, so konsist
     * walks them like any other module -- and then grades the framework's 72 rule
     * classes against the consumer's architecture. Measured on a bare app with
     * two source files: 302 declarations in scope, 152 of them violating "module
     * owns its package root", none of them written by the consumer.
     *
     * That is the failure EXCLUDED_ROOTS' own comment describes one directory
     * earlier -- the scope widening to include the framework's sources, which
     * then report on code the consumer did not write and cannot fix. It was
     * invisible in the origin repository, where these two paths are mapped in
     * MODULE_PACKAGE_ROOTS and every file in them matches.
     *
     * A consumer's OWN rules, added to these modules later, are excluded too.
     * That is the accepted cost, and it is the same trade `prism-doctor` already
     * makes when it leaves these two modules out of its detekt-reach count.
     */
    val PRISM_OWNED_MODULES = listOf("tooling/prism-rules", "tooling/konsist")

    val all: KoScope by lazy {
        Konsist.scopeFromProject().slice { file ->
            EXCLUDED_ROOTS.none { file.projectPath.startsWith("/$it/") } &&
                PRISM_OWNED_MODULES.none { file.projectPath.startsWith("/$it/") }
        }
    }

    val files: List<KoFileDeclaration> by lazy { all.files }

    val productionFiles: List<KoFileDeclaration> by lazy {
        files.filter { it.sourceSetName == MAIN_SOURCE_SET }
    }

    val testFiles: List<KoFileDeclaration> by lazy {
        files.filterNot { it.sourceSetName == MAIN_SOURCE_SET }
    }

    const val MAIN_SOURCE_SET = "main"

    /**
     * The root package this project's own code lives under -- the namespace
     * konsist analyses.
     *
     * Rendered per repository, with no default. A wrong value here does not
     * fail: it selects nothing, and every architecture rule then passes over an
     * empty scope while reporting success. `ScopeIntegrityTest` is the guard
     * against that, and it is the reason this is a required parameter rather
     * than a value with a plausible-looking fallback.
     */
    const val PACKAGE_ROOT = "com.juandgaines.prismgreen"
}
