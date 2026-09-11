package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reports a `*State` class with collection-typed properties and no stability annotation.
 *
 * The Compose compiler infers standard collection interfaces as unstable, so a
 * screen passing such a state defeats skipping and recomposes on every parent
 * recomposition. Either promise stability with `@Stable`/`@Immutable` or hold
 * the data in kotlinx immutable collections, which are stable on their own.
 */
class StableAnnotationOnUnstableState(config: Config) :
    Rule(
        config,
        "A State class holding standard collections carries @Stable or @Immutable.",
    ) {
    @Configuration("collection type names that count as stable without an annotation")
    private val stableCollectionTypes: List<String> by config(
        listOf(
            "ImmutableList",
            "ImmutableSet",
            "ImmutableMap",
            "PersistentList",
            "PersistentSet",
            "PersistentMap",
        ),
    )

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.isInterface() || klass.isEnum() || klass.isAnnotation()) return
        val className = klass.name ?: return
        if (!className.endsWith(STATE_SUFFIX)) return
        if (klass.annotationEntries.any { it.shortName?.asString() in STABILITY_ANNOTATIONS }) return

        val propertyTypes =
            buildList {
                klass.primaryConstructor
                    ?.valueParameters
                    .orEmpty()
                    .filter { it.hasValOrVar() }
                    .forEach { parameter -> parameter.typeReference?.let { add(parameter.name to it) } }
                klass.body
                    ?.properties
                    .orEmpty()
                    .forEach { property -> property.typeReference?.let { add(property.name to it) } }
            }
        val unstableProperties =
            propertyTypes.mapNotNull { (propertyName, type) ->
                val baseName = type.baseTypeName() ?: return@mapNotNull null
                if (baseName in stableCollectionTypes) return@mapNotNull null
                if (baseName !in UNSTABLE_COLLECTION_TYPES) return@mapNotNull null
                propertyName
            }
        if (unstableProperties.isEmpty()) return

        report(
            Finding(
                Entity.from(klass),
                "'$className' holds ${unstableProperties.filterNotNull().joinToString()} in " +
                    "unstable collection types. Annotate the class with @Stable or @Immutable, " +
                    "or use kotlinx immutable collections.",
            ),
        )
    }

    private fun KtTypeReference.baseTypeName(): String? {
        var element = typeElement
        while (element is KtNullableType) {
            element = element.innerType
        }
        return (element as? KtUserType)?.referencedName
    }

    private companion object {
        const val STATE_SUFFIX = "State"
        val STABILITY_ANNOTATIONS = setOf("Stable", "Immutable")
        val UNSTABLE_COLLECTION_TYPES =
            setOf("List", "MutableList", "Set", "MutableSet", "Map", "MutableMap", "Collection")
    }
}
