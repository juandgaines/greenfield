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

class DispatchersSetMainWithoutResetTest {
    private val rule = DispatchersSetMainWithoutReset(Config.empty)

    @Test
    fun `reports setMain in a class with no teardown at all`() {
        val code =
            """
            class SampleViewModelTest {
                @BeforeEach
                fun setUp() {
                    Dispatchers.setMain(UnconfinedTestDispatcher())
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("Dispatchers.resetMain()")
    }

    @Test
    fun `reports setMain when the teardown resets nothing`() {
        val code =
            """
            class SampleViewModelTest {
                @BeforeEach
                fun setUp() {
                    Dispatchers.setMain(UnconfinedTestDispatcher())
                }

                @AfterEach
                fun tearDown() {
                    database.close()
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `does not report when an AfterEach teardown calls resetMain`() {
        val code =
            """
            class SampleViewModelTest {
                @BeforeEach
                fun setUp() {
                    Dispatchers.setMain(UnconfinedTestDispatcher())
                }

                @AfterEach
                fun tearDown() {
                    Dispatchers.resetMain()
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report when a JUnit4 After teardown calls resetMain`() {
        val code =
            """
            class SampleViewModelTest {
                @Before
                fun setUp() {
                    Dispatchers.setMain(UnconfinedTestDispatcher())
                }

                @After
                fun tearDown() {
                    Dispatchers.resetMain()
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report setMain outside any class`() {
        val code =
            """
            fun installMainDispatcher() {
                Dispatchers.setMain(UnconfinedTestDispatcher())
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report an unrelated setMain callee`() {
        val code =
            """
            class SampleViewModelTest {
                fun setUp() {
                    scheduler.setMain(worker)
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `fires in test sources and is excluded from main sources`(
        @TempDir root: Path,
    ) {
        val violation =
            """
            class SampleViewModelTest {
                fun setUp() {
                    Dispatchers.setMain(UnconfinedTestDispatcher())
                }
            }
            """.trimIndent()
        val scoped =
            DispatchersSetMainWithoutReset(
                TestConfig("active" to true, "includes" to listOf("**/test/**", "**/androidTest/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
