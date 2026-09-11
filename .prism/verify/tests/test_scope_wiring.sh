# Tests for how the convention plugins CONSULT scope.json. Sourced by run-tests.sh.
#
# These are source assertions, not behavioural ones, and that is a deliberate
# trade: the behaviour needs a Gradle daemon and a multi-module fixture, which
# the gate suite cannot afford to run. What they guard are two defects that were
# both written, both shipped past a compiler, and both produced a build that
# looked correct:
#
#   1. `PrismScope.observes(rootProject.projectDir, path, ...)` INSIDE a
#      `tasks.register<JavaExec> { }` block. The receiver there is the TASK, so
#      `path` reads ":module:ktlintCheck" -- which matches no module in
#      scope.json, falls back to the default, and silently ENFORCES a module the
#      consumer set to observe. It is the same trap StaticAnalysisConventionPlugin
#      already documents for its subprojects loop, and it was walked into again.
#
#   2. A static cache in PrismScope. The Gradle daemon outlives the build, so the
#      first invocation's answer is reused by every later one and editing
#      scope.json appears to do nothing until the daemon is stopped. Caught only
#      because a manual test changed the file between two runs.
#
# Both are invisible in review and one grep away here.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

SW_LOGIC="$HOOK_DIR/../build-logic/src/main/kotlin"

# --- 1. the module path is bound at PROJECT scope ------------------------
for sw_plugin in DetektConventionPlugin KtlintConventionPlugin; do
    sw_file="$SW_LOGIC/$sw_plugin.kt"
    if [ ! -f "$sw_file" ]; then
        assert_eq "present" "missing" "$sw_plugin.kt exists"
        continue
    fi
    # A bare `path` handed to PrismScope is the defect. The correct form binds
    # `val modulePath = path` while the receiver is still the Project.
    #
    # Matched on the argument, not on the first parameter: 0.6.1 changed that
    # from `rootProject.projectDir` to `project` so the file could be read
    # through a provider (check 2b below), and an assertion keyed to the old
    # signature would have gone vacuously green.
    sw_bare=$(grep -cE 'PrismScope\.[a-zA-Z]*\([^)]*, path,' "$sw_file" || true)
    assert_eq "0" "$sw_bare" \
        "$sw_plugin does not hand a bare \`path\` to PrismScope"

    sw_bound=$(grep -c '^ *val modulePath = path$' "$sw_file" || true)
    assert_ne "0" "$sw_bound" \
        "$sw_plugin binds modulePath while the receiver is the Project"
done

# --- 2. PrismScope re-reads, because the daemon outlives the build --------
SW_SCOPE="$SW_LOGIC/PrismScope.kt"
# Comment lines are stripped first. The KDoc on load() explains this very
# defect and names `mutableMapOf` while doing so; a naive grep matched its own
# documentation and failed. Same shape as a banner comment containing
# `active: true` and breaking a rule count -- prose that quotes code is code to
# a grep, and every counter in this payload is anchored past it.
# Anchored to OBJECT-SCOPE state -- four spaces of indent, i.e. a member of
# `object PrismScope`. A `mutableMapOf` inside parse() is a local built and
# discarded within one call and is fine; a member map survives the build.
sw_cache=$(sed 's|//.*||; s|^ *\*.*||' "$SW_SCOPE" \
    | grep -cE '^    (private )?(val|var) .*(mutableMapOf|by lazy)' || true)
assert_eq "0" "$sw_cache" \
    "PrismScope holds no cross-build cache (the daemon outlives the build)"

# --- 2b. scope.json is a CONFIGURATION-CACHE INPUT -----------------------
#
# THE SECOND CACHE, and the fix for the daemon one above does not touch it.
# `ignoreFailures` is set at configuration time from PrismScope's answer, so the
# posture is baked into the cached task graph. A plain File read is invisible to
# the configuration cache, so editing scope.json did not invalidate the entry.
#
# Measured on the greenfield fixture, 2026-09-10, same tree both times:
#
#   gradlew staticAnalysis                          -> BUILD FAILED   (3 of 3)
#   gradlew staticAnalysis ktlintToolingClasspath   -> BUILD SUCCESSFUL
#         ^ gate 0's own command, whose different task list had an older entry
#   ...the same command --no-configuration-cache    -> BUILD FAILED
#
# So `./prism promote` appeared to do nothing and the push floor allowed a
# violating push -- both silently, and both only for whichever task list
# happened to hold a stale entry, which is why no test caught it.
#
# Source assertions for the same reason as the rest of this file: reproducing it
# needs two Gradle invocations with a live configuration cache.
assert_contains "$(cat "$SW_SCOPE")" 'providers' \
    "PrismScope reads scope.json through a provider, not a bare File"
