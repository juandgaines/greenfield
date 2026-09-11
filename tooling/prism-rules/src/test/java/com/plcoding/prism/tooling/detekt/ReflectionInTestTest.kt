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

class ReflectionInTestTest {
    private val rule = ReflectionInTest(Config.empty)

    @Test
    fun `reports getDeclaredField`() {
        val findings = rule.lint("fun peek(target: Any) = target.javaClass.getDeclaredField(\"secret\")")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("getDeclaredField")
    }

    @Test
    fun `reports getDeclaredMethod`() {
        assertThat(
            rule.lint("fun peek(target: Any) = target.javaClass.getDeclaredMethod(\"secret\")"),
        ).hasSize(1)
    }

    @Test
    fun `reports an isAccessible override`() {
        val snippet =
            """
            fun open(field: Field) {
                field.isAccessible = true
            }
            """.trimIndent()

        assertThat(rule.lint(snippet)).hasSize(1)
    }

    @Test
    fun `reports a java reflect import`() {
        assertThat(rule.lint("import java.lang.reflect.Field\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `reports a kotlin reflect full import`() {
        assertThat(rule.lint("import kotlin.reflect.full.declaredMemberProperties\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `does not report an isAccessible read`() {
        assertThat(rule.lint("fun check(holder: Holder) = holder.isAccessible")).isEmpty()
    }

    @Test
    fun `does not report assigning false to isAccessible`() {
        assertThat(rule.lint("fun close(holder: Holder) { holder.isAccessible = false }")).isEmpty()
    }

    @Test
    fun `does not report a basic kotlin reflect import`() {
        assertThat(rule.lint("import kotlin.reflect.KClass\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `does not report public API calls`() {
        assertThat(rule.lint("fun use(repository: Repository) = repository.load()")).isEmpty()
    }

    @Test
    fun `fires under both test trees and stays out of main sources`(
        @TempDir root: Path,
    ) {
        val violation = "fun peek(target: Any) = target.javaClass.getDeclaredField(\"secret\")"
        val scoped =
            ReflectionInTest(
                TestConfig("active" to true, "includes" to listOf("**/test/**", "**/androidTest/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
