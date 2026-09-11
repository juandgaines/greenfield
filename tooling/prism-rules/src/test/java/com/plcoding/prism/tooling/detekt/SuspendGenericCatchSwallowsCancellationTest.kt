package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.junit.KotlinCoreEnvironmentTest
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import org.junit.jupiter.api.Test

/**
 * Every case here compiles and resolves against kotlinx-coroutines on the test
 * classpath. That is deliberate: a resolution failure presents as the absence
 * of a finding, so the violating cases double as the canary — if the Analysis
 * API stops resolving, they fail loudly instead of the rule going silent.
 */
@KotlinCoreEnvironmentTest
class SuspendGenericCatchSwallowsCancellationTest(private val env: KotlinEnvironmentContainer) {
    private val rule = SuspendGenericCatchSwallowsCancellation(Config.empty)

    // ------------------------------------------------------------------
    // Violations
    // ------------------------------------------------------------------

    @Test
    fun `reports a bare generic catch in a suspend fun`() {
        val code =
            """
            suspend fun load(): Int =
                try {
                    compute()
                } catch (e: Exception) {
                    -1
                }

            suspend fun compute(): Int = 42
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).hasSize(1)
    }

    @Test
    fun `reports a caught Throwable and RuntimeException too`() {
        val code =
            """
            suspend fun load() {
                try {
                    compute()
                } catch (t: Throwable) {
                    log(t)
                }
                try {
                    compute()
                } catch (e: RuntimeException) {
                    log(e)
                }
            }

            suspend fun compute(): Int = 42
            fun log(t: Throwable) = Unit
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).hasSize(2)
    }

    @Test
    fun `reports the guard buried after other handling`() {
        val code =
            """
            import kotlinx.coroutines.CancellationException

            suspend fun load(): Int =
                try {
                    compute()
                } catch (e: Exception) {
                    log(e)
                    if (e is CancellationException) throw e
                    -1
                }

            suspend fun compute(): Int = 42
            fun log(t: Throwable) = Unit
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).hasSize(1)
    }

    @Test
    fun `reports a lambda passed to a project-defined CoroutineScope extension`() {
        // The launchSafely declaration lives in a separate fragment, so this case
        // resolves only through a declaration outside the analysed file — the
        // canary for cross-fragment resolution.
        val builder =
            """
            package builders

            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.Job

            fun CoroutineScope.launchSafely(block: suspend () -> Unit): Job = error("stub")
            """.trimIndent()
        val code =
            """
            import builders.launchSafely
            import kotlinx.coroutines.CoroutineScope

            fun start(scope: CoroutineScope) {
                scope.launchSafely {
                    try {
                        compute()
                    } catch (e: Exception) {
                        // swallowed
                    }
                }
            }

            suspend fun compute(): Int = 42
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code, builder)).hasSize(1)
    }

    @Test
    fun `reports a lambda passed to a Flow operator`() {
        val code =
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.map

            fun decorate(numbers: Flow<Int>): Flow<Int> =
                numbers.map { value ->
                    try {
                        transform(value)
                    } catch (e: Exception) {
                        0
                    }
                }

            suspend fun transform(value: Int): Int = value
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).hasSize(1)
    }

    @Test
    fun `reports a rethrow of the wrong variable`() {
        val code =
            """
            import kotlinx.coroutines.CancellationException

            suspend fun load(previous: Exception): Int =
                try {
                    compute()
                } catch (e: Exception) {
                    if (previous is CancellationException) throw previous
                    -1
                }

            suspend fun compute(): Int = 42
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).hasSize(1)
    }

    // ------------------------------------------------------------------
    // Compliant forms
    // ------------------------------------------------------------------

    @Test
    fun `does not report ensureActive on coroutineContext as the first statement`() {
        val code =
            """
            import kotlinx.coroutines.ensureActive
            import kotlin.coroutines.coroutineContext

            suspend fun load(): Int =
                try {
                    compute()
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    -1
                }

            suspend fun compute(): Int = 42
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).isEmpty()
    }

    @Test
    fun `does not report ensureActive on currentCoroutineContext as the first statement`() {
        val code =
            """
            import kotlinx.coroutines.currentCoroutineContext
            import kotlinx.coroutines.ensureActive

            suspend fun load(): Int =
                try {
                    compute()
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    -1
                }

            suspend fun compute(): Int = 42
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).isEmpty()
    }

    @Test
    fun `does not report a first-statement rethrow of CancellationException`() {
        val code =
            """
            import kotlinx.coroutines.CancellationException

            suspend fun load(): Int =
                try {
                    compute()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    -1
                }

            suspend fun compute(): Int = 42
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).isEmpty()
    }

    @Test
    fun `does not report an aliased CancellationException check`() {
        val code =
            """
            import kotlinx.coroutines.CancellationException as Cancelled

            suspend fun load(): Int =
                try {
                    compute()
                } catch (e: Exception) {
                    if (e is Cancelled) throw e
                    -1
                }

            suspend fun compute(): Int = 42
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).isEmpty()
    }

    @Test
    fun `does not report a when arm that rethrows CancellationException`() {
        val code =
            """
            import kotlinx.coroutines.CancellationException

            suspend fun load(): Int =
                try {
                    compute()
                } catch (e: Exception) {
                    when (e) {
                        is CancellationException -> throw e
                        else -> -1
                    }
                }

            suspend fun compute(): Int = 42
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).isEmpty()
    }

    @Test
    fun `does not report the canonical safe-call wrapper shape`() {
        val code =
            """
            import kotlinx.coroutines.CancellationException
            import kotlinx.coroutines.ensureActive
            import kotlin.coroutines.coroutineContext

            suspend fun safeCall(execute: suspend () -> Int): Int =
                try {
                    execute()
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    coroutineContext.ensureActive()
                    -1
                }
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).isEmpty()
    }

    // ------------------------------------------------------------------
    // Out of scope
    // ------------------------------------------------------------------

    @Test
    fun `does not report a generic catch in an ordinary function`() {
        val code =
            """
            fun parse(raw: String): Int =
                try {
                    raw.toInt()
                } catch (e: Exception) {
                    0
                }
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).isEmpty()
    }

    @Test
    fun `does not report a non-suspending lambda`() {
        val code =
            """
            fun parseAll(values: List<String>): List<Int> =
                values.map { value ->
                    try {
                        value.toInt()
                    } catch (e: Exception) {
                        0
                    }
                }
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).isEmpty()
    }

    @Test
    fun `does not report a specific catch in a suspend fun`() {
        val code =
            """
            suspend fun load(): Int =
                try {
                    compute()
                } catch (e: IllegalStateException) {
                    -1
                }

            suspend fun compute(): Int = 42
            """.trimIndent()

        assertThat(rule.lintWithContext(env, code)).isEmpty()
    }
}