assert_contains "$(cat "$SW_SCOPE")" 'fileContents' \
    "and specifically fileContents(), which registers it as a config input"

# The two shapes that are invisible to the configuration cache. Both were the
# code this replaced.
sw_bare_read=$(sed 's|//.*||; s|^ *\*.*||' "$SW_SCOPE" \
    | grep -cE 'File\(rootDir|\.readText\(\)|JsonSlurper\(\)\.parse\(' || true)
assert_eq "0" "$sw_bare_read" \
    "and never reads the file in a way the configuration cache cannot track"

# It needs a Project to reach providers at all, so the signature is part of the
# property rather than incidental to it.
assert_contains "$(cat "$SW_SCOPE")" 'project: Project' \
    "PrismScope takes a Project, which is what gives it providers"

# --- 3. absence of the file is the STRICT reading ------------------------
#
# The uniform artifact ships without scope.json. If this ever inverted, that
# build would enforce nothing while reporting success -- the worst outcome the
# whole design is arranged to avoid.
assert_contains "$(cat "$SW_SCOPE")" 'Resolved(ENGINES.associateWith { ENFORCE }, emptyMap())' \
    "a missing scope.json resolves to enforce, not observe"

# --- 4. seeding is never wired into the gate -----------------------------
#
# `prismBaseline` and `prismKtlintBaseline` record findings. A gate that could
# write its own baseline would be an escape hatch with a task name.
SW_STATIC="$SW_LOGIC/StaticAnalysisConventionPlugin.kt"
assert_eq "0" \
    "$(grep -c 'staticAnalysis.configure { dependsOn("\$module:prismKtlintBaseline") }' "$SW_STATIC" || true)" \
    "staticAnalysis does not depend on prismKtlintBaseline"
assert_contains "$(cat "$SW_STATIC")" 'prismBaseline.configure { dependsOn("$module:$KTLINT_BASELINE_TASK") }' \
    "prismBaseline does depend on prismKtlintBaseline"

# --- 5. the ktlint baseline is only PASSED when it exists -----------------
#
# `--baseline` pointed at a missing file makes ktlint CREATE it and report
# success. Passed unconditionally, the first ktlintCheck on a dirty repository
# becomes a silent, permanent amnesty.
SW_KTLINT="$SW_LOGIC/KtlintConventionPlugin.kt"
assert_contains "$(cat "$SW_KTLINT")" 'if (baselineFile.isFile) listOf("--baseline=$BASELINE_FILE") else emptyList()' \
    "ktlintCheck passes --baseline only when the file already exists"

# --- 5. the observe list is built from MODULES, not from container projects
#
# Defect family 1 again, from the other direction. `subprojects` includes
# Gradle's implicit container projects: :core exists because settings.gradle.kts
# says include(":core:domain"), and nothing ever writes include(":core"). A
# container holds no code and carries no scope entry, so it resolves to
# `default`; when the default is observe it joins the list, becomes the
# directory prefix "/core/", and swallows "/core/domain/" whole. A module
# promoted to `konsist: enforce` then reported enforce, found its violations,
# printed them, and passed the build — a false PASS on every nested module.
#
# RuleAssertions guards the SIBLING collision with a trailing slash and says
# so; that guard cannot help against an ancestor, because a parent genuinely is
# a prefix of its children. So the filter has to be here, at the producer.
sw_observed=$(sed 's|//.*||' "$SW_STATIC" \
    | grep -A 2 'val observedModules' | grep -c 'buildFile\.isFile' || true)
assert_ne "0" "$sw_observed" \
    "the konsist observe list filters out projects with no build file"

# And the filter must come BEFORE the posture lookup, or every container is
# still resolved and the prefix is still emitted.
#
# Pinned to that ORDERING rather than to the literal chain. 0.6.1 moved the
# posture filter ahead of `.map { it.path }` because PrismScope now needs the
# Project, not its path -- so a test matching the old
# `.filter{buildFile}.map{path}` adjacency would have failed a change that keeps
# the property it exists to protect.
sw_order=$(sed 's|//.*||' "$SW_STATIC" | tr -d ' \n' \
    | grep -c 'subprojects\.filter{it\.buildFile\.isFile}\.filter{PrismScope\.observes(' || true)
assert_ne "0" "$sw_order" \
    "the build-file filter runs before the posture lookup, so no container is ever resolved"
