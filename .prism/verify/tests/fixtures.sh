# Shared repository fixtures for the prism-verify hook tests.
#
# NOT a test file: run-tests.sh only picks up tests/test_*.sh, so this is
# sourced explicitly by the suites that need it.
#
# Why fixtures exist at all: several gate behaviours are properties of a
# REPOSITORY — "this is a worktree of the same repo", "this is a foreign
# checkout", "nothing changed here" — and asserting them against whichever
# checkout the developer happens to have open makes the verdict depend on
# their uncommitted work. Cases written that way lied in both directions:
# test_stop_gate.sh's broken-python3 case passed only on a clean tree, and
# test_affected.sh's no-upstream case passed only on a branch that had never
# been pushed. A fixture makes the precondition a property of the fixture.

# prism_fixture_config <repo> [base-branch] — writes the repo's .prism/prism.json.
#
# A fixture is an INSTALL, not just a git repository, and an install carries its
# configuration. Since the base branch stopped defaulting to `master` in the
# engine, a fixture without this file makes every gate deny before it reaches
# the behaviour the test was written for — correctly, and uselessly.
#
# The default is `master` because that is the branch the fixtures init, not
# because it is PRISM's default. PRISM's is `main`; a fixture asserting against
# a value it did not set would be asserting against the shipped default by
# accident.
prism_fixture_config() {
    mkdir -p "$1/.prism"
    printf '{\n  "baseBranch": "%s"\n}\n' "${2:-master}" > "$1/.prism/prism.json"
}

# prism_fixture_repo — prints the path of a fresh throwaway git repo that
# looks enough like this project for the gates to run against it: a master
# branch with one commit, a settings.gradle.kts naming one module, that
# module's build file and a source file, and a copy of the hook tree at the
# same relative path the real one lives at (so $0 implies the fixture root).
prism_fixture_repo() {
    fixture=$(mktemp -d)
    fixture=$(CDPATH= cd -- "$fixture" && pwd -P)
    mkdir -p "$fixture/core/domain/src/main/java" "$fixture/.prism"
    cp -R "$HOOK_DIR" "$fixture/.prism/verify"
    rm -rf "$fixture/.prism/verify/state"
    find "$fixture/.prism/verify" -name '__pycache__' -type d \
        -exec rm -rf {} + 2>/dev/null
    mkdir -p "$fixture/core/domain/src/test/java" \
             "$fixture/build-logic/src/main/kotlin"
    mkdir -p "$fixture/core/other/src/main/java"
    printf 'include(":core:domain")\ninclude(":core:other")\n' \
        > "$fixture/settings.gradle.kts"
    # A real dependency edge, so a test can prove a gate does NOT widen to
    # dependents when it is not supposed to. :core:other consumes :core:domain.
    printf 'plugins {\n    id("acme.kotlin.java.library")\n}\ndependencies {\n    implementation(projects.core.domain)\n}\n' \
        > "$fixture/core/other/build.gradle.kts"
    printf 'class Other\n' > "$fixture/core/other/src/main/java/Other.kt"
    printf 'plugins {\n    id("acme.kotlin.java.library")\n}\n' \
        > "$fixture/core/domain/build.gradle.kts"
    printf 'class Seed\n' > "$fixture/core/domain/src/main/java/Seed.kt"
    printf 'class SeedTest\n' > "$fixture/core/domain/src/test/java/SeedTest.kt"
    # coverage.py answers "is this module configured for coverage" by reading
    # build-logic and following what the module's plugin applies, so a fixture
    # that omits build-logic reports every module as no-jacoco and stops any
    # test before the behaviour it meant to reach.
    cat > "$fixture/build-logic/build.gradle.kts" <<'BUILDLOGIC'
gradlePlugin {
    plugins {
        register("kotlinJavaLibrary") {
            id = "acme.kotlin.java.library"
            implementationClass = "KotlinJavaLibraryConventionPlugin"
        }
    }
}
BUILDLOGIC
    cat > "$fixture/build-logic/src/main/kotlin/KotlinJavaLibraryConventionPlugin.kt" <<'CONVENTION'
class KotlinJavaLibraryConventionPlugin {
    fun apply(target: Any) {
        pluginManager.apply("prism.jacoco")
    }
}
CONVENTION
    prism_fixture_config "$fixture"
    git -C "$fixture" init -q -b master
    git -C "$fixture" -c user.email=fixture@example.com -c user.name=fixture \
        add -A
    git -C "$fixture" -c user.email=fixture@example.com -c user.name=fixture \
        -c commit.gpgsign=false commit -qm 'seed'
    printf '%s\n' "$fixture"
}

