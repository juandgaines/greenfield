package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EmptyResultOverResultUnitTest {
    private val rule = EmptyResultOverResultUnit(Config.empty)

    @Test
    fun `reports a Result of Unit`() {
        val findings = rule.lint("fun logout(): Result<Unit, DataError> = TODO()")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("EmptyResult<DataError>")
    }

    @Test
    fun `reports a Result of Unit in a property type`() {
        assertThat(rule.lint("val outcome: Result<Unit, AuthError> = TODO()")).hasSize(1)
    }

    @Test
    fun `does not report a Result with a real success value`() {
        assertThat(rule.lint("fun load(): Result<Note, DataError> = TODO()")).isEmpty()
    }

    @Test
    fun `does not report the EmptyResult typealias itself in use`() {
        assertThat(rule.lint("fun logout(): EmptyResult<DataError> = TODO()")).isEmpty()
    }

    @Test
    fun `does not report an unrelated two-argument type of Unit`() {
        assertThat(rule.lint("fun load(): Either<Unit, DataError> = TODO()")).isEmpty()
    }

    @Test
    fun `fires outside the typealias file and is excluded inside it`(
        @TempDir root: Path,
    ) {
        val violation = "fun logout(): Result<Unit, DataError> = TODO()"
        val scoped =
            EmptyResultOverResultUnit(
                TestConfig(
                    "active" to true,
                    "excludes" to
                        listOf(
                            "**/test/**",
                            "**/androidTest/**",
                            "**/core/domain/src/main/java/com/example/app/core/domain/util/Result.kt",
                        ),
                ),
            )
        val typealiasFile = "core/domain/src/main/java/com/example/app/core/domain/util/Result.kt"
        val consumerFile = "feature/auth/domain/src/main/java/com/example/app/auth/domain/AuthRepository.kt"

        assertThat(scoped.lintAt(root, consumerFile, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, typealiasFile, violation)).isEmpty()
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
