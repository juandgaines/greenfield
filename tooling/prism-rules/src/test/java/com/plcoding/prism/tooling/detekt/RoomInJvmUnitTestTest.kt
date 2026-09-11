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

class RoomInJvmUnitTestTest {
    private val rule = RoomInJvmUnitTest(Config.empty)

    @Test
    fun `reports an in-memory database builder`() {
        val findings =
            rule.lint("fun database(context: Context) = Room.inMemoryDatabaseBuilder(context, FilesDatabase::class.java)")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("src/androidTest")
    }

    @Test
    fun `reports a persistent database builder`() {
        assertThat(
            rule.lint("fun database(context: Context) = Room.databaseBuilder(context, FilesDatabase::class.java, \"files\")"),
        ).hasSize(1)
    }

    @Test
    fun `does not report other Room members`() {
        assertThat(rule.lint("fun marker() = Room.MASTER_TABLE_NAME")).isEmpty()
    }

    @Test
    fun `does not report a builder on another receiver`() {
        assertThat(rule.lint("fun database() = FakeDb.inMemoryDatabaseBuilder()")).isEmpty()
    }

    @Test
    fun `fires under unit test sources only`(
        @TempDir root: Path,
    ) {
        val violation =
            "fun database(context: Context) = Room.inMemoryDatabaseBuilder(context, FilesDatabase::class.java)"
        val scoped =
            RoomInJvmUnitTest(
                TestConfig("active" to true, "includes" to listOf("**/test/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
