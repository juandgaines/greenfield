package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class NoResultListOfErrorsTest {
    private val rule = NoResultListOfErrors(Config.empty)

    @Test
    fun `reports a Result with a List error side`() {
        val findings =
            rule.lint("fun validate(password: String): Result<Unit, List<PasswordValidationError>> = TODO()")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("List")
    }

    @Test
    fun `reports each forbidden collection error side`() {
        val collectionNames =
            listOf("MutableList", "Set", "MutableSet", "Collection", "Map", "MutableMap")

        collectionNames.forEach { collectionName ->
            val typeArguments = if (collectionName.endsWith("Map")) "<String, DataError>" else "<DataError>"
            val findings =
                rule.lint("fun load(): Result<Note, $collectionName$typeArguments> = TODO()")

            assertThat(findings).hasSize(1)
        }
    }

    @Test
    fun `reports a nullable collection error side`() {
        assertThat(rule.lint("fun load(): Result<Note, List<DataError>?> = TODO()")).hasSize(1)
    }

    @Test
    fun `does not report a single error type`() {
        assertThat(rule.lint("fun load(): Result<Note, DataError> = TODO()")).isEmpty()
    }

    @Test
    fun `does not report a collection on the success side`() {
        assertThat(rule.lint("fun load(): Result<List<Note>, DataError> = TODO()")).isEmpty()
    }

    @Test
    fun `does not report an unrelated two-argument type`() {
        assertThat(rule.lint("fun load(): Either<Note, List<DataError>> = TODO()")).isEmpty()
    }

    @Test
    fun `honors a custom collection list`() {
        val configured = NoResultListOfErrors(TestConfig("collectionTypeNames" to listOf("ErrorBag")))

        assertThat(configured.lint("fun load(): Result<Note, ErrorBag<DataError>> = TODO()")).hasSize(1)
        assertThat(configured.lint("fun load(): Result<Note, List<DataError>> = TODO()")).isEmpty()
    }
}