# prism_fixture_graph_repo — a fixture with a REALISTIC MULTI-MODULE GRAPH.
#
# prism_fixture_repo above is deliberately minimal: two JVM modules, enough to
# prove a gate does not widen to dependents when it should not. Several
# behaviours need more than that — longest-prefix module mapping over nested
# feature paths, the JVM-only heuristic distinguishing an Android module from a
# plain Kotlin one, and dependency reach from a core module out to a feature
# and to :app.
#
# Those assertions used to run against the AMBIENT checkout, on the assumption
# that whoever ran the suite had this project's own 15-module Android graph
# open. That assumption died the moment the framework was extracted into a
# product repository that is not an Android project: prism_module_for_path
# returned empty for every path and ten assertions failed for a reason that had
# nothing to do with module mapping.
#
# It is the same bug the branch-state cases hit at 7.5.2 in test_affected.sh,
# and it has the same fix. A module graph is a property of a REPOSITORY, so it
# belongs to a fixture repository.
#
# The graph mirrors the shapes the gates actually have to tell apart:
#
#   :app                          android application, depends on the features
#   :core:domain                  pure Kotlin/JVM — the JVM-only case
#   :core:data                    android library, depends on :core:domain
#   :core:crypto                  android library with BOTH test suites
#   :feature:auth:presentation    android library, nested path, depends on domain
#   :feature:files:presentation   android library, nested path — the
#                                 longest-prefix case against :feature:files
prism_fixture_graph_repo() {
    fixture=$(mktemp -d)
    fixture=$(CDPATH= cd -- "$fixture" && pwd -P)
    mkdir -p "$fixture/.prism"
    cp -R "$HOOK_DIR" "$fixture/.prism/verify"
    rm -rf "$fixture/.prism/verify/state"
    find "$fixture/.prism/verify" -name '__pycache__' -type d \
        -exec rm -rf {} + 2>/dev/null

    cat > "$fixture/settings.gradle.kts" <<'SETTINGS'
include(":app")
include(":core:domain")
include(":core:data")
include(":core:crypto")
include(":feature:auth:presentation")
include(":feature:files:presentation")
include(":core:design-system")
SETTINGS

    # An Android module is one that ends up applying AGP, however indirectly. That is
    # the exact signal prism_is_jvm_only reads, so the fixture has to carry it
    # rather than merely look Android-shaped.
    prism_fixture_module "$fixture" app "acme.android.application" \
        "implementation(projects.feature.auth.presentation)
    implementation(projects.feature.files.presentation)
    implementation(projects.core.domain)"
    prism_fixture_module "$fixture" core/domain "acme.kotlin.java.library" ""
    prism_fixture_module "$fixture" core/data "acme.android.library" \
        "implementation(projects.core.domain)"
    prism_fixture_module "$fixture" core/crypto "acme.android.library" \
        "implementation(projects.core.domain)"
    prism_fixture_module "$fixture" feature/auth/presentation \
        "acme.android.library.compose" "implementation(projects.core.domain)"
    prism_fixture_module "$fixture" feature/files/presentation \
        "acme.android.library.compose" \
        "androidTestImplementation(projects.core.data)"

    # A module with production code and NO unit-test source set, so a gate can
    # be shown to skip the test task rather than inventing one that would fail.
    prism_fixture_module "$fixture" core/design-system "acme.android.library" ""
    rm -rf "$fixture/core/design-system/src/test"

    # :core:crypto carries both suites, so a coverage decision has a module
    # whose report kind is `combined` rather than jvm or instrumentation alone.
    mkdir -p "$fixture/core/crypto/src/androidTest/java"
    printf 'class SeedAndroidTest\n' \
        > "$fixture/core/crypto/src/androidTest/java/SeedAndroidTest.kt"

    mkdir -p "$fixture/build-logic/src/main/kotlin"
    cat > "$fixture/build-logic/build.gradle.kts" <<'BUILDLOGIC'
gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "acme.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "acme.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "acme.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("kotlinJavaLibrary") {
            id = "acme.kotlin.java.library"
            implementationClass = "KotlinJavaLibraryConventionPlugin"
        }
    }
}
BUILDLOGIC
    # The convention plugins are named `acme.*`, NOT `prism.*`, and that is the
    # whole point of the fixture. PRISM ships four verification plugins and no
    # Android or Kotlin conventions -- those belong to the consumer. So every
    # question the engine asks about a module ("is it Android?", "is it
    # configured for coverage?") has to be answerable through plugin names
    # PRISM has never seen, resolved down to the real upstream ids. A fixture
    # using PRISM's own names would prove only that the engine recognises
    # itself.
    cat > "$fixture/build-logic/src/main/kotlin/AndroidApplicationConventionPlugin.kt" <<'CONVENTION'
class AndroidApplicationConventionPlugin {
    fun apply(target: Any) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("prism.jacoco")
    }
}
CONVENTION
    cat > "$fixture/build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt" <<'CONVENTION'
class AndroidLibraryConventionPlugin {
    fun apply(target: Any) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("prism.jacoco")
    }
}
CONVENTION
    cat > "$fixture/build-logic/src/main/kotlin/KotlinJavaLibraryConventionPlugin.kt" <<'CONVENTION'
class KotlinJavaLibraryConventionPlugin {
    fun apply(target: Any) {
        pluginManager.apply("prism.jacoco")
    }
}
CONVENTION
    cat > "$fixture/build-logic/src/main/kotlin/AndroidLibraryComposeConventionPlugin.kt" <<'CONVENTION'
