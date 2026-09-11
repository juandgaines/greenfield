package com.plcoding.prism.tooling.konsist

import com.lemonappdev.konsist.api.declaration.KoClassDeclaration

/**
 * The subject set of the MVI family: ViewModels that own a screen.
 *
 * The scoping is the load-bearing decision here. A bootstrap ViewModel — one that
 * resolves a start destination or emits session events rather than owning a
 * screen — has no `state` and no `onAction`, and is correct as it stands.
 * Applied to every class named `*ViewModel`, this family would fail it twice on
 * the first run.
 *
 * Restricting to presentation packages fixes that by asking the right question
 * rather than by suppressing the answer: the MVI contract is a statement about
 * screens, and screens live in presentation packages. A suppression list would
 * give the same green result today and a worse rule tomorrow, because the next
 * bootstrap-shaped ViewModel would fail and someone would add a second entry
 * instead of noticing the rule was mis-scoped.
 *
 * THE FILTER NAMES ONE PACKAGE SEGMENT, NOT A MODULE LAYOUT. It was
 * `..feature..presentation..`, which additionally required a `feature` segment
 * ahead of the presentation one — a multi-module convention. A single-module app
 * with `presentation/` matched none of it, so this returned an empty list and
 * every rule in the MVI family reported NOT APPLICABLE on a repository that has
 * exactly the shape they were written for. Callers use assertAllWhereApplicable,
 * so that surfaced as a skip rather than a false green — but a skip nobody
 * expected is still a check nobody received.
 */
internal fun featureViewModels(): List<KoClassDeclaration> =
    ProjectScope.productionFiles
        .flatMap { it.classes() }
        .filter { it.name.endsWith("ViewModel") }
        .filter { it.resideInPackage("..presentation..") }
