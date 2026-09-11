package com.plcoding.prism.tooling.konsist

import com.lemonappdev.konsist.api.declaration.KoPropertyDeclaration
import org.junit.jupiter.api.Test

/**
 * The MVI contract from **android-presentation-mvi**: a `StateFlow`-owning
 * ViewModel, a single `onAction` entry point, sealed `Action` and `Event`
 * hierarchies, an immutable `State`, and a stateless `Screen`.
 *
 * Every rule here is cross-file — "this ViewModel has a `State` but no sibling
 * `Action`" is invisible to a per-file analyser, because neither file is wrong on
 * its own. That is why this family lives in Konsist and not in detekt.
 */
class MviContractRulesTest {
    // TWO TESTS WERE DELETED HERE, and their absence is the point.
    //
    // `the subject set is exactly the seven feature ViewModels` asserted set
    // equality against a hardcoded list of the origin project's screen names,
    // and `MainViewModel is outside the contract by scope` asserted that one
    // named class existed. Both are statements about ONE repository's inventory
    // rather than about anyone's architecture: they fail on every other project,
    // and they would fail on the origin itself the day someone adds a screen.
    // Parameterising them would not have helped -- an exact-equality assertion
    // over a list that grows with the product is a rule that has to be edited to
    // stay green, which is a rule nobody trusts.
    //
    // They survived the rename because a search for the origin's name cannot see
    // a class called `SignInViewModel`, and because this module had never been
    // run against a repository other than the one it came from. The rules below
    // carry the property those two were protecting: a selector that stops
    // matching is reported NOT APPLICABLE by assertAllWhereApplicable rather
    // than passing vacuously.

    @Test
    fun `every feature ViewModel exposes a public state property`() {
        featureViewModels().assertAllWhereApplicable(
            rule = "ViewModel exposes `state`",
            subject = "feature ViewModels",
            requirement = "the screen reads exactly one state holder, named `state`, from outside the ViewModel",
        ) { viewModel ->
            viewModel.properties().any { it.name == STATE && it.hasPublicOrDefaultModifier }
        }
    }

    @Test
    fun `the state property is a StateFlow`() {
        featureViewModels().assertAllWhereApplicable(
            rule = "`state` is a StateFlow",
            subject = "feature ViewModels",
            requirement =
                "state must be a `StateFlow` so it always has a current value for the " +
                    "screen to render on recomposition, rather than a cold or replayed Flow",
        ) { viewModel ->
            viewModel
                .properties()
                .filter { it.name == STATE }
                .all { it.isStateFlow() }
        }
    }

    @Test
    fun `every feature ViewModel declares onAction with exactly one parameter`() {
        featureViewModels().assertAllWhereApplicable(
            rule = "ViewModel declares `onAction`",
            subject = "feature ViewModels",
            requirement =
                "user intent enters the ViewModel through one function taking one `Action`, " +
                    "not through a per-event method surface",
        ) { viewModel ->
            viewModel.functions().any { it.name == ON_ACTION && it.parameters.size == 1 }
        }
    }

    @Test
    fun `every feature ViewModel has a sibling State and Action`() {
        featureViewModels().assertAllWhereApplicable(
            rule = "ViewModel has sibling State and Action",
            subject = "feature ViewModels",
            requirement =
                "the three parts of one screen's contract live together in one package, so " +
                    "the screen can be read without chasing declarations across the module",
        ) { viewModel ->
            val screen = viewModel.name.removeSuffix("ViewModel")
            val siblings =
                ProjectScope.productionFiles
                    .filter { it.packagee?.name == viewModel.packagee?.name }
                    .flatMap { it.classes() + it.interfaces() + it.objects() }
                    .map { it.name }
                    .toSet()
            "${screen}State" in siblings && "${screen}Action" in siblings
        }
    }

    @Test
    fun `Action and Event hierarchies in presentation are sealed`() {
        // Scoped to presentation. An `Action` type in a design-system module is a data
        // carrier describing one speed-dial entry, not an MVI intent hierarchy, and
        // it is the measured false positive that forced this scoping.
        val subjects =
            ProjectScope.productionFiles
                .filter { it.packagee?.name?.contains(".presentation") == true }
                .flatMap { it.classes() + it.interfaces() }
                .filter { it.name.endsWith("Action") || it.name.endsWith("Event") }

        subjects.assertAllWhereApplicable(
            rule = "Action and Event are sealed",
            subject = "Action or Event hierarchies in a presentation package",
            requirement =
                "the set of intents and one-time events a screen can produce must be closed, " +
                    "so `when` over them is exhaustive and a new case is a compile error",
        ) { it.hasSealedModifier }
    }

    @Test
    fun `State declarations in presentation are immutable data classes`() {
        // Enums are excluded: an enum-shaped display state (the listing footer's
        // three cases, now `PaginationFooterUi`) cannot be mutated in the first
        // place, so the rule it would fail is not a rule about it.
        val subjects =
            ProjectScope.productionFiles
                .filter { it.packagee?.name?.contains(".presentation") == true }
                .flatMap { it.classes() }
                .filter { it.name.endsWith("State") }
                .filterNot { it.hasEnumModifier }

        subjects.assertAllWhereApplicable(
            rule = "State is an immutable data class",
            subject = "State declarations in a presentation package",
            requirement =
                "state is replaced with `copy`, never mutated in place — a `var` would let a " +
                    "write bypass the StateFlow and leave the screen showing stale data",
        ) { state ->
            state.hasDataModifier && state.properties().none { it.hasVarModifier }
        }
    }

    @Test
    fun `Screen composables take no ViewModel`() {
        val subjects =
            ProjectScope.productionFiles
                .flatMap { it.functions() }
                .filter { it.hasAnnotationWithName(COMPOSABLE) && it.name.endsWith("Screen") }

        subjects.assertAllWhereApplicable(
            rule = "Screen is stateless",
            subject = "Screen composables",
            requirement =
                "a `Screen` renders state and raises actions; the `Root` above it owns the " +
                    "ViewModel. A Screen holding one cannot be previewed or tested in isolation",
        ) { screen ->
            screen.parameters.none { it.type.sourceType.endsWith("ViewModel") }
        }
    }

    /**
     * Konsist is syntax-only, so a property with an inferred type reports no type at
     * all — `FilesViewModel` writes `val state = _state.asStateFlow()`, which is
     * correct and would fail a declared-type check. Both idioms in this codebase are
     * accepted: an explicit `StateFlow<…>`, or an initializer that produces one.
     */
    private fun KoPropertyDeclaration.isStateFlow(): Boolean {
        val declared = type?.name?.startsWith("StateFlow<") == true
        val built = value?.let { it.contains(".asStateFlow()") || it.contains(".stateIn(") } == true
        return declared || built
    }

    private companion object {
        const val STATE = "state"
        const val ON_ACTION = "onAction"
        const val COMPOSABLE = "Composable"
    }
}
