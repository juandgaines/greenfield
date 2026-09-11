package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The twelve rules that find nothing on the current tree.
 *
 * A rule with no live hits is indistinguishable from a rule that does not work,
 * so for these the tests are the only evidence that they do anything at all.
 * Each gets a violating case, a compliant case, and both directions of every
 * exclusion it declares.
 */
class RegressionGuardTest {
    @Nested
    inner class NoOpenClassInMainTest {
        private val rule = NoOpenClassInMain(Config.empty)

        @Test
        fun `reports an open class`() {
            assertThat(rule.lint("open class Repository")).hasSize(1)
        }

        @Test
        fun `reports an open function`() {
            assertThat(rule.lint("class Repository { open fun load() = Unit }")).hasSize(1)
        }

        @Test
        fun `does not report a final class`() {
            assertThat(rule.lint("class Repository { fun load() = Unit }")).isEmpty()
        }

        @Test
        fun `does not report an abstract class or its open members`() {
            assertThat(rule.lint("abstract class Base { open fun load() = Unit }")).isEmpty()
        }

        @Test
        fun `does not report an interface`() {
            assertThat(rule.lint("interface Repository { fun load() }")).isEmpty()
        }

        @Test
        fun `fires in a main source and is excluded in test sources`(
            @TempDir root: Path,
        ) {
            val scoped = NoOpenClassInMain(TestConfig(*MAIN_SOURCES_ONLY))
            assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, "open class Repository")).hasSize(1)
            assertThat(scoped.lintAt(root, UNIT_TEST_PATH, "open class Repository")).isEmpty()
            assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, "open class Repository")).isEmpty()
        }
    }

    @Nested
    inner class TextFieldStateInViewModelTest {
        private val rule = TextFieldStateInViewModel(Config.empty)

        @Test
        fun `reports remembering text-field state in a composable`() {
            assertThat(
                rule.lint("@Composable fun Form() { val email = rememberTextFieldState() }"),
            ).hasSize(1)
        }

        @Test
        fun `does not report state passed in from the ViewModel`() {
            assertThat(rule.lint("@Composable fun Form(emailState: TextFieldState) { Field(emailState) }")).isEmpty()
        }

        @Test
        fun `does not report the call outside a composable`() {
            assertThat(rule.lint("class FormViewModel { val email = rememberTextFieldState() }")).isEmpty()
        }

        @Test
        fun `fires in a main source and is excluded in test sources`(
            @TempDir root: Path,
        ) {
            val code = "@Composable fun Form() { val email = rememberTextFieldState() }"
            val scoped = TextFieldStateInViewModel(TestConfig(*MAIN_SOURCES_ONLY))
            assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, code)).hasSize(1)
            assertThat(scoped.lintAt(root, UNIT_TEST_PATH, code)).isEmpty()
        }
    }

    @Nested
    inner class ObserveAsEventsRequiredTest {
        private val rule = ObserveAsEventsRequired(Config.empty)

        @Test
        fun `reports a LaunchedEffect that collects`() {
            assertThat(
                rule.lint("@Composable fun Root() { LaunchedEffect(Unit) { viewModel.events.collect { handle(it) } } }"),
            ).hasSize(1)
        }

        @Test
        fun `reports collectLatest too`() {
            assertThat(
                rule.lint("@Composable fun Root() { LaunchedEffect(Unit) { flow.collectLatest { handle(it) } } }"),
            ).hasSize(1)
        }

        @Test
        fun `does not report ObserveAsEvents`() {
            assertThat(
                rule.lint("@Composable fun Root() { ObserveAsEvents(viewModel.events) { handle(it) } }"),
            ).isEmpty()
        }

        @Test
        fun `does not report a LaunchedEffect that does not collect`() {
            assertThat(rule.lint("@Composable fun Root() { LaunchedEffect(key) { scroll() } }")).isEmpty()
        }

        @Test
        fun `runs on Root files and is skipped elsewhere`(
            @TempDir root: Path,
        ) {
            val code = "@Composable fun Root() { LaunchedEffect(Unit) { flow.collect { handle(it) } } }"
            val scoped =
                ObserveAsEventsRequired(
                    TestConfig(
                        "active" to true,
                        "excludes" to listOf("**/test/**", "**/androidTest/**"),
                        "includes" to listOf("**/*Root.kt"),
                    ),
                )
            val rootFile = "feature/auth/presentation/src/main/java/com/example/app/SignInRoot.kt"
            val screenFile = "feature/auth/presentation/src/main/java/com/example/app/SignInScreen.kt"
            assertThat(scoped.lintAt(root, rootFile, code)).hasSize(1)
            assertThat(scoped.lintAt(root, screenFile, code)).isEmpty()
        }
    }

    @Nested
    inner class NoPublicMutableFlowTest {
        private val rule = NoPublicMutableFlow(Config.empty)

        @Test
        fun `reports a public MutableStateFlow`() {
            assertThat(rule.lint("class ViewModel { val state = MutableStateFlow(State()) }")).hasSize(1)
        }

        @Test
        fun `reports a declared MutableSharedFlow type`() {
            assertThat(rule.lint("class ViewModel { val events: MutableSharedFlow<Event> = create() }")).hasSize(1)
        }

        @Test
        fun `does not report a private backing flow`() {
            assertThat(rule.lint("class ViewModel { private val _state = MutableStateFlow(State()) }")).isEmpty()
        }

        @Test
        fun `does not report the read-only projection`() {
            assertThat(
                rule.lint("class ViewModel { val state: StateFlow<State> = _state.asStateFlow() }"),
            ).isEmpty()
        }

        @Test
        fun `fires in a main source and is excluded in test sources`(
            @TempDir root: Path,
        ) {
            val code = "class ViewModel { val state = MutableStateFlow(State()) }"
            val scoped = NoPublicMutableFlow(TestConfig(*MAIN_SOURCES_ONLY))
            assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, code)).hasSize(1)
            assertThat(scoped.lintAt(root, UNIT_TEST_PATH, code)).isEmpty()
        }
    }

    @Nested
    inner class NoRunBlockingInMainTest {
        private val rule = NoRunBlockingInMain(Config.empty)

        @Test
        fun `reports runBlocking`() {
            assertThat(rule.lint("fun load() { runBlocking { fetch() } }")).hasSize(1)
        }

        @Test
        fun `does not report runBlocking in an entry point`() {
            assertThat(rule.lint("fun main() { runBlocking { start() } }")).isEmpty()
        }

        @Test
        fun `does not report a suspending call`() {
            assertThat(rule.lint("suspend fun load() { fetch() }")).isEmpty()
        }

        @Test
        fun `fires in a main source and is excluded in test sources`(
            @TempDir root: Path,
        ) {
            val code = "fun load() { runBlocking { fetch() } }"
            val scoped = NoRunBlockingInMain(TestConfig(*MAIN_SOURCES_ONLY))
            assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, code)).hasSize(1)
            assertThat(scoped.lintAt(root, UNIT_TEST_PATH, code)).isEmpty()
        }
    }

    @Nested
    inner class NoNotNullAssertionTest {
        private val rule = NoNotNullAssertion(Config.empty)

        @Test
        fun `reports a not-null assertion`() {
            assertThat(rule.lint("fun name(user: User?) = user!!.name")).hasSize(1)
        }

        @Test
        fun `does not report safe access with a fallback`() {
            assertThat(rule.lint("fun name(user: User?) = user?.name ?: \"\"")).isEmpty()
        }

        @Test
        fun `does not report a negation`() {
            assertThat(rule.lint("fun absent(user: User?) = !isPresent(user)")).isEmpty()
        }

        @Test
        fun `fires in a main source and is excluded in test sources`(
            @TempDir root: Path,
        ) {
            val code = "fun name(user: User?) = user!!.name"
            val scoped = NoNotNullAssertion(TestConfig(*MAIN_SOURCES_ONLY))
            assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, code)).hasSize(1)
            assertThat(scoped.lintAt(root, UNIT_TEST_PATH, code)).isEmpty()
        }
    }
}
