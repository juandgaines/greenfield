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

class IconContentDescriptionTest {
    private val rule = IconContentDescription(Config.empty)

    @Test
    fun `reports an Icon with no content description`() {
        assertThat(rule.lint("@Composable fun Row() { Icon(imageVector = Icons.Check, modifier = Modifier) }")).hasSize(1)
    }

    @Test
    fun `reports an Icon with a single positional argument`() {
        assertThat(rule.lint("@Composable fun Row() { Icon(Icons.Check) }")).hasSize(1)
    }

    @Test
    fun `does not report an explicit null description`() {
        assertThat(rule.lint("@Composable fun Row() { Icon(painter = check, contentDescription = null) }")).isEmpty()
    }

    @Test
    fun `does not report a described Icon`() {
        assertThat(
            rule.lint("@Composable fun Row() { Icon(painter = check, contentDescription = stringResource(R.string.close)) }"),
        ).isEmpty()
    }

    @Test
    fun `does not report a description passed positionally`() {
        assertThat(rule.lint("@Composable fun Row() { Icon(Icons.Check, null) }")).isEmpty()
    }

    @Test
    fun `does not report an unrelated call`() {
        assertThat(rule.lint("@Composable fun Row() { Image(painter = check) }")).isEmpty()
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "@Composable fun Row() { Icon(imageVector = Icons.Check) }"
        val scoped = IconContentDescription(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
