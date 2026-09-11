package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class IconButtonHardcodedSizeTest {
    private val rule = IconButtonHardcodedSize(Config.empty)

    @Test
    fun `reports an IconButton sized by its modifier`() {
        assertThat(
            rule.lint("@Composable fun Bar() { IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) { } }"),
        ).hasSize(1)
    }

    @Test
    fun `reports a size buried in a longer modifier chain`() {
        assertThat(
            rule.lint("@Composable fun Bar() { IconButton(onClick = onDismiss, modifier = Modifier.padding(4.dp).size(24.dp)) { } }"),
        ).hasSize(1)
    }

    @Test
    fun `does not report an IconButton that keeps its default size`() {
        assertThat(rule.lint("@Composable fun Bar() { IconButton(onClick = onDismiss) { } }")).isEmpty()
    }

    @Test
    fun `does not report a modifier that only pads`() {
        assertThat(
            rule.lint("@Composable fun Bar() { IconButton(onClick = onDismiss, modifier = Modifier.padding(4.dp)) { } }"),
        ).isEmpty()
    }

    @Test
    fun `does not report a sized Icon inside an IconButton`() {
        assertThat(
            rule.lint(
                """
                @Composable
                fun Bar() {
                    IconButton(onClick = onDismiss) {
                        Icon(painter = close, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                """.trimIndent(),
            ),
        ).isEmpty()
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "@Composable fun Bar() { IconButton(onClick = go, modifier = Modifier.size(24.dp)) { } }"
        val scoped = IconButtonHardcodedSize(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