class AndroidLibraryComposeConventionPlugin {
    fun apply(target: Any) {
        pluginManager.apply("acme.android.library")
    }
}
CONVENTION

    prism_fixture_config "$fixture"
    git -C "$fixture" init -q -b master
    git -C "$fixture" -c user.email=fixture@example.com -c user.name=fixture add -A
    git -C "$fixture" -c user.email=fixture@example.com -c user.name=fixture \
        -c commit.gpgsign=false commit -qm 'seed the module graph'
    printf '%s\n' "$fixture"
}

# prism_fixture_module <repo> <dir> <plugin-id> <dependency-lines>
prism_fixture_module() {
    prism_fm_repo="$1"
    prism_fm_dir="$2"
    prism_fm_plugin="$3"
    prism_fm_deps="$4"
    mkdir -p "$prism_fm_repo/$prism_fm_dir/src/main/java" \
             "$prism_fm_repo/$prism_fm_dir/src/test/java"
    {
        printf 'plugins {\n    id("%s")\n}\n' "$prism_fm_plugin"
        if [ -n "$prism_fm_deps" ]; then
            printf 'dependencies {\n    %s\n}\n' "$prism_fm_deps"
        fi
    } > "$prism_fm_repo/$prism_fm_dir/build.gradle.kts"
    printf 'class Seed\n' > "$prism_fm_repo/$prism_fm_dir/src/main/java/Seed.kt"
    printf 'class SeedTest\n' > "$prism_fm_repo/$prism_fm_dir/src/test/java/SeedTest.kt"
}

# prism_fixture_worktree <repo> — prints the path of a detached worktree of
# that repo, created OUTSIDE it so it is not itself a changed file.
prism_fixture_worktree() {
    worktree=$(mktemp -d)
    worktree=$(CDPATH= cd -- "$worktree" && pwd -P)/wt
    git -C "$1" worktree add -q --detach "$worktree" HEAD
    printf '%s\n' "$worktree"
}

# prism_fixture_cleanup <repo> [<worktree>...] — removes the worktrees from
# the repo's registry first, so nothing is left `prunable` behind the suite.
prism_fixture_cleanup() {
    repo="$1"
    shift
    for worktree in "$@"; do
        git -C "$repo" worktree remove --force "$worktree" 2>/dev/null
        rm -rf "$worktree"
    done
    git -C "$repo" worktree prune 2>/dev/null
    rm -rf "$repo"
}

# prism_fixture_branch_commit <repo> — puts the fixture on a feature branch
# with one extra commit touching the module, so `master..HEAD` is a NON-EMPTY
# range. Anything asserting on what a push would send needs that; deriving it
# from whichever branch the developer has open is how those assertions drift.
prism_fixture_branch_commit() {
    git -C "$1" checkout -q -b fixture-feature
    printf 'class Seed { val extra = 1 }\n' \
        > "$1/core/domain/src/main/java/Seed.kt"
    git -C "$1" -c user.email=fixture@example.com -c user.name=fixture add -A
    git -C "$1" -c user.email=fixture@example.com -c user.name=fixture \
        -c commit.gpgsign=false commit -qm 'edit the module'
}

# prism_fixture_gradlew <repo> — installs a stub `gradlew` that records every
# task it is asked to run and takes its exit status from
# <repo>/.fixture-gradle-exit (default 0).
#
# The gates SHELL OUT to "$PRISM_ROOT/gradlew", so a fixture repo cannot run
# them for real — there is no Android SDK behind it and no reason to want one.
# What these tests are about is the gate's wiring: does a failing build deny,
# does a passing one allow, and which tasks does it choose. A stub answers
# exactly those questions and nothing else.
prism_fixture_gradlew() {
    cat > "$1/gradlew" <<'GRADLEW'
#!/bin/sh
root=$(dirname "$0")
for arg in "$@"; do
    case "$arg" in
        :*|staticAnalysis|prismBaseline) printf '%s\n' "$arg" >> "$root/.fixture-gradle-tasks" ;;
    esac
done
# A REALISTIC FAILURE, so the gates' failing-task attribution can be tested.
# Gradle names the task that actually failed, which is normally a dependency
# of the one requested -- the whole reason the Stop gate classifies on the
# reported name rather than on what it asked for. Only the module-task
# invocation fails, so gate 0 (staticAnalysis, passed as a bare word) still
# passes and the run reaches gates 1 and 2.
if [ -f "$root/.fixture-gradle-failed-task" ]; then
    for arg in "$@"; do
        case "$arg" in
            :*)
                printf 'FAILURE: Build failed with an exception.\n'
                printf '* What went wrong:\n'
                printf "Execution failed for task '%s'.\n" \
                    "$(cat "$root/.fixture-gradle-failed-task")"
                exit 1
                ;;
        esac
    done
fi
status=0
if [ -f "$root/.fixture-gradle-exit" ]; then
    status=$(cat "$root/.fixture-gradle-exit")
fi
if [ "$status" != "0" ]; then
    echo "FIXTURE BUILD FAILURE"
fi
exit "$status"
GRADLEW
    chmod +x "$1/gradlew"
    rm -f "$1/.fixture-gradle-tasks" "$1/.fixture-gradle-exit" \
          "$1/.fixture-gradle-failed-task"
}
