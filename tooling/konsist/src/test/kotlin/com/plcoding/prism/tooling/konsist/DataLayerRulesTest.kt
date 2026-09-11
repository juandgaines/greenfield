package com.plcoding.prism.tooling.konsist

import org.junit.jupiter.api.Test

/**
 * Placement and shape rules for the data layer, per **android-data-layer**.
 *
 * The pair 6.1 / 6.2 is deliberate: one rule says everything in a `dto` package is
 * a DTO, the other says every DTO is in a `dto` package. Either alone leaves a way
 * for a wire type to drift out of the transport boundary.
 */
class DataLayerRulesTest {
    @Test
    fun `classes in a dto package are serializable Dto data classes`() {
        val subjects =
            ProjectScope.productionFiles
                .filter { it.packagee?.name?.endsWith(".dto") == true }
                .flatMap { it.classes() }

        subjects.assertAllWhereApplicable(
            rule = "a dto package holds only DTOs",
            subject = "dto packages",
            requirement =
                "a wire type is a `@Serializable` data class named `*Dto` — the suffix is " +
                    "what tells a reader at the call site that they are holding transport " +
                    "shape rather than a domain model",
        ) { klass ->
            klass.name.endsWith(DTO_SUFFIX) &&
                klass.hasDataModifier &&
                klass.hasAnnotationWithName(SERIALIZABLE)
        }
    }

    @Test
    fun `every Dto resides in a dto package`() {
        val subjects =
            ProjectScope.productionFiles
                .flatMap { it.classes() }
                .filter { it.name.endsWith(DTO_SUFFIX) }

        subjects.assertAllWhereApplicable(
            rule = "DTOs live in a dto package",
            subject = "classes named *Dto",
            requirement =
                "the transport boundary is a package, so that what crosses the wire can be " +
                    "seen from the module tree instead of read out of import lists",
        ) { it.resideInPackage("..dto..") }
    }

    @Test
    fun `every Room entity is named Entity and lives in an entity package`() {
        val subjects =
            ProjectScope.productionFiles
                .flatMap { it.classes() }
                .filter { it.hasAnnotationWithName(ENTITY) }

        subjects.assertAllWhereApplicable(
            rule = "Room entities live in an entity package",
            subject = "Room entities",
            requirement =
                "a persisted row is named `*Entity` and sits in an `entity` package, so the " +
                    "cache schema is not mistaken for a domain model",
        ) { klass ->
            klass.name.endsWith(ENTITY_SUFFIX) && klass.resideInPackage("..entity..")
        }
    }

    @Test
    fun `every repository function returns a typed result`() {
        val subjects =
            ProjectScope.productionFiles
                .flatMap { it.interfaces() }
                .filter { it.name.endsWith(REPOSITORY_SUFFIX) }
                .flatMap { it.functions() }
                .filterNot { it.hasAnnotationWithName(LOCAL_ONLY) }

        subjects.assertAllWhereApplicable(
            rule = "repository functions return a typed result",
            subject = "repositories",
            requirement =
                "failure must be visible in the return type rather than signalled by an " +
                    "exception, a null or a boolean — so the caller cannot forget it. A " +
                    "function that genuinely cannot fail is exempted at the declaration " +
                    "with an annotation named LocalOnly, never by an exclusion in this " +
                    "rule. PRISM DOES NOT SHIP THAT ANNOTATION: this rule matches it by " +
                    "SIMPLE NAME, so declare your own in a module your data layer already " +
                    "depends on and put the reason in its KDoc — " +
                    "`annotation class LocalOnly` is the whole of it",
        ) { function ->
            val returnType = function.returnType?.name.orEmpty()
            TYPED_RESULT_PREFIXES.any { returnType.startsWith(it) }
        }
    }

    private companion object {
        const val DTO_SUFFIX = "Dto"
        const val ENTITY_SUFFIX = "Entity"
        const val SERIALIZABLE = "Serializable"
        const val ENTITY = "Entity"
        const val REPOSITORY_SUFFIX = "Repository"
        const val LOCAL_ONLY = "LocalOnly"

        val TYPED_RESULT_PREFIXES = listOf("Result<", "EmptyResult<", "Flow<")
    }
}
