package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.test.lint
import dev.detekt.test.utils.compileContentForTest
import dev.detekt.utils.PathFilters
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Lints [content] as though it lived at [relativePath] beneath [root].
 *
 * An exclusion is where a rule quietly stops protecting, and a mistyped glob
 * fails open with no warning at all — the same failure mode `.editorconfig` has.
 * The only way to know a glob does what it claims is to run the rule against a
 * real path on both sides of it, which is what this makes cheap enough to do
 * for every exclusion the rule set declares.
 *
 * detekt 2.0 applies a rule's `excludes`/`includes` in its Analyzer, outside
 * the rule (`shouldAnalyzeFile` in detekt-core, which is internal), so this
 * helper applies the same predicate before linting — [PathFilters] over the
 * rule's config, matched against the base-relative path — and a skipped path
 * returns no findings, exactly as the gate behaves.
 */
fun Rule.lintAt(
    root: Path,
    relativePath: String,
    content: String,
): List<Finding> {
    val includes = config.valueOrDefault(Config.INCLUDES_KEY, emptyList<String>())
    val excludes = config.valueOrDefault(Config.EXCLUDES_KEY, emptyList<String>())
    val filters = PathFilters.of(includes, excludes)
    if (filters?.isIgnored(Paths.get(".", relativePath)) == true) return emptyList()
    return lint(compileContentForTest(content, root.resolve(relativePath)))
}

const val MAIN_SOURCE_PATH = "feature/auth/presentation/src/main/java/com/example/app/Sample.kt"
const val UNIT_TEST_PATH = "feature/auth/presentation/src/test/java/com/example/app/SampleTest.kt"
const val INSTRUMENTATION_TEST_PATH = "feature/auth/presentation/src/androidTest/java/com/example/app/SampleTest.kt"

/** The exclusions every rule in this set declares, mirroring `detekt.yml`. */
val MAIN_SOURCES_ONLY: Array<Pair<String, Any>> =
    arrayOf("active" to true, "excludes" to listOf("**/test/**", "**/androidTest/**"))
