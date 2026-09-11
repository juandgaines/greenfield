import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Which engines block, per module — the Gradle-side reader for `.prism/scope.json`.
 *
 * THE ABSENCE OF THE FILE IS THE STRICT READING, NOT THE PERMISSIVE ONE. The
 * an install that found nothing to record writes no scope file, and every engine enforces
 * everywhere; the `scoped` build ships one. So deleting the file is how a
 * consumer finishes adopting, and a scope file that fails to load must never
 * quietly become "nothing blocks".
 *
 * It therefore fails CLOSED in both directions, and the two directions are
 * different failures:
 *
 *  - file absent            → everything enforces. A deliberate state.
 *  - file present, unreadable → the build FAILS, naming the file. Guessing
 *    here would mean either silently enforcing everything (a wall of findings
 *    nobody asked for) or silently enforcing nothing (a gate that stopped
 *    working and said so to no one). The second is unacceptable, and telling
 *    them apart is not this class's job.
 *
 * Kept deliberately in step with `verify/lib/scope.py`, which is the same
 * resolution for the gate scripts and for `prism status`. Two readers of one
 * file is a drift hazard, so the format is kept small enough that both are
 * obvious, and `test_scope_agreement.sh` renders a matrix through each and
 * diffs them.
 */
internal object PrismScope {
    val ENGINES = listOf("detekt", "ktlint", "konsist", "coverage")

    private const val ENFORCE = "enforce"
    private const val OBSERVE = "observe"
    private const val SCOPE_FILE = ".prism/scope.json"

    private data class Resolved(val default: Map<String, String>, val modules: Map<String, Map<String, String>>)

    /** `enforce` or `observe` for one module and one engine. */
    fun postureFor(
        project: Project,
        modulePath: String,
        engine: String,
    ): String {
        val resolved = load(project)
        return resolved.modules[modulePath]?.get(engine)
            ?: resolved.default[engine]
            ?: ENFORCE
    }

    /** True when findings from this engine must NOT fail the build here. */
    fun observes(
        project: Project,
        modulePath: String,
        engine: String,
    ): Boolean = postureFor(project, modulePath, engine) == OBSERVE

    /**
     * Read every time, deliberately NOT cached — and read THROUGH A PROVIDER,
     * which is not a style preference. There are two caches to defeat here and
     * only one of them is obvious.
     *
     * 1. THE DAEMON. A `mutableMapOf` here looks free and is not: the Gradle
     *    daemon outlives the build, so a static cache means the first
     *    invocation's answer is reused by every later one and editing
     *    scope.json appears to do nothing until the daemon is stopped. That was
     *    written, and caught by a test that changed the file between two runs
     *    in the same daemon.
     *
     * 2. THE CONFIGURATION CACHE, which the fix for (1) does not touch, and
     *    which is worse. `ignoreFailures` is set at CONFIGURATION time from
     *    this value, so the resolved task graph carries the posture. A plain
     *    `File(rootDir, SCOPE_FILE).readText()` is invisible to the
     *    configuration cache, so editing scope.json did not invalidate the
     *    entry and the OLD posture kept being applied. Measured on 2026-09-10:
     *    `gradlew staticAnalysis` failed on a new violation while
     *    `gradlew staticAnalysis ktlintToolingClasspath` — gate 0's own command,
     *    whose different task list had an older cache entry — reported BUILD
     *    SUCCESSFUL over the same tree. Two consequences, both silent:
     *    `./prism promote` appeared to do nothing, and the push floor allowed a
     *    violating push.
     *
     *    `providers.fileContents(...).asText` registers the file as a
     *    configuration input, so changing it — or creating it where there was
     *    none — invalidates the entry. Absence is tracked too: `orNull` is null
     *    for a missing file, and that absence is itself part of the input.
     *
     * The cost it saves is one read of a file of a few hundred bytes, once per
     * module at configuration time.
     */
    private fun load(project: Project): Resolved {
        val text =
            project.providers
                .fileContents(
                    project.rootProject.layout.projectDirectory
                        .file(SCOPE_FILE),
                ).asText
                .orNull
        return if (text == null) {
            Resolved(ENGINES.associateWith { ENFORCE }, emptyMap())
        } else {
            parse(text)
        }
    }

    private fun parse(text: String): Resolved {
        val root =
            try {
                JsonSlurper().parseText(text)
            } catch (error: Exception) {
                throw GradleException(
                    "$SCOPE_FILE cannot be read: ${error.message}. " +
                        "PRISM will not guess which engines block. Fix the file, or " +
                        "delete it to enforce every engine in every module.",
                )
            }
        if (root !is Map<*, *>) {
            throw GradleException("$SCOPE_FILE must be a JSON object.")
        }

        val default = ENGINES.associateWith { ENFORCE }.toMutableMap()
        default.putAll(postures(root["default"], "default"))

        val modules = mutableMapOf<String, Map<String, String>>()
        val supplied = root["modules"]
        if (supplied != null) {
            if (supplied !is Map<*, *>) {
                throw GradleException("$SCOPE_FILE: 'modules' must be an object.")
            }
            supplied.forEach { (key, value) ->
                val path = key as? String ?: throw GradleException("$SCOPE_FILE: module keys must be strings.")
                if (!path.startsWith(":")) {
                    throw GradleException(
                        "$SCOPE_FILE: module '$path' must be a Gradle path starting with ':'.",
                    )
                }
                modules[path] = postures(value, path)
            }
        }
        return Resolved(default, modules)
    }

    /**
     * An unrecognised engine name is REFUSED, never ignored. A typo that is
     * silently dropped still reads in the file as though it were in force,
     * which is precisely the silent green this framework exists to prevent.
     */
    private fun postures(entry: Any?, where: String): Map<String, String> {
        if (entry == null) return emptyMap()
        if (entry !is Map<*, *>) {
            throw GradleException("$SCOPE_FILE: $where must be an object.")
        }
        return entry.entries.associate { (key, value) ->
            val engine = key as? String
            if (engine == null || engine !in ENGINES) {
                throw GradleException(
                    "$SCOPE_FILE: $where names engine '$key' — known engines are ${ENGINES.joinToString()}.",
                )
            }
            if (value != ENFORCE && value != OBSERVE) {
                throw GradleException(
                    "$SCOPE_FILE: $where.$engine is '$value' — must be '$ENFORCE' or '$OBSERVE'.",
                )
            }
            engine to value as String
        }
    }
}
