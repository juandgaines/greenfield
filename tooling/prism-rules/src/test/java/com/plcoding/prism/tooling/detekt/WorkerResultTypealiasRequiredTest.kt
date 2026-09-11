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

class WorkerResultTypealiasRequiredTest {
    private val rule = WorkerResultTypealiasRequired(Config.empty)

    @Test
    fun `reports an import of the raw result type`() {
        val findings = rule.lint("import androidx.work.ListenableWorker.Result")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("WorkerResult")
    }

    @Test
    fun `reports a qualified result reference in an expression`() {
        val code =
            """
            import androidx.work.ListenableWorker

            fun done() = ListenableWorker.Result.success()
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `reports a fully qualified result reference`() {
        assertThat(rule.lint("fun done() = androidx.work.ListenableWorker.Result.success()")).hasSize(1)
    }

    @Test
    fun `reports a qualified result type reference`() {
        val code =
            """
            import androidx.work.ListenableWorker

            fun map(): ListenableWorker.Result = TODO()
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `does not report the typealias usage`() {
        val code =
            """
            import com.example.app.core.data.work.WorkerResult

            fun done(): WorkerResult = WorkerResult.success()
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a plain ListenableWorker import`() {
        val code =
            """
            import androidx.work.ListenableWorker

            fun log(worker: ListenableWorker) = println(worker)
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report an unrelated result type`() {
        assertThat(rule.lint("fun parse(): kotlin.Result<String> = TODO()")).isEmpty()
    }

    @Test
    fun `fires outside the declaring file and is excluded inside it`(
        @TempDir root: Path,
    ) {
        val violation = "typealias WorkerResult = androidx.work.ListenableWorker.Result"
        val scoped =
            WorkerResultTypealiasRequired(
                TestConfig(
                    "active" to true,
                    "excludes" to
                        listOf("**/test/**", "**/androidTest/**", "**/core/data/**/WorkerResult.kt"),
                ),
            )
        val declaringFile = "core/data/src/main/java/com/example/app/core/data/work/WorkerResult.kt"
        val workerFile = "feature/files/data/src/main/java/com/example/app/feature/files/data/SyncWorker.kt"

        assertThat(scoped.lintAt(root, workerFile, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, declaringFile, violation)).isEmpty()
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
