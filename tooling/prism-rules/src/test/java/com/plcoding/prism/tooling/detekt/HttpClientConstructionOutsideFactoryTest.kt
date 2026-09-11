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

class HttpClientConstructionOutsideFactoryTest {
    private val rule = HttpClientConstructionOutsideFactory(Config.empty)

    @Test
    fun `reports an HttpClient construction`() {
        val findings = rule.lint("fun create() = HttpClient(CIO) { install(SSE) }")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("HttpClientFactory")
    }

    @Test
    fun `reports an engine-less construction too`() {
        assertThat(rule.lint("fun create() = HttpClient { }")).hasSize(1)
    }

    @Test
    fun `does not report using an injected client`() {
        val code =
            """
            class NoteApi(private val httpClient: HttpClient) {
                suspend fun notes() = httpClient.safeGet<List<NoteDto>>("notes")
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `fires outside the factory and is excluded inside it and in tests`(
        @TempDir root: Path,
    ) {
        val violation = "fun create() = HttpClient(CIO) { }"
        val scoped =
            HttpClientConstructionOutsideFactory(
                TestConfig(
                    "active" to true,
                    "excludes" to
                        listOf(
                            "**/test/**",
                            "**/androidTest/**",
                            "**/core/data/networking/HttpClientFactory.kt",
                        ),
                ),
            )
        val factory = "core/data/src/main/java/com/example/app/core/data/networking/HttpClientFactory.kt"
        val elsewhereInCoreData = "core/data/src/main/java/com/example/app/core/data/networking/SafeCall.kt"

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, elsewhereInCoreData, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, factory, violation)).isEmpty()
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
