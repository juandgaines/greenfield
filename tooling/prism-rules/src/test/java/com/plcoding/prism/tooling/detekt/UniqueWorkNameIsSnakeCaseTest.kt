package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class UniqueWorkNameIsSnakeCaseTest {
    private val rule = UniqueWorkNameIsSnakeCase(Config.empty)

    @Test
    fun `reports a camelCase unique work name`() {
        val findings =
            rule.lint(
                """fun schedule() = workManager.enqueueUniquePeriodicWork("syncNotes", policy, request)""",
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("snake_case")
    }

    @Test
    fun `reports a camelCase name on every unique work function`() {
        val code =
            """
            fun schedule() {
                workManager.enqueueUniqueWork("uploadPhoto", policy, request)
                workManager.enqueueUniquePeriodicWork("syncNotes", policy, request)
                workManager.beginUniqueWork("cleanCache", policy, request)
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(3)
    }

    @Test
    fun `reports a camelCase literal prefix of an interpolated name`() {
        val code =
            """fun schedule(id: String) = workManager.enqueueUniqueWork("syncNotes_${'$'}id", policy, request)"""

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `reports a camelCase named name argument`() {
        val code =
            """fun schedule() = workManager.enqueueUniqueWork(uniqueWorkName = "syncNotes", policy, request)"""

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `does not report a snake case name`() {
        val code =
            """fun schedule() = workManager.enqueueUniqueWork("sync_notes", policy, request)"""

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a snake case literal prefix`() {
        val code =
            """fun schedule(id: String) = workManager.enqueueUniqueWork("sync_${'$'}id", policy, request)"""

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a non literal name`() {
        val code =
            """fun schedule(name: String) = workManager.enqueueUniqueWork(name, policy, request)"""

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a fully interpolated name`() {
        val code =
            """fun schedule(name: String) = workManager.enqueueUniqueWork("${'$'}name", policy, request)"""

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report an unrelated call with a camelCase literal`() {
        assertThat(rule.lint("""fun log() = println("syncNotes")""")).isEmpty()
    }

    @Test
    fun `honors a custom function list`() {
        val custom =
            UniqueWorkNameIsSnakeCase(
                TestConfig("uniqueWorkFunctions" to listOf("scheduleUnique")),
            )

        assertThat(custom.lint("""fun go() = scheduler.scheduleUnique("syncNotes")""")).hasSize(1)
        assertThat(
            custom.lint("""fun go() = workManager.enqueueUniqueWork("syncNotes", policy, request)"""),
        ).isEmpty()
    }
}
