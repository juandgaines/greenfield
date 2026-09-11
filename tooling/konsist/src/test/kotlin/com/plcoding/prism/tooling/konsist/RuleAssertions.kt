package com.plcoding.prism.tooling.konsist

import com.lemonappdev.konsist.api.provider.KoBaseProvider
import com.lemonappdev.konsist.api.provider.KoLocationProvider
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.provider.KoPathProvider
import org.junit.jupiter.api.Assumptions.abort

/**
 * Modules where konsist is set to `observe` in `.prism/scope.json`.
 *
 * Konsist is one module analysing the whole graph, so it cannot be made
 * non-blocking per module the way detekt and ktlint can -- there is one test
 * task and one exit status. Violations are therefore PARTITIONED instead: those
 * in enforced modules fail the test, those in observed modules are printed and
 * do not.
 *
 * The list arrives as a system property rather than by parsing scope.json here,
 * so that the Gradle-side reader stays the single interpreter of that file.
 * Empty property, or no property at all, means every module enforces -- the
 * same strict default as everywhere else.
 */
private val OBSERVED_PREFIXES: List<String> =
    System
        .getProperty("prism.konsist.observe", "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        // A Gradle path becomes a directory prefix: colons become slashes,
        // bounded by one at each end, which is the form konsist reports as
        // projectPath. The TRAILING slash is load-bearing -- without it any
        // module whose path is a prefix of a sibling's would swallow that
        // sibling, and observing one module would silently observe the other.
        .map { "/" + it.trim(':').replace(':', '/') + "/" }

private fun KoBaseProvider.isObserved(): Boolean {
    if (OBSERVED_PREFIXES.isEmpty()) return false
    val path = (this as? KoPathProvider)?.projectPath ?: return false
    return OBSERVED_PREFIXES.any { path.startsWith(it) }
}

/**
 * Reports the violations in observed modules without failing.
 *
 * Printed, not swallowed. `observe` means the engine still tells you what it
 * found; a posture that hid findings would be indistinguishable from the rule
 * being off, and the entire reason posture is per module is so that a module
 * nobody has reached yet can still say how much work is in it.
 */
private fun <T : KoBaseProvider> reportObserved(
    rule: String,
    requirement: String,
    observed: List<T>,
) {
    if (observed.isEmpty()) return
    println(
        buildString {
            appendLine("PRISM observe -- $rule: ${observed.size} violation(s), not blocking.")
            appendLine("Requirement: $requirement")
            observed.forEach { appendLine("  - ${describe(it)}") }
            appendLine("Promote the module in .prism/scope.json when these are cleared.")
        },
    )
}

/**
 * Asserts [predicate] holds for every declaration in this subject set, naming each
 * offender with its file path.
 *
 * An empty subject set is itself a failure. Eighteen of this suite's rules find
 * nothing on conformant code, so a rule whose selector has quietly stopped matching
 * is indistinguishable from a rule that is passing — unless matching nothing is
 * loud. See the "rules that find nothing can rot unnoticed" risk in the design.
 *
 * Use this for a rule whose subject MUST exist in any repository the scope covers:
 * every module owns its package root, every module is mapped. Matching nothing
 * there means the scope is broken, and reporting that as a pass would hide it.
 *
 * For a rule about a construct a project may simply not have — DTOs, Room
 * entities, ViewModels — use [assertAllWhereApplicable] instead.
 */
internal fun <T : KoBaseProvider> List<T>.assertAll(
    rule: String,
    requirement: String,
    predicate: (T) -> Boolean,
) {
    assertMatchesSomething(rule)

    val (observed, blocking) = filterNot(predicate).partition { it.isObserved() }
    reportObserved(rule, requirement, observed)
    if (blocking.isEmpty()) return

    throw AssertionError(
        buildString {
            appendLine("$rule: ${blocking.size} of $size declarations violate this rule.")
            appendLine("Requirement: $requirement")
            blocking.forEach { appendLine("  - ${describe(it)}") }
        },
    )
}

/**
 * The same rule, for a construct a repository is allowed not to have.
 *
 * The distinction this draws is the difference between two facts that
 * [assertAll] was treating as one:
 *
 *   NOT APPLICABLE — this project has no DTOs, so "every DTO lives in a dto
 *                    package" is vacuously true, and correctly so.
 *   MISCONFIGURED  — this project HAS DTOs and the selector found none.
 *
 * Measured on a greenfield single-module app: sixteen of twenty-five rules
 * failed, thirteen of them purely because the project had no ViewModels, no
 * DTOs and no Room entities. Not one of those failures was about the code. A
 * framework that cannot be installed into a new project until that project
 * already has the architecture it checks is not a verification framework, it is
 * a migration.
 *
 * The empty case ABORTS rather than passes. JUnit reports an aborted test as
 * skipped, with this reason attached, so it appears in the report as something
 * that did not run — never as a green check. A silent vacuous pass is the exact
 * failure mode `assertAll`'s empty-set rule was written to prevent, and this
 * must not reintroduce it through the back door.
 */
internal fun <T : KoBaseProvider> List<T>.assertAllWhereApplicable(
    rule: String,
    requirement: String,
    subject: String,
    predicate: (T) -> Boolean,
) {
    if (isEmpty()) {
        abort<Unit>(
            "$rule did not run: this project has no $subject. The rule is not " +
                "applicable here rather than satisfied — if you DO have $subject, " +
                "the selector or the analysis scope is wrong, and that is a defect.",
        )
    }

    val (observed, blocking) = filterNot(predicate).partition { it.isObserved() }
    reportObserved(rule, requirement, observed)
    if (blocking.isEmpty()) return

    throw AssertionError(
        buildString {
            appendLine("$rule: ${blocking.size} of $size declarations violate this rule.")
            appendLine("Requirement: $requirement")
            blocking.forEach { appendLine("  - ${describe(it)}") }
        },
    )
}

/** Fails when the selector behind [rule] matches nothing, rather than passing vacuously. */
internal fun <T : KoBaseProvider> List<T>.assertMatchesSomething(rule: String) {
    if (isNotEmpty()) return

    throw AssertionError(
        "$rule matched zero declarations. A rule with an empty subject set passes " +
            "without checking anything, so it is treated as a failure: either the " +
            "selector is stale, or the analysis scope no longer covers the sources " +
            "the rule is about.",
    )
}

private fun describe(declaration: KoBaseProvider): String {
    val name = (declaration as? KoNameProvider)?.name ?: declaration.toString()
    val location =
        (declaration as? KoLocationProvider)?.location
            ?: (declaration as? KoPathProvider)?.projectPath
    return if (location == null) name else "$name  ($location)"
}
