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

class InMemoryRoomNotClosedTest {
    private val rule = InMemoryRoomNotClosed(Config.empty)

    @Test
    fun `reports a builder in a class with no teardown at all`() {
        val code =
            """
            class FilesDaoTest {
                @Before
                fun setUp() {
                    database = Room.inMemoryDatabaseBuilder(context, FilesDatabase::class.java).build()
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("close()")
    }

    @Test
    fun `reports a builder when the teardown never closes`() {
        val code =
            """
            class FilesDaoTest {
                @Before
                fun setUp() {
                    database = Room.inMemoryDatabaseBuilder(context, FilesDatabase::class.java).build()
                }

                @After
                fun tearDown() {
                    Dispatchers.resetMain()
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `does not report when an After teardown closes the database`() {
        val code =
            """
            class FilesDaoTest {
                @Before
                fun setUp() {
                    database = Room.inMemoryDatabaseBuilder(context, FilesDatabase::class.java).build()
                }

                @After
                fun tearDown() {
                    database.close()
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report when an AfterEach teardown calls close bare`() {
        val code =
            """
            class FilesDaoTest {
                fun setUp() {
                    database = Room.inMemoryDatabaseBuilder(context, FilesDatabase::class.java).build()
                }

                @AfterEach
                fun tearDown() {
                    close()
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a builder outside any class`() {
        val code =
            """
            fun buildDatabase(context: Context): FilesDatabase {
                return Room.inMemoryDatabaseBuilder(context, FilesDatabase::class.java).build()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report an unrelated builder callee`() {
        val code =
            """
            class FilesDaoTest {
                fun setUp() {
                    database = Room.databaseBuilder(context, FilesDatabase::class.java, "files").build()
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
            class FilesDaoTest {
                fun setUp() {
                    database = Room.inMemoryDatabaseBuilder(context, FilesDatabase::class.java).build()
                }
            }
            """.trimIndent()
        val scoped =
            InMemoryRoomNotClosed(
                TestConfig("active" to true, "includes" to listOf("**/test/**", "**/androidTest/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
